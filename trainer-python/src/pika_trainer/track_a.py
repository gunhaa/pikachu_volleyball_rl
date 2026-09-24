"""Track A 러너 — FSM(컴퓨터)을 상대로 PPO 를 돌린다.

(FR-3, FR-5, FR-6, FR-8, FR-9, FR-10, M3-a, M3-b, M3-d, M3-e, M3-h / plan.md §5, §6.2, §11)

─────────────────────────────────────────────────────────────────────────────
이 파일에 알고리즘이 없다
─────────────────────────────────────────────────────────────────────────────
PPO 는 `ppo.py`, 마스킹과 GAE 는 `rollout.py`, 스케줄은 `schedules.py`, 평가는
`evaluate.py` 에 있다. 여기 있는 것은 **배선과 기록**뿐이다. 그래야 Phase 7 의 Track B 가
이 파일만 갈아 끼우고 나머지를 그대로 쓴다 (NFR-4 의 방향).

FSM 을 아는 Python 파일은 `track_a.py` 와 `evaluate.py` **둘뿐**이라는 것이 NFR-4 이고,
그 "안다" 가 실제로 나타나는 곳은 `p1="external", p2="fsm"` 한 줄이다.

─────────────────────────────────────────────────────────────────────────────
시드 하나가 전부를 정한다 (FR-10, M3-d / plan.md §11)
─────────────────────────────────────────────────────────────────────────────
====================  ================================================
`torch.manual_seed`   망 초기화 · 행동 샘플링 · 미니배치 셔플
`np.random.seed`      지금은 쓰는 곳이 없다. 나중에 생겨도 새지 않게 못 박는다
서버 `base_seed`      환경의 초기 상태와 FSM 의 boldness 추첨
`torch` 스레드 수     리덕션 순서 — **스레드가 바뀌면 부동소수 합이 바뀐다**
====================  ================================================

그래서 **주기 평가가 전역 RNG 를 건드리면 안 된다.** 건드리면 학습 결과가 "평가를 몇 번
했는가" 의 함수가 되고, M3-d 는 우연히 통과하거나 우연히 깨진다. `evaluate.py` 의
`net_policy` 가 전용 `Generator` 를 쓰는 이유가 여기서 값을 치른다 (argmax 는 아예 RNG 를
쓰지 않는다).

─────────────────────────────────────────────────────────────────────────────
JVM 이 둘이다 (plan.md §9.3, §12 함정 5)
─────────────────────────────────────────────────────────────────────────────
서버는 **단일 테넌트**다 (`ConfigureReply.session_id`). 주기 평가가 학습 서버에
`Configure` 를 부르면 학습 세션이 그 자리에서 무효가 되고 다음 `Step` 이
`FAILED_PRECONDITION` 으로 죽는다. 그래서 평가는 자기 서버 프로세스를 쓴다.
"""

from __future__ import annotations

import argparse
import time
from contextlib import ExitStack
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
import torch

from .env_client import ACTION_COUNT, EnvOptions, PikaVectorEnv
from .evaluate import EvalReport, evaluate_boldness_axis, evaluate_policy, net_policy
from .metrics import SCHEMA_VERSION, JsonlLogger, RunDir, git_commit
from .net import ActorCritic, configure_torch
from .ppo import PPOConfig, PPOUpdater
from .rollout import RolloutCollector
from .schedules import Linear, RewardWeighting, shaping
from .server_process import launch_server, repo_root


# ═══════════════════════════════════════════════════════════════════════════
# 구성
# ═══════════════════════════════════════════════════════════════════════════


