"""P1 예산 측정 — 진짜 병목이 어디인지 확정한다. (M3-e, NFR-1, NFR-5 / plan.md §3)

─────────────────────────────────────────────────────────────────────────────
왜 하이퍼파라미터보다 먼저 재는가
─────────────────────────────────────────────────────────────────────────────
N·T·에폭·미니배치·H 는 전부 **예산의 함수**다. 나중에 재면 이미 고른 뒤다.
`plan.md` §3 은 이 비용을 GFLOPS 가정으로 추정했고 "±3배 틀릴 수 있다" 고 적어 두었다.
이 벤치가 그 자리를 실측치로 바꾼다.

작은 MLP 의 CPU matmul 은 피크 FLOPS 근처에 가지 못한다 (메모리 바운드 + BLAS 커널
오버헤드). 그래서 FLOPS 가 아니라 **벽시계**를 잰다.

─────────────────────────────────────────────────────────────────────────────
재는 것
─────────────────────────────────────────────────────────────────────────────
  (a) 정책 forward        N ∈ {256, 512, 1024} × H ∈ {128, 256}   — 롤아웃 쪽 비용
  (b) 스레드 수           1 / 4 / 8 의 forward·backward           — 재현성(M3-d)과 직결
  (c) 업데이트 한 반복    배치 65,536 · 에폭 4 · 미니배치 8       — plan.md §3 의 그 숫자
  (d) MPS                 같은 조건, 참고용                       — NFR-5 는 CPU 가 기본이다
  (e) 종단 한 반복        --target 이 있으면 롤아웃까지 실제로    — M3-e 의 선행 추정

⚠️ (c) 의 손실은 `ppo.py`(P5)가 아니라 **여기 있는 사본**이다. P1 이 P5 보다 먼저여야
   하기 때문이다 (tasks.md 의 의존 관계). 이것은 비용 대리(proxy)이고, M3-e 의 확정
   수치는 P6 이 실제 러너로 기록한다.
"""

from __future__ import annotations

import argparse
import os
import time

import numpy as np
import torch
from torch import Tensor, nn

from .net import ActorCritic

#: plan.md §8.1 의 출발점.
DEFAULT_BATCH = 65_536
DEFAULT_EPOCHS = 4
DEFAULT_MINIBATCHES = 8


def _sync(device: str) -> None:
    """MPS 는 비동기 큐다. 동기화 없이 재면 제출 시간만 잰다."""
    if device == "mps":
        torch.mps.synchronize()


def make_batch(batch: int, obs_dim: int, action_count: int, device: str) -> dict[str, Tensor]:
    """합성 롤아웃 배치. 값의 분포는 비용에 영향을 주지 않는다 — 모양만 맞추면 된다."""
    g = torch.Generator().manual_seed(0)
    obs = torch.randn(batch, obs_dim, generator=g)
    actions = torch.randint(0, action_count, (batch,), generator=g)
    return {
        "obs": obs.to(device),
        "actions": actions.to(device),
        "old_logp": torch.full((batch,), -float(np.log(action_count)), device=device),
        "adv": torch.randn(batch, generator=g).to(device),
        "returns": torch.randn(batch, generator=g).to(device),
        # 버려지는 스텝 비율 ≈ 1.5% (plan.md §7.3). 마스킹 비용까지 포함해 잰다.
        "valid": (torch.rand(batch, generator=g) > 0.015).to(device),
    }


