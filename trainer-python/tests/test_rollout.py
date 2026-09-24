"""롤아웃의 정확성. (M3-f / tasks.md P4 / plan.md §6.3, §7)

─────────────────────────────────────────────────────────────────────────────
왜 이 파일이 구현보다 먼저 쓰였는가
─────────────────────────────────────────────────────────────────────────────
여기서 틀리면 **조용히** 틀린다. 버려지는 스텝을 학습에 섞어도 손실은 내려가고 보상
곡선은 올라간다 (`plan.md` §7.1). 증상이 없는 버그는 나중에 "PPO 하이퍼파라미터가
나쁘다" 로 오진된다 — 그래서 알고리즘을 얹기 전에 계약을 못 박는다.

─────────────────────────────────────────────────────────────────────────────
서버를 띄우지 않는다
─────────────────────────────────────────────────────────────────────────────
이 파일이 검사하는 것은 **환경이 주는 플래그 수열을 어떻게 해석하는가** 다. 실제 서버는
그 수열을 만들 뿐이고, 실제 서버로는 "랠리가 정확히 여기서 끝나는" 상황을 만들 수 없다.
그래서 `ScriptedEnv` 로 `PikaEnv` 의 **오토리셋 상태기계만** 흉내낸다 (`plan.md` §7.1 의 표).
그 상태기계가 진짜와 같다는 것은 `test_env_client.py` 가 실제 서버로 확인한다.
"""

from __future__ import annotations

import numpy as np
import pytest
import torch

from pika_trainer.rollout import (
    RolloutCollector,
    compute_gae,
    masked_mean,
    masked_normalize,
)
from pika_trainer.schedules import Linear, Piecewise, RewardWeighting

TERM_NAMES = ["rally_win", "ball_touch", "crossed_net", "opponent_miss", "time_penalty"]
ACTION_COUNT = 18
OBS_DIM = 4


# ═══════════════════════════════════════════════════════════════════════════
# 가짜 환경 — PikaEnv 의 next-step autoreset 상태기계 (plan.md §7.1)
# ═══════════════════════════════════════════════════════════════════════════


