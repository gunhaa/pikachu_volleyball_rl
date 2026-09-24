"""Track A 러너의 배선. (FR-8, FR-9, FR-10, M3-d / tasks.md P6 / plan.md §11)

─────────────────────────────────────────────────────────────────────────────
여기서 무엇을 막는가
─────────────────────────────────────────────────────────────────────────────
손실 계산은 `test_ppo.py` 가, 마스킹과 GAE 는 `test_rollout.py` 가 이미 못 박았다.
러너에서 새로 생기는 위험은 알고리즘이 아니라 **전역 상태**다:

1. 시드 하나가 torch·numpy·서버를 전부 정하는가 (FR-10, M3-d)
2. **주기 평가가 학습을 바꾸지 않는가** — 평가가 전역 RNG 를 한 번이라도 소비하면
   학습 결과가 "평가를 몇 번 했는가" 의 함수가 된다. 그러면 M3-d 는 우연히 통과한다
3. 평가가 학습 서버에 `Configure` 를 불러 세션을 죽이지 않는가 (plan.md §12 함정 5)
4. 체크포인트 규약이 `evaluate.load_net` 과 갈라지지 않는가 (FR-8)
"""

from __future__ import annotations

from collections.abc import Iterator

import numpy as np
import pytest
import torch

from pika_trainer.evaluate import load_net
from pika_trainer.metrics import RunDir, read_jsonl
from pika_trainer.schedules import SHAPING_HOLD, SHAPING_OFF
from pika_trainer.server_process import launch_server
from pika_trainer.track_a import (
    TrackAConfig,
    TrackARunner,
    build_config,
    build_parser,
    default_run_id,
    load_checkpoint,
)

#: 작은 학습. 8 env × 8 스텝 = 반복당 64 step, 3 반복.
ITERATIONS = 3


def small(**overrides: object) -> TrackAConfig:
    base = dict(
        num_envs=8, horizon=8, total_steps=64 * ITERATIONS,
        threads=1, hidden=16, epochs=2, minibatches=2,
        eval_every=0, ckpt_every=0, log_every=10_000,
    )
    base.update(overrides)
    return TrackAConfig(**base)  # type: ignore[arg-type]


@pytest.fixture(scope="module")
def eval_target() -> Iterator[str]:
    """**두 번째** 서버. 서버는 단일 테넌트라 평가가 학습 서버에 붙을 수 없다 (plan.md §9.3)."""
    try:
        with launch_server() as target:
            yield target
    except RuntimeError as e:
        pytest.skip(str(e))


# ═══════════════════════════════════════════════════════════════════════════
# 구성 — 서버를 띄우지 않는다
# ═══════════════════════════════════════════════════════════════════════════


def test_default_config_matches_plan_table():
    """`plan.md` §8.1 의 출발점. 바꿀 때는 **이유를 plan.md 에 적는다.**"""
    c = TrackAConfig()
    assert (c.num_envs, c.horizon, c.batch_size) == (512, 128, 65_536)
    assert (c.gamma, c.lam, c.clip_eps) == (0.997, 0.95, 0.2)
    assert (c.epochs, c.minibatches, c.target_kl) == (4, 8, 0.02)
    assert (c.lr_start, c.ent_start, c.ent_end) == (3e-4, 0.02, 0.002)
    assert c.threads == 4 and c.hidden == 128


def test_iterations_round_up():
    """마지막 롤아웃이 목표를 넘긴다. 내림으로 세면 목표에 못 미친다."""
    assert small(total_steps=64 * 3).iterations == 3
    assert small(total_steps=64 * 3 + 1).iterations == 4


def test_env_options_carry_side_split_and_flag():
    """진영 절반(FR-3)과 진영 플래그(FR-14). `for_policy` 를 거치는 것이 요구사항이다."""
    options = TrackAConfig(num_envs=512, seed=7).env_options()
    assert options.swapped_envs == 256
    assert options.obs_include_side_flag is True
    assert options.base_seed == 7
    assert (options.p1, options.p2) == ("external", "fsm")