def ppo_like_iteration(
    net: ActorCritic,
    opt: torch.optim.Optimizer,
    b: dict[str, Tensor],
    *,
    epochs: int,
    minibatches: int,
    clip: float = 0.2,
    vf_coef: float = 0.5,
    ent_coef: float = 0.02,
    max_grad_norm: float = 0.5,
) -> int:
    """PPO 업데이트 한 반복의 **비용 대리**. 학습 성질이 아니라 벽시계를 위한 것이다."""
    batch = b["obs"].shape[0]
    size = batch // minibatches
    steps = 0
    for _ in range(epochs):
        perm = torch.randperm(batch, device=b["obs"].device)
        for k in range(minibatches):
            idx = perm[k * size : (k + 1) * size]
            valid = b["valid"][idx]
            logp, entropy, value = net.evaluate_actions(b["obs"][idx], b["actions"][idx])

            # advantage 정규화는 **마스크 안에서만** (plan.md §8.2 함정 4).
            adv = b["adv"][idx]
            m = valid.float()
            n = m.sum().clamp(min=1.0)
            mean = (adv * m).sum() / n
            std = (((adv - mean) ** 2 * m).sum() / n).sqrt()
            adv = (adv - mean) / (std + 1e-8)

            ratio = (logp - b["old_logp"][idx]).exp()
            pi = -torch.min(ratio * adv, ratio.clamp(1 - clip, 1 + clip) * adv)
            loss_pi = (pi * m).sum() / n
            loss_v = (((value - b["returns"][idx]) ** 2) * m).sum() / n
            loss_ent = (entropy * m).sum() / n
            loss = loss_pi + vf_coef * 0.5 * loss_v - ent_coef * loss_ent

            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), max_grad_norm)
            opt.step()
            steps += 1
    return steps


# ── (a) forward ─────────────────────────────────────────────────────────────


def bench_forward(obs_dim: int, action_count: int, sizes: list[int], hiddens: list[int],
                  device: str, iters: int = 200) -> list[dict[str, float]]:
    rows = []
    for hidden in hiddens:
        net = ActorCritic(obs_dim, action_count, hidden).to(device)
        for n in sizes:
            obs = torch.randn(n, obs_dim, device=device)
            for _ in range(20):
                net.act(obs)
            _sync(device)
            t0 = time.perf_counter()
            for _ in range(iters):
                net.act(obs)
            _sync(device)
            ms = (time.perf_counter() - t0) / iters * 1000
            rows.append({"hidden": hidden, "n": n, "ms": ms, "per_env_us": ms * 1000 / n})
    return rows


# ── (b) 스레드 ──────────────────────────────────────────────────────────────


def bench_threads(obs_dim: int, action_count: int, hidden: int, threads: list[int],
                  batch: int, epochs: int, minibatches: int) -> list[dict[str, float]]:
    rows = []
    for t in threads:
        torch.set_num_threads(t)
        torch.manual_seed(0)
        net = ActorCritic(obs_dim, action_count, hidden)
        opt = torch.optim.Adam(net.parameters(), lr=3e-4, eps=1e-5)
        b = make_batch(batch, obs_dim, action_count, "cpu")

        # 워밍업 — 첫 반복은 BLAS 스레드 풀 생성과 Adam 상태 할당을 포함한다.
        ppo_like_iteration(net, opt, b, epochs=1, minibatches=minibatches)

        t0 = time.perf_counter()
        steps = ppo_like_iteration(net, opt, b, epochs=epochs, minibatches=minibatches)
        sec = time.perf_counter() - t0
        rows.append({
            "threads": t, "update_s": sec, "grad_steps": steps,
            "samples_per_sec": batch * epochs / sec,
        })
    return rows


# ── (c)(d) 업데이트 한 반복 ─────────────────────────────────────────────────


def bench_update(obs_dim: int, action_count: int, hiddens: list[int], device: str,
                 batch: int, epochs: int, minibatches: int, repeats: int = 3) -> list[dict[str, float]]:
    rows = []
    for hidden in hiddens:
        torch.manual_seed(0)
        net = ActorCritic(obs_dim, action_count, hidden).to(device)
        opt = torch.optim.Adam(net.parameters(), lr=3e-4, eps=1e-5)
        b = make_batch(batch, obs_dim, action_count, device)
        ppo_like_iteration(net, opt, b, epochs=1, minibatches=minibatches)
        _sync(device)

        best = float("inf")
        for _ in range(repeats):
            t0 = time.perf_counter()
            ppo_like_iteration(net, opt, b, epochs=epochs, minibatches=minibatches)
            _sync(device)
            best = min(best, time.perf_counter() - t0)
        params = sum(p.numel() for p in net.parameters())
        rows.append({
            "hidden": hidden, "params": params, "update_s": best,
            # 롤아웃이 공짜라고 가정했을 때의 상한. 종단 처리량은 이보다 낮다.
            "ceiling_steps_per_sec": batch / best,
        })
    return rows