class ScriptedEnv:
    """정해진 랠리 수열을 그대로 재생하는 벡터 환경.

    ⚠️ `PikaVectorEnv` 처럼 **버퍼를 재사용한다**. 롤아웃이 참조를 담으면 과거 스텝이
       덮어써지고, 그 버그는 손실 곡선에 드러나지 않는다 (`plan.md` §12 함정 3).
       이 클래스가 재사용하지 않으면 그 함정을 테스트가 통과시켜 버린다.
    """

    def __init__(
        self,
        rallies: list[list[tuple[int, str]]],
        *,
        sides: np.ndarray | None = None,
        winners: list[list[bool]] | None = None,
    ) -> None:
        """
        Args:
            rallies: 환경별 `(랠리 길이, "term" | "trunc")` 목록. 다 쓰면 처음부터 순환한다.
            winners: 환경별 랠리 승패. 주지 않으면 전부 승리다.
        """
        self.num_envs = len(rallies)
        self.obs_dim = OBS_DIM
        self.reward_term_names = list(TERM_NAMES)
        self._plan = rallies
        self._winners = winners or [[True] * len(r) for r in rallies]
        self._slot_side = (
            sides if sides is not None else np.zeros(self.num_envs, dtype=np.intp)
        )

        self._obs = np.zeros((self.num_envs, OBS_DIM), dtype=np.float32)
        self._terms = np.zeros((self.num_envs, len(TERM_NAMES)), dtype=np.float32)
        self._terminated = np.zeros(self.num_envs, dtype=bool)
        self._truncated = np.zeros(self.num_envs, dtype=bool)

        self.step_count = 0
        self.actions_seen: list[np.ndarray] = []
        self.reset()

    # ── Gymnasium 규약의 일부만 ──────────────────────────────────────────

    def reset(self, *, seed: int | None = None):  # noqa: ARG002
        self._rally_idx = np.zeros(self.num_envs, dtype=np.intp)
        self._frame = np.zeros(self.num_envs, dtype=np.intp)
        self._pending = np.zeros(self.num_envs, dtype=bool)
        self.step_count = 0
        self._write_obs()
        self._terms[:] = 0.0
        self._terminated[:] = False
        self._truncated[:] = False
        return self._obs, {}

    def step(self, actions: np.ndarray):
        assert actions.dtype == np.uint8, "행동은 uint8 로 온다 (plan.md §8.2)"
        self.actions_seen.append(np.array(actions))
        self.step_count += 1
        self._terms[:] = 0.0
        self._terminated[:] = False
        self._truncated[:] = False

        for i in range(self.num_envs):
            if self._pending[i]:
                # (1) 버려지는 스텝 — 리셋만 하고 행동은 버린다. 보상 0, 플래그 0.
                self._pending[i] = False
                self._rally_idx[i] += 1
                self._frame[i] = 0
                continue

            self._frame[i] += 1
            length, ending = self._rally(i)
            # 셰이핑 항이 0 이 아니어야 term_share 가 의미를 갖는다.
            self._terms[i, TERM_NAMES.index("ball_touch")] = float(self._frame[i] % 2 == 1)
            self._terms[i, TERM_NAMES.index("crossed_net")] = float(self._frame[i] % 3 == 0)
            self._terms[i, TERM_NAMES.index("time_penalty")] = -1.0
            if self._frame[i] >= length:
                won = self._winners[i][int(self._rally_idx[i]) % len(self._winners[i])]
                if ending == "term":
                    self._terminated[i] = True
                    self._terms[i, TERM_NAMES.index("rally_win")] = 1.0 if won else -1.0
                    self._terms[i, TERM_NAMES.index("opponent_miss")] = float(won)
                else:
                    # 잘린 랠리에는 승자가 없다 (Reward.kt 의 RALLY_WIN 주석).
                    self._truncated[i] = True
                self._pending[i] = True

        self._write_obs()
        rewards = np.zeros(self.num_envs, dtype=np.float32)  # 서버의 합계는 쓰지 않는다 (FR-5)
        return self._obs, rewards, self._terminated, self._truncated, {}

    # ── 조회 ─────────────────────────────────────────────────────────────

    @property
    def reward_terms(self) -> np.ndarray:
        return self._terms.copy()

    @property
    def slot_sides(self) -> np.ndarray:
        return self._slot_side

    # ── 내부 ─────────────────────────────────────────────────────────────

    def _rally(self, i: int) -> tuple[int, str]:
        plan = self._plan[i]
        return plan[int(self._rally_idx[i]) % len(plan)]

    def _write_obs(self) -> None:
        """관측에 **신원**을 적는다 — 어느 스텝의 관측인지 테스트가 알아볼 수 있게."""
        self._obs[:, 0] = self.step_count
        self._obs[:, 1] = np.arange(self.num_envs)
        self._obs[:, 2] = self._rally_idx
        self._obs[:, 3] = self._frame


class FakeNet:
    """가치가 관측의 함수로 **손으로 계산 가능한** 망.

    `RolloutCollector` 는 `ActorCritic` 을 import 하지 않는다 (NFR-4 의 방향). 필요한 것은
    `act` 와 `value` 두 메서드뿐이고, 이 클래스가 그 계약을 문서화한다.
    """

    def __init__(self, action: int = 3) -> None:
        self.action = action

    def act(self, obs: torch.Tensor):
        n = obs.shape[0]
        action = torch.full((n,), self.action, dtype=torch.int64)
        logp = torch.full((n,), -0.5)
        return action, logp, self.value(obs)

    def value(self, obs: torch.Tensor) -> torch.Tensor:
        # 스텝 번호를 그대로 가치로 쓴다 — s_final 부트스트랩을 눈으로 확인할 수 있다.
        return obs[:, 0].clone()


# ═══════════════════════════════════════════════════════════════════════════
# GAE — 손으로 계산한 예제와 대조한다
# ═══════════════════════════════════════════════════════════════════════════

GAMMA, LAM = 0.5, 0.5


def _gae(rewards, values, term, trunc, bootstrap):
    return compute_gae(
        rewards=torch.tensor(rewards, dtype=torch.float32).unsqueeze(1),
        values=torch.tensor(values, dtype=torch.float32).unsqueeze(1),
        terminated=torch.tensor(term, dtype=torch.bool).unsqueeze(1),
        truncated=torch.tensor(trunc, dtype=torch.bool).unsqueeze(1),
        bootstrap_value=torch.tensor([bootstrap], dtype=torch.float32),
        gamma=GAMMA,
        lam=LAM,
    )


