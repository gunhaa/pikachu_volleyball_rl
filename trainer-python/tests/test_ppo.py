"""PPO 손실의 수치 성질. (FR-1, NFR-4 / tasks.md P5 / plan.md §8)

─────────────────────────────────────────────────────────────────────────────
여기서 무엇을 확인하는가
─────────────────────────────────────────────────────────────────────────────
"학습이 된다" 가 아니다 — 그것은 P6 이 평가기로 잰다. 이 파일은 **부호와 크기**를 본다.
엔트로피 보너스의 부호가 뒤집혀도, 클리핑이 반대 방향에 걸려도, 가치 계수가 절반이어도
학습은 돌아가고 손실은 내려간다. 증상은 "왜인지 승률이 안 오른다" 뿐이다.

⚠️ 서버를 띄우지 않는다. `ppo.py` 는 상대를 모르고 (NFR-4), 그것이 Phase 7 의 Track B 가
   같은 파일을 재사용할 수 있는 조건이다.
"""

from __future__ import annotations

import ast
import math
import subprocess
import sys

import pytest
import torch

from pika_trainer.net import ActorCritic
from pika_trainer.ppo import (
    LossParts,
    PPOConfig,
    PPOUpdater,
    approx_kl,
    compute_losses,
    explained_variance,
)
from pika_trainer.rollout import RolloutBatch

OBS_DIM, ACTIONS = 41, 18
CLIP = 0.2


def _losses(*, log_ratio: float, adv, valid=None, values=None, returns=None, entropy=0.0):
    """`compute_losses` 를 스칼라 인자로 부르는 얇은 포장."""
    adv = torch.tensor(adv, dtype=torch.float32)
    n = adv.shape[0]
    old_logp = torch.zeros(n)
    return compute_losses(
        log_probs=old_logp + log_ratio,
        old_log_probs=old_logp,
        entropy=torch.full((n,), float(entropy)),
        values=torch.zeros(n) if values is None else torch.tensor(values, dtype=torch.float32),
        advantages=adv,
        returns=torch.zeros(n) if returns is None else torch.tensor(returns, dtype=torch.float32),
        valid=torch.ones(n) if valid is None else torch.tensor(valid, dtype=torch.float32),
        clip_eps=CLIP,
    )


# ═══════════════════════════════════════════════════════════════════════════
# clipped surrogate
# ═══════════════════════════════════════════════════════════════════════════


def test_ratio_1_에서_정책_손실은_마이너스_A다() -> None:
    """첫 에폭의 첫 미니배치가 정확히 이 상태다 (정책이 아직 안 변했다).

    여기가 틀리면 PPO 가 **시작부터** 잘못된 기울기를 타고, 그 사실은 어떤 곡선에도
    드러나지 않는다.
    """
    parts = _losses(log_ratio=0.0, adv=[1.0, -2.0, 0.5])
    assert float(parts.policy) == pytest.approx(-(1.0 - 2.0 + 0.5) / 3)
    assert float(parts.kl) == pytest.approx(0.0)
    assert float(parts.clipfrac) == pytest.approx(0.0)


def test_이득_방향은_클리핑된다() -> None:
    """A > 0 인데 ratio 가 1.5 면 이득이 `(1+ε)A` 에서 멈춘다."""
    import math

    parts = _losses(log_ratio=math.log(1.5), adv=[1.0])
    assert float(parts.policy) == pytest.approx(-(1.0 + CLIP) * 1.0)
    assert float(parts.clipfrac) == pytest.approx(1.0)


def test_손해_방향은_클리핑되지_않는다() -> None:
    """같은 ratio 1.5 라도 A < 0 이면 자르지 않는다 — **비대칭이 요점이다.**

    `min` 은 정책이 좋아지는 방향으로만 제한을 건다. 반대쪽까지 자르면 한 번 크게 틀린
    스텝에서 되돌아오는 기울기까지 잘려, 정책이 나쁜 행동에 갇힌다.
    """
    import math

    parts = _losses(log_ratio=math.log(1.5), adv=[-1.0])
    assert float(parts.policy) == pytest.approx(1.5)      # = −min(−1.5, −1.2) = 1.5
    # 잘리지는 않았지만 **ratio 가 밴드 밖이라는 사실**은 clipfrac 이 보고한다.
    assert float(parts.clipfrac) == pytest.approx(1.0)


def test_밴드_안에서는_클리핑이_아무것도_하지_않는다() -> None:
    import math

    parts = _losses(log_ratio=math.log(1.1), adv=[1.0, -1.0])
    assert float(parts.policy) == pytest.approx(0.0)      # 1.1 − 1.1 의 평균
    assert float(parts.clipfrac) == pytest.approx(0.0)