# ── (e) 종단 ────────────────────────────────────────────────────────────────


def bench_end_to_end(target: str, obs_dim_hint: int, hidden: int, num_envs: int, T: int,
                     epochs: int, minibatches: int) -> dict[str, float]:
    """롤아웃 + 업데이트 한 반복을 **실제 서버로** 돈다. M3-e 의 선행 추정이다.

    GAE·버퍼 정리는 빠져 있다 (rollout.py 는 P4 다). 그만큼 낙관적인 숫자이고,
    확정치는 P6 이 기록한다.
    """
    from .env_client import ACTION_COUNT, EnvOptions, PikaVectorEnv

    env = PikaVectorEnv(target, EnvOptions(num_envs=num_envs, base_seed=1,
                                           obs_include_side_flag=True))
    try:
        obs_dim = env.obs_dim
        torch.manual_seed(0)
        net = ActorCritic(obs_dim, ACTION_COUNT, hidden)
        opt = torch.optim.Adam(net.parameters(), lr=3e-4, eps=1e-5)
        obs_np, _ = env.reset(seed=1)

        for _ in range(20):
            a, _, _ = net.act(torch.from_numpy(obs_np))
            obs_np, *_ = env.step(a.numpy().astype(np.uint8))

        # 롤아웃을 정책과 전송으로 쪼갠다. 합계만 재면 미달일 때 어디를 고칠지 알 수 없다
        # — Phase 2 의 (a)(b)(c) 분해와 같은 이유다.
        policy_s = 0.0
        t0 = time.perf_counter()
        for _ in range(T):
            t1 = time.perf_counter()
            a, _logp, _v = net.act(torch.from_numpy(obs_np))
            action_np = a.numpy().astype(np.uint8)
            policy_s += time.perf_counter() - t1
            obs_np, *_ = env.step(action_np)
        rollout_s = time.perf_counter() - t0

        batch = num_envs * T
        b = make_batch(batch, obs_dim, ACTION_COUNT, "cpu")
        ppo_like_iteration(net, opt, b, epochs=1, minibatches=minibatches)
        t0 = time.perf_counter()
        ppo_like_iteration(net, opt, b, epochs=epochs, minibatches=minibatches)
        update_s = time.perf_counter() - t0

        total = rollout_s + update_s
        return {
            "num_envs": float(env.num_envs), "T": float(T), "batch": float(batch),
            "rollout_s": rollout_s, "policy_s": policy_s,
            "transfer_s": rollout_s - policy_s,
            "update_s": update_s, "iter_s": total,
            "env_steps_per_sec": batch / total,
            "update_share": update_s / total,
            "obs_dim": float(obs_dim),
        }
    finally:
        env.close()