def test_GAE가_손계산과_일치한다() -> None:
    """종료가 없는 구간. 부트스트랩은 롤아웃 밖의 마지막 관측에서 온다.

        t=2: δ = 3 + .5·4 − 2 = 3.0            A = 3.0
        t=1: δ = 2 + .5·2 − 1 = 2.0            A = 2.0 + .25·3.0 = 2.75
        t=0: δ = 1 + .5·1 − .5 = 1.0           A = 1.0 + .25·2.75 = 1.6875
    """
    adv, ret = _gae([1, 2, 3], [0.5, 1.0, 2.0], [0, 0, 0], [0, 0, 0], 4.0)
    assert adv.squeeze(1).tolist() == pytest.approx([1.6875, 2.75, 3.0])
    # returns = advantage + V(s) — 가치 손실의 목표다.
    assert ret.squeeze(1).tolist() == pytest.approx([2.1875, 3.75, 5.0])


def test_terminated는_부트스트랩하지_않는다() -> None:
    """`t=1` 이 종료다. `next_value` 에 `(1 − terminated)` 가 곱해진다 (plan.md §6.3).

        t=1: δ = 2 + .5·2·**0** − 1 = 1.0      A = 1.0 + .25·**0**·3.0 = 1.0  ← t=2 를 흡수하지 않는다
        t=0: δ = 1.0                           A = 1.0 + .25·1.0 = 1.25       ← t=0,1 은 같은 랠리다

    ⚠️ `cut[t]` 이 끊는 것은 "t 가 t+1 을 흡수하는 것" 이지 "t−1 이 t 를 흡수하는 것" 이
       아니다. 종료 스텝의 advantage 자체는 유효한 학습 신호이고, 그 앞 스텝은 그것을
       정상적으로 받는다.
    """
    adv, _ = _gae([1, 2, 3], [0.5, 1.0, 2.0], [0, 1, 0], [0, 0, 0], 4.0)
    assert adv.squeeze(1).tolist() == pytest.approx([1.25, 1.0, 3.0])


def test_truncated는_V_s_final_로_부트스트랩한다() -> None:
    """같은 자리가 truncation 이면 `V(s_final)` 이 들어온다. 자르는 것은 우리 사정이다.

        t=1: δ = 2 + .5·**2** − 1 = 2.0        A = 2.0
        t=0: δ = 1.0                           A = 1.0 + .25·2.0 = 1.5
    """
    adv, _ = _gae([1, 2, 3], [0.5, 1.0, 2.0], [0, 0, 0], [0, 1, 0], 4.0)
    assert adv.squeeze(1).tolist() == pytest.approx([1.5, 2.0, 3.0])
    # terminated 와의 차이가 곧 부트스트랩의 크기다: γ·V(s_final) = .5 × 2 = 1.0.
    # 그리고 그 차이는 γλ 로 **앞으로 번진다** — 랠리가 잘리지 않았다면 그 앞의 행동들도
    # 이어질 가치의 몫을 받아야 하기 때문이다.
    term_adv, _ = _gae([1, 2, 3], [0.5, 1.0, 2.0], [0, 1, 0], [0, 0, 0], 4.0)
    assert (adv - term_adv).squeeze(1).tolist() == pytest.approx([0.25, 1.0, 0.0])


def test_GAE_재귀가_에피소드_경계를_넘지_않는다() -> None:
    """경계 뒤의 보상을 아무리 키워도 경계 앞의 advantage 는 움직이지 않는다."""
    base, _ = _gae([1, 2, 3], [0.5, 1.0, 2.0], [0, 1, 0], [0, 0, 0], 4.0)
    loud, _ = _gae([1, 2, 1000], [0.5, 1.0, 2.0], [0, 1, 0], [0, 0, 0], 4.0)
    assert base[0].item() == pytest.approx(loud[0].item())
    assert base[1].item() == pytest.approx(loud[1].item())
    assert base[2].item() != pytest.approx(loud[2].item())


def test_환경마다_독립으로_계산된다() -> None:
    """한 env 의 종료가 다른 env 의 재귀를 끊으면 안 된다."""
    rewards = torch.tensor([[1.0, 1.0], [2.0, 2.0], [3.0, 3.0]])
    values = torch.tensor([[0.5, 0.5], [1.0, 1.0], [2.0, 2.0]])
    term = torch.tensor([[False, False], [True, False], [False, False]])
    adv, _ = compute_gae(
        rewards=rewards,
        values=values,
        terminated=term,
        truncated=torch.zeros_like(term),
        bootstrap_value=torch.tensor([4.0, 4.0]),
        gamma=GAMMA,
        lam=LAM,
    )
    assert adv[:, 0].tolist() == pytest.approx([1.25, 1.0, 3.0])      # 종료 있음
    assert adv[:, 1].tolist() == pytest.approx([1.6875, 2.75, 3.0])   # 종료 없음


