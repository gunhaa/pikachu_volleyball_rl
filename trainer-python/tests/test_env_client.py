"""M2-c — Gymnasium 규약과 next-step autoreset. (tasks.md P7)

⚠️ 여기서 가장 중요한 것은 [test_next_step_autoreset] 이다.
   same-step autoreset 으로 잘못 구현해도 **학습은 돌아가고 숫자도 나온다.**
   PPO 가 "종료 상태의 value" 자리에 새 에피소드의 첫 관측을 쓰게 될 뿐이고,
   그것은 손실 곡선에 이상하게 보이지 않는다. 그래서 전용 테스트로 못 박는다.
"""

from __future__ import annotations

import numpy as np
import pytest
from gymnasium import spaces
from gymnasium.vector import AutoresetMode

from pika_trainer.env_client import ACTION_COUNT, EnvOptions, PikaVectorEnv
from pika_trainer.obs_spec import ObsOptions, ObsSpec

# ⚠️ 서버는 **단일 테넌트**다 (VectorEnv 를 하나만 들고 있다). 두 클라이언트를 동시에
#    살려 두면 뒤에 Configure 한 쪽이 이깁니다 — 앞 클라이언트는 세션 번호 덕에 조용히
#    망가지는 대신 FAILED_PRECONDITION 을 받는다. 테스트는 한 번에 하나씩 쓴다.


@pytest.fixture
def track_a(env_target: str):
    env = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=3, p1="external", p2="fsm"))
    yield env
    env.close()


@pytest.fixture
def track_b(env_target: str):
    env = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=3, p1="external", p2="external"))
    yield env
    env.close()


def rollout(env: PikaVectorEnv, steps: int, rng: np.random.Generator):
    """관측·보상·플래그를 기록하며 굴린다."""
    obs_log, reward_log, term_log = [], [], []
    for _ in range(steps):
        actions = rng.integers(0, ACTION_COUNT, size=env.num_envs, dtype=np.int64)
        obs, rewards, terminated, truncated, _ = env.step(actions)
        obs_log.append(obs.copy())
        reward_log.append(rewards.copy())
        term_log.append((terminated | truncated).copy())
    return np.array(obs_log), np.array(reward_log), np.array(term_log)


# ── 규약 ────────────────────────────────────────────────────────────────


def test_spaces_and_shapes(track_a: PikaVectorEnv):
    assert track_a.num_envs == 4, "Track A 는 환경당 외부 슬롯이 하나다"
    assert track_a.single_action_space == spaces.Discrete(ACTION_COUNT)
    assert track_a.single_observation_space.shape == (track_a.obs_dim,)
    assert track_a.observation_space.shape == (track_a.num_envs, track_a.obs_dim)

    obs, info = track_a.reset(seed=7)
    assert obs.shape == (track_a.num_envs, track_a.obs_dim)
    assert obs.dtype == np.float32
    assert track_a.observation_space.contains(obs)
    assert np.isfinite(obs).all(), "관측에 NaN/Inf 가 있습니다"

    actions = track_a.action_space.sample()
    obs, rewards, terminated, truncated, info = track_a.step(actions)
    assert track_a.observation_space.contains(obs)
    assert rewards.shape == (track_a.num_envs,) and rewards.dtype == np.float32
    assert terminated.shape == (track_a.num_envs,) and terminated.dtype == bool
    assert truncated.shape == (track_a.num_envs,) and truncated.dtype == bool
    for name in track_a.reward_term_names:
        assert info[name].shape == (track_a.num_envs,)


def test_autoreset_mode_is_next_step(track_a: PikaVectorEnv):
    assert track_a.metadata["autoreset_mode"] is AutoresetMode.NEXT_STEP


