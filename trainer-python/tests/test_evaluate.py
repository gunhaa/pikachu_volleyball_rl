"""평가기의 자기 검증 — 무작위 정책 기준선과 재현성. (M3-c, M3-g / plan.md §9)

─────────────────────────────────────────────────────────────────────────────
이 파일이 지키는 것
─────────────────────────────────────────────────────────────────────────────
평가기는 학습기보다 먼저 만들어지고, **자기 자신을 검증할 방법이 하나뿐**이다:
균등 무작위 정책을 넣었을 때 Kotlin `RandomBaselineTest` 와 같은 숫자가 나오는가.

두 구현은 난수원이 다르므로 (Kotlin `XorShift32` vs numpy PCG64) 바이트가 아니라
**통계적으로** 일치해야 한다. 그것이 이 대조가 의미 있는 이유다 — 같은 코드를 두 번
부르면 아무것도 검증하지 못한다. 게임의 시드 규약도 다르다 (평가기는 `deriveSeed(base, i, k)`,
`GameEvaluator` 는 `baseSeed + i/2`). 즉 **같은 경기가 아니라 같은 분포**를 비교한다.
"""

from __future__ import annotations

import math

import numpy as np
import pytest
import torch

from pika_trainer.env_client import EnvOptions, PikaVectorEnv
from pika_trainer.evaluate import (
    UNRESOLVED_ALARM,
    evaluate_boldness_axis,
    evaluate_policy,
    evaluate_target,
    net_policy,
    uniform_random_policy,
)
from pika_trainer.net import ActorCritic

# ── Kotlin 기준선 (`RandomBaselineTest.randomPolicyNeverWins` 의 골든) ────────
#
# ⚠️ 이 숫자를 고치기 전에 Kotlin 테스트를 먼저 돌려라. 양쪽이 동시에 바뀌었다면
#    물리나 경기 규칙이 바뀐 것이고, 그때는 두 골든이 함께 갱신되어야 한다.
KOTLIN_RALLY_WINS = 111 + 144
KOTLIN_RALLIES = (15 * 200 + 111) + (15 * 200 + 144)
KOTLIN_RALLY_WIN_RATE = KOTLIN_RALLY_WINS / KOTLIN_RALLIES  # 0.0408

#: M3-g 의 밴드. PRD §4 가 그대로 적어 둔 값이다.
M3G_CENTER, M3G_TOLERANCE = 0.045, 0.010


@pytest.fixture
def random_report(env_target: str):
    """무작위 정책 200 게임. 여러 테스트가 같은 리포트를 본다 (한 번만 돈다)."""
    return evaluate_target(
        env_target,
        lambda _env: uniform_random_policy(seed=0),
        games_per_side=100,
        num_envs=40,
        base_seed=0,
        mode="random",
    )


# ── M3-g: 평가기 자기 검증 ──────────────────────────────────────────────


def test_random_policy_never_wins_a_game(random_report):
    """게임 승 0/200. **Track A 의 출발 신호가 0 이라는 사실**이다 (PRD §2.3)."""
    print(f"\n{random_report}")
    assert random_report.as_left.wins == 0, "왼쪽 진영에서 무작위 정책이 게임을 이겼다"
    assert random_report.as_right.wins == 0, "오른쪽 진영에서 무작위 정책이 게임을 이겼다"
    assert random_report.as_left.games == 100
    assert random_report.as_right.games == 100
    assert random_report.combined == 0.0
    assert random_report.unresolved == 0, "무작위 정책의 랠리는 평균 65프레임이다 — 미결이 나올 수 없다"


def test_random_policy_rally_win_rate_matches_m3g(random_report):
    """랠리 승률 0.045 ± 0.010 (M3-g). 0 이 아닌 것이 핵심이다 — 서브 실패로 굴러온다."""
    rate = random_report.rally_win_rate
    assert abs(rate - M3G_CENTER) <= M3G_TOLERANCE, f"M3-g 밴드 밖입니다: {rate:.4f}"


def test_random_policy_agrees_with_kotlin_baseline(random_report):
    """Kotlin 기준선과 ±2SE 안에서 일치한다 (P3 의 마지막 확인 항목).

    두 표본의 차이에 대한 표준오차로 본다 — 한쪽 표본만의 SE 로 보면 Kotlin 쪽 표본이
    무한히 정확하다고 가정하는 셈이다.
    """
    rate = random_report.rally_win_rate
    rallies = random_report.as_left.rallies + random_report.as_right.rallies
    pooled = (random_report.as_left.rally_wins + random_report.as_right.rally_wins + KOTLIN_RALLY_WINS) / (
        rallies + KOTLIN_RALLIES
    )
    se = math.sqrt(pooled * (1 - pooled) * (1 / rallies + 1 / KOTLIN_RALLIES))
    delta = abs(rate - KOTLIN_RALLY_WIN_RATE)
    print(f"\nPython {rate:.4f} vs Kotlin {KOTLIN_RALLY_WIN_RATE:.4f} → {delta / se:.2f}SE (SE={se:.4f})")
    assert delta <= 2 * se, (
        f"Python {rate:.4f} 와 Kotlin {KOTLIN_RALLY_WIN_RATE:.4f} 가 {delta / se:.1f}SE 떨어져 있습니다. "
        "두 구현의 게임 규칙 해석이 갈라졌을 수 있습니다 (점수 해석·서브권·랠리 경계)."
    )


