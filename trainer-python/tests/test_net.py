"""정책·가치망의 계약. (tasks.md P1 / plan.md §8.1)

이 파일이 지키는 것은 성능이 아니라 **초기 조건**이다. PPO 는 초기 정책이 균등에서
멀면 첫 반복의 KL 이 커지고, 조기 종료(KL > 0.02)가 매번 걸려 학습이 기어간다.
그 실패는 "학습이 느리다" 로만 보이고 원인을 가리키지 않는다 — 그래서 여기서 못 박는다.

⚠️ 서버를 띄우지 않는다. `net.py` 는 환경을 모르고(NFR-4), 그래서 이 테스트는 빠르다.
"""

from __future__ import annotations

import math
import subprocess
import sys

import pytest
import torch

from pika_trainer.net import ActorCritic

OBS_DIM = 41  # 관측 40 + 진영 플래그 (FR-14)
ACTIONS = 18


@pytest.fixture
def net() -> ActorCritic:
    torch.manual_seed(0)
    return ActorCritic(OBS_DIM, ACTIONS, hidden=128)


def test_초기_정책은_균등하다(net: ActorCritic) -> None:
    """정책 헤드 gain 0.01 의 계약. 엔트로피가 `ln(18)` 이어야 한다."""
    obs = torch.randn(256, OBS_DIM)
    _, entropy, _ = net.evaluate_actions(obs, torch.zeros(256, dtype=torch.long))
    assert entropy.mean().item() == pytest.approx(math.log(ACTIONS), abs=1e-3)
    # 관측이 달라도 초기 정책은 거의 같다 — 로짓이 0 근처로 눌려 있기 때문이다.
    assert entropy.std().item() < 1e-3


def test_가치_헤드는_눌려_있지_않다(net: ActorCritic) -> None:
    """가치는 확률이 아니라 스케일이 있는 양이다. gain 1.0 인 이유다 (plan.md §8.1).

    여기까지 0.01 로 누르면 초기 `explained_var` 가 0 에 붙고 회복이 느리다.
    """
    v = net.value(torch.randn(256, OBS_DIM))
    assert v.std().item() > 0.1


def test_직교_초기화가_실제로_직교다(net: ActorCritic) -> None:
    """hidden gain √2 → `WᵀW = 2I`."""
    w = net.actor[2].weight  # (128, 128)
    gram = w.T @ w
    assert torch.allclose(gram, 2.0 * torch.eye(w.shape[1]), atol=1e-4)
    assert torch.equal(net.actor[2].bias, torch.zeros_like(net.actor[2].bias))


def test_log_probs가_정규화되어_있다(net: ActorCritic) -> None:
    logp = net.log_probs(torch.randn(64, OBS_DIM))
    assert logp.shape == (64, ACTIONS)
    assert torch.allclose(logp.exp().sum(-1), torch.ones(64), atol=1e-6)


def test_act와_evaluate_actions가_같은_logp를_준다(net: ActorCritic) -> None:
    """롤아웃이 담은 `old_logp` 와 업데이트 첫 에폭의 `logp` 가 같아야 한다.

    이것이 어긋나면 첫 미니배치의 ratio 가 1 이 아니게 되고, PPO 는 **정책이 이미
    변한 것처럼** 클리핑을 걸기 시작한다. 조용히 틀리는 종류다.
    """
    obs = torch.randn(64, OBS_DIM)
    action, logp, value = net.act(obs)
    logp2, _, value2 = net.evaluate_actions(obs, action)
    assert torch.allclose(logp, logp2, atol=1e-6)
    assert torch.allclose(value, value2, atol=1e-6)


def test_greedy는_결정론적이다(net: ActorCritic) -> None:
    """평가의 주 지표(M3-a)가 여기 기댄다 (plan.md §9.4)."""
    obs = torch.randn(64, OBS_DIM)
    a1, a2 = net.act_greedy(obs), net.act_greedy(obs)
    assert torch.equal(a1, a2)
    assert torch.equal(a1, net.log_probs(obs).argmax(-1))


def test_같은_시드는_같은_망과_같은_샘플을_준다() -> None:
    """시드 하나가 학습 전체를 정한다 (FR-10, plan.md §11)."""
    def run() -> tuple[torch.Tensor, torch.Tensor]:
        torch.manual_seed(7)
        n = ActorCritic(OBS_DIM, ACTIONS, hidden=64)
        obs = torch.randn(32, OBS_DIM)
        action, _, _ = n.act(obs)
        return n.actor[0].weight.clone(), action

    w1, a1 = run()
    w2, a2 = run()
    assert torch.equal(w1, w2)
    assert torch.equal(a1, a2)


@pytest.mark.parametrize(("obs_dim", "actions", "hidden"), [(40, 18, 64), (41, 18, 128), (41, 6, 256)])
def test_차원은_전부_인자다(obs_dim: int, actions: int, hidden: int) -> None:
    """관측 40/41, 행동 수, H 가 전부 바깥에서 정해진다 — 환경을 모르기 위한 조건이다."""
    n = ActorCritic(obs_dim, actions, hidden)
    obs = torch.randn(5, obs_dim)
    assert n.log_probs(obs).shape == (5, actions)
    assert n.value(obs).shape == (5,)
    assert n.act(obs)[0].shape == (5,)


def test_net은_환경을_모른다() -> None:
    """NFR-4 의 선행. `net.py` 를 import 해도 grpc·env_client 가 딸려오지 않는다.

    P5 가 `ppo.py` 에 같은 것을 요구한다. 망이 먼저 깨끗해야 그것이 가능하다.
    """
    r = subprocess.run(
        [sys.executable, "-c",
         "import pika_trainer.net, sys;"
         "print('grpc' in sys.modules, 'pika_trainer.env_client' in sys.modules)"],
        capture_output=True, text=True, check=True,
    )
    assert r.stdout.strip() == "False False"