def test_track_b_flattens_slots(track_b: PikaVectorEnv):
    assert track_b.slot_count == 2
    assert track_b.num_envs == 8, "서버 환경 4 × 슬롯 2"

    obs, _ = track_b.reset(seed=1)
    assert obs.shape == (8, track_b.obs_dim)

    # 같은 서버 환경의 두 슬롯은 같은 랠리를 본다 → 종료가 짝을 이룬다.
    rng = np.random.default_rng(0)
    _, _, terms = rollout(track_b, 300, rng)
    pairs = terms.reshape(300, 4, 2)
    assert np.array_equal(pairs[:, :, 0], pairs[:, :, 1]), "두 슬롯의 에피소드 경계가 어긋났습니다"


def test_rejects_bad_actions(track_a: PikaVectorEnv):
    track_a.reset(seed=0)
    with pytest.raises(ValueError):
        track_a.step(np.zeros(track_a.num_envs + 1, dtype=np.int64))
    with pytest.raises(ValueError):
        track_a.step(np.full(track_a.num_envs, ACTION_COUNT, dtype=np.int64))


# ── M2-c: next-step autoreset ───────────────────────────────────────────


def test_next_step_autoreset(env_target: str):
    """종료 스텝의 obs ≠ 리셋 직후 obs, **그 다음** 스텝의 obs = 리셋 직후 obs."""
    options = EnvOptions(num_envs=1, base_seed=21, p1="external", p2="fsm")

    # 서버는 단일 테넌트다. "리셋 직후 관측" 을 먼저 받아 두고 그 클라이언트를 닫는다.
    reference = PikaVectorEnv(env_target, options)
    try:
        fresh_obs, _ = reference.reset(seed=21)
        fresh_obs = fresh_obs.copy()
    finally:
        reference.close()

    env = PikaVectorEnv(env_target, options)
    try:
        obs, _ = env.reset(seed=21)
        assert np.array_equal(obs, fresh_obs), "같은 시드의 reset 이 다른 관측을 줍니다"

        action = np.zeros(env.num_envs, dtype=np.int64)
        for _ in range(100_000):
            obs, rewards, terminated, truncated, _ = env.step(action)
            if terminated[0] or truncated[0]:
                break
        else:
            pytest.fail("랠리가 끝나지 않았습니다")

        terminal_obs = obs.copy()
        assert not np.array_equal(terminal_obs, fresh_obs), (
            "종료 스텝이 이미 리셋된 관측을 돌려줬습니다 — same-step autoreset 입니다"
        )

        # 다음 스텝: 리셋이 일어난다. 이 스텝의 행동은 버려지고 보상은 0 이다.
        after_obs, after_rewards, after_term, after_trunc, _ = env.step(
            np.full(env.num_envs, ACTION_COUNT - 1, dtype=np.int64),
        )
        assert not after_term[0] and not after_trunc[0]
        assert after_rewards[0] == 0.0, "리셋 스텝의 보상은 0 이어야 합니다"
        assert not np.array_equal(after_obs, terminal_obs), "리셋이 일어나지 않았습니다"

        # 리셋 직후의 관측은 "새 랠리의 첫 관측" 이다. 첫 랠리와 같은 관측은 아니다
        # (점수·서브권·라운드를 넘어 살아남는 상태가 다르다). 확인할 것은 두 가지다:
        # (1) 종료 관측과 다르다, (2) 공이 서브 위치로 돌아갔다.
        ball_y_index = env.obs_field_names.index("ball.y")
        assert after_obs[0, ball_y_index] < terminal_obs[0, ball_y_index], (
            "새 랠리의 공이 위에서 시작하지 않았습니다"
        )
    finally:
        env.close()


def test_reset_step_action_is_ignored(env_target: str):
    """리셋 스텝에 무슨 행동을 줘도 결과가 같다."""

    def run(action_at_reset: int) -> np.ndarray:
        env = PikaVectorEnv(env_target, EnvOptions(num_envs=1, base_seed=5, p2="fsm"))
        try:
            env.reset(seed=5)
            action = np.full(1, 3, dtype=np.int64)
            for _ in range(100_000):
                _, _, terminated, truncated, _ = env.step(action)
                if terminated[0] or truncated[0]:
                    break
            obs, _, _, _, _ = env.step(np.full(1, action_at_reset, dtype=np.int64))
            return obs.copy()
        finally:
            env.close()

    assert np.array_equal(run(0), run(ACTION_COUNT - 1))