# ═══════════════════════════════════════════════════════════════════════════
# 마스킹 — 버려지는 스텝이 손실에 0 을 기여한다 (M3-f)
# ═══════════════════════════════════════════════════════════════════════════


def test_masked_mean이_마스크_밖을_무시한다() -> None:
    x = torch.tensor([1.0, 2.0, 3.0, 4.0])
    mask = torch.tensor([True, False, True, False])
    assert masked_mean(x, mask).item() == pytest.approx(2.0)
    # 마스크 밖을 어떤 값으로 바꿔도 결과가 같다 = "0 기여" 의 정의다.
    x[1], x[3] = 1e9, -1e9
    assert masked_mean(x, mask).item() == pytest.approx(2.0)


def test_masked_mean은_마스크가_비면_0이다() -> None:
    """미니배치 전체가 버려지는 스텝일 수 있다. 그때 NaN 이 나오면 학습이 통째로 죽는다."""
    x = torch.tensor([1e9, -1e9])
    out = masked_mean(x, torch.tensor([False, False]))
    assert out.item() == 0.0
    assert torch.isfinite(out)


def test_advantage_정규화가_마스크_안에서만_통계를_잡는다() -> None:
    """마스크 밖의 쓰레기가 평균·표준편차를 오염시키면 안 된다 (plan.md §8.2 함정 4)."""
    adv = torch.tensor([1.0, 999.0, 2.0, -999.0, 3.0])
    mask = torch.tensor([True, False, True, False, True])
    out = masked_normalize(adv, mask)
    # 유효한 세 값 [1,2,3] 만의 평균 2, 모분산 2/3 → ∓√1.5, 0
    # (표본분산이 아니라 모분산이다. 미니배치 8,192 에서 둘의 차이는 1e-4 이고,
    #  ddof 를 마스크 안에서 다시 세는 복잡함을 살 이유가 없다.)
    root = 1.5**0.5
    assert out[mask].tolist() == pytest.approx([-root, 0.0, root], abs=1e-5)
    # 마스크 밖 값을 바꿔도 마스크 안 결과가 변하지 않는다.
    adv2 = adv.clone()
    adv2[1], adv2[3] = 5e5, 7.0
    assert masked_normalize(adv2, mask)[mask].tolist() == pytest.approx(out[mask].tolist(), abs=1e-5)


def test_정규화가_유효_표본_하나에서도_유한하다() -> None:
    out = masked_normalize(torch.tensor([5.0, 1e9]), torch.tensor([True, False]))
    assert torch.isfinite(out[0])


# ═══════════════════════════════════════════════════════════════════════════
# 수집 루프 — 버려지는 스텝과 pending
# ═══════════════════════════════════════════════════════════════════════════


def collector(env: ScriptedEnv, **kwargs) -> RolloutCollector:
    return RolloutCollector(env, FakeNet(), gamma=GAMMA, lam=LAM, **kwargs)


def test_버려지는_스텝이_valid_False_로_표시된다() -> None:
    """랠리 길이 3 → 스텝 3 에서 종료, 스텝 4 는 리셋이라 버린다."""
    env = ScriptedEnv([[(3, "term")]])
    batch, _ = collector(env).collect(8, np.zeros(5, dtype=np.float32))

    # 0-based: t=2 가 종료, t=3 이 버려지는 스텝. 이후 4주기로 반복한다.
    assert batch.terminated[:, 0].tolist() == [0, 0, 1, 0, 0, 0, 1, 0]
    assert batch.valid[:, 0].tolist() == [1, 1, 1, 0, 1, 1, 1, 0]
    assert batch.num_valid == 6


def test_버려지는_스텝은_손실에_0을_기여한다() -> None:
    """M3-f 의 본문. 그 스텝의 advantage·가치 목표를 어떻게 흔들어도 손실이 같다."""
    env = ScriptedEnv([[(3, "term")]])
    batch, _ = collector(env).collect(8, np.array([1, 0, 0, 0, 0], dtype=np.float32))
    mask = batch.valid.bool()

    before = (masked_mean(batch.advantages, mask).item(), masked_mean(batch.returns, mask).item())
    batch.advantages[~mask] = 1e9
    batch.returns[~mask] = -1e9
    after = (masked_mean(batch.advantages, mask).item(), masked_mean(batch.returns, mask).item())
    assert before == pytest.approx(after)