# ═══════════════════════════════════════════════════════════════════════════
# 엔트로피 · 가치 · KL
# ═══════════════════════════════════════════════════════════════════════════


def test_엔트로피는_보너스다() -> None:
    """부호가 뒤집히면 정책이 **한 행동으로 붕괴한다.** 그 실패는 승률로만 보인다.

    행동 18개인데 FSM 은 13개만 쓴다 (Phase 2 측정) — 탐색이 좁아지는 것을 막는 항이다.
    """
    low = LossParts(*[torch.tensor(v) for v in (0.0, 0.0, 1.0, 0.0, 0.0)])
    high = LossParts(*[torch.tensor(v) for v in (0.0, 0.0, 2.0, 0.0, 0.0)])
    assert float(high.total(value_coef=0.5, entropy_coef=0.02)) < float(
        low.total(value_coef=0.5, entropy_coef=0.02),
    )
    # 크기도 계수 그대로여야 한다 — 0.02 × (2 − 1) = 0.02
    assert float(low.total(value_coef=0.5, entropy_coef=0.02)) - float(
        high.total(value_coef=0.5, entropy_coef=0.02),
    ) == pytest.approx(0.02)


def test_가치_손실은_순수_MSE다() -> None:
    """`0.5 × MSE × value_coef` 관습을 쓰지 않는다 — 0.5 를 두 번 곱하면 `value_coef` 가
    표(plan.md §8.1)에 적힌 값의 절반을 뜻하게 된다."""
    parts = _losses(log_ratio=0.0, adv=[0.0, 0.0], values=[1.0, 3.0], returns=[0.0, 0.0])
    assert float(parts.value) == pytest.approx((1.0 + 9.0) / 2)


def test_KL은_0_이상이고_같은_정책에서_0이다() -> None:
    """조기 종료의 문턱(0.02)이 음수로 흔들리는 추정 위에 서면 안 된다."""
    logp = torch.tensor([-1.0, -2.0, -0.5])
    assert float(approx_kl(torch.zeros(3), torch.ones(3), torch.ones(3, dtype=torch.bool))) == 0.0
    for shift in (-0.3, 0.3, 1.0):
        log_ratio = torch.full_like(logp, shift)
        kl = float(approx_kl(log_ratio, log_ratio.exp(), torch.ones(3, dtype=torch.bool)))
        assert kl > 0.0, shift


def test_설명분산() -> None:
    mask = torch.ones(4, dtype=torch.bool)
    returns = torch.tensor([1.0, 2.0, 3.0, 4.0])
    assert explained_variance(returns.clone(), returns, mask) == pytest.approx(1.0)
    # 평균만 찍는 가치 함수는 0 이다 — "가치가 아직 아무것도 모른다" 의 기준선.
    assert explained_variance(torch.full((4,), 2.5), returns, mask) == pytest.approx(0.0)
    assert explained_variance(-returns, returns, mask) < 0.0


# ═══════════════════════════════════════════════════════════════════════════
# 마스킹 — 버려지는 스텝 (M3-f 의 손실 쪽)
# ═══════════════════════════════════════════════════════════════════════════


def test_마스크_밖은_손실에_0을_기여한다() -> None:
    clean = _losses(log_ratio=0.0, adv=[1.0, 2.0], valid=[1, 1])
    dirty = _losses(
        log_ratio=0.0, adv=[1.0, 2.0, 1e9], valid=[1, 1, 0],
        values=[0.0, 0.0, 1e6], returns=[0.0, 0.0, -1e6],
    )
    assert float(dirty.policy) == pytest.approx(float(clean.policy))
    assert float(dirty.value) == pytest.approx(float(clean.value))


def test_마스크_밖은_그래디언트에도_0을_기여한다() -> None:
    """손실 값이 같아도 **기울기**가 새면 소용없다. 파라미터 수준에서 확인한다."""
    def grads(extra_garbage: bool) -> torch.Tensor:
        torch.manual_seed(0)
        net = ActorCritic(OBS_DIM, ACTIONS, hidden=32)
        obs = torch.arange(3 * OBS_DIM, dtype=torch.float32).reshape(3, OBS_DIM) / 100
        actions = torch.tensor([0, 1, 2])
        valid = torch.tensor([1.0, 1.0, 0.0])
        adv = torch.tensor([1.0, -1.0, 1e6 if extra_garbage else 0.0])
        ret = torch.tensor([0.5, 0.2, -1e6 if extra_garbage else 0.0])
        logp, entropy, value = net.evaluate_actions(obs, actions)
        parts = compute_losses(
            logp, torch.zeros(3), entropy, value, adv, ret, valid, clip_eps=CLIP,
        )
        parts.total(value_coef=0.5, entropy_coef=0.02).backward()
        return net.actor[0].weight.grad.clone()

    assert torch.allclose(grads(False), grads(True), atol=1e-7)