# ── M2-d: 결정론 ────────────────────────────────────────────────────────


def test_determinism_same_seed_same_bytes(env_target: str):
    """같은 (시드, 행동) 이면 관측 바이트가 완전히 같다 — Python 에서 본 M2-d."""

    def run() -> tuple[bytes, bytes]:
        env = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=42))
        try:
            env.reset(seed=42)
            obs_log, reward_log, _ = rollout(env, 400, np.random.default_rng(99))
            return obs_log.tobytes(), reward_log.tobytes()
        finally:
            env.close()

    first = run()
    second = run()
    assert first[0] == second[0], "관측 바이트가 갈라졌습니다"
    assert first[1] == second[1], "보상 바이트가 갈라졌습니다"


def test_different_seed_diverges(env_target: str):
    def run(seed: int) -> bytes:
        env = PikaVectorEnv(env_target, EnvOptions(num_envs=2, base_seed=seed))
        try:
            env.reset(seed=seed)
            obs_log, _, _ = rollout(env, 200, np.random.default_rng(1))
            return obs_log.tobytes()
        finally:
            env.close()

    assert run(1) != run(2), "시드를 바꿨는데 수열이 같습니다"


# ── NFR-5: 레이아웃 대조 ────────────────────────────────────────────────


def test_layout_hash_matches_server(track_a: PikaVectorEnv):
    spec = ObsSpec.load()
    assert track_a.server_layout_hash == spec.layout_hash(ObsOptions())
    assert track_a.obs_field_names == spec.field_names(ObsOptions())
    assert track_a.obs_dim == 40, "기본 관측은 40차원이다 (me 15 + opp 15 + ball 6 + match 4)"


def test_layout_flags_change_the_hash(env_target: str):
    spec = ObsSpec.load()
    env = PikaVectorEnv(
        env_target,
        EnvOptions(num_envs=1, obs_include_expected_landing=False, obs_include_side_flag=True),
    )
    try:
        assert env.obs_dim == 40, "착지점 빼고(-1) 진영 플래그 넣으면(+1) 다시 40 이다"
        expected = spec.layout_hash(ObsOptions(include_expected_landing=False, include_side_flag=True))
        assert env.server_layout_hash == expected
        assert env.server_layout_hash != spec.layout_hash(ObsOptions()), "다른 레이아웃인데 해시가 같습니다"
        assert "ball.expected_landing_point_x" not in env.obs_field_names
        assert "match.side_flag" in env.obs_field_names
    finally:
        env.close()


def test_health_reports_layout_and_throughput(track_a: PikaVectorEnv):
    track_a.reset(seed=0)
    rollout(track_a, 50, np.random.default_rng(0))
    health = track_a.health()
    assert health.configured
    assert health.obs_layout_hash == track_a.server_layout_hash
    assert health.total_env_steps >= 50 * 4
    assert health.env_steps_per_sec > 0


def test_vector_space_invariants(env_target: str):
    """벡터 환경의 정준 불변식: batch_space(single, N) == batched.

    Gymnasium 에는 벡터 환경용 `check_env` 가 없다 (`env_checker` 는 단일 환경용이다).
    단일 환경 검사기를 억지로 끼우는 대신, 벡터 환경이 실제로 지켜야 하는 관계를 본다.
    """
    from gymnasium.vector.utils import batch_space

    for p2 in ("fsm", "external"):
        env = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=0, p2=p2))
        try:
            assert batch_space(env.single_observation_space, env.num_envs) == env.observation_space
            assert batch_space(env.single_action_space, env.num_envs) == env.action_space
            assert env.unwrapped is env

            obs, info = env.reset(seed=0)
            assert env.observation_space.contains(obs)
            assert isinstance(info, dict)

            actions = env.action_space.sample()
            assert env.action_space.contains(actions)
            obs, rewards, terminated, truncated, info = env.step(actions)
            assert env.observation_space.contains(obs)
            assert not np.any(terminated & truncated), "terminated 와 truncated 가 동시에 켜졌습니다"
        finally:
            env.close()