def test_버려지는_스텝의_쓰레기가_앞으로_새지_않는다() -> None:
    """`cut` 이 종료 스텝에서 재귀를 끊으므로, 버려지는 스텝의 보상을 키워도 앞은 그대로다.

    이것이 "마스킹 하나로 충분한" 이유다 (plan.md §7.2). 끊기지 않으면 종단 상태의 가치가
    새 랠리의 첫 상태 쪽으로 끌려간다.
    """
    env = ScriptedEnv([[(3, "term")]])
    batch, _ = collector(env).collect(8, np.zeros(5, dtype=np.float32))
    clean = batch.advantages[:3, 0].tolist()

    dirty_adv, _ = compute_gae(
        rewards=batch.rewards.clone().index_put_(
            (torch.tensor([3]), torch.tensor([0])), torch.tensor([1000.0]),
        ),
        values=batch.values,
        terminated=batch.terminated.bool(),
        truncated=batch.truncated.bool(),
        bootstrap_value=batch.bootstrap_value,
        gamma=GAMMA,
        lam=LAM,
    )
    assert dirty_adv[:3, 0].tolist() == pytest.approx(clean)


def test_pending이_롤아웃_경계를_넘어_유지된다() -> None:
    """롤아웃이 종료 스텝에서 정확히 끝나면, **다음** 롤아웃의 첫 스텝이 버려지는 스텝이다.

    이 상태를 인스턴스에 들고 있지 않으면 매 롤아웃의 첫 스텝이 조용히 오염된다.
    """
    env = ScriptedEnv([[(3, "term")]])
    c = collector(env)
    first, _ = c.collect(3, np.zeros(5, dtype=np.float32))
    assert first.valid[:, 0].tolist() == [1, 1, 1]
    assert first.terminated[2, 0] == 1

    second, _ = c.collect(3, np.zeros(5, dtype=np.float32))
    assert second.valid[:, 0].tolist() == [0, 1, 1]  # 첫 스텝이 버려진다


def test_관측이_한_스텝_밀려_저장된다() -> None:
    """`values[t+1]` 이 `V(s_final)` 이려면 obs 정렬이 정확해야 한다 (plan.md §6.3).

    next-step autoreset 의 이점이 여기서 나온다 — 종료 스텝이 돌려준 관측이 곧 `s_final` 이고,
    그것이 다음 슬롯에 그대로 들어온다. same-step autoreset 이었다면 그 자리에 새 랠리의
    첫 관측이 들어와 `V(s_final)` 대신 `V(s'_0)` 로 부트스트랩하게 된다.
    """
    env = ScriptedEnv([[(3, "term")]])
    batch, _ = collector(env).collect(6, np.zeros(5, dtype=np.float32))
    # obs[:, 0] 은 그 관측이 나온 스텝 번호다. t 번째 슬롯에는 t 번째 스텝의 입력 관측이 들어간다.
    assert batch.obs[:, 0, 0].tolist() == [0, 1, 2, 3, 4, 5]
    # 종료가 t=2 → t=3 슬롯의 관측은 **랠리의 마지막 프레임**(frame=3)이지 새 랠리가 아니다.
    assert batch.obs[3, 0, 3].item() == 3.0
    assert batch.obs[4, 0, 3].item() == 0.0  # 버려지는 스텝이 새 랠리의 첫 관측을 낸다
    # 따라서 truncation 부트스트랩이 쓰는 values[t+1] 은 s_final 의 가치다.
    assert batch.values[3, 0].item() == pytest.approx(3.0)


def test_마지막_스텝은_돌려받은_관측으로_부트스트랩한다() -> None:
    env = ScriptedEnv([[(100, "term")]])
    batch, _ = collector(env).collect(5, np.zeros(5, dtype=np.float32))
    assert batch.bootstrap_value.tolist() == pytest.approx([5.0])


# ═══════════════════════════════════════════════════════════════════════════
# 보상 — 가중치는 클라이언트에 있다 (FR-5)
# ═══════════════════════════════════════════════════════════════════════════


