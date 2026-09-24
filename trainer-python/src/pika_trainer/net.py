"""정책·가치망과 행동 샘플링. (FR-1 / plan.md §8.1)

─────────────────────────────────────────────────────────────────────────────
이 파일은 환경을 모른다
─────────────────────────────────────────────────────────────────────────────
`obs_dim` 과 `action_count` 는 **인자**다. `env_client` 를 import 하지 않는다 (NFR-4).
Track B(Phase 6)가 같은 망을 그대로 쓰기 위한 조건이고, 테스트가 서버 없이 도는 이유다.

─────────────────────────────────────────────────────────────────────────────
왜 actor/critic 을 분리하는가
─────────────────────────────────────────────────────────────────────────────
몸통을 공유하면 파라미터가 절반이지만, 가치 손실의 그래디언트가 정책 표현을 흔든다.
이 문제는 망이 작을 때(48k 파라미터, plan.md §3) 아낄 것이 없으므로 분리가 공짜다.

─────────────────────────────────────────────────────────────────────────────
초기화 (plan.md §8.1)
─────────────────────────────────────────────────────────────────────────────
직교 초기화, hidden gain √2 · **정책 헤드 0.01** · 가치 헤드 1.0.

정책 헤드만 0.01 인 것이 핵심이다 — 로짓을 0 근처로 눌러 초기 정책을 균등분포에
붙인다. 초기 정책이 한 행동에 쏠리면 첫 반복의 KL 이 커지고 조기 종료(KL > 0.02)가
매번 걸린다. 가치 헤드는 확률이 아니라 스케일이 있는 양이므로 누르지 않는다.
"""

from __future__ import annotations

import math

import torch
from torch import Tensor, nn


def layer_init(layer: nn.Linear, gain: float) -> nn.Linear:
    """직교 초기화 + 편향 0."""
    nn.init.orthogonal_(layer.weight, gain)
    nn.init.zeros_(layer.bias)
    return layer


def _mlp(obs_dim: int, hidden: int, out_dim: int, out_gain: float) -> nn.Sequential:
    return nn.Sequential(
        layer_init(nn.Linear(obs_dim, hidden), math.sqrt(2)),
        nn.Tanh(),
        layer_init(nn.Linear(hidden, hidden), math.sqrt(2)),
        nn.Tanh(),
        layer_init(nn.Linear(hidden, out_dim), out_gain),
    )


class ActorCritic(nn.Module):
    """`obs_dim → hidden → hidden → (action_count / 1)` 두 갈래.

    관측 정규화를 하지 않는다 — `ObsEncoder` 가 이미 ``[-1, 1]`` 척도로 낸다
    (plan.md §8.1). 러닝 평균 정규화를 얹으면 체크포인트에 상태가 하나 더 붙고
    평가 재현성(M3-c)의 표면이 넓어진다.
    """

    def __init__(self, obs_dim: int, action_count: int, hidden: int = 128) -> None:
        super().__init__()
        self.obs_dim = obs_dim
        self.action_count = action_count
        self.hidden = hidden
        self.actor = _mlp(obs_dim, hidden, action_count, out_gain=0.01)
        self.critic = _mlp(obs_dim, hidden, 1, out_gain=1.0)

    # ── 기본 연산 ────────────────────────────────────────────────────────────

    def value(self, obs: Tensor) -> Tensor:
        """``(B,)`` 가치. 마지막 축을 짜서 돌려준다."""
        return self.critic(obs).squeeze(-1)

    def log_probs(self, obs: Tensor) -> Tensor:
        """``(B, action_count)`` 로그 확률.

        `torch.distributions.Categorical` 대신 `log_softmax` 를 직접 쓴다. 이 망은
        파라미터가 48k 뿐이라(plan.md §3) 분포 객체 생성 비용이 matmul 에 비해
        무시할 수 없고, 분포는 여기서 샘플링과 엔트로피에만 쓰인다.
        """
        return torch.log_softmax(self.actor(obs), dim=-1)

    # ── 롤아웃 ───────────────────────────────────────────────────────────────

    @torch.no_grad()
    def act(self, obs: Tensor) -> tuple[Tensor, Tensor, Tensor]:
        """샘플링. ``(action, logp, value)`` — 롤아웃이 버퍼에 담는 세 가지다.

        `torch.multinomial` 은 torch 전역 RNG 를 쓴다. 시드 하나가 학습 전체를
        정하는 것(plan.md §11)이 여기에 달려 있다.
        """
        logp_all = self.log_probs(obs)
        action = torch.multinomial(logp_all.exp(), num_samples=1).squeeze(-1)
        logp = logp_all.gather(-1, action.unsqueeze(-1)).squeeze(-1)
        return action, logp, self.value(obs)

    @torch.no_grad()
    def act_greedy(self, obs: Tensor) -> Tensor:
        """argmax. 평가의 주 지표가 쓰는 결정론적 모드다 (plan.md §9.4)."""
        return self.actor(obs).argmax(dim=-1)

    # ── 학습 ─────────────────────────────────────────────────────────────────

    def evaluate_actions(self, obs: Tensor, actions: Tensor) -> tuple[Tensor, Tensor, Tensor]:
        """이미 취한 행동을 **현재** 정책으로 다시 채점한다.

        Returns:
            ``(logp, entropy, value)`` 전부 ``(B,)``. PPO 의 ratio·엔트로피 보너스·
            가치 손실이 이 셋만 쓴다.
        """
        logp_all = self.log_probs(obs)
        logp = logp_all.gather(-1, actions.long().unsqueeze(-1)).squeeze(-1)
        entropy = -(logp_all.exp() * logp_all).sum(dim=-1)
        return logp, entropy, self.value(obs)


def configure_torch(threads: int) -> None:
    """스레드 수를 고정한다. **학습과 평가가 반드시 같은 값을 써야 한다.**

    스레드 수가 바뀌면 리덕션 순서가 바뀌고 부동소수 합이 달라진다 — 재현성(M3-d)이
    스레드 수의 함수다 (plan.md §11). 그래서 이 값은 체크포인트에 적힌다.
    """
    torch.set_num_threads(threads)