def test_second_client_invalidates_the_first(env_target: str):
    """서버가 단일 테넌트라는 사실이 **에러로** 드러난다.

    세션 번호가 없으면 첫 클라이언트는 아무 경고 없이 모양이 다른 관측을 받고,
    numpy 의 reshape 에서야 터진다 — 그것도 운이 좋을 때 얘기다. 크기가 우연히 맞으면
    뒤섞인 관측으로 학습이 계속된다.
    """
    import grpc

    first = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=1))
    second = PikaVectorEnv(env_target, EnvOptions(num_envs=8, base_seed=1))
    try:
        assert second.session_id > first.session_id

        with pytest.raises(grpc.RpcError) as caught:
            first.step(np.zeros(first.num_envs, dtype=np.int64))
        assert caught.value.code() == grpc.StatusCode.FAILED_PRECONDITION

        # 나중에 붙은 쪽은 정상이다.
        obs, _, _, _, _ = second.step(np.zeros(second.num_envs, dtype=np.int64))
        assert obs.shape == (second.num_envs, second.obs_dim)
    finally:
        first.close()
        second.close()


# ── 진영 분할 (P2, FR-3 · FR-14) ────────────────────────────────────────


def _obs_stream(env: PikaVectorEnv, steps: int, seed: int) -> np.ndarray:
    """고정 시드의 행동으로 굴려 관측만 쌓는다. 두 구성이 같은지는 이것으로 묻는다."""
    rng = np.random.default_rng(seed)
    env.reset()
    return rollout(env, steps, rng)[0]


def test_for_policy_turns_on_the_side_flag(env_target: str):
    """학습기와 평가기가 **하나의 상수**를 공유한다 (FR-14).

    미러링은 오른쪽 관측을 왼쪽 시점으로 뒤집지만 이 게임은 좌우 대칭이 아니다.
    플래그가 없으면 정책은 두 진영의 중간값으로 헤지한다. Track B(Phase 4)도 같은
    값을 써야 Phase 5 의 동일 예산 비교가 성립한다.
    """
    from pika_trainer.env_client import OBS_INCLUDE_SIDE_FLAG

    assert OBS_INCLUDE_SIDE_FLAG is True

    options = EnvOptions.for_policy(num_envs=4, base_seed=3)
    assert options.obs_include_side_flag is True
    assert options.swapped_envs == 2, "주지 않으면 절반이 오른쪽 진영이다"

    env = PikaVectorEnv(env_target, options)
    try:
        assert env.obs_dim == 41, "진영 플래그를 켜면 40 → 41 이다"
        assert "match.side_flag" in env.obs_field_names
        spec = ObsSpec.load()
        assert env.server_layout_hash == spec.layout_hash(ObsOptions(include_side_flag=True))
    finally:
        env.close()


def test_swapped_envs_equals_a_flipped_configuration(env_target: str):
    """전량 스왑은 진영을 뒤집어 구성한 것과 **바이트 단위로** 같다.

    이것이 참이어야 앞뒤 절반을 한 배치에 섞어도 각 절반이 정직한 표본이다.
    """
    flipped = PikaVectorEnv(env_target, EnvOptions(num_envs=4, base_seed=5, p1="fsm", p2="external"))
    try:
        expected = _obs_stream(flipped, 300, seed=1)
    finally:
        flipped.close()

    swapped = PikaVectorEnv(
        env_target, EnvOptions(num_envs=4, base_seed=5, p1="external", p2="fsm", swapped_envs=4),
    )
    try:
        actual = _obs_stream(swapped, 300, seed=1)
    finally:
        swapped.close()

    assert np.array_equal(expected, actual), "스왑이 슬롯 구성 이상의 일을 하고 있습니다"