def test_보상이_항별_원시값에_현재_가중치를_곱한_값이다() -> None:
    env = ScriptedEnv([[(3, "term")]])
    weights = np.array([1.0, 0.05, 0.10, 0.0, 0.0], dtype=np.float32)
    batch, _ = collector(env).collect(4, weights)
    # frame 1: ball_touch → .05 / frame 2: 없음 → 0
    # frame 3: rally_win 1 + ball_touch .05 + crossed_net .10 = 1.15 / frame 4: 버려지는 스텝 → 0
    assert batch.rewards[:, 0].tolist() == pytest.approx([0.05, 0.0, 1.15, 0.0])


def test_가중치를_바꾸면_같은_전이의_보상이_달라진다() -> None:
    """어닐링의 전제다 — 서버를 다시 Configure 하지 않고 반복마다 가중치를 바꾼다 (plan.md §6.1)."""
    batch_a, _ = collector(ScriptedEnv([[(3, "term")]])).collect(
        4, np.array([1, 0, 0, 0, 0], dtype=np.float32),
    )
    batch_b, _ = collector(ScriptedEnv([[(3, "term")]])).collect(
        4, np.array([1, 0, 0, 0, 1], dtype=np.float32),
    )
    assert batch_a.rewards[:, 0].tolist() == pytest.approx([0.0, 0.0, 1.0, 0.0])
    assert batch_b.rewards[:, 0].tolist() == pytest.approx([-1.0, -1.0, 0.0, 0.0])


def test_버려지는_스텝의_보상은_0이다() -> None:
    env = ScriptedEnv([[(2, "term")]])
    batch, _ = collector(env).collect(6, np.array([1, 1, 1, 1, 1], dtype=np.float32))
    assert batch.rewards[2, 0].item() == 0.0
    assert batch.rewards[5, 0].item() == 0.0


def test_보상이_재사용_버퍼를_참조하지_않는다() -> None:
    """`info[...]` 와 내부 배열은 스텝마다 덮어써진다 (plan.md §12 함정 3).

    참조를 담았다면 모든 스텝의 보상이 마지막 스텝 값으로 같아진다.
    """
    env = ScriptedEnv([[(4, "term")]])
    batch, _ = collector(env).collect(4, np.array([1, 1, 1, 0, 0], dtype=np.float32))
    assert len(set(batch.rewards[:, 0].tolist())) > 1


def test_행동이_uint8로_전달된다() -> None:
    """torch 의 int64 를 그대로 넘기면 클라이언트가 핫 패스에서 변환한다 (plan.md §8.2)."""
    env = ScriptedEnv([[(5, "term")]])
    collector(env).collect(3, np.zeros(5, dtype=np.float32))
    assert all(a.dtype == np.uint8 for a in env.actions_seen)


# ═══════════════════════════════════════════════════════════════════════════
# 진영별 메트릭 (FR-9, plan.md §4.4)
# ═══════════════════════════════════════════════════════════════════════════


def test_진영별로_통계가_갈린다() -> None:
    """앞 절반이 왼쪽, 뒤 절반이 오른쪽이다. 나누지 않으면 한쪽의 실패가 평균에 묻힌다."""
    env = ScriptedEnv(
        [[(3, "term")], [(3, "term")], [(5, "term")], [(5, "term")]],
        sides=np.array([0, 0, 1, 1], dtype=np.intp),
        winners=[[True], [True], [False], [False]],
    )
    _, metrics = collector(env).collect(12, np.array([1, 0, 0, 0, 0], dtype=np.float32))

    assert metrics.left.rally_win_rate == pytest.approx(1.0)
    assert metrics.right.rally_win_rate == pytest.approx(0.0)
    assert metrics.left.mean_rally_frames == pytest.approx(3.0)
    assert metrics.right.mean_rally_frames == pytest.approx(5.0)
    assert metrics.left.rallies == 6   # env 2개 × 12스텝 / 4스텝주기 = 3랠리씩
    assert metrics.right.rallies == 4  # env 2개 × 12스텝 / 6스텝주기 = 2랠리씩


def test_truncation_비율이_따로_집계된다() -> None:
    """M3-h 의 감시 지표다. 셰이핑이 저글링 정책을 만들면 여기가 먼저 움직인다."""
    env = ScriptedEnv([[(3, "term"), (3, "trunc")]])
    _, metrics = collector(env).collect(16, np.zeros(5, dtype=np.float32))
    assert metrics.left.rallies == 4
    assert metrics.left.truncated_rallies == 2
    assert metrics.left.trunc_rate == pytest.approx(0.5)
    # 잘린 랠리에는 승자가 없다 — 승률의 분모에는 들어가고 분자에는 들어가지 않는다.
    assert metrics.left.rally_wins == 2
    assert metrics.left.rally_win_rate == pytest.approx(0.5)