def test_both_sides_are_sampled(random_report, env_target: str):
    """진영은 설정이 아니라 규칙이다 (FR-3). 앞 절반 왼쪽, 뒤 절반 오른쪽."""
    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=40, base_seed=0))
    try:
        sides = env.slot_sides
        assert env.obs_dim == 41, "진영 플래그가 꺼져 있습니다 (FR-14)"
        assert sides.tolist() == [0] * 20 + [1] * 20
    finally:
        env.close()

    # 두 진영의 게임 수가 같다 — 할당량 설계가 지켜졌다는 뜻이다.
    assert random_report.as_left.games == random_report.as_right.games


# ── M3-c: 평가 재현성 ───────────────────────────────────────────────────


def _untrained_net() -> ActorCritic:
    torch.manual_seed(0)
    return ActorCritic(41, 18, 64)


def test_argmax_evaluation_is_reproducible(env_target: str):
    """같은 체크포인트·시드로 두 번 평가하면 **모든 집계가 정확히 일치한다** (M3-c).

    ⚠️ `argmax` 라서 공짜로 성립하는 것이 아니다. 서버의 시드 유도, 할당량 설계,
       그리고 `net_policy` 가 전역 RNG 를 쓰지 않는다는 것까지가 이 등식의 조건이다.
    """
    net = _untrained_net()
    runs = [
        evaluate_target(
            env_target, lambda _env: net_policy(net, mode="argmax", seed=0),
            games_per_side=4, num_envs=8, base_seed=5,
        ).to_dict()
        for _ in range(2)
    ]
    assert runs[0] == runs[1]


def test_sample_mode_does_not_touch_global_rng(env_target: str):
    """`sample` 평가가 전역 torch RNG 를 소비하면 학습 재현성(M3-d)이 평가 횟수의 함수가 된다."""
    net = _untrained_net()
    torch.manual_seed(1234)
    before = torch.rand(1).item()

    torch.manual_seed(1234)
    evaluate_target(
        env_target, lambda _env: net_policy(net, mode="sample", seed=7),
        games_per_side=2, num_envs=8, base_seed=5, mode="sample",
    )
    after = torch.rand(1).item()
    assert before == after, "평가가 전역 RNG 를 소비했습니다"


# ── 끝나지 않는 게임 (plan.md §9.4) ─────────────────────────────────────


def test_unresolved_games_are_counted_separately(env_target: str):
    """상한을 넘긴 게임은 **패가 아니다.** 패로 세면 정책이 아니라 상한을 재게 된다."""
    report = evaluate_target(
        env_target, lambda _env: uniform_random_policy(seed=0),
        games_per_side=4, num_envs=8, base_seed=0, mode="random",
        max_game_frames=500,  # 무작위 정책의 게임은 1,000프레임이다 → 전부 미결
    )
    assert report.as_left.games == 0 and report.as_right.games == 0
    assert report.as_left.unresolved == 4 and report.as_right.unresolved == 4
    assert report.unresolved_ratio == 1.0
    assert not report.is_reportable, f"미결 100% 인데 보고 가능으로 나옵니다 (기준 {UNRESOLVED_ALARM})"
    # 미결 게임의 랠리는 집계에 섞이지 않는다 — 중간에 끊긴 게임의 통계는 편향돼 있다.
    assert report.as_left.rallies == 0


def test_stuck_evaluation_fails_loudly(env_target: str):
    """진행이 멈추면 조용히 도는 대신 실패한다. 평가가 멈춘 채 몇 시간을 보내는 것이 최악이다."""
    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=8, base_seed=0))
    try:
        with pytest.raises(RuntimeError, match="스텝 안에 끝나지 않았습니다"):
            evaluate_policy(
                env, uniform_random_policy(seed=0),
                games_per_side=4, max_steps=10,
            )
    finally:
        env.close()


# ── FR-13: 진단 축 ──────────────────────────────────────────────────────


def test_boldness_axis_pins_each_value(env_target: str):
    """boldness 0~4 를 각각 고정해 잰다. **난이도 조절이 아니라 과적합의 모양을 본다.**"""
    axis = evaluate_boldness_axis(
        env_target, lambda _env: uniform_random_policy(seed=0),
        games_per_side=3, num_envs=6, base_seed=0, mode="random",
    )
    assert sorted(axis) == [0, 1, 2, 3, 4]
    for b, report in axis.items():
        assert report.fixed_boldness == b
        assert report.as_left.games == 3 and report.as_right.games == 3
        # 무작위 정책은 어떤 b 에서도 이기지 못한다 (`BoldnessProbeTest` 와 같은 결론).
        assert report.combined == 0.0

    # b 마다 다른 경기가 된다 — 같은 수치가 나오면 `fixed_boldness` 가 서버에 닿지 않은 것이다.
    frames = {b: r.as_left.frames for b, r in axis.items()}
    assert len(set(frames.values())) > 1, f"boldness 를 바꿨는데 프레임이 같습니다: {frames}"


def test_side_stats_definitions_match_game_evaluator(random_report):
    """`GameEvaluator` 와 같은 정의인가 (FR-7) — 항등식으로 확인한다."""
    for side in (random_report.as_left, random_report.as_right):
        # 랠리 = 득점된 랠리 + 잘린 랠리. 무작위 정책에서는 잘린 랠리가 0 이다.
        assert side.rallies == side.points_for + side.points_against + side.truncated_rallies
        # 내가 딴 점수 = 내가 이긴 랠리.
        assert side.rally_wins == side.points_for
        assert side.points_against == 15 * side.games, "FSM 이 15점으로 끝내지 않은 게임이 있다"
    assert random_report.side_gap == random_report.as_left.rate - random_report.as_right.rate
    assert np.isclose(
        random_report.combined, (random_report.as_left.rate + random_report.as_right.rate) / 2,
    )