def test_shaping_schedule_shape():
    """0~20% 유지 → 50% 에서 0 (plan.md §6.2). `rally_win` 은 어닐링하지 않는다."""
    names = ["rally_win", "ball_touch", "crossed_net", "opponent_miss", "time_penalty"]
    w = TrackAConfig().weighting(names)
    assert w.as_dict(0.0)["crossed_net"] == pytest.approx(0.10)
    assert w.as_dict(SHAPING_HOLD)["crossed_net"] == pytest.approx(0.10)
    assert w.as_dict(SHAPING_OFF)["crossed_net"] == pytest.approx(0.0)
    assert w.as_dict(0.9)["ball_touch"] == pytest.approx(0.0)
    assert w.as_dict(0.9)["rally_win"] == pytest.approx(1.0)
    # 중복이거나 불필요한 항은 처음부터 0 이다.
    assert w.as_dict(0.0)["opponent_miss"] == 0.0
    assert w.as_dict(0.0)["time_penalty"] == 0.0


def test_crossed_net_outweighs_ball_touch():
    """반대로 두면 자기 진영 저글링이 최적 전략이 된다 (M3-h, plan.md §6.2)."""
    c = TrackAConfig()
    assert c.shaping_crossed_net > c.shaping_ball_touch > 0


def test_run_id_carries_gamma_and_seed():
    """γ A/B 를 돌리면 런이 여러 개다. 디렉터리 이름만 보고 구분되어야 한다."""
    run_id = default_run_id(TrackAConfig(gamma=0.999, seed=3))
    assert "g0.999" in run_id and "s3" in run_id


def test_cli_defaults_match_dataclass():
    """CLI 기본값이 dataclass 와 갈라지면 "스크립트로 돌린 것" 과 "테스트한 것" 이 달라진다.

    특히 γ 와 셰이핑은 A/B 축이다. 두 곳의 기본값이 다르면 "기본 구성" 이라고 적은
    런이 실제로는 다른 설정이 되고, 그 사고는 결과표를 다시 만들 때까지 드러나지 않는다.
    """
    args = build_parser().parse_args([])
    assert build_config(args) == TrackAConfig(
        seed=args.seed, total_steps=args.total_steps, num_envs=args.num_envs,
        horizon=args.horizon, threads=args.threads, hidden=args.hidden,
        gamma=args.gamma, lam=args.lam, clip_eps=args.clip_eps,
        value_coef=args.value_coef, epochs=args.epochs, minibatches=args.minibatches,
        target_kl=args.target_kl, lr_start=args.lr, ent_start=args.ent_start,
        ent_end=args.ent_end, shaping_crossed_net=args.shaping_crossed_net,
        shaping_ball_touch=args.shaping_ball_touch, eval_every=args.eval_every,
        eval_games=args.eval_games, ckpt_every=args.ckpt_every, log_every=args.log_every,
    )
    # 기본 구성이 곧 `plan.md` §8.1 의 표다.
    assert build_config(args).gamma == 0.997


# ═══════════════════════════════════════════════════════════════════════════
# 학습 — 실제 서버
# ═══════════════════════════════════════════════════════════════════════════