def test_랠리_길이가_롤아웃_경계를_넘어_이어진다() -> None:
    """랠리는 T 보다 길 수 있다 (plan.md §5.3). 롤아웃마다 세면 길이가 잘려서 보인다."""
    env = ScriptedEnv([[(10, "term")]])
    c = collector(env)
    _, first = c.collect(6, np.zeros(5, dtype=np.float32))
    _, second = c.collect(6, np.zeros(5, dtype=np.float32))
    assert first.left.rallies == 0              # 아직 안 끝났다 — 반쪽을 집계하지 않는다
    assert second.left.rallies == 1
    assert second.left.mean_rally_frames == pytest.approx(10.0)


def test_행동_분포와_항별_기여가_집계된다() -> None:
    env = ScriptedEnv([[(3, "term")]])
    _, metrics = collector(env).collect(8, np.array([1.0, 0.05, 0.0, 0.0, 0.0], dtype=np.float32))
    hist = metrics.left.action_hist
    assert hist.shape == (ACTION_COUNT,)
    assert hist[3] == 6 and hist.sum() == 6  # FakeNet 은 항상 3 을 낸다. 버려지는 스텝은 빠진다
    # 기여 비율: rally_win 2회 × 1.0 vs ball_touch 4회 × 0.05
    share = metrics.left.term_share
    assert share["rally_win"] == pytest.approx(2.0 / 2.2)
    assert share["ball_touch"] == pytest.approx(0.2 / 2.2)
    assert share["crossed_net"] == pytest.approx(0.0)


def test_메트릭이_진영_접두사로_평탄화된다() -> None:
    """`plan.md` §11 의 JSONL 스키마 — Phase 8 이 두 트랙을 겹쳐 그린다."""
    env = ScriptedEnv([[(3, "term")], [(3, "term")]], sides=np.array([0, 1], dtype=np.intp))
    _, metrics = collector(env).collect(8, np.array([1, 0, 0, 0, 0], dtype=np.float32))
    flat = metrics.to_dict()
    for key in ("left.rally_win_rate", "right.rally_win_rate", "left.trunc_rate",
                "left.rally_frames_mean", "left.term_share.rally_win", "left.action_hist"):
        assert key in flat, key
    assert flat["valid_share"] == pytest.approx(6 / 8)


# ═══════════════════════════════════════════════════════════════════════════
# 스케줄 (plan.md §6.2, §8.1)
# ═══════════════════════════════════════════════════════════════════════════


def test_선형_스케줄() -> None:
    lr = Linear(3e-4, 0.0)
    assert lr(0.0) == pytest.approx(3e-4)
    assert lr(0.5) == pytest.approx(1.5e-4)
    assert lr(1.0) == pytest.approx(0.0)


def test_스케줄은_구간_밖에서_고정된다() -> None:
    """진행도가 1 을 넘을 수 있다 — 총 스텝을 넘겨 도는 경우. LR 이 음수가 되면 안 된다."""
    lr = Linear(3e-4, 0.0)
    assert lr(1.5) == pytest.approx(0.0)
    assert lr(-0.5) == pytest.approx(3e-4)


def test_구간_스케줄이_유지_후_감쇠한다() -> None:
    """셰이핑의 모양: 0~20% 유지 → 50% 에서 0 (plan.md §6.2)."""
    w = Piecewise([(0.0, 0.10), (0.2, 0.10), (0.5, 0.0)])
    assert w(0.0) == pytest.approx(0.10)
    assert w(0.2) == pytest.approx(0.10)
    assert w(0.35) == pytest.approx(0.05)
    assert w(0.5) == pytest.approx(0.0)
    assert w(0.9) == pytest.approx(0.0)