# ═══════════════════════════════════════════════════════════════════════════
# 업데이트 루프
# ═══════════════════════════════════════════════════════════════════════════


def fake_batch(horizon: int = 8, num_envs: int = 8, *, seed: int = 0) -> RolloutBatch:
    """롤아웃 모양의 배치. 값은 학습용이 아니라 배선 확인용이다."""
    g = torch.Generator().manual_seed(seed)
    obs = torch.randn(horizon, num_envs, OBS_DIM, generator=g)
    valid = torch.ones(horizon, num_envs)
    valid[0, 0] = 0.0  # 버려지는 스텝 하나
    return RolloutBatch(
        obs=obs,
        actions=torch.randint(0, ACTIONS, (horizon, num_envs), generator=g),
        log_probs=torch.full((horizon, num_envs), -2.8904),
        values=torch.randn(horizon, num_envs, generator=g),
        rewards=torch.zeros(horizon, num_envs),
        terminated=torch.zeros(horizon, num_envs),
        truncated=torch.zeros(horizon, num_envs),
        valid=valid,
        advantages=torch.randn(horizon, num_envs, generator=g),
        returns=torch.randn(horizon, num_envs, generator=g),
        bootstrap_value=torch.zeros(num_envs),
        sides=torch.tensor([0] * (num_envs // 2) + [1] * (num_envs // 2)),
    )


def updater(config: PPOConfig | None = None, *, seed: int = 0, lr: float = 3e-4):
    torch.manual_seed(seed)
    net = ActorCritic(OBS_DIM, ACTIONS, hidden=32)
    opt = torch.optim.Adam(net.parameters(), lr=lr)
    return PPOUpdater(net, opt, config or PPOConfig(epochs=2, minibatches=4))


def test_업데이트가_통계를_낸다() -> None:
    up = updater()
    stats = up.update(fake_batch())
    assert stats.epochs_run == 2 and stats.minibatches_run == 8
    assert stats.kl >= 0.0 and 0.0 <= stats.clipfrac <= 1.0
    assert stats.grad_norm > 0.0
    assert set(stats.to_dict()) >= {"loss_pi", "loss_v", "entropy", "kl", "clipfrac",
                                    "explained_var", "lr", "ent_coef"}


def test_KL이_크면_남은_에폭을_중단한다() -> None:
    """`plan.md` §8.1 — 조기 종료가 없으면 한 반복 안에서 정책이 너무 멀리 간다."""
    stopped = updater(PPOConfig(epochs=4, minibatches=4, target_kl=1e-12)).update(fake_batch())
    assert stopped.epochs_run == 1 and stopped.early_stopped

    ran = updater(PPOConfig(epochs=4, minibatches=4, target_kl=1e9)).update(fake_batch())
    assert ran.epochs_run == 4 and not ran.early_stopped


def test_lr을_주면_옵티마이저에_적용되고_기록된다() -> None:
    up = updater(lr=3e-4)
    stats = up.update(fake_batch(), lr=1e-5, entropy_coef=0.002)
    assert up.optimizer.param_groups[0]["lr"] == pytest.approx(1e-5)
    assert stats.lr == pytest.approx(1e-5) and stats.entropy_coef == pytest.approx(0.002)


def test_그래디언트_노름이_상한에서_잘린다() -> None:
    cfg = PPOConfig(epochs=1, minibatches=1, max_grad_norm=1e-8, normalize_advantage=False)
    up = updater(cfg)
    before = up.net.actor[0].weight.clone()
    up.update(fake_batch())
    # 상한이 1e-8 이면 한 스텝의 변화가 LR 규모를 넘을 수 없다.
    assert (up.net.actor[0].weight - before).abs().max().item() < 1e-3


def test_같은_시드는_같은_업데이트를_낸다() -> None:
    """미니배치 셔플까지 전역 RNG 가 정한다 (FR-10, M3-d 의 선행 조건)."""
    def run() -> tuple[float, float, float]:
        up = updater(seed=5)
        torch.manual_seed(11)  # 셔플 순서를 정하는 것도 이 시드다
        s = up.update(fake_batch(seed=2))
        return s.loss_pi, s.loss_v, s.kl

    assert run() == run()


def test_advantage_정규화가_미니배치_단위다() -> None:
    """배치 전체로 정규화하면 미니배치마다의 스케일 차이가 남는다 (plan.md §8.1)."""
    batch = fake_batch()
    on = updater(PPOConfig(epochs=1, minibatches=4, normalize_advantage=True))
    off = updater(PPOConfig(epochs=1, minibatches=4, normalize_advantage=False))
    torch.manual_seed(3)
    a = on.update(batch)
    torch.manual_seed(3)
    b = off.update(batch)
    assert a.loss_pi != pytest.approx(b.loss_pi)


def test_정책이_이득이_큰_행동_쪽으로_움직인다() -> None:
    """부호 배선의 종단 확인. 한 행동에만 A > 0 을 주면 그 행동의 확률이 올라가야 한다.

    이 테스트 하나가 "엔트로피 부호", "정책 손실 부호", "옵티마이저 방향" 셋을 한꺼번에
    잡는다. 반대로 배선되어 있어도 손실은 내려간다 — 그래서 **확률을 직접 본다.**
    """
    torch.manual_seed(0)
    net = ActorCritic(OBS_DIM, ACTIONS, hidden=32)
    opt = torch.optim.Adam(net.parameters(), lr=1e-2)
    up = PPOUpdater(net, opt, PPOConfig(epochs=1, minibatches=1, target_kl=0.0,
                                        entropy_coef=0.0, normalize_advantage=False))

    favored = 7
    obs = torch.zeros(4, 16, OBS_DIM)
    actions = torch.randint(0, ACTIONS, (4, 16))
    advantages = torch.where(actions == favored, 1.0, -1.0)
    batch = fake_batch(4, 16)
    batch.obs, batch.actions, batch.advantages = obs, actions, advantages
    batch.valid = torch.ones(4, 16)
    # 초기 정책은 균등이므로 어느 행동이든 logp = −ln(18) 이다 (test_net.py 가 못 박는다).
    batch.log_probs = torch.full((4, 16), -math.log(ACTIONS))

    def probability() -> float:
        with torch.no_grad():
            return float(net.log_probs(obs[0, :1]).exp()[0, favored])

    before = probability()
    for _ in range(20):
        up.update(batch)
    after = probability()
    assert after > before * 1.5, f"{before:.4f} → {after:.4f}"


# ═══════════════════════════════════════════════════════════════════════════
# NFR-4 — 이 파일은 상대를 모른다
# ═══════════════════════════════════════════════════════════════════════════


def test_ppo는_환경도_상대도_모른다() -> None:
    """`ppo.py` 를 import 해도 grpc·env_client·evaluate 가 딸려오지 않는다.

    Phase 7 의 Track B 가 같은 파일을 재사용할 수 있는 조건이고, Phase 8 의 동일 예산
    비교가 성립하는 조건이다. 주석으로 적어 두면 지켜지지 않으므로 프로세스를 띄워 확인한다.
    """
    r = subprocess.run(
        [sys.executable, "-c",
         "import pika_trainer.ppo, sys;"
         "print(sorted(m for m in ('grpc', 'gymnasium', 'pika_trainer.env_client',"
         "'pika_trainer.evaluate') if m in sys.modules))"],
        capture_output=True, text=True, check=True,
    )
    assert r.stdout.strip() == "[]"


def test_ppo_소스에_진영도_FSM도_없다() -> None:
    """이름 수준의 확인. `if side == 0:` 한 줄이 들어오는 순간 두 트랙이 갈라진다.

    ⚠️ 원문 문자열 검색으로 쓰면 안 된다 — `consider` 안에 `side` 가 들어 있고, 주석과
       문서화 문자열은 이 규칙의 대상이 아니다 (이 모듈의 docstring 은 "FSM 을 모른다" 고
       **말해야** 한다). 그래서 AST 에서 **식별자만** 걷는다.
    """
    from pathlib import Path

    import pika_trainer.ppo as module

    tree = ast.parse(Path(module.__file__).read_text(encoding="utf-8"))
    names: set[str] = set()
    for node in ast.walk(tree):
        for attr in ("id", "attr", "name", "arg"):
            value = getattr(node, attr, None)
            if isinstance(value, str):
                names.add(value.lower())
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            names.update(a.name.lower() for a in node.names)
            names.add((getattr(node, "module", None) or "").lower())

    # `evaluate_actions` 는 망의 메서드다 — 금지어가 아니다. `pika_trainer.evaluate` 를
    # 실제로 끌고 오는지는 위 테스트가 프로세스를 띄워 본다.
    for banned in ("fsm", "side", "boldness", "rally", "env_client", "pikavectorenv"):
        hits = {n for n in names if banned in n}
        assert not hits, f"{banned}: {hits}"