def test_slot_sides_and_score_orientation(env_target: str):
    """진영은 **환경마다** 다르다. 점수를 열 인덱싱으로 읽으면 뒤쪽 절반이 뒤집힌다."""
    env = PikaVectorEnv(
        env_target, EnvOptions(num_envs=4, base_seed=13, p1="external", p2="fsm", swapped_envs=2),
    )
    try:
        assert env.slot_sides.tolist() == [0, 0, 1, 1]

        rng = np.random.default_rng(3)
        env.reset()
        # 점수가 0:0 이 아니게 될 때까지 굴린다 — 전부 0 이면 방향을 시험할 수 없다.
        for _ in range(4000):
            _, _, _, _, info = env.step(rng.integers(0, ACTION_COUNT, size=env.num_envs, dtype=np.int64))
            if env._scores.any():
                break

        scores = env._scores  # (server_num_envs, 2) — [player1, player2] 진영 순서
        assert scores.any(), "점수가 한 번도 나지 않았습니다"
        assert info["score_me"].tolist() == [scores[0, 0], scores[1, 0], scores[2, 1], scores[3, 1]]
        assert info["score_opponent"].tolist() == [scores[0, 1], scores[1, 1], scores[2, 0], scores[3, 0]]
        assert info["side"].tolist() == [0, 0, 1, 1]
    finally:
        env.close()


def test_swapped_envs_is_range_checked(env_target: str):
    import grpc

    for bad in (5, -1):
        with pytest.raises(grpc.RpcError) as caught:
            PikaVectorEnv(env_target, EnvOptions(num_envs=4, swapped_envs=bad))
        assert caught.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ── boldness 고정 (P2, FR-13 — 진단 축이다) ─────────────────────────────


def test_fixed_boldness_changes_the_stream(env_target: str):
    """b 를 바꾸면 FSM 의 행동이 실제로 달라진다 — 배선이 죽어 있지 않다는 증거다.

    ⚠️ 추첨 대비로 비교하면 안 된다. 추첨값이 마침 고정값과 같으면 (b 는 다섯 값뿐이다)
       아무 차이도 없고 테스트가 이유 없이 실패한다. **고정값끼리** 비교한다.
    """
    def stream(boldness: int) -> np.ndarray:
        env = PikaVectorEnv(
            env_target, EnvOptions(num_envs=2, base_seed=17, fixed_boldness=boldness),
        )
        try:
            return _obs_stream(env, 500, seed=2)
        finally:
            env.close()

    assert not np.array_equal(stream(0), stream(4)), "boldness 를 바꿨는데 아무 변화가 없습니다"


def test_fixed_boldness_is_range_checked(env_target: str):
    import grpc

    for bad in (5, -2):
        with pytest.raises(grpc.RpcError) as caught:
            PikaVectorEnv(env_target, EnvOptions(num_envs=2, fixed_boldness=bad))
        assert caught.value.code() == grpc.StatusCode.INVALID_ARGUMENT


# ── 보상 항 (P4 가 쓴다) ────────────────────────────────────────────────


def test_reward_terms_is_a_snapshot(track_a: PikaVectorEnv):
    """롤아웃 버퍼가 참조를 들고 있으면 다음 스텝이 과거 값을 덮어쓴다.

    그 버그는 손실 곡선에 드러나지 않는다 — 조용히 틀린다. 그래서 복사본을 낸다.
    """
    track_a.reset(seed=1)
    rng = np.random.default_rng(0)
    track_a.step(rng.integers(0, ACTION_COUNT, size=track_a.num_envs, dtype=np.int64))

    terms = track_a.reward_terms
    assert terms.shape == (track_a.num_envs, len(track_a.reward_term_names))
    assert terms is not track_a._terms

    # time_penalty 는 프레임마다 -1 이다 (항의 부호는 항 안에 있다).
    penalty = track_a.reward_term_names.index("time_penalty")
    assert np.all(terms[:, penalty] == -1.0)

    terms[:] = 12345.0
    for _ in range(3):
        track_a.step(rng.integers(0, ACTION_COUNT, size=track_a.num_envs, dtype=np.int64))
    assert np.all(track_a.reward_terms != 12345.0), "내부 버퍼가 밖으로 새어 나갔습니다"