def test_metrics_schema(env_target, tmp_path):
    """`plan.md` §11 이 고정한 키. **Phase 7 이 두 트랙을 이 키로 겹쳐 그린다.**"""
    run = RunDir.create("schema", root=tmp_path)
    with TrackARunner(env_target, small(seed=1), run=run, verbose=False) as runner:
        runner.train()

    rows = read_jsonl(run.metrics_path)
    assert len(rows) == ITERATIONS
    row = rows[-1]

    required = [
        "iter", "env_steps", "wall_s", "lr", "ent_coef",
        "loss_pi", "loss_v", "entropy", "kl", "clipfrac", "explained_var",
        "rally_win_rate", "rally_frames_mean", "trunc_rate", "valid_share",
        "shaping_w.rally_win", "shaping_w.crossed_net", "shaping_w.ball_touch",
    ]
    assert not [k for k in required if k not in row], [k for k in required if k not in row]

    # 진영별로 따로 (plan.md §11 — 한쪽의 실패가 평균에 묻히면 M3-b 가 마지막에 터진다).
    for side in ("left", "right"):
        assert f"{side}.rally_win_rate" in row
        assert f"{side}.trunc_rate" in row
        assert f"{side}.term_share.rally_win" in row
        assert len(row[f"{side}.action_hist"]) == 18

    assert row["iter"] == ITERATIONS
    assert row["env_steps"] == 64 * ITERATIONS
    assert row["steps_per_s"] > 0

    # 구성도 함께 남는다 — 런을 재현하는 데 필요한 전부다.
    import json
    config = json.loads(run.config_path.read_text(encoding="utf-8"))
    assert config["obs_dim"] == 41          # 진영 플래그 on (FR-14)
    assert config["config"]["seed"] == 1
    assert "git_commit" in config


def test_observation_is_41_dim(env_target):
    """진영 플래그가 켜져 있다 (FR-14). Track B 의 전제이고, 꺼지면 Phase 7 비교가 무효다."""
    with TrackARunner(env_target, small(), verbose=False) as runner:
        assert runner.env.obs_dim == 41
        assert runner.net.obs_dim == 41


def test_both_sides_are_trained(env_target):
    """벡터의 절반이 오른쪽 진영이다 (FR-3). 한쪽만 학습하면 M3-b 가 운에 걸린다."""
    with TrackARunner(env_target, small(), verbose=False) as runner:
        sides = np.asarray(runner.env.slot_sides)
        assert set(np.unique(sides)) == {0, 1}
        assert int((sides == 1).sum()) == 4
        row = runner.train_iteration()
        assert row["left.steps"] == row["right.steps"] == 4 * 8


def test_same_seed_reproduces(env_target):
    """M3-d 의 축소판. 손실·KL 이 **정확히** 일치해야 한다 (근사가 아니다)."""
    def run() -> list[dict]:
        with TrackARunner(env_target, small(seed=11), verbose=False) as runner:
            return [runner.train_iteration() for _ in range(ITERATIONS)]

    first, second = run(), run()
    keys = ("loss_pi", "loss_v", "entropy", "kl", "clipfrac", "explained_var",
            "rally_win_rate", "left.rally_win_rate", "right.rally_win_rate")
    for a, b in zip(first, second, strict=True):
        assert [a[k] for k in keys] == [b[k] for k in keys]


def test_different_seed_diverges(env_target):
    """시드를 바꿨는데 같으면 시드가 어디에도 배선되지 않은 것이다."""
    def run(seed: int) -> dict:
        with TrackARunner(env_target, small(seed=seed), verbose=False) as runner:
            return runner.train_iteration()

    assert run(11)["loss_pi"] != run(12)["loss_pi"]


def test_periodic_eval_does_not_change_training(env_target, eval_target, tmp_path):
    """**이 파일에서 가장 중요한 테스트.**

    평가가 torch 전역 RNG 를 한 번이라도 소비하면 학습 결과가 "평가를 몇 번 했는가" 의
    함수가 된다. 그러면 M3-d 는 평가 주기를 바꾸는 순간 깨지고, 그 사실은 한참 뒤에
    드러난다. argmax 평가는 RNG 를 쓰지 않아야 하고, 전용 서버를 써야 한다.

    같은 테스트가 함정 5 도 덮는다 — 평가가 학습 서버에 `Configure` 를 부르면 다음
    `Step` 이 `FAILED_PRECONDITION` 으로 죽어 이 테스트가 예외로 끝난다.
    """
    def run(with_eval: bool) -> list[dict]:
        run_dir = RunDir.create(f"eval-{with_eval}", root=tmp_path)
        config = small(
            seed=21,
            eval_every=64 if with_eval else 0,
            eval_games=1, eval_num_envs=4,
        )
        with TrackARunner(
            env_target, config, run=run_dir, verbose=False,
            eval_target=eval_target if with_eval else None,
        ) as runner:
            runner.train()
            assert (runner.last_eval is not None) == with_eval
        return read_jsonl(run_dir.metrics_path)

    without, with_ = run(False), run(True)
    keys = ("loss_pi", "loss_v", "entropy", "kl", "rally_win_rate")
    for a, b in zip(without, with_, strict=True):
        assert [a[k] for k in keys] == [b[k] for k in keys]