def main() -> None:
    p = argparse.ArgumentParser(description="P1 학습 예산 측정 (plan.md §3)")
    p.add_argument("--obs-dim", type=int, default=41, help="진영 플래그 포함 (FR-14)")
    p.add_argument("--actions", type=int, default=18)
    p.add_argument("--hiddens", type=int, nargs="+", default=[128, 256])
    p.add_argument("--sizes", type=int, nargs="+", default=[256, 512, 1024])
    p.add_argument("--threads", type=int, nargs="+", default=[1, 4, 8])
    p.add_argument("--batch", type=int, default=DEFAULT_BATCH)
    p.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    p.add_argument("--minibatches", type=int, default=DEFAULT_MINIBATCHES)
    p.add_argument("--target", default=os.environ.get("PIKA_ENV_TARGET"),
                   help="주면 (e) 종단까지 잰다")
    p.add_argument("--num-envs", type=int, default=512)
    p.add_argument("--rollout-t", type=int, default=128)
    p.add_argument("--skip-mps", action="store_true")
    args = p.parse_args()

    default_threads = torch.get_num_threads()
    print(f"torch {torch.__version__} · 기본 스레드 {default_threads} · "
          f"MPS {'있음' if torch.backends.mps.is_available() else '없음'}")
    print(f"관측 {args.obs_dim}차원 · 행동 {args.actions}개 · "
          f"배치 {args.batch:,} · 에폭 {args.epochs} · 미니배치 {args.minibatches}")
    print()

    print("── (a) 정책 forward (act: log_softmax + multinomial + value) ──")
    print(f"{'H':<6}{'N':>8}{'배치':>12}{'env당':>12}")
    print("-" * 38)
    for r in bench_forward(args.obs_dim, args.actions, args.sizes, args.hiddens, "cpu"):
        print(f"{int(r['hidden']):<6}{int(r['n']):>8}{r['ms']:>10.3f}ms{r['per_env_us']:>10.3f}µs")
    print()

    print(f"── (b) 스레드 수 × 업데이트 한 반복 (H={args.hiddens[0]}) ──")
    print(f"{'threads':<10}{'업데이트':>12}{'샘플패스/s':>16}")
    print("-" * 38)
    for r in bench_threads(args.obs_dim, args.actions, args.hiddens[0], args.threads,
                           args.batch, args.epochs, args.minibatches):
        print(f"{int(r['threads']):<10}{r['update_s']:>10.3f}s{r['samples_per_sec']:>16,.0f}")
    torch.set_num_threads(default_threads)
    print()

    print(f"── (c) 업데이트 한 반복 — CPU ({default_threads} 스레드) ──")
    print(f"{'H':<6}{'파라미터':>12}{'업데이트':>12}{'상한 step/s':>16}")
    print("-" * 46)
    for r in bench_update(args.obs_dim, args.actions, args.hiddens, "cpu",
                          args.batch, args.epochs, args.minibatches):
        print(f"{int(r['hidden']):<6}{int(r['params']):>12,}{r['update_s']:>10.3f}s"
              f"{r['ceiling_steps_per_sec']:>16,.0f}")
    print()

    if not args.skip_mps and torch.backends.mps.is_available():
        print("── (d) 업데이트 한 반복 — MPS (참고. NFR-5 는 CPU 가 기본이다) ──")
        print(f"{'H':<6}{'파라미터':>12}{'업데이트':>12}{'상한 step/s':>16}")
        print("-" * 46)
        for r in bench_update(args.obs_dim, args.actions, args.hiddens, "mps",
                              args.batch, args.epochs, args.minibatches):
            print(f"{int(r['hidden']):<6}{int(r['params']):>12,}{r['update_s']:>10.3f}s"
                  f"{r['ceiling_steps_per_sec']:>16,.0f}")
        print()

    if args.target:
        print(f"── (e) 종단 한 반복 — 실제 서버 (N={args.num_envs}, T={args.rollout_t}) ──")
        T_ = args.rollout_t
        r = bench_end_to_end(args.target, args.obs_dim, args.hiddens[0], args.num_envs,
                             T_, args.epochs, args.minibatches)
        print(f"   관측 {int(r['obs_dim'])}차원 · 배치 {int(r['batch']):,}")
        print(f"   롤아웃   {r['rollout_s']:.3f}s"
              f"   = 정책 {r['policy_s']:.3f}s ({r['policy_s'] / T_ * 1000:.3f}ms/배치)"
              f" + 전송·서버 {r['transfer_s']:.3f}s ({r['transfer_s'] / T_ * 1000:.3f}ms/배치)")
        print(f"   업데이트 {r['update_s']:.3f}s  ({r['update_share'] * 100:.0f}%)")
        print(f"   합계     {r['iter_s']:.3f}s  →  {r['env_steps_per_sec']:,.0f} env-step/s")
        print(f"   업데이트/롤아웃 비율: {r['update_s'] / r['rollout_s']:.1f}배")


if __name__ == "__main__":
    main()
