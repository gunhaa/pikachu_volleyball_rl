"""게임 단위 평가 — 양 진영 승률·진영차·진단 축. (FR-7, FR-13, M3-c, M3-g / plan.md §9)

─────────────────────────────────────────────────────────────────────────────
학습기보다 이것이 먼저다
─────────────────────────────────────────────────────────────────────────────
RL 은 틀려도 돌아간다. 손실은 내려가고 보상 곡선은 올라가는데 정책은 엉뚱한 것을 배운
상태가 흔하다. 그래서 **학습을 시작하기 전에 "0%" 를 정확히 재 둔다** — 이후 모든 숫자가
그 기준선과의 차이다 (`RandomBaselineTest.kt` 가 Kotlin 쪽 기준선이다).

─────────────────────────────────────────────────────────────────────────────
게임 경계를 어떻게 아는가 (plan.md §9.1)
─────────────────────────────────────────────────────────────────────────────
서버의 에피소드는 **랠리**다. 게임(15점제)은 서버가 스스로 이어 붙이고 (`PikaEnv.startGame`),
Python 은 `info` 의 점수로 경계를 읽는다:

    game_over = terminated & (score_me >= winning_score | score_opponent >= winning_score)

집계의 정의는 `GameEvaluator` 와 **같다** (FR-7): 진영별 승률, `combined = (좌 + 우)/2`,
`sideGap = 좌 − 우`. 정의가 갈라지면 Kotlin 기준선과의 대조(M3-g)가 의미를 잃는다.

─────────────────────────────────────────────────────────────────────────────
끝나지 않는 게임 (plan.md §9.4)
─────────────────────────────────────────────────────────────────────────────
결정론적 정책(argmax) × 거의 결정론적인 FSM = **끝나지 않는 게임이 가능하다.** 잘린 랠리는
득점을 주지 않으므로 점수가 영원히 멈출 수 있다 (`BoldnessProbeTest` 가 FSM 끼리의 그 현상을
못 박는다). 그래서 게임당 프레임 상한 :data:`MAX_GAME_FRAMES` 를 두고, 넘긴 게임은 승도 패도
아닌 **`미결`(unresolved)** 로 따로 센다. 조용히 패로 세면 정책이 아니라 상한을 재게 된다.

미결이 :data:`UNRESOLVED_ALARM` 을 넘으면 :attr:`EvalReport.is_reportable` 이 False 다 —
원인을 찾기 전까지 승률을 보고하지 않는다.

─────────────────────────────────────────────────────────────────────────────
표본 설계 — 행마다 같은 할당량
─────────────────────────────────────────────────────────────────────────────
"먼저 끝난 게임부터 N개" 로 세면 **빨리 끝나는 게임이 과대표집된다** (빠른 행이 여러 판을
넣는다). 정책이 세지면 게임이 길어지므로 이 편향은 학습이 진행될수록 커진다. 그래서 행마다
할당량을 고정하고, 모든 행이 제 몫을 채울 때까지 돌린다.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Callable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch

from .env_client import ACTION_COUNT, EnvOptions, PikaVectorEnv
from .net import ActorCritic, configure_torch
from .replay import ReplaySink, sha256_file
from .server_process import launch_server

#: 게임당 프레임 상한 (plan.md §9.4). FSM vs FSM 의 게임당 평균 15,275 의 약 4배다.
MAX_GAME_FRAMES = 60_000

#: 미결 비율이 이것을 넘으면 승률을 보고하지 않는다.
UNRESOLVED_ALARM = 0.01

#: 평가의 기본 스레드 수. **학습과 같아야 한다** (`plan.md` §8.1, NFR-5).
EVAL_THREADS = 4

#: `(num_envs, obs_dim)` 관측 → `(num_envs,)` uint8 행동.
Policy = Callable[[np.ndarray], np.ndarray]


# ═══════════════════════════════════════════════════════════════════════════
# 결과
# ═══════════════════════════════════════════════════════════════════════════


@dataclass(frozen=True)
class SideStats:
    """한 진영에서의 성적. 이름과 계산은 `GameEvaluator.WinRate` 와 같다 (FR-7)."""

    games: int
    wins: int
    unresolved: int
    points_for: int
    points_against: int
    rallies: int
    rally_wins: int
    frames: int
    truncated_rallies: int

    @property
    def rate(self) -> float:
        return self.wins / self.games if self.games else 0.0

    @property
    def rally_win_rate(self) -> float:
        return self.rally_wins / self.rallies if self.rallies else 0.0

    @property
    def mean_rally_frames(self) -> float:
        return self.frames / self.rallies if self.rallies else 0.0

    @property
    def mean_game_frames(self) -> float:
        return self.frames / self.games if self.games else 0.0

    @property
    def truncated_ratio(self) -> float:
        return self.truncated_rallies / self.rallies if self.rallies else 0.0

    def __str__(self) -> str:
        return (
            f"{self.wins}/{self.games} ({self.rate:.4f}) 득점 {self.points_for}:{self.points_against}, "
            f"랠리 {self.rallies} (승 {self.rally_wins} = {self.rally_win_rate:.4f}), "
            f"평균 {self.mean_rally_frames:.1f}프레임, 미결 {self.unresolved}"
        )


@dataclass(frozen=True)
class EvalReport:
    """양 진영 결과. :meth:`to_dict` 가 M3-c 의 비교 단위다."""

    as_left: SideStats
    as_right: SideStats
    mode: str
    games_per_side: int
    max_game_frames: int
    fixed_boldness: int
    base_seed: int
    steps: int

    @property
    def combined(self) -> float:
        """양 진영 평균 승률. 단일 숫자가 필요한 곳은 이것을 쓴다 (M3-a)."""
        return (self.as_left.rate + self.as_right.rate) / 2

    @property
    def side_gap(self) -> float:
        """진영 효과의 크기 (PRD §2.4). 0 에서 멀수록 정책이 진영에 의존한다 (M3-b)."""
        return self.as_left.rate - self.as_right.rate

    @property
    def rally_win_rate(self) -> float:
        rallies = self.as_left.rallies + self.as_right.rallies
        return (self.as_left.rally_wins + self.as_right.rally_wins) / rallies if rallies else 0.0

    @property
    def unresolved(self) -> int:
        return self.as_left.unresolved + self.as_right.unresolved

    @property
    def unresolved_ratio(self) -> float:
        total = self.as_left.games + self.as_right.games + self.unresolved
        return self.unresolved / total if total else 0.0

    @property
    def is_reportable(self) -> bool:
        """미결이 드문가. False 면 **승률을 보고하지 않는다** (plan.md §9.4)."""
        return self.unresolved_ratio <= UNRESOLVED_ALARM

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "games_per_side": self.games_per_side,
            "max_game_frames": self.max_game_frames,
            "fixed_boldness": self.fixed_boldness,
            "base_seed": self.base_seed,
            "steps": self.steps,
            "combined": self.combined,
            "side_gap": self.side_gap,
            "rally_win_rate": self.rally_win_rate,
            "unresolved": self.unresolved,
            "unresolved_ratio": self.unresolved_ratio,
            "as_left": asdict(self.as_left),
            "as_right": asdict(self.as_right),
        }

    def __str__(self) -> str:
        head = f"모드 {self.mode} · 진영별 {self.games_per_side} 게임 · boldness "
        head += "추첨" if self.fixed_boldness < 0 else str(self.fixed_boldness)
        body = (
            f"\n  왼쪽   {self.as_left}"
            f"\n  오른쪽 {self.as_right}"
            f"\n  합산 {self.combined:.4f} · 진영차 {self.side_gap:+.4f} · "
            f"랠리 승률 {self.rally_win_rate:.4f} · 미결 {self.unresolved} ({self.unresolved_ratio:.2%})"
        )
        if not self.is_reportable:
            body += (
                f"\n  ⚠️ 미결이 {UNRESOLVED_ALARM:.0%} 를 넘습니다. 원인을 찾기 전까지 "
                "이 승률을 보고하지 마세요 (plan.md §9.4)."
            )
        return head + body


# ═══════════════════════════════════════════════════════════════════════════
# 정책
# ═══════════════════════════════════════════════════════════════════════════


def uniform_random_policy(seed: int = 0) -> Policy:
    """균등 무작위 정책. **평가기의 자기 검증용이다** (M3-g).

    Kotlin `RandomBaselineTest` 와 같은 분포이지만 난수원이 다르다. 바이트가 아니라
    **통계적으로** 일치해야 서로를 검증한다 — 같은 코드를 두 번 부르면 아무것도 검증하지 못한다.
    """
    rng = np.random.default_rng(seed)

    def policy(obs: np.ndarray) -> np.ndarray:
        return rng.integers(0, ACTION_COUNT, size=obs.shape[0], dtype=np.uint8)

    return policy


def net_policy(net: ActorCritic, *, mode: str = "argmax", seed: int = 0) -> Policy:
    """망을 정책으로 감싼다.

    Args:
        mode: ``argmax`` 는 결정론적이다 — **주 지표**이고 M3-c 가 이것을 요구한다.
            ``sample`` 은 정책의 분산을 보는 보조 지표다.

    ⚠️ ``sample`` 은 전역 torch RNG 를 쓰지 않는다 (`ActorCritic.act` 은 쓴다). 평가가
       전역 RNG 를 소비하면 **학습 시드가 평가 횟수의 함수가 된다** — P6 의 주기적 평가가
       학습 재현성(M3-d)을 조용히 깨뜨린다.
    """
    if mode not in ("argmax", "sample"):
        raise ValueError(f"mode 는 argmax 또는 sample 이어야 합니다: {mode}")
    net.eval()
    generator = torch.Generator().manual_seed(seed)

    def policy(obs: np.ndarray) -> np.ndarray:
        with torch.no_grad():
            tensor = torch.from_numpy(obs)
            if mode == "argmax":
                action = net.act_greedy(tensor)
            else:
                probs = net.log_probs(tensor).exp()
                action = torch.multinomial(probs, num_samples=1, generator=generator).squeeze(-1)
        return action.numpy().astype(np.uint8)

    return policy


def load_net(path: str | Path) -> ActorCritic:
    """체크포인트에서 망을 되살린다 (FR-8).

    **체크포인트 규약** — P6 의 `track_a.py` 가 이 형식으로 저장한다::

        {"model": state_dict, "obs_dim": int, "action_count": int, "hidden": int, ...}

    키가 없으면 `state_dict` 의 모양에서 유도한다. 평가가 체크포인트 **하나만으로** 재현되어야
    하므로 (FR-8) 러너의 구성 파일을 읽지 않는다.
    """
    blob = torch.load(Path(path), map_location="cpu", weights_only=True)
    state = blob["model"] if isinstance(blob, dict) and "model" in blob else blob
    meta = blob if isinstance(blob, dict) else {}

    obs_dim = int(meta.get("obs_dim") or state["actor.0.weight"].shape[1])
    hidden = int(meta.get("hidden") or state["actor.0.weight"].shape[0])
    action_count = int(meta.get("action_count") or state["actor.4.weight"].shape[0])

    net = ActorCritic(obs_dim, action_count, hidden)
    net.load_state_dict(state)
    net.eval()
    return net


# ═══════════════════════════════════════════════════════════════════════════
# 평가 루프
# ═══════════════════════════════════════════════════════════════════════════


def _quotas(sides: np.ndarray, games_per_side: int) -> np.ndarray:
    """행마다 몇 게임을 셀 것인가. 진영 안에서 고르게 나눈다."""
    quota = np.zeros(sides.shape[0], dtype=np.int64)
    for side in (0, 1):
        rows = np.flatnonzero(sides == side)
        if rows.size == 0:
            raise ValueError("한쪽 진영의 행이 없습니다. EnvOptions.for_policy 로 구성하세요 (FR-3).")
        base, rest = divmod(games_per_side, rows.size)
        quota[rows] = base
        quota[rows[:rest]] += 1
    return quota


def evaluate_policy(
    env: PikaVectorEnv,
    policy: Policy,
    *,
    games_per_side: int,
    mode: str = "argmax",
    max_game_frames: int = MAX_GAME_FRAMES,
    max_steps: int | None = None,
    recorder: ReplaySink | None = None,
    fetch_every: int = 256,
) -> EvalReport:
    """양 진영에서 `games_per_side` 게임씩 돌린다.

    ⚠️ `env` 는 **양 진영을 모두** 담고 있어야 한다 (`EnvOptions.for_policy` 의 `swapped_envs`).
       한 진영에서만 재면 승률이 실력이 아니라 진영을 재게 된다 (PRD §2.4).

    Args:
        recorder: 주면 **센 게임만** 리플레이로 남긴다 (Phase 4 FR-6). `env` 는
            ``record_replays=True`` 이고 ``replay_frame_cap == max_game_frames`` 여야 한다 —
            그래야 미결로 센 게임과 상한에서 잘린 리플레이가 같은 게임이다.
            기록은 리포트를 바꾸지 않는다 (같은 스텝 · 같은 행동).
    """
    if recorder is not None:
        if not env.options.record_replays:
            raise ValueError("recorder 를 주려면 EnvOptions.record_replays=True 로 구성하세요")
        if env.options.replay_frame_cap != max_game_frames:
            raise ValueError(
                f"replay_frame_cap({env.options.replay_frame_cap}) ≠ max_game_frames({max_game_frames}) — "
                "미결 게임과 잘린 리플레이의 짝이 어긋납니다",
            )
    sides = np.asarray(env.slot_sides)
    rows = env.num_envs
    slot_count = env.slot_count
    winning = env.options.winning_score
    quota = _quotas(sides, games_per_side)

    # 행별 진행 상태.
    consumed = np.zeros(rows, dtype=np.int64)   # 이 행이 지금까지 센 게임 (승패 + 미결)
    dead = np.zeros(rows, dtype=bool)           # 상한을 넘겨 버린 게임을 진행 중인가
    pending = np.zeros(rows, dtype=bool)        # 다음 스텝이 버려지는 autoreset 스텝인가
    # 행이 지금 치르는 게임의 번호 = 서버 `RecordedGame.game_in_env` (리셋 이후 끝난 게임 수).
    game_idx = np.zeros(rows, dtype=np.int64)

    # 행별 "현재 게임" 누적. 게임이 끝날 때 통째로 진영 집계에 옮긴다 —
    # 진행 중인 게임의 프레임이 "게임당 프레임" 에 섞이면 GameEvaluator 와 정의가 갈라진다.
    g_frames = np.zeros(rows, dtype=np.int64)
    g_rallies = np.zeros(rows, dtype=np.int64)
    g_rally_wins = np.zeros(rows, dtype=np.int64)
    g_truncated = np.zeros(rows, dtype=np.int64)

    acc = {
        side: dict(games=0, wins=0, unresolved=0, points_for=0, points_against=0,
                   rallies=0, rally_wins=0, frames=0, truncated_rallies=0)
        for side in (0, 1)
    }

    if max_steps is None:
        # 안전장치. 모든 행이 상한까지 끌어도 끝나는 값이고, 정상 평가는 여기 근처도 안 간다.
        max_steps = int(2 * quota.max() * max_game_frames + 1000)

    obs, _info = env.reset()
    steps = 0
    while bool((consumed < quota).any()):
        if steps >= max_steps:
            raise RuntimeError(
                f"평가가 {max_steps} 스텝 안에 끝나지 않았습니다. "
                f"진행 {consumed.sum()}/{quota.sum()} 게임 — 끝나지 않는 게임을 의심하세요 (plan.md §9.4).",
            )
        obs, _rewards, terminated, truncated, info = env.step(policy(obs))
        steps += 1

        # 버려지는 스텝(autoreset)에서는 물리가 가지 않는다. 프레임을 세면 안 된다.
        advanced = ~pending
        done = (terminated | truncated) & advanced
        active = consumed < quota

        g_frames += advanced
        g_rallies += done
        g_truncated += truncated & advanced
        g_rally_wins += (terminated & advanced & (info["rally_win"] > 0))

        score_me = info["score_me"]
        score_opponent = info["score_opponent"]
        game_over = terminated & advanced & ((score_me >= winning) | (score_opponent >= winning))

        # (1) 상한 초과 → 미결. 승도 패도 아니다.
        for row in np.flatnonzero((~dead) & active & ~game_over & (g_frames > max_game_frames)):
            acc[int(sides[row])]["unresolved"] += 1
            consumed[row] += 1
            dead[row] = True
            if recorder is not None:
                recorder.mark(int(row) // slot_count, int(game_idx[row]), unresolved=True)

        # (2) 게임 종료 → 집계하고 행을 비운다.
        for row in np.flatnonzero(game_over):
            if active[row] and not dead[row]:
                side = acc[int(sides[row])]
                side["games"] += 1
                side["wins"] += int(score_me[row] >= winning)
                side["points_for"] += int(score_me[row])
                side["points_against"] += int(score_opponent[row])
                side["rallies"] += int(g_rallies[row])
                side["rally_wins"] += int(g_rally_wins[row])
                side["frames"] += int(g_frames[row])
                side["truncated_rallies"] += int(g_truncated[row])
                consumed[row] += 1
                if recorder is not None:
                    recorder.mark(int(row) // slot_count, int(game_idx[row]), unresolved=False)
            dead[row] = False
            g_frames[row] = g_rallies[row] = g_rally_wins[row] = g_truncated[row] = 0
            game_idx[row] += 1

        pending = done
        # ⚠️ 꺼내는 것은 이 스텝을 처리(mark)한 **뒤** 다 — 꺼낸 게임의 판정이 이미 끝나 있다.
        if recorder is not None and steps % fetch_every == 0:
            recorder.offer(env.fetch_replays())

    if recorder is not None:
        recorder.offer(env.fetch_replays())
        recorder.close()

    return EvalReport(
        as_left=SideStats(**acc[0]),
        as_right=SideStats(**acc[1]),
        mode=mode,
        games_per_side=games_per_side,
        max_game_frames=max_game_frames,
        fixed_boldness=env.options.fixed_boldness,
        base_seed=env.options.base_seed,
        steps=steps,
    )


def evaluate_target(
    target: str,
    policy_factory: Callable[[PikaVectorEnv], Policy],
    *,
    games_per_side: int = 400,
    num_envs: int = 64,
    base_seed: int = 0,
    fixed_boldness: int = -1,
    mode: str = "argmax",
    max_game_frames: int = MAX_GAME_FRAMES,
    max_rally_frames: int = 3_000,
    record_dir: str | Path | None = None,
    set_name: str | None = None,
    participant: dict[str, Any] | None = None,
) -> EvalReport:
    """서버 하나에 붙어 한 조건을 평가한다. 환경은 **이 함수가 만들고 닫는다.**

    ``record_dir`` 를 주면 센 게임의 리플레이 + ``manifest.jsonl`` 을 거기 쓴다 (Phase 4 FR-6).
    ``participant`` 는 정책 쪽 참가자 (``{"kind": "external", "checkpoint": "sha256:…", "label": …}``).

    `policy_factory` 가 환경을 받는 이유: 관측 차원이 서버 구성에서 나오므로 (FR-14 의
    41차원) 정책을 그보다 먼저 만들 수 없는 경우가 있다.
    """
    options = EnvOptions.for_policy(
        num_envs=num_envs,
        base_seed=base_seed,
        p1="external",
        p2="fsm",
        fixed_boldness=fixed_boldness,
        max_rally_frames=max_rally_frames,
        record_replays=record_dir is not None,
        replay_frame_cap=max_game_frames,
    )
    recorder = None
    if record_dir is not None:
        recorder = ReplaySink(
            record_dir, set_name or Path(record_dir).name,
            participant or {"kind": "external", "checkpoint": None, "label": set_name},
        )
    env = PikaVectorEnv(target, options)
    try:
        return evaluate_policy(
            env, policy_factory(env),
            games_per_side=games_per_side, mode=mode, max_game_frames=max_game_frames,
            recorder=recorder,
        )
    finally:
        env.close()


def evaluate_boldness_axis(
    target: str,
    policy_factory: Callable[[PikaVectorEnv], Policy],
    *,
    games_per_side: int = 50,
    **kwargs: Any,
) -> dict[int, EvalReport]:
    """FSM 의 boldness 를 0~4 에 고정해 각각 평가한다 (FR-13, plan.md §9.5).

    **난이도 조절이 아니라 과적합의 모양을 보는 것이다.** 특정 boldness 에서만 승률이
    무너지면 정책이 FSM 의 그 버릇에 붙은 것이다. boldness 가 난이도 축이 **아니라는**
    사실은 이미 측정됐다 (`BoldnessProbeTest`, PRD §2.1).

    ⚠️ b 마다 `Configure` 가 필요하므로 환경을 새로 만든다. 서버는 단일 테넌트라
       같은 서버에 두 클라이언트를 동시에 살려 둘 수 없다.
    """
    return {
        b: evaluate_target(target, policy_factory, games_per_side=games_per_side,
                           fixed_boldness=b, **kwargs)
        for b in range(5)
    }


# ═══════════════════════════════════════════════════════════════════════════
# CLI
# ═══════════════════════════════════════════════════════════════════════════


def _policy_factory(args: argparse.Namespace) -> Callable[[PikaVectorEnv], Policy]:
    if args.random:
        return lambda _env: uniform_random_policy(args.policy_seed)

    net = load_net(args.checkpoint)

    def factory(env: PikaVectorEnv) -> Policy:
        if net.obs_dim != env.obs_dim:
            raise RuntimeError(
                f"체크포인트의 관측이 {net.obs_dim}차원인데 서버는 {env.obs_dim}차원입니다. "
                "진영 플래그(FR-14) 설정이 다를 가능성이 큽니다.",
            )
        return net_policy(net, mode=args.mode, seed=args.policy_seed)

    return factory


def main() -> None:
    p = argparse.ArgumentParser(description="게임 단위 평가 (plan.md §9)")
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--checkpoint", help="평가할 체크포인트 (.pt)")
    source.add_argument("--random", action="store_true", help="균등 무작위 정책 — 기준선 (M3-g)")
    p.add_argument("--mode", default="argmax", choices=["argmax", "sample"])
    p.add_argument("--games", type=int, default=400, help="**진영별** 게임 수 (M3-a 는 400)")
    p.add_argument("--num-envs", type=int, default=64)
    p.add_argument("--seed", type=int, default=0, help="서버 base_seed")
    p.add_argument("--policy-seed", type=int, default=0, help="정책의 난수 시드 (sample·random 모드)")
    p.add_argument("--max-game-frames", type=int, default=MAX_GAME_FRAMES)
    p.add_argument("--threads", type=int, default=EVAL_THREADS)
    p.add_argument("--boldness", action="store_true", help="boldness 0~4 진단 축도 돌린다 (FR-13)")
    p.add_argument("--boldness-games", type=int, default=50, help="b 당 **진영별** 게임 수")
    p.add_argument("--target", help="이미 떠 있는 서버. 없으면 직접 띄운다 (plan.md §9.3)")
    p.add_argument("--json", help="리포트를 JSON 으로 저장할 경로")
    p.add_argument("--record-replays", metavar="DIR",
                   help="센 게임의 리플레이 + manifest.jsonl 을 여기에 쓴다 (Phase 4, `analysis ingest` 입력)")
    p.add_argument("--set-name", help="리플레이 묶음 이름 (기본: 디렉터리 이름)")
    args = p.parse_args()

    configure_torch(args.threads)
    factory = _policy_factory(args)
    # 리포트의 `mode` 는 라벨이다. 무작위 기준선을 "argmax" 로 적으면 JSON 을 나중에 읽을 때
    # 학습된 정책의 결과와 구분되지 않는다.
    mode = "random" if args.random else args.mode

    participant: dict[str, Any] | None = None
    if args.record_replays:
        # 체크포인트 SHA-256 — "같은 가중치로 만든 리플레이인가" 를 확인하는 장치 (M6-d).
        if args.random:
            participant = {"kind": "external", "checkpoint": None, "label": "random"}
        else:
            participant = {
                "kind": "external",
                "checkpoint": f"sha256:{sha256_file(args.checkpoint)}",
                "label": args.set_name or Path(args.checkpoint).parent.name,
            }

    def run(target: str) -> dict[str, Any]:
        report = evaluate_target(
            target, factory, games_per_side=args.games, num_envs=args.num_envs,
            base_seed=args.seed, mode=mode, max_game_frames=args.max_game_frames,
            record_dir=args.record_replays, set_name=args.set_name, participant=participant,
        )
        print("── 평가 ──")
        print(report)
        out: dict[str, Any] = {"main": report.to_dict()}

        if args.boldness:
            print("\n── 진단: boldness 별 승률 (FR-13) ──")
            print(f"  {'b':<4}{'합산':>10}{'왼쪽':>10}{'오른쪽':>10}{'진영차':>10}{'미결':>8}")
            axis = evaluate_boldness_axis(
                target, factory, games_per_side=args.boldness_games,
                num_envs=args.num_envs, base_seed=args.seed, mode=mode,
                max_game_frames=args.max_game_frames,
            )
            for b, r in axis.items():
                print(f"  {b:<4}{r.combined:>10.4f}{r.as_left.rate:>10.4f}"
                      f"{r.as_right.rate:>10.4f}{r.side_gap:>+10.4f}{r.unresolved:>8}")
            out["boldness"] = {str(b): r.to_dict() for b, r in axis.items()}
        return out

    if args.target:
        out = run(args.target)
    else:
        with launch_server() as target:
            out = run(target)

    if args.json:
        Path(args.json).write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\n저장: {args.json}")


if __name__ == "__main__":
    main()