def test_eval_writes_its_own_stream(env_target, eval_target, tmp_path):
    """평가는 `evals.jsonl` 로 간다 — `metrics.jsonl` 에 섞으면 줄마다 스키마가 달라진다."""
    run = RunDir.create("streams", root=tmp_path)
    config = small(seed=3, eval_every=64, eval_games=1, eval_num_envs=4)
    with TrackARunner(
        env_target, config, run=run, eval_target=eval_target, verbose=False,
    ) as runner:
        runner.train()

    evals = read_jsonl(run.evals_path)
    assert evals, "평가 기록이 없습니다"
    assert {"iter", "env_steps", "combined", "side_gap"} <= set(evals[0])
    assert len(read_jsonl(run.metrics_path)) == ITERATIONS


# ═══════════════════════════════════════════════════════════════════════════
# 체크포인트 (FR-8)
# ═══════════════════════════════════════════════════════════════════════════


def test_checkpoint_is_readable_by_evaluator(env_target, tmp_path):
    """평가는 **체크포인트만으로** 재현된다 (FR-8). 규약이 갈라지면 여기서 걸린다."""
    run = RunDir.create("ckpt", root=tmp_path)
    with TrackARunner(env_target, small(seed=2), run=run, verbose=False) as runner:
        runner.train_iteration()
        path = runner.save("test")
        expected = {k: v.clone() for k, v in runner.net.state_dict().items()}

    blob = load_checkpoint(path)
    assert blob["env_steps"] == 64 and blob["iteration"] == 1
    assert blob["seed"] == 2 and blob["threads"] == 1
    assert blob["config"]["gamma"] == 0.997
    assert "git_commit" in blob and "torch_version" in blob
    assert "optimizer" in blob

    # 러너의 구성 파일을 읽지 않고 되살아난다.
    net = load_net(path)
    assert (net.obs_dim, net.action_count, net.hidden) == (41, 18, 16)
    for key, value in net.state_dict().items():
        assert torch.equal(value, expected[key])


def test_restore_resumes_progress(env_target, tmp_path):
    run = RunDir.create("resume", root=tmp_path)
    with TrackARunner(env_target, small(seed=4), run=run, verbose=False) as runner:
        runner.train_iteration()
        runner.train_iteration()
        path = runner.save("mid")
        weights = runner.net.state_dict()["actor.0.weight"].clone()

    with TrackARunner(env_target, small(seed=4), verbose=False) as resumed:
        resumed.restore(path)
        assert resumed.env_steps == 128 and resumed.iteration == 2
        assert torch.equal(resumed.net.state_dict()["actor.0.weight"], weights)


def test_restore_rejects_thread_mismatch(env_target, tmp_path):
    """스레드 수가 바뀌면 리덕션 순서가 바뀌어 결과가 갈라진다 (plan.md §11)."""
    run = RunDir.create("threads", root=tmp_path)
    with TrackARunner(env_target, small(seed=5), run=run, verbose=False) as runner:
        runner.train_iteration()
        path = runner.save("t")

    with TrackARunner(env_target, small(seed=5, threads=2), verbose=False) as other:
        with pytest.raises(ValueError, match="스레드"):
            other.restore(path)