def test_가중치가_서버가_선언한_항_순서를_따른다() -> None:
    """항 순서를 하드코딩하면 서버가 항을 추가하는 날 **조용히** 어긋난다."""
    weighting = RewardWeighting(
        {"rally_win": 1.0, "crossed_net": Piecewise([(0.0, 0.10), (0.2, 0.10), (0.5, 0.0)])},
        TERM_NAMES,
    )
    assert weighting.at(0.0).tolist() == pytest.approx([1.0, 0.0, 0.10, 0.0, 0.0])
    assert weighting.at(0.35).tolist() == pytest.approx([1.0, 0.0, 0.05, 0.0, 0.0])
    assert weighting.at(1.0).tolist() == pytest.approx([1.0, 0.0, 0.0, 0.0, 0.0])
    assert weighting.at(0.0).dtype == np.float32


def test_모르는_항_이름은_즉시_실패한다() -> None:
    """오타가 "가중치 0" 으로 조용히 흡수되면 셰이핑이 꺼진 채로 학습이 돈다."""
    with pytest.raises(ValueError, match="ball_touhc"):
        RewardWeighting({"ball_touhc": 0.05}, TERM_NAMES)


def test_셰이핑이_넘기기를_닿기보다_크게_잡는다() -> None:
    """반대로 두면 자기 진영 저글링이 최적 전략이 된다 (plan.md §6.2, M3-h)."""
    from pika_trainer.schedules import track_a_weighting

    w = track_a_weighting(TERM_NAMES)
    at0 = dict(zip(TERM_NAMES, w.at(0.0).tolist(), strict=True))
    assert at0["rally_win"] == pytest.approx(1.0)
    assert at0["crossed_net"] > at0["ball_touch"] > 0
    assert at0["opponent_miss"] == 0.0 and at0["time_penalty"] == 0.0
    # 50% 지점에서 셰이핑이 꺼지고 목적만 남는다.
    at_end = dict(zip(TERM_NAMES, w.at(0.6).tolist(), strict=True))
    assert at_end == pytest.approx({"rally_win": 1.0, "ball_touch": 0.0, "crossed_net": 0.0,
                                    "opponent_miss": 0.0, "time_penalty": 0.0})


# ═══════════════════════════════════════════════════════════════════════════
# 실제 서버와의 접합 — 여기만 서버를 띄운다
# ═══════════════════════════════════════════════════════════════════════════


def test_실제_환경과_망에_그대로_붙는다(env_target: str) -> None:
    """`ScriptedEnv` 와 `FakeNet` 이 흉내낸 계약이 진짜와 같은지 한 번 확인한다.

    위의 테스트들은 전부 가짜 환경을 쓴다 — 실제 서버로는 "랠리가 정확히 여기서 끝나는"
    상황을 만들 수 없기 때문이다. 하지만 가짜만 있으면 **계약이 갈라진 것을 아무도 모른다**
    (`reward_terms` 의 모양, `slot_sides` 의 길이, uint8 행동, 관측 차원 41).
    그 접합만 여기서 산다. 통계는 보지 않는다 — 8 env × 32 스텝으로는 아무것도 말할 수 없다.
    """
    from pika_trainer.env_client import EnvOptions, PikaVectorEnv
    from pika_trainer.net import ActorCritic
    from pika_trainer.schedules import track_a_weighting

    env = PikaVectorEnv(env_target, EnvOptions.for_policy(num_envs=8, base_seed=3))
    try:
        torch.manual_seed(0)
        net = ActorCritic(env.obs_dim, ACTION_COUNT, hidden=32)
        c = RolloutCollector(env, net, gamma=0.997, lam=0.95)
        weights = track_a_weighting(env.reward_term_names)
        batch, metrics = c.collect(32, weights.at(0.0))

        assert env.obs_dim == 41, "진영 플래그가 켜져 있어야 한다 (FR-14)"
        assert batch.obs.shape == (32, 8, 41)
        assert torch.isfinite(batch.advantages).all() and torch.isfinite(batch.returns).all()
        assert batch.actions.max().item() < ACTION_COUNT
        # 벡터의 절반이 오른쪽 진영이다 (FR-3). 양쪽이 실제로 스텝을 받는다.
        assert metrics.left.steps == metrics.right.steps == 4 * 32
        # 버려지는 스텝은 드물다 — 랠리 길이의 역수다 (plan.md §7.3).
        assert 0.9 < batch.valid_share <= 1.0
        # 두 번째 롤아웃이 첫 스텝을 버리는지는 pending 이 정한다 (경계 유지).
        second, _ = c.collect(4, weights.at(0.0))
        assert second.valid[0].tolist() == (1 - batch.terminated[-1] - batch.truncated[-1]).tolist()
    finally:
        env.close()
