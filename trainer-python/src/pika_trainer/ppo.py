"""PPO 손실과 업데이트. (FR-1, NFR-4 / plan.md §8)

─────────────────────────────────────────────────────────────────────────────
이 파일은 상대가 누군지 모른다
─────────────────────────────────────────────────────────────────────────────
입력은 롤아웃 배치와 하이퍼파라미터뿐이다. `env_client` · FSM · 진영 · 보상 항 —
어느 것도 import 하지 않는다 (NFR-4, `test_ppo.py` 가 프로세스를 띄워 확인한다).

그 제약이 요구사항인 이유: **Phase 6 의 Track B(셀프플레이)가 이 파일을 그대로 재사용한다.**
여기에 "FSM 상대일 때는…" 이 한 줄이라도 들어가면 두 트랙이 다른 알고리즘을 쓰게 되고,
Phase 7 의 동일 예산 비교가 무효가 된다.

─────────────────────────────────────────────────────────────────────────────
마스크는 롤아웃이 준 것을 그대로 쓴다
─────────────────────────────────────────────────────────────────────────────
버려지는 스텝(next-step autoreset 의 리셋 프레임)은 `batch.valid == 0` 이다. 정책·가치·
엔트로피 **셋 다** 그 마스크 안에서만 평균을 잡고, advantage 정규화의 평균·분산도
마스크 안에서만 잡는다 — 손실에서 빼는 것만으로는 부족하다 (plan.md §8.2 함정 4).

─────────────────────────────────────────────────────────────────────────────
가치 손실을 클리핑하지 않는다
─────────────────────────────────────────────────────────────────────────────
구현체에 따라 가치 예측도 `clip_eps` 로 자르지만 (`v_clipped`), `plan.md` §8.1 의
하이퍼파라미터 표에 없다. 효과가 논쟁적인 손잡이를 근거 없이 늘리면 P6 의 A/B 가
설명해야 할 축만 늘어난다. 필요해지면 **측정한 뒤에** 넣는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import torch
from torch import Tensor, nn

from .rollout import masked_mean, masked_normalize, masked_var

#: 이 값을 넘으면 그 반복의 남은 에폭을 중단한다 (plan.md §8.1).
DEFAULT_TARGET_KL = 0.02


@dataclass(frozen=True)
class PPOConfig:
    """`plan.md` §8.1 의 출발점. 값을 바꾸면 **바꾼 이유를 `plan.md` 에 적는다** (P6)."""

    clip_eps: float = 0.2
    value_coef: float = 0.5
    entropy_coef: float = 0.02
    max_grad_norm: float = 0.5
    epochs: int = 4
    minibatches: int = 8
    target_kl: float = DEFAULT_TARGET_KL
    normalize_advantage: bool = True


# ═══════════════════════════════════════════════════════════════════════════
# 진단값
# ═══════════════════════════════════════════════════════════════════════════


def explained_variance(values: Tensor, returns: Tensor, mask: Tensor) -> float:
    """``1 − Var(returns − values) / Var(returns)``.

    가치 함수가 **쓸모 있는가**를 보는 하나뿐인 지표다. 0 이면 평균을 찍는 것과 같고,
    음수면 평균보다 나쁘다. 가치 손실 자체는 보상 스케일에 묶여 있어서 셰이핑 어닐링
    중에는 비교가 불가능하다 — 그래서 이 무차원 지표가 필요하다.
    """
    var = masked_var(returns, mask)
    if var.item() == 0.0:
        return 0.0
    return float(1.0 - masked_var(returns - values, mask) / var)


def approx_kl(log_ratio: Tensor, ratio: Tensor, mask: Tensor) -> Tensor:
    """``KL(old ‖ new)`` 의 저분산 추정 (Schulman 의 k3).

    ``−mean(log_ratio)`` 가 아니다. 그것은 불편추정이지만 **음수가 될 수 있고** 분산이 크다.
    조기 종료(KL > 0.02)의 문턱이 그 잡음 위에서 흔들리면 어떤 반복은 4에폭을, 어떤
    반복은 1에폭을 돌게 되고, 그 차이는 하이퍼파라미터가 아니라 운이다.

    ``(r − 1) − log r`` 은 항상 ≥ 0 이고 ``r = 1`` 에서 정확히 0 이다.
    """
    return masked_mean((ratio - 1.0) - log_ratio, mask)


@dataclass
class PPOStats:
    """한 반복의 업데이트 요약. `plan.md` §11 의 JSONL 에 그대로 들어간다."""

    loss_pi: float
    loss_v: float
    entropy: float
    kl: float
    clipfrac: float
    explained_var: float
    grad_norm: float
    epochs_run: int
    minibatches_run: int
    lr: float
    entropy_coef: float
    #: 조기 종료가 걸렸는가. 매 반복 걸린다면 LR 이나 에폭 수가 큰 것이다.
    early_stopped: bool

    def to_dict(self) -> dict[str, Any]:
        return {
            "loss_pi": self.loss_pi,
            "loss_v": self.loss_v,
            "entropy": self.entropy,
            "kl": self.kl,
            "clipfrac": self.clipfrac,
            "explained_var": self.explained_var,
            "grad_norm": self.grad_norm,
            "epochs_run": self.epochs_run,
            "minibatches_run": self.minibatches_run,
            "lr": self.lr,
            "ent_coef": self.entropy_coef,
            "early_stopped": self.early_stopped,
        }


# ═══════════════════════════════════════════════════════════════════════════
# 손실
# ═══════════════════════════════════════════════════════════════════════════


@dataclass
class LossParts:
    """합치기 **전**의 항들. 테스트가 부호와 크기를 따로 못 박을 수 있게 나눠 둔다."""

    policy: Tensor
    value: Tensor
    entropy: Tensor
    kl: Tensor
    clipfrac: Tensor

    def total(self, *, value_coef: float, entropy_coef: float) -> Tensor:
        """엔트로피는 **빼는** 것이 보너스다 — 손실을 줄이려면 엔트로피를 키워야 한다."""
        return self.policy + value_coef * self.value - entropy_coef * self.entropy

    def detached(self) -> LossParts:
        """기록용 사본. 그래프를 붙든 텐서를 반복 밖으로 들고 나가면 그만큼이 살아남는다."""
        return LossParts(*(t.detach() for t in
                           (self.policy, self.value, self.entropy, self.kl, self.clipfrac)))


def compute_losses(
    log_probs: Tensor,
    old_log_probs: Tensor,
    entropy: Tensor,
    values: Tensor,
    advantages: Tensor,
    returns: Tensor,
    valid: Tensor,
    *,
    clip_eps: float,
) -> LossParts:
    """PPO 의 세 항. 전부 `valid` 마스크 안에서만 평균을 잡는다.

    Args:
        advantages: **이미 정규화된** advantage. 정규화는 미니배치 단위이고
            :class:`PPOUpdater` 가 한다 (마스크 안에서 — plan.md §8.2).
        valid: 이 전이를 학습에 쓰는가. 버려지는 스텝만 0 이다 (FR-2).

    clipped surrogate 의 모양::

        L = −E[ min( r·A , clip(r, 1−ε, 1+ε)·A ) ]

    `min` 이 하는 일은 **정책이 좋아지는 방향으로만 제한을 건다**는 것이다. A > 0 인데
    r 이 커지면 이득이 `(1+ε)A` 에서 멈추고, A < 0 인데 r 이 작아지면 이득이
    `(1−ε)A` 에서 멈춘다. 반대 방향(정책이 나빠지는 쪽)은 자르지 않는다 — 그래야
    한 번 크게 틀린 스텝에서 되돌아올 수 있다.
    """
    mask = valid.bool()
    log_ratio = log_probs - old_log_probs
    ratio = log_ratio.exp()

    surrogate = ratio * advantages
    clipped = torch.clamp(ratio, 1.0 - clip_eps, 1.0 + clip_eps) * advantages
    policy_loss = -masked_mean(torch.min(surrogate, clipped), mask)

    # 가치 손실은 순수 MSE 다. 흔히 보이는 `0.5 × MSE × value_coef` 는 0.5 를 두 번 곱하는
    # 관습이고, 그러면 `value_coef` 가 표에 적힌 값의 절반을 뜻하게 된다 (plan.md §8.1).
    value_loss = masked_mean((values - returns) ** 2, mask)

    return LossParts(
        policy=policy_loss,
        value=value_loss,
        entropy=masked_mean(entropy, mask),
        kl=approx_kl(log_ratio, ratio, mask),
        clipfrac=masked_mean(((ratio - 1.0).abs() > clip_eps).to(ratio.dtype), mask),
    )


# ═══════════════════════════════════════════════════════════════════════════
# 업데이트
# ═══════════════════════════════════════════════════════════════════════════


class PPOUpdater:
    """배치 하나로 `epochs × minibatches` 번의 그래디언트 스텝을 밟는다.

    **미니배치 셔플은 전역 torch RNG 를 쓴다.** 학습 시드 하나가 망 초기화·행동 샘플링·
    셔플 순서를 전부 정한다는 뜻이고 (FR-10, plan.md §11), 그래서 평가는 전역 RNG 를
    건드리지 않는다 (`evaluate.py` 의 전용 `Generator`).
    """

    def __init__(
        self,
        net: nn.Module,
        optimizer: torch.optim.Optimizer,
        config: PPOConfig | None = None,
    ) -> None:
        self.net = net
        self.optimizer = optimizer
        self.config = config or PPOConfig()

    def update(
        self,
        batch: Any,
        *,
        lr: float | None = None,
        entropy_coef: float | None = None,
    ) -> PPOStats:
        """한 반복.

        Args:
            batch: `RolloutBatch` — `flat()` 만 쓴다. 타입으로 묶지 않는 이유는
                Track B 가 다른 수집기를 끼울 수 있게 하기 위해서다.
            lr: 주면 옵티마이저의 학습률을 덮어쓴다. 스케줄은 러너의 것이고 (`schedules.py`),
                여기서는 **적용과 기록**만 한다.
        """
        cfg = self.config
        ent_coef = cfg.entropy_coef if entropy_coef is None else entropy_coef
        if lr is not None:
            for group in self.optimizer.param_groups:
                group["lr"] = lr
        current_lr = float(self.optimizer.param_groups[0]["lr"])

        flat = batch.flat()
        obs, actions = flat["obs"], flat["actions"]
        old_log_probs, old_values = flat["log_probs"], flat["values"]
        advantages, returns, valid = flat["advantages"], flat["returns"], flat["valid"]
        mask = valid.bool()

        total = obs.shape[0]
        size = total // cfg.minibatches
        if size == 0:
            raise ValueError(f"배치 {total} 을 미니배치 {cfg.minibatches} 개로 나눌 수 없습니다.")

        # 업데이트 **전**의 가치로 잰다 — "이 반복의 롤아웃을 예측했는가" 가 질문이다.
        ev = explained_variance(old_values, returns, mask)

        last: LossParts | None = None
        grad_norm = 0.0
        epochs_run = minibatches_run = 0
        early_stopped = False

        for _ in range(cfg.epochs):
            order = torch.randperm(total)
            epoch_kl = 0.0
            for start in range(0, size * cfg.minibatches, size):
                idx = order[start : start + size]
                mb_valid = valid[idx]
                mb_adv = advantages[idx]
                if cfg.normalize_advantage:
                    # ⚠️ 마스크 안에서만. 버려지는 스텝의 쓰레기가 평균·분산에 들어가면
                    #    유효한 스텝의 값이 전부 오염된다 (plan.md §8.2 함정 4).
                    mb_adv = masked_normalize(mb_adv, mb_valid.bool())

                logp, entropy, value = self.net.evaluate_actions(obs[idx], actions[idx])
                parts = compute_losses(
                    logp, old_log_probs[idx], entropy, value,
                    mb_adv, returns[idx], mb_valid,
                    clip_eps=cfg.clip_eps,
                )
                loss = parts.total(value_coef=cfg.value_coef, entropy_coef=ent_coef)

                self.optimizer.zero_grad(set_to_none=True)
                loss.backward()
                grad_norm = float(
                    nn.utils.clip_grad_norm_(self.net.parameters(), cfg.max_grad_norm),
                )
                self.optimizer.step()

                last = parts.detached()
                epoch_kl += last.kl.item()
                minibatches_run += 1

            epochs_run += 1
            # 조기 종료는 **에폭 경계**에서 판정한다. 미니배치마다 끊으면 그 반복이 본
            # 데이터가 셔플 순서의 함수가 되어, 같은 시드에서만 재현되는 결과가 된다.
            if cfg.target_kl > 0 and epoch_kl / cfg.minibatches > cfg.target_kl:
                early_stopped = True
                break

        assert last is not None
        return PPOStats(
            loss_pi=last.policy.item(),
            loss_v=last.value.item(),
            entropy=last.entropy.item(),
            kl=last.kl.item(),
            clipfrac=last.clipfrac.item(),
            explained_var=ev,
            grad_norm=grad_norm,
            epochs_run=epochs_run,
            minibatches_run=minibatches_run,
            lr=current_lr,
            entropy_coef=ent_coef,
            early_stopped=early_stopped,
        )