@dataclass(frozen=True)
class TrackAConfig:
    """`plan.md` §8.1 의 표가 그대로 여기 있다.

    **값을 바꾸면 바꾼 이유를 `plan.md` 에 적는다** (tasks.md P6). 이 dataclass 가
    `config.json` 과 체크포인트에 통째로 들어가므로, 나중에 "그 런은 무슨 설정이었나" 에
    답하는 것은 이 파일 하나다.
    """

    # ── 시드와 예산 ──────────────────────────────────────────────────────
    seed: int = 0
    total_steps: int = 50_000_000
    num_envs: int = 512
    horizon: int = 128           # T (plan.md §5.3)
    threads: int = 4             # §3.3 실측. 1 은 2배 느리고 8 은 4보다 느리다

    # ── 망 ───────────────────────────────────────────────────────────────
    hidden: int = 128

    # ── PPO (§8.1) ───────────────────────────────────────────────────────
    gamma: float = 0.997         # §5.2 — {0.99, 0.997, 0.999} 를 A/B 한다
    lam: float = 0.95
    clip_eps: float = 0.2
    value_coef: float = 0.5
    max_grad_norm: float = 0.5
    epochs: int = 4
    minibatches: int = 8
    target_kl: float = 0.02
    lr_start: float = 3e-4
    lr_end: float = 0.0
    ent_start: float = 0.02
    ent_end: float = 0.002
    #: torch 의 기본값. `plan.md` §8.1 의 표에 없는 손잡이이므로 **관습(1e-5)을 따르지
    #: 않는다** — advantage 를 정규화하므로 그래디언트 스케일이 이미 O(1) 이다.
    adam_eps: float = 1e-8

    # ── 환경 ─────────────────────────────────────────────────────────────
    max_rally_frames: int = 3_000   # PRD §2.2 — 편의가 아니라 안전장치다
    winning_score: int = 15

    # ── 셰이핑 (§6.2) ────────────────────────────────────────────────────
    #: `crossed_net` 이 `ball_touch` 보다 **커야 한다.** 반대면 자기 진영 저글링이 최적이
    #: 되고, 그 실패는 승률보다 truncation 비율에 먼저 나온다 (M3-h).
    shaping_crossed_net: float = 0.10
    shaping_ball_touch: float = 0.05

    # ── 평가·체크포인트 주기 (env_steps 단위, 0 이면 끈다) ───────────────
    eval_every: int = 2_000_000
    eval_games: int = 20          # 주기 평가는 **추세용**이다. M3-a 의 400 은 최종 평가
    eval_num_envs: int = 64
    ckpt_every: int = 5_000_000
    log_every: int = 10           # 콘솔 출력 간격 (JSONL 은 매 반복 남는다)

    # ── 파생값 ───────────────────────────────────────────────────────────

    @property
    def batch_size(self) -> int:
        return self.num_envs * self.horizon

    @property
    def iterations(self) -> int:
        """총 반복 수(올림). 마지막 롤아웃이 `total_steps` 를 넘긴다."""
        return -(-self.total_steps // self.batch_size)

    def ppo_config(self) -> PPOConfig:
        return PPOConfig(
            clip_eps=self.clip_eps,
            value_coef=self.value_coef,
            entropy_coef=self.ent_start,
            max_grad_norm=self.max_grad_norm,
            epochs=self.epochs,
            minibatches=self.minibatches,
            target_kl=self.target_kl,
        )

    def env_options(self) -> EnvOptions:
        """`for_policy` 를 쓴다 — 진영 플래그(FR-14)와 절반 스왑(FR-3)을 빠뜨리지 않기 위해서다."""
        return EnvOptions.for_policy(
            num_envs=self.num_envs,
            base_seed=self.seed,
            p1="external",
            p2="fsm",
            winning_score=self.winning_score,
            max_rally_frames=self.max_rally_frames,
        )

    def weighting(self, term_names: list[str]) -> RewardWeighting:
        """셰이핑 스케줄 (FR-6). 항 순서는 **서버가 선언한 것**을 따른다."""
        return RewardWeighting(
            {
                "rally_win": 1.0,
                "crossed_net": shaping(self.shaping_crossed_net),
                "ball_touch": shaping(self.shaping_ball_touch),
            },
            term_names,
        )


# ═══════════════════════════════════════════════════════════════════════════
# 체크포인트 (FR-8)
# ═══════════════════════════════════════════════════════════════════════════

#: `evaluate.load_net` 이 읽는 키. 규약이 갈라지면 평가가 체크포인트를 못 읽는다.
CKPT_NET_KEYS = ("model", "obs_dim", "action_count", "hidden")


def save_checkpoint(
    path: str | Path,
    *,
    net: ActorCritic,
    optimizer: torch.optim.Optimizer,
    config: TrackAConfig,
    env_steps: int,
    iteration: int,
    git: str,
) -> Path:
    """가중치 + 옵티마이저 + 하이퍼파라미터 + 시드 + git 해시 (plan.md §11).

    RNG 상태도 담는다 — 그래야 재개한 학습이 **끊긴 지점의 연속**이 된다. 다만 서버 쪽
    환경 상태는 담기지 않으므로 재개는 "비슷한 지점에서 다시 시작" 이지 바이트 단위
    이어붙이기가 아니다. 그 사실을 아는 것이 재개 결과를 M3-d 로 착각하지 않게 한다.

    `weights_only=True` 로 읽히도록 **텐서와 기본 자료형만** 담는다 (`evaluate.load_net`).
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save(
        {
            "schema": SCHEMA_VERSION,
            "model": net.state_dict(),
            "obs_dim": net.obs_dim,
            "action_count": net.action_count,
            "hidden": net.hidden,
            "optimizer": optimizer.state_dict(),
            "config": asdict(config),
            "env_steps": env_steps,
            "iteration": iteration,
            "seed": config.seed,
            # 스레드 수가 바뀌면 리덕션 순서가 바뀌어 재현성이 깨진다 (plan.md §11).
            "threads": config.threads,
            "torch_rng_state": torch.get_rng_state(),
            # ⚠️ `str(...)` 이 필수다. `torch.__version__` 은 `str` 이 아니라 `TorchVersion`
            #    객체이고, `weights_only=True` 언피클러가 **거부한다** — 그대로 담으면
            #    평가기가 체크포인트를 못 읽는다 (`test_track_a.py` 가 잡았다).
            "torch_version": str(torch.__version__),
            "git_commit": git,
        },
        path,
    )
    return path


def load_checkpoint(path: str | Path) -> dict[str, Any]:
    blob = torch.load(Path(path), map_location="cpu", weights_only=True)
    missing = [k for k in CKPT_NET_KEYS if k not in blob]
    if missing:
        raise ValueError(f"체크포인트에 없는 키: {missing} ({path})")
    return blob


# ═══════════════════════════════════════════════════════════════════════════
# 러너
# ═══════════════════════════════════════════════════════════════════════════


class TrackARunner:
    """학습 루프. 환경·망·수집기·업데이터를 들고 있다.

    환경은 **이 클래스가 만들고 닫는다**. `target` 은 이미 떠 있는 서버여야 한다 —
    JVM 기동은 호출자(`main`)의 몫이고, 그래야 테스트가 세션 서버를 재사용할 수 있다.
    """

    def __init__(
        self,
        target: str,
        config: TrackAConfig,
        *,
        run: RunDir | None = None,
        eval_target: str | None = None,
        verbose: bool = True,
    ) -> None:
        self.config = config
        self.run = run
        self.eval_target = eval_target
        self.verbose = verbose
        self.git = git_commit(repo_root())

        # (1) 시드를 **환경을 만들기 전에** 건다. 망 초기화가 여기에 달려 있다.
        configure_torch(config.threads)
        torch.manual_seed(config.seed)
        np.random.seed(config.seed)

        # (2) 환경 — 진영 절반씩 (FR-3), 진영 플래그 on (FR-14).
        self.env = PikaVectorEnv(target, config.env_options())
        self.term_names = list(self.env.reward_term_names)
        self.weighting = config.weighting(self.term_names)

        # (3) 망과 옵티마이저. `obs_dim` 은 **서버가 정한다** — 41 을 상수로 박으면
        #     진영 플래그 설정이 갈라진 날 조용히 틀린 망을 학습한다.
        self.net = ActorCritic(self.env.obs_dim, ACTION_COUNT, config.hidden)
        self.optimizer = torch.optim.Adam(
            self.net.parameters(), lr=config.lr_start, eps=config.adam_eps,
        )
        self.collector = RolloutCollector(
            self.env, self.net, gamma=config.gamma, lam=config.lam,
        )
        self.updater = PPOUpdater(self.net, self.optimizer, config.ppo_config())

        self.lr_schedule = Linear(config.lr_start, config.lr_end)
        self.ent_schedule = Linear(config.ent_start, config.ent_end)

        self.env_steps = 0
        self.iteration = 0
        self.wall_s = 0.0
        self.last_eval: EvalReport | None = None
        self._next_eval = config.eval_every
        self._next_ckpt = config.ckpt_every

        self._metrics: JsonlLogger | None = None
        self._evals: JsonlLogger | None = None
        if run is not None:
            run.write_json(run.config_path, self.describe())
            self._metrics = JsonlLogger(run.metrics_path)
            self._evals = JsonlLogger(run.evals_path)

    # ── 기록 ─────────────────────────────────────────────────────────────

    def describe(self) -> dict[str, Any]:
        """`config.json` 의 내용. 런을 재현하는 데 필요한 전부다."""
        return {
            "schema": SCHEMA_VERSION,
            "config": asdict(self.config),
            "git_commit": self.git,
            "torch_version": str(torch.__version__),
            "obs_dim": self.env.obs_dim,
            "action_count": ACTION_COUNT,
            "reward_term_names": self.term_names,
            "batch_size": self.config.batch_size,
            "iterations": self.config.iterations,
            "shaping": {
                name: repr(sched) for name, sched in self.weighting.schedules.items()
            },
            "lr": repr(self.lr_schedule),
            "entropy_coef": repr(self.ent_schedule),
            "started_at": datetime.now().isoformat(timespec="seconds"),
        }

    # ── 한 반복 ──────────────────────────────────────────────────────────

    def train_iteration(self) -> dict[str, Any]:
        """롤아웃 한 번 + 업데이트 한 번. 돌려주는 것이 JSONL 한 줄이다."""
        cfg = self.config
        progress = self.env_steps / cfg.total_steps if cfg.total_steps else 0.0
        weights = self.weighting.at(progress)

        t0 = time.perf_counter()
        batch, rollout_metrics = self.collector.collect(cfg.horizon, weights)
        t1 = time.perf_counter()
        stats = self.updater.update(
            batch,
            lr=self.lr_schedule(progress),
            entropy_coef=self.ent_schedule(progress),
        )
        t2 = time.perf_counter()

        self.iteration += 1
        self.env_steps += rollout_metrics.env_steps
        self.wall_s += t2 - t0

        roll = rollout_metrics.to_dict()
        # ⚠️ 롤아웃의 `env_steps` 는 **이 반복의** 스텝 수다. 러너의 누적과 이름이 겹치므로
        #    꺼내서 다른 이름을 준다 — 덮어쓰게 두면 처리량 계산이 조용히 틀린다.
        iter_steps = roll.pop("env_steps")

        row = {
            "iter": self.iteration,
            "env_steps": self.env_steps,
            "iter_env_steps": iter_steps,
            "progress": progress,
            "wall_s": t2 - t0,
            "rollout_s": t1 - t0,
            "update_s": t2 - t1,
            "steps_per_s": iter_steps / (t2 - t0) if t2 > t0 else 0.0,
            "shaping_w": self.weighting.as_dict(progress),
            **stats.to_dict(),
            **roll,
        }
        if self._metrics is not None:
            self._metrics.log(row)
        return row

    # ── 루프 ─────────────────────────────────────────────────────────────

    def train(self) -> dict[str, Any]:
        """`total_steps` 를 채울 때까지. 돌려주는 것은 마지막 반복의 행이다."""
        cfg = self.config
        if self.verbose:
            print(
                f"── Track A 학습 ── seed={cfg.seed} γ={cfg.gamma} "
                f"N={cfg.num_envs} T={cfg.horizon} 배치={cfg.batch_size:,} "
                f"목표={cfg.total_steps:,} step ({cfg.iterations:,} 반복)",
            )
        row: dict[str, Any] = {}
        try:
            while self.env_steps < cfg.total_steps:
                row = self.train_iteration()
                if self.verbose and self.iteration % cfg.log_every == 0:
                    self._print(row)
                self._maybe_eval()
                self._maybe_checkpoint()
        finally:
            self._finish()
        return row

    def _print(self, row: dict[str, Any]) -> None:
        print(
            f"  it {row['iter']:>5} {row['env_steps'] / 1e6:>7.2f}M "
            f"| 랠리승 {row['rally_win_rate']:.3f} "
            f"(좌 {row['left.rally_win_rate']:.3f} 우 {row['right.rally_win_rate']:.3f}) "
            f"| 길이 {row['rally_frames_mean']:>6.1f} trunc {row['trunc_rate']:.3f} "
            f"| KL {row['kl']:.4f} ent {row['entropy']:.3f} ev {row['explained_var']:>6.3f} "
            f"| {row['steps_per_s']:>7,.0f} step/s",
        )

    # ── 주기 작업 ────────────────────────────────────────────────────────

    def _maybe_eval(self) -> None:
        cfg = self.config
        if not cfg.eval_every or self.eval_target is None:
            return
        if self.env_steps < self._next_eval:
            return
        self._next_eval = self.env_steps + cfg.eval_every
        self.evaluate(games_per_side=cfg.eval_games)

    def evaluate(self, *, games_per_side: int, num_envs: int | None = None) -> EvalReport:
        """평가 서버에 붙어 argmax 정책을 채점한다 (FR-7).

        ⚠️ **학습 서버가 아니다** (plan.md §12 함정 5). 그리고 argmax 이므로 전역 RNG 를
           한 번도 쓰지 않는다 — 평가 횟수가 학습 결과를 바꾸면 M3-d 가 무의미해진다.
        """
        if self.eval_target is None:
            raise RuntimeError("평가 서버가 없습니다 (eval_target).")
        cfg = self.config
        options = EnvOptions.for_policy(
            num_envs=num_envs or cfg.eval_num_envs,
            base_seed=cfg.seed,
            p1="external",
            p2="fsm",
            winning_score=cfg.winning_score,
            max_rally_frames=cfg.max_rally_frames,
        )
        env = PikaVectorEnv(self.eval_target, options)
        try:
            if env.obs_dim != self.net.obs_dim:
                raise RuntimeError(
                    f"평가 서버의 관측이 {env.obs_dim}차원인데 망은 {self.net.obs_dim}차원입니다.",
                )
            t0 = time.perf_counter()
            report = evaluate_policy(
                env, net_policy(self.net, mode="argmax"),
                games_per_side=games_per_side, mode="argmax",
            )
        finally:
            env.close()

        self.last_eval = report
        if self._evals is not None:
            self._evals.log({
                "iter": self.iteration,
                "env_steps": self.env_steps,
                "eval_s": time.perf_counter() - t0,
                **report.to_dict(),
            })
        if self.verbose:
            flag = "" if report.is_reportable else "  ⚠️ 미결 과다 — 보고 불가"
            print(
                f"  ▸ 평가 {self.env_steps / 1e6:.2f}M: 합산 {report.combined:.4f} "
                f"(좌 {report.as_left.rate:.4f} 우 {report.as_right.rate:.4f}, "
                f"진영차 {report.side_gap:+.4f}){flag}",
            )
        return report

    def _maybe_checkpoint(self) -> None:
        if not self.config.ckpt_every or self.env_steps < self._next_ckpt:
            return
        self._next_ckpt = self.env_steps + self.config.ckpt_every
        self.save(self.env_steps)

    def save(self, tag: int | str) -> Path | None:
        if self.run is None:
            return None
        path = save_checkpoint(
            self.run.checkpoint_path(tag),
            net=self.net, optimizer=self.optimizer, config=self.config,
            env_steps=self.env_steps, iteration=self.iteration, git=self.git,
        )
        if self.verbose:
            print(f"  ▸ 체크포인트 {path}")
        return path

    def restore(self, path: str | Path) -> None:
        """체크포인트에서 학습을 재개한다.

        ⚠️ 서버 쪽 환경 상태는 복원되지 않는다 (랠리 진행도·FSM 의 추첨 자리). 재개는
           "비슷한 지점에서 다시 시작" 이지 바이트 단위 이어붙이기가 아니다. 그래서
           **재현성(M3-d)의 근거로 재개를 쓰면 안 된다** — 그것은 처음부터 두 번 돌려 잰다.
        """
        blob = load_checkpoint(path)
        if blob["obs_dim"] != self.net.obs_dim:
            raise ValueError(
                f"체크포인트의 관측이 {blob['obs_dim']}차원인데 서버는 {self.net.obs_dim}차원입니다.",
            )
        if int(blob.get("threads", self.config.threads)) != self.config.threads:
            raise ValueError(
                f"체크포인트의 스레드 수({blob['threads']})가 지금({self.config.threads})과 "
                "다릅니다. 리덕션 순서가 바뀌어 결과가 갈라집니다 (plan.md §11).",
            )
        self.net.load_state_dict(blob["model"])
        self.optimizer.load_state_dict(blob["optimizer"])
        self.env_steps = int(blob["env_steps"])
        self.iteration = int(blob["iteration"])
        if "torch_rng_state" in blob:
            torch.set_rng_state(blob["torch_rng_state"].to(torch.uint8))
        self._next_eval = self.env_steps + self.config.eval_every
        self._next_ckpt = self.env_steps + self.config.ckpt_every

    # ── 정리 ─────────────────────────────────────────────────────────────

    def _finish(self) -> None:
        for logger in (self._metrics, self._evals):
            if logger is not None:
                logger.close()
        self._metrics = self._evals = None

    def close(self) -> None:
        self._finish()
        self.env.close()

    def __enter__(self) -> TrackARunner:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()


# ═══════════════════════════════════════════════════════════════════════════
# CLI
# ═══════════════════════════════════════════════════════════════════════════


def build_config(args: argparse.Namespace) -> TrackAConfig:
    return TrackAConfig(
        seed=args.seed,
        total_steps=args.total_steps,
        num_envs=args.num_envs,
        horizon=args.horizon,
        threads=args.threads,
        hidden=args.hidden,
        gamma=args.gamma,
        lam=args.lam,
        clip_eps=args.clip_eps,
        value_coef=args.value_coef,
        epochs=args.epochs,
        minibatches=args.minibatches,
        target_kl=args.target_kl,
        lr_start=args.lr,
        ent_start=args.ent_start,
        ent_end=args.ent_end,
        shaping_crossed_net=args.shaping_crossed_net,
        shaping_ball_touch=args.shaping_ball_touch,
        eval_every=args.eval_every,
        eval_games=args.eval_games,
        ckpt_every=args.ckpt_every,
        log_every=args.log_every,
    )


def default_run_id(config: TrackAConfig) -> str:
    stamp = datetime.now().strftime("%m%d-%H%M%S")
    return f"track-a-g{config.gamma}-s{config.seed}-{stamp}"


def build_parser() -> argparse.ArgumentParser:
    """CLI 파서. **`main()` 에서 분리해 둔다** — 테스트가 기본값을 읽으려고 `main()` 을
    반쯤 실행하는 일이 없도록. 기본값이 :class:`TrackAConfig` 와 갈라지면 "스크립트로
    돌린 것" 과 "테스트한 것" 이 달라진다.
    """
    p = argparse.ArgumentParser(description="Track A 학습 (plan.md §5, §6, §11)")
    p.add_argument("--seed", type=int, default=0, help="이 하나가 torch·numpy·서버를 전부 정한다")
    p.add_argument("--total-steps", type=int, default=50_000_000)
    p.add_argument("--num-envs", type=int, default=512)
    p.add_argument("--horizon", type=int, default=128)
    p.add_argument("--threads", type=int, default=4, help="재현성의 일부다 (plan.md §11)")
    p.add_argument("--hidden", type=int, default=128)
    p.add_argument("--gamma", type=float, default=0.997, help="A/B 축 (plan.md §5.2)")
    p.add_argument("--lam", type=float, default=0.95)
    p.add_argument("--clip-eps", type=float, default=0.2)
    p.add_argument("--value-coef", type=float, default=0.5)
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--minibatches", type=int, default=8)
    p.add_argument("--target-kl", type=float, default=0.02)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--ent-start", type=float, default=0.02)
    p.add_argument("--ent-end", type=float, default=0.002)
    p.add_argument("--shaping-crossed-net", type=float, default=0.10)
    p.add_argument("--shaping-ball-touch", type=float, default=0.05,
                   help="crossed-net 보다 작아야 한다 (M3-h)")
    p.add_argument("--eval-every", type=int, default=2_000_000, help="0 이면 주기 평가를 끈다")
    p.add_argument("--eval-games", type=int, default=20, help="주기 평가의 **진영별** 게임 수")
    p.add_argument("--ckpt-every", type=int, default=5_000_000)
    p.add_argument("--log-every", type=int, default=10)
    p.add_argument("--final-games", type=int, default=400,
                   help="마지막 평가의 진영별 게임 수 (M3-a 는 400)")
    p.add_argument("--final-boldness", action="store_true",
                   help="마지막에 boldness 0~4 진단 축도 돌린다 (FR-13)")
    p.add_argument("--run-id", default=None)
    p.add_argument("--runs-root", default="runs")
    p.add_argument("--target", default=None, help="학습용 서버. 없으면 직접 띄운다")
    p.add_argument("--eval-target", default=None, help="평가용 **별도** 서버 (plan.md §9.3)")
    p.add_argument("--resume", default=None, help="이 체크포인트에서 재개")
    return p


def main() -> None:
    args = build_parser().parse_args()
    config = build_config(args)
    if config.shaping_ball_touch > config.shaping_crossed_net:
        # 막지는 않는다 — A/B 로 일부러 뒤집어 볼 수 있다. 다만 조용히 지나가면 안 된다.
        print("⚠️ ball_touch 가 crossed_net 보다 큽니다. 저글링 정책의 조건입니다 (M3-h).")

    run = RunDir.create(args.run_id or default_run_id(config), root=args.runs_root)
    print(f"런 디렉터리: {run.path}")

    with ExitStack() as stack:
        target = args.target or stack.enter_context(launch_server())
        eval_target = args.eval_target
        if eval_target is None and (args.eval_every or args.final_games):
            # 두 번째 JVM 은 다시 빌드하지 않는다 — 첫 번째가 이미 최신으로 만들었다.
            eval_target = stack.enter_context(launch_server(build=False))

        runner = stack.enter_context(
            TrackARunner(target, config, run=run, eval_target=eval_target),
        )
        if args.resume:
            runner.restore(args.resume)
            print(f"재개: {args.resume} ({runner.env_steps:,} step, {runner.iteration} 반복)")

        runner.train()
        runner.save("final")

        if args.final_games:
            print("\n── 최종 평가 (M3-a, M3-b) ──")
            report = runner.evaluate(games_per_side=args.final_games, num_envs=64)
            print(report)
            run.write_json(run.eval_report_path("final"), report.to_dict())

        if args.final_boldness:
            print("\n── 진단: boldness 별 승률 (FR-13) ──")
            axis = evaluate_boldness_axis(
                eval_target,
                lambda _env: net_policy(runner.net, mode="argmax"),
                games_per_side=50, num_envs=64, base_seed=config.seed,
                max_rally_frames=config.max_rally_frames,
            )
            for b, r in axis.items():
                print(f"  b={b}  합산 {r.combined:.4f}  좌 {r.as_left.rate:.4f}  "
                      f"우 {r.as_right.rate:.4f}  진영차 {r.side_gap:+.4f}")
            run.write_json(
                run.eval_report_path("boldness"),
                {str(b): r.to_dict() for b, r in axis.items()},
            )

    print(f"\n총 {runner.env_steps:,} env-step · {runner.wall_s:,.0f}s "
          f"· 평균 {runner.env_steps / max(runner.wall_s, 1e-9):,.0f} step/s (M3-e)")


if __name__ == "__main__":
    main()
