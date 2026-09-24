"""롤아웃 수집 · 마스킹 · GAE. (FR-2, FR-4, FR-5, M3-f / plan.md §6.3, §7)

─────────────────────────────────────────────────────────────────────────────
이 파일이 막는 것 하나
─────────────────────────────────────────────────────────────────────────────
next-step autoreset 에서는 랠리가 끝난 **다음** 스텝이 리셋에 쓰인다. 그 스텝의 행동은
버려지고 보상은 0 이며, 전이 `(s_T, a, 0, s'_0)` 는 **에피소드를 가로지르는 가짜**다
(plan.md §7.1). 학습에 섞으면 가치 함수가 종단 상태의 값을 초기 상태 쪽으로 끌어당긴다.

**손실 곡선은 멀쩡하다.** 증상이 없으므로 `test_rollout.py` 가 계약을 못 박는다 (M3-f).

─────────────────────────────────────────────────────────────────────────────
마스크가 둘인 이유 (plan.md §7.2)
─────────────────────────────────────────────────────────────────────────────
====================  ==========================================
`valid`               이 전이를 **배우는가**. 버려지는 스텝만 False
`cut = term | trunc`  에피소드가 **여기서 끝났는가**. GAE 재귀를 끊는다
====================  ==========================================

그리고 부트스트랩에는 `terminated` **하나만** 쓴다 — truncated 는 랠리가 계속될 수
있었으므로 `V(s_final)` 로 이어야 한다 (FR-4, §6.3). 셋을 하나로 합치면 긴 랠리의 가치가
조용히 과소평가된다.

─────────────────────────────────────────────────────────────────────────────
환경을 얼마나 아는가
─────────────────────────────────────────────────────────────────────────────
`env_client` 를 import 하지 않는다. 필요한 것은 `step` · `reward_terms` · `slot_sides`
뿐이고, 망에게 요구하는 것은 `act` 와 `value` 뿐이다. Track B(Phase 7)가 같은 수집기를
쓰고, 테스트가 서버 없이 도는 이유다 (NFR-4 의 방향).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

import numpy as np
import torch
from torch import Tensor

#: 행동 공간의 크기. `env_client.ACTION_COUNT` 와 같은 값이지만 **import 하지 않는다** —
#: 이 파일은 환경을 모른다. 다르면 :class:`RolloutCollector` 의 인자로 넘긴다.
ACTION_COUNT = 18


class PolicyNet(Protocol):
    """수집기가 망에게 요구하는 전부."""

    def act(self, obs: Tensor) -> tuple[Tensor, Tensor, Tensor]:
        """``(action, logp, value)`` — 그래디언트 없이."""
        ...

    def value(self, obs: Tensor) -> Tensor:
        ...


# ═══════════════════════════════════════════════════════════════════════════
# 마스킹 도우미 — PPO(P5)가 그대로 쓴다
# ═══════════════════════════════════════════════════════════════════════════


def masked_mean(x: Tensor, mask: Tensor) -> Tensor:
    """마스크 안의 평균. 마스크가 비면 **0** 이다.

    ⚠️ `x[mask].mean()` 이 아니다. 마스크가 전부 False 인 미니배치에서 그것은 NaN 을 내고,
       NaN 은 한 번의 역전파로 망 전체를 죽인다. 여기서는 그런 미니배치가 손실에 0 을
       기여하고 지나간다.
    """
    m = mask.to(x.dtype)
    count = m.sum()
    return (x * m).sum() / count.clamp(min=1.0) * (count > 0).to(x.dtype)


def masked_var(x: Tensor, mask: Tensor) -> Tensor:
    mean = masked_mean(x, mask)
    return masked_mean((x - mean) ** 2, mask)


def masked_normalize(x: Tensor, mask: Tensor, eps: float = 1e-8) -> Tensor:
    """advantage 정규화. **평균·분산을 마스크 안에서만 잡는다** (plan.md §8.2 함정 4).

    마스크 밖을 손실에서 빼는 것만으로는 부족하다 — 버려지는 스텝의 쓰레기 advantage 가
    정규화 통계에 들어가면 **유효한 스텝의 값이 전부 오염된다.**
    """
    return (x - masked_mean(x, mask)) / (masked_var(x, mask).sqrt() + eps)


# ═══════════════════════════════════════════════════════════════════════════
# GAE
# ═══════════════════════════════════════════════════════════════════════════


def compute_gae(
    rewards: Tensor,
    values: Tensor,
    terminated: Tensor,
    truncated: Tensor,
    bootstrap_value: Tensor,
    *,
    gamma: float,
    lam: float,
) -> tuple[Tensor, Tensor]:
    """``(advantages, returns)`` — 둘 다 ``(T, N)``.

    Args:
        values: ``(T, N)`` — `values[t]` 는 **스텝 t 에 입력한 관측**의 가치다. 따라서
            `values[t + 1]` 이 곧 `V(s_{t+1})` 이고, 종료 스텝에서는 그것이 `V(s_final)`
            이다 (next-step autoreset 의 이점 — plan.md §6.3).
        bootstrap_value: ``(N,)`` — 마지막 스텝이 돌려받은 관측의 가치.

    두 마스크가 서로 다른 자리에 들어간다::

        delta = r + γ·V(s') · (1 − terminated)   ← truncated 는 부트스트랩한다
        A     = delta + γλ · (1 − term|trunc) · A'  ← 재귀는 둘 다에서 끊는다
    """
    horizon = rewards.shape[0]
    term = terminated.to(rewards.dtype)
    cut = (terminated.bool() | truncated.bool()).to(rewards.dtype)

    advantages = torch.zeros_like(rewards)
    last_gae = torch.zeros_like(bootstrap_value)
    for t in reversed(range(horizon)):
        next_value = values[t + 1] if t + 1 < horizon else bootstrap_value
        delta = rewards[t] + gamma * next_value * (1.0 - term[t]) - values[t]
        last_gae = delta + gamma * lam * (1.0 - cut[t]) * last_gae
        advantages[t] = last_gae
    return advantages, advantages + values


# ═══════════════════════════════════════════════════════════════════════════
# 배치
# ═══════════════════════════════════════════════════════════════════════════


@dataclass
class RolloutBatch:
    """한 롤아웃. 전부 ``(T, N, ...)`` 이고 `flat()` 이 PPO 가 먹는 모양으로 편다."""

    obs: Tensor           # (T, N, obs_dim)
    actions: Tensor       # (T, N)  int64
    log_probs: Tensor     # (T, N)
    values: Tensor        # (T, N)
    rewards: Tensor       # (T, N)  가중치가 적용된 값 (FR-5)
    terminated: Tensor    # (T, N)  float 0/1
    truncated: Tensor     # (T, N)
    #: 이 전이를 학습에 쓰는가. 버려지는 스텝만 0 이다 (FR-2).
    valid: Tensor         # (T, N)
    advantages: Tensor    # (T, N)
    returns: Tensor       # (T, N)
    bootstrap_value: Tensor  # (N,)
    #: 진영 (0 왼쪽 / 1 오른쪽). 진영별 손실 진단에 쓴다.
    sides: Tensor         # (N,)

    @property
    def horizon(self) -> int:
        return self.obs.shape[0]

    @property
    def num_envs(self) -> int:
        return self.obs.shape[1]

    @property
    def num_valid(self) -> int:
        return int(self.valid.sum().item())

    @property
    def valid_share(self) -> float:
        return self.num_valid / (self.horizon * self.num_envs)

    def flat(self) -> dict[str, Tensor]:
        """``(T·N, ...)`` 로 편 뷰. PPO 의 미니배치 셔플이 이것을 인덱싱한다.

        ⚠️ `valid` 를 **함께** 넘긴다. 여기서 미리 걸러 버리면 미니배치 크기가 반복마다
           달라져 재현성(M3-d)의 표면이 넓어지고, 진영별 진단도 못 하게 된다.
        """
        n = self.horizon * self.num_envs
        return {
            "obs": self.obs.reshape(n, -1),
            "actions": self.actions.reshape(n),
            "log_probs": self.log_probs.reshape(n),
            "values": self.values.reshape(n),
            "advantages": self.advantages.reshape(n),
            "returns": self.returns.reshape(n),
            "valid": self.valid.reshape(n),
            "sides": self.sides.expand(self.horizon, self.num_envs).reshape(n),
        }


# ═══════════════════════════════════════════════════════════════════════════
# 진영별 메트릭 (FR-9, plan.md §4.4, §11)
# ═══════════════════════════════════════════════════════════════════════════


@dataclass
class SideStats:
    """한 진영의 롤아웃 통계.

    앞 절반과 뒤 절반은 **분포가 다르다** (뒷벽이 20px 다르다 — PRD §2.4). 나누어 찍지
    않으면 한쪽의 실패가 평균에 묻히고, M3-b(각 진영 ≥ 80%)가 마지막에 가서야 터진다.
    """

    term_names: Sequence[str]
    action_count: int = ACTION_COUNT
    steps: int = 0
    valid_steps: int = 0
    rallies: int = 0
    rally_wins: int = 0
    truncated_rallies: int = 0
    rally_frames: int = 0
    reward_sum: float = 0.0
    action_hist: np.ndarray = field(default_factory=lambda: np.zeros(ACTION_COUNT, dtype=np.int64))
    #: 항별 **가중 기여의 절댓값 합**. 비율의 분자다 (FR-6 — "지금 보상의 몇 %가 셰이핑인가").
    term_abs: np.ndarray | None = None

    def __post_init__(self) -> None:
        if self.term_abs is None:
            self.term_abs = np.zeros(len(self.term_names), dtype=np.float64)
        if len(self.action_hist) != self.action_count:
            self.action_hist = np.zeros(self.action_count, dtype=np.int64)

    # ── 파생 지표 ────────────────────────────────────────────────────────

    @property
    def rally_win_rate(self) -> float:
        """분모는 **끝난 랠리 전부**다 — 잘린 랠리도 포함한다 (`GameEvaluator` 와 같은 정의)."""
        return self.rally_wins / self.rallies if self.rallies else 0.0

    @property
    def mean_rally_frames(self) -> float:
        return self.rally_frames / self.rallies if self.rallies else 0.0

    @property
    def trunc_rate(self) -> float:
        """M3-h 의 감시 지표. 셰이핑이 저글링 정책을 만들면 승률보다 먼저 여기가 움직인다."""
        return self.truncated_rallies / self.rallies if self.rallies else 0.0

    @property
    def mean_reward(self) -> float:
        return self.reward_sum / self.valid_steps if self.valid_steps else 0.0

    @property
    def term_share(self) -> dict[str, float]:
        total = float(self.term_abs.sum())
        return {
            name: (float(self.term_abs[i]) / total if total else 0.0)
            for i, name in enumerate(self.term_names)
        }

    @property
    def action_share(self) -> list[float]:
        total = int(self.action_hist.sum())
        return [(int(c) / total if total else 0.0) for c in self.action_hist]

    def to_dict(self) -> dict[str, Any]:
        return {
            "steps": self.steps,
            "valid_steps": self.valid_steps,
            "rallies": self.rallies,
            "rally_wins": self.rally_wins,
            "rally_win_rate": self.rally_win_rate,
            "rally_frames_mean": self.mean_rally_frames,
            "trunc_rate": self.trunc_rate,
            "truncated_rallies": self.truncated_rallies,
            "reward_mean": self.mean_reward,
            "term_share": self.term_share,
            "action_hist": self.action_hist.tolist(),
        }


@dataclass
class RolloutMetrics:
    """`plan.md` §11 의 JSONL 한 줄 중 롤아웃이 책임지는 부분."""

    left: SideStats
    right: SideStats
    valid_share: float
    env_steps: int

    # ── 합산 (진영을 가로지른다) ─────────────────────────────────────────

    @property
    def rallies(self) -> int:
        return self.left.rallies + self.right.rallies

    @property
    def rally_win_rate(self) -> float:
        """양 진영 합산 랠리 승률.

        **여기서 정의한다** — 러너가 `(left + right) / 2` 로 따로 계산하면 진영별 랠리
        수가 다를 때(랠리 길이가 다르다) 조용히 다른 값이 된다. 분모는 랠리 수의 합이다.
        """
        wins = self.left.rally_wins + self.right.rally_wins
        return wins / self.rallies if self.rallies else 0.0

    @property
    def trunc_rate(self) -> float:
        """M3-h 의 판정값. 임계 2% 는 이 합산 수치에 대한 것이다."""
        truncated = self.left.truncated_rallies + self.right.truncated_rallies
        return truncated / self.rallies if self.rallies else 0.0

    @property
    def rally_frames_mean(self) -> float:
        frames = self.left.rally_frames + self.right.rally_frames
        return frames / self.rallies if self.rallies else 0.0

    def to_dict(self) -> dict[str, Any]:
        """`left.*` · `right.*` 로 평탄화하고, 합산은 최상위에 둔다.

        Phase 8 이 두 트랙을 겹쳐 그린다 — 주 곡선이 최상위에 있어야 플롯이 진영 두 열을
        먼저 합치지 않아도 된다.
        """
        out: dict[str, Any] = {
            "valid_share": self.valid_share,
            "env_steps": self.env_steps,
            "rallies": self.rallies,
            "rally_win_rate": self.rally_win_rate,
            "trunc_rate": self.trunc_rate,
            "rally_frames_mean": self.rally_frames_mean,
        }
        for prefix, stats in (("left", self.left), ("right", self.right)):
            for key, value in stats.to_dict().items():
                if isinstance(value, dict):
                    for sub, sub_value in value.items():
                        out[f"{prefix}.{key}.{sub}"] = sub_value
                else:
                    out[f"{prefix}.{key}"] = value
        return out


# ═══════════════════════════════════════════════════════════════════════════
# 수집기
# ═══════════════════════════════════════════════════════════════════════════


class RolloutCollector:
    """환경 · 망 · 롤아웃 사이의 상태를 들고 있는다.

    **상태를 왜 인스턴스에 두는가**: `pending`(직전 스텝이 랠리를 끝냈는가)과 진행 중인
    랠리의 프레임 수는 롤아웃 경계를 **넘어** 이어진다. 롤아웃마다 새로 시작하면
    (1) 매 롤아웃의 첫 스텝이 조용히 오염되고 (2) 랠리가 T 보다 길어지는 학습 후반에
    랠리 길이가 T 로 잘려 보인다 (plan.md §5.3).
    """

    def __init__(
        self,
        env: Any,
        net: PolicyNet,
        *,
        gamma: float = 0.997,
        lam: float = 0.95,
        action_count: int = ACTION_COUNT,
    ) -> None:
        self.env = env
        self.net = net
        self.gamma = gamma
        self.lam = lam
        self.action_count = action_count

        self.num_envs: int = env.num_envs
        self.obs_dim: int = env.obs_dim
        self.term_names: list[str] = list(env.reward_term_names)
        self._rally_win_idx = self.term_names.index("rally_win")

        sides = np.asarray(env.slot_sides).reshape(-1)
        self.sides = torch.from_numpy(sides.astype(np.int64))
        #: 진영별 행 인덱스. 앞 절반/뒤 절반을 가정하지 **않는다** — 서버가 정한 것을 읽는다.
        self._rows = {side: np.flatnonzero(sides == side) for side in (0, 1)}

        self._obs = np.zeros((self.num_envs, self.obs_dim), dtype=np.float32)
        self._pending = np.zeros(self.num_envs, dtype=bool)
        self._frames = np.zeros(self.num_envs, dtype=np.int64)
        self.total_env_steps = 0
        self.reset()

    # ── 수명 ─────────────────────────────────────────────────────────────

    def reset(self, seed: int | None = None) -> None:
        """환경을 처음 상태로. `pending` 과 진행 중인 랠리도 함께 버린다."""
        obs, _ = self.env.reset(seed=seed) if seed is not None else self.env.reset()
        np.copyto(self._obs, obs)
        self._pending[:] = False
        self._frames[:] = 0

    # ── 수집 ─────────────────────────────────────────────────────────────

    def collect(self, horizon: int, weights: np.ndarray) -> tuple[RolloutBatch, RolloutMetrics]:
        """`horizon` 스텝을 모은다.

        Args:
            weights: `(len(term_names),)` — **이 반복의** 셰이핑 가중치 (FR-5, FR-6).
                서버를 다시 `Configure` 하지 않고 여기서 곱한다.
        """
        weights = np.asarray(weights, dtype=np.float32)
        if weights.shape != (len(self.term_names),):
            raise ValueError(
                f"가중치 모양이 {weights.shape} 입니다. ({len(self.term_names)},) 이어야 합니다 "
                f"— 서버가 선언한 항: {self.term_names}",
            )

        n, d = self.num_envs, self.obs_dim
        obs_b = torch.zeros(horizon, n, d)
        act_b = torch.zeros(horizon, n, dtype=torch.int64)
        logp_b = torch.zeros(horizon, n)
        val_b = torch.zeros(horizon, n)
        rew_b = torch.zeros(horizon, n)
        term_b = torch.zeros(horizon, n)
        trunc_b = torch.zeros(horizon, n)
        valid_b = torch.zeros(horizon, n)

        stats = {side: SideStats(self.term_names, self.action_count) for side in (0, 1)}

        for t in range(horizon):
            # (1) 직전 스텝이 랠리를 끝냈다면 이 스텝은 리셋에 쓰인다 — 배우지 않는다.
            valid = ~self._pending
            obs_t = torch.from_numpy(self._obs)
            obs_b[t] = obs_t          # 대입이 곧 복사다. 뷰를 담으면 다음 스텝에 덮어써진다
            valid_b[t] = torch.from_numpy(valid.astype(np.float32))

            action, logp, value = self.net.act(obs_t)
            act_b[t], logp_b[t], val_b[t] = action, logp, value

            # (2) 행동은 uint8 로 나간다 (plan.md §8.2 — int64 를 넘기면 핫 패스에서 변환된다).
            actions_np = action.numpy().astype(np.uint8)
            obs_next, _, terminated, truncated, _ = self.env.step(actions_np)

            # (3) 서버의 `reward` 합계는 쓰지 않는다. 항별 원시값에 **지금의** 가중치를 곱한다.
            #     `reward_terms` 는 복사본이다 (plan.md §12 함정 3).
            terms = self.env.reward_terms
            rew_b[t] = torch.from_numpy(terms @ weights)
            term_b[t] = torch.from_numpy(np.asarray(terminated, dtype=np.float32))
            trunc_b[t] = torch.from_numpy(np.asarray(truncated, dtype=np.float32))

            self._accumulate(stats, valid, actions_np, terms, weights, terminated, truncated)

            self._pending = np.asarray(terminated) | np.asarray(truncated)
            np.copyto(self._obs, obs_next)

        # (4) 롤아웃 밖의 마지막 관측 — 잘린 세그먼트를 잇는 부트스트랩이다.
        with torch.no_grad():
            bootstrap = self.net.value(torch.from_numpy(self._obs)).clone()

        advantages, returns = compute_gae(
            rew_b, val_b, term_b.bool(), trunc_b.bool(), bootstrap,
            gamma=self.gamma, lam=self.lam,
        )
        self.total_env_steps += horizon * n

        batch = RolloutBatch(
            obs=obs_b, actions=act_b, log_probs=logp_b, values=val_b, rewards=rew_b,
            terminated=term_b, truncated=trunc_b, valid=valid_b,
            advantages=advantages, returns=returns, bootstrap_value=bootstrap,
            sides=self.sides,
        )
        metrics = RolloutMetrics(
            left=stats[0], right=stats[1],
            valid_share=batch.valid_share, env_steps=horizon * n,
        )
        return batch, metrics

    # ── 메트릭 ───────────────────────────────────────────────────────────

    def _accumulate(
        self,
        stats: dict[int, SideStats],
        valid: np.ndarray,
        actions: np.ndarray,
        terms: np.ndarray,
        weights: np.ndarray,
        terminated: np.ndarray,
        truncated: np.ndarray,
    ) -> None:
        """진영별 집계. **끝난 랠리만** 옮긴다.

        진행 중인 랠리를 스텝마다 더하면 마지막에 끊긴 랠리의 절반이 섞여
        `GameEvaluator` 의 "랠리당 프레임" 과 정의가 갈라진다 (plan.md §9.6 의 2번과 같은 이유).
        """
        # 유효한 스텝만 실제 물리 프레임이다. 버려지는 스텝은 랠리 길이에 들어가지 않는다.
        self._frames += valid
        ended = np.asarray(terminated) | np.asarray(truncated)
        weighted = np.abs(terms * weights)

        for side, rows in self._rows.items():
            if rows.size == 0:
                continue
            s = stats[side]
            v = valid[rows]
            s.steps += rows.size
            s.valid_steps += int(v.sum())
            if v.any():
                live = rows[v]
                s.action_hist += np.bincount(actions[live], minlength=self.action_count)
                s.term_abs += weighted[live].sum(axis=0)
                s.reward_sum += float((terms[live] @ weights).sum())

            done = rows[ended[rows]]
            if done.size:
                s.rallies += done.size
                s.rally_wins += int((terms[done, self._rally_win_idx] > 0).sum())
                s.truncated_rallies += int(np.asarray(truncated)[done].sum())
                s.rally_frames += int(self._frames[done].sum())

        self._frames[ended] = 0
