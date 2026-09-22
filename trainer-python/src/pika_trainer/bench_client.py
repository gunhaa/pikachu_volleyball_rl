"""(c) Python 종단 처리량. (M2-a, NFR-3, plan.md §11)

(a) env 단독, (b) gRPC 루프백에 이어지는 **세 번째 지점**이다.
(b)−(c) 가 Python 쪽 비용 = 디코딩 + 정책 forward + GIL 이다.

더미 정책은 40 → 256 → 256 → 18 MLP 를 numpy 로 돌린다. 무작위 행동으로 재면
"정책이 없는 처리량" 을 재게 되고, 그 숫자는 Phase 3 에서 쓸모가 없다.
(torch 는 Phase 3 에 들어온다. 파라미터 수는 같고, CPU forward 비용도 비슷한 자리다.)
"""

from __future__ import annotations

import argparse
import os
import time

import numpy as np

from .env_client import ACTION_COUNT, EnvOptions, PikaVectorEnv

#: 목표. PRD §4 의 M2-a.
TARGET_ENV_STEPS_PER_SEC = 50_000.0


class DummyPolicy:
    """고정 가중치 MLP. 학습하지 않는다 — forward 비용만 재기 위한 것이다."""

    def __init__(self, obs_dim: int, hidden: int = 256, rng: np.random.Generator | None = None) -> None:
        rng = rng or np.random.default_rng(0)
        scale = 1.0 / np.sqrt(obs_dim)
        self.w1 = rng.normal(0, scale, (obs_dim, hidden)).astype(np.float32)
        self.b1 = np.zeros(hidden, dtype=np.float32)
        self.w2 = rng.normal(0, 1 / np.sqrt(hidden), (hidden, hidden)).astype(np.float32)
        self.b2 = np.zeros(hidden, dtype=np.float32)
        self.w3 = rng.normal(0, 1 / np.sqrt(hidden), (hidden, ACTION_COUNT)).astype(np.float32)
        self.b3 = np.zeros(ACTION_COUNT, dtype=np.float32)
        self.params = self.w1.size + self.b1.size + self.w2.size + self.b2.size + self.w3.size + self.b3.size

    def __call__(self, obs: np.ndarray) -> np.ndarray:
        h = np.maximum(obs @ self.w1 + self.b1, 0.0)
        h = np.maximum(h @ self.w2 + self.b2, 0.0)
        logits = h @ self.w3 + self.b3
        return np.argmax(logits, axis=1).astype(np.uint8)


def bench(target: str, num_envs: int, steps: int, p2: str = "fsm", warmup: int | None = None) -> dict[str, float]:
    env = PikaVectorEnv(target, EnvOptions(num_envs=num_envs, base_seed=1, p2=p2))
    try:
        policy = DummyPolicy(env.obs_dim)
        obs, _ = env.reset(seed=1)

        for _ in range(warmup if warmup is not None else max(10, steps // 10)):
            obs, *_ = env.step(policy(obs))

        # 정책 forward 만 따로도 재 둔다. (c) 가 미달일 때 "서버가 느린가 정책이 느린가" 를
        # 묻게 되는데, 그 답을 나중에 추측하지 않으려면 지금 재는 것이 싸다.
        t_policy = time.perf_counter()
        for _ in range(steps):
            policy(obs)
        policy_seconds = time.perf_counter() - t_policy

        t0 = time.perf_counter()
        for _ in range(steps):
            obs, rewards, terminated, truncated, info = env.step(policy(obs))
        seconds = time.perf_counter() - t0

        env_steps = steps * env.num_envs
        return {
            "num_envs": float(env.num_envs),
            "env_steps_per_sec": env_steps / seconds,
            "batch_ms": seconds / steps * 1000,
            "policy_ms": policy_seconds / steps * 1000,
            "server_env_steps_per_sec": env.health().env_steps_per_sec,
            "params": float(policy.params),
        }
    finally:
        env.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="(c) Python 종단 처리량")
    parser.add_argument("--target", default=os.environ.get("PIKA_ENV_TARGET", "unix:///tmp/pika-env.sock"))
    parser.add_argument("--steps", type=int, default=500)
    parser.add_argument("--sizes", type=int, nargs="+", default=[64, 256, 1024])
    parser.add_argument("--p2", default="fsm", choices=["fsm", "external"])
    args = parser.parse_args()

    print("── (c) Python 종단 처리량 — 더미 MLP 정책 (M2-a) ──")
    print(f"   대상: {args.target}, 스텝: {args.steps} (워밍업 별도), 상대: {args.p2}")
    print()
    print(f"{'N':<8}{'env-step/s':>14}{'배치시간':>12}{'정책':>10}{'전송+서버':>12}{'예산대비':>11}")
    print("-" * 68)

    results = {}
    for n in args.sizes:
        r = bench(args.target, n, args.steps, p2=args.p2)
        results[n] = r
        budget_ms = r["num_envs"] / TARGET_ENV_STEPS_PER_SEC * 1000
        print(
            f"{int(r['num_envs']):<8}{r['env_steps_per_sec']:>14,.0f}"
            f"{r['batch_ms']:>10.3f}ms{r['policy_ms']:>8.3f}ms"
            f"{r['batch_ms'] - r['policy_ms']:>10.3f}ms"
            f"{r['batch_ms'] / budget_ms * 100:>10.1f}%",
        )

    print("-" * 68)
    print(f"더미 정책 파라미터 {int(results[args.sizes[0]]['params']):,}개 (40 → 256 → 256 → 18)")
    print(f"M2-a 기준: N=256 에서 ≥ {TARGET_ENV_STEPS_PER_SEC:,.0f} env-step/s")

    if 256 in results:
        achieved = results[256]["env_steps_per_sec"]
        verdict = "통과" if achieved >= TARGET_ENV_STEPS_PER_SEC else "미달"
        print(f"  → N=256: {achieved:,.0f} env-step/s ({achieved / TARGET_ENV_STEPS_PER_SEC:.1f}배) — {verdict}")
        if achieved < TARGET_ENV_STEPS_PER_SEC:
            print("  ⚠️ 미달이면 (a)(b)(c) 간극으로 원인을 특정한 뒤에만 손댄다.")
            print("     엔진 병렬화는 오진이다 — 엔진은 예산의 1% 도 쓰지 않는다 (PRD §6).")


if __name__ == "__main__":
    main()
