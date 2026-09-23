"""학습 진행도의 함수인 값들. (FR-6 / plan.md §6.2, §8.1)

─────────────────────────────────────────────────────────────────────────────
왜 한 파일에 모으는가
─────────────────────────────────────────────────────────────────────────────
LR·엔트로피 계수·셰이핑 가중치는 전부 "진행도 → 값" 이고, 셋 다 **메트릭에 기록되어야
한다** (FR-6). 러너 안에 `if it < 100: ...` 로 흩어 두면 기록이 빠지고, 빠진 기록은
"그때 셰이핑이 얼마였지?" 에 답하지 못하게 만든다.

진행도는 `env_steps / total_steps` 이고 **1 을 넘을 수 있다** (마지막 롤아웃이 총량을
넘긴다). 구간 밖에서는 끝값으로 고정한다 — 외삽하면 LR 이 음수가 된다.

─────────────────────────────────────────────────────────────────────────────
가중치는 왜 서버가 아니라 여기 있는가
─────────────────────────────────────────────────────────────────────────────
서버의 `RewardWeights` 를 바꾸려면 `Configure` 를 다시 불러야 하고 그것은 **환경 상태를
전부 버린다**. 매 반복 리셋하면 정책은 랠리 초반만 보게 된다. 서버는 항별 원시값을
언제나 내보내므로 (`reward_terms`), 가중 합계는 Python 쪽 산수다 (FR-5, plan.md §6.1).
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence

import numpy as np

#: 진행도 ``[0, 1]`` → 값.
Schedule = Callable[[float], float]


class Piecewise:
    """꼭짓점 사이를 선형으로 잇는다. 구간 밖은 끝값으로 고정.

    셰이핑의 모양이 이것이다 (plan.md §6.2)::

        Piecewise([(0.0, 0.10), (0.2, 0.10), (0.5, 0.0)])
        #  0~20% 유지 → 50% 에서 0
    """

    def __init__(self, points: Sequence[tuple[float, float]]) -> None:
        if not points:
            raise ValueError("꼭짓점이 최소 하나는 있어야 합니다.")
        pts = sorted(points, key=lambda p: p[0])
        if any(a[0] == b[0] for a, b in zip(pts, pts[1:], strict=False)):
            raise ValueError(f"진행도가 겹치는 꼭짓점이 있습니다: {pts}")
        self.points = tuple(pts)
        self._x = np.array([p[0] for p in pts], dtype=np.float64)
        self._y = np.array([p[1] for p in pts], dtype=np.float64)

    def __call__(self, progress: float) -> float:
        # np.interp 는 구간 밖을 끝값으로 고정한다 — 외삽하지 않는 것이 요구사항이다.
        return float(np.interp(float(progress), self._x, self._y))

    def __repr__(self) -> str:
        return f"Piecewise({list(self.points)})"


class Linear(Piecewise):
    """`start → end` 선형. LR `3e-4 → 0`, 엔트로피 계수 `0.02 → 0.002` (plan.md §8.1)."""

    def __init__(self, start: float, end: float) -> None:
        super().__init__([(0.0, start), (1.0, end)])
        self.start, self.end = start, end

    def __repr__(self) -> str:
        return f"Linear({self.start} → {self.end})"


class Constant(Piecewise):
    """고정값. `rally_win` 처럼 어닐링하지 않는 항에 쓴다."""

    def __init__(self, value: float) -> None:
        super().__init__([(0.0, value)])
        self.value = value

    def __repr__(self) -> str:
        return f"Constant({self.value})"


def as_schedule(value: Schedule | float) -> Schedule:
    """숫자면 :class:`Constant` 로 감싼다. 구성 파일이 숫자만 적을 수 있게."""
    return value if callable(value) else Constant(float(value))


class RewardWeighting:
    """항별 스케줄 묶음 → 반복마다의 가중치 벡터. (FR-5, FR-6)

    ⚠️ **항의 순서는 서버가 선언한 것을 따른다** (`ConfigureReply.reward_term_names`).
       `[1.0, 0, 0.1, 0, 0]` 처럼 배열을 직접 쓰면 서버가 항을 하나 추가하는 날
       가중치가 통째로 밀리고, 그 사고는 승률이 이상해지기 전까지 드러나지 않는다.

    ⚠️ 항의 **부호는 항 안에** 있다 (`Reward.kt`: `rally_win` 은 ±1, `time_penalty` 는
       프레임당 -1). 가중치는 크기다 — 음수를 넣으면 페널티가 보상이 된다.
    """

    def __init__(
        self,
        schedules: Mapping[str, Schedule | float],
        term_names: Sequence[str],
    ) -> None:
        unknown = set(schedules) - set(term_names)
        if unknown:
            raise ValueError(
                f"서버가 모르는 보상 항입니다: {sorted(unknown)} (아는 항: {list(term_names)})",
            )
        self.term_names = list(term_names)
        self.schedules = {name: as_schedule(schedules.get(name, 0.0)) for name in self.term_names}

    def at(self, progress: float) -> np.ndarray:
        """`(len(term_names),)` float32 — `reward_terms @ w` 에 바로 들어간다."""
        return np.array(
            [self.schedules[name](progress) for name in self.term_names], dtype=np.float32,
        )

    def as_dict(self, progress: float) -> dict[str, float]:
        """메트릭의 `shaping_w` (plan.md §11). 0 인 항도 남긴다 — "껐다" 도 기록이다."""
        return dict(zip(self.term_names, self.at(progress).tolist(), strict=True))


# ═══════════════════════════════════════════════════════════════════════════
# Track A 의 기본 스케줄 (plan.md §6.2)
# ═══════════════════════════════════════════════════════════════════════════

#: 셰이핑을 유지하는 구간과 끄는 지점. 0~20% 유지 → 50% 에서 0.
SHAPING_HOLD, SHAPING_OFF = 0.2, 0.5

#: `crossed_net` 이 `ball_touch` 보다 **커야 한다.** 반대로 두면 자기 진영에서 공을 계속
#: 치는 것이 최적 전략이 되고, 그 정책은 랠리를 끝내지 않아 truncation 비율로 드러난다 (M3-h).
SHAPING_CROSSED_NET, SHAPING_BALL_TOUCH = 0.10, 0.05


def shaping(peak: float) -> Piecewise:
    return Piecewise([(0.0, peak), (SHAPING_HOLD, peak), (SHAPING_OFF, 0.0)])


def track_a_weighting(term_names: Sequence[str]) -> RewardWeighting:
    """Track A 의 출발 구성.

    `PRD.md` §2.3 이 말한 대로 Track A 의 출발 신호는 0 이 아니다 (랠리 승률 4.5%,
    접촉 37%). 셰이핑은 필수가 아니라 가속 장치이므로 **작게 걸고 빨리 끈다** —
    이것이 Track B(Phase 4)와 정반대인 이유다.

    `opponent_miss` 는 `rally_win` 과 중복이고, `time_penalty` 는 랠리를 짧게 만드는 힘이
    이미 있으므로 둘 다 0 이다.
    """
    return RewardWeighting(
        {
            "rally_win": 1.0,
            "crossed_net": shaping(SHAPING_CROSSED_NET),
            "ball_touch": shaping(SHAPING_BALL_TOUCH),
        },
        term_names,
    )
