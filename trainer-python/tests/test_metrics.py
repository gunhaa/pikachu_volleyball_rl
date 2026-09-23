"""메트릭 JSONL 과 런 디렉터리. (FR-9 / tasks.md P6 / plan.md §11)

여기서 막는 사고는 **학습 3시간 뒤에 죽는 것**이다. `action_hist` 는 `np.bincount` 의
결과이므로 `np.int64` 이고, `json.dumps` 는 그것을 거부한다 (`int` 의 서브클래스가 아니다).
변환이 없으면 첫 반복에서 죽는데, 그 첫 반복은 서버를 띄우고 롤아웃을 돌린 뒤다.
"""

from __future__ import annotations

import json
import math

import numpy as np
import pytest

from pika_trainer.metrics import (
    SCHEMA_VERSION,
    JsonlLogger,
    RunDir,
    flatten,
    git_commit,
    read_jsonl,
    to_jsonable,
)


# ═══════════════════════════════════════════════════════════════════════════
# 직렬화
# ═══════════════════════════════════════════════════════════════════════════


def test_numpy_scalars_become_python():
    """`np.int64` 를 그대로 넘기면 `json.dumps` 가 죽는다."""
    out = to_jsonable({"a": np.int64(3), "b": np.float32(0.5), "c": np.bool_(True)})
    assert out == {"a": 3, "b": pytest.approx(0.5), "c": True}
    assert all(isinstance(v, (int, float, bool)) for v in out.values())
    json.dumps(out)  # 여기서 죽지 않는 것이 요구사항이다


def test_numpy_array_becomes_list():
    hist = np.bincount([0, 0, 2], minlength=4)
    assert to_jsonable(hist) == [2, 0, 1, 0]
    json.dumps(to_jsonable(hist))


def test_nonfinite_floats_become_strings():
    """발산을 0 으로 지우지 않는다. `NaN` 리터럴은 유효한 JSON 이 아니다."""
    out = to_jsonable({"a": float("nan"), "b": float("inf"), "c": float("-inf")})
    assert out == {"a": "nan", "b": "inf", "c": "-inf"}
    json.loads(json.dumps(out, allow_nan=False))  # 다른 파서도 읽을 수 있어야 한다


def test_unknown_objects_become_repr():
    """스케줄 객체는 `repr` 이 곧 기록이다."""
    from pika_trainer.schedules import Linear

    assert to_jsonable(Linear(3e-4, 0.0)) == "Linear(0.0003 → 0.0)"


def test_flatten_nests_with_dots_but_keeps_lists():
    row = flatten({
        "iter": 1,
        "shaping_w": {"rally_win": 1.0, "ball_touch": 0.05},
        "left": {"term_share": {"rally_win": 0.9}},
        "action_hist": [1, 2, 3],
    })
    assert row["shaping_w.rally_win"] == 1.0
    assert row["left.term_share.rally_win"] == 0.9
    # 리스트는 펴지 않는다 — `action_hist[18]` 은 한 값이다.
    assert row["action_hist"] == [1, 2, 3]


def test_git_commit_shape():
    commit = git_commit()
    assert commit == "unknown" or len(commit.split("+")[0]) == 40


# ═══════════════════════════════════════════════════════════════════════════
# 로거
# ═══════════════════════════════════════════════════════════════════════════


def test_logger_writes_immediately(tmp_path):
    """줄마다 flush 한다 — 학습이 중간에 끊겨도 앞의 기록은 남아야 한다."""
    path = tmp_path / "metrics.jsonl"
    logger = JsonlLogger(path)
    logger.log({"iter": 1, "loss": 0.5})
    # 닫기 **전에** 읽는다.
    rows = read_jsonl(path)
    assert rows == [{"schema": SCHEMA_VERSION, "iter": 1, "loss": 0.5}]
    logger.log({"iter": 2, "loss": np.float64(0.25)})
    logger.close()
    assert [r["iter"] for r in read_jsonl(path)] == [1, 2]


def test_logger_appends(tmp_path):
    """재개한 런이 앞의 기록을 지우지 않는다."""
    path = tmp_path / "m.jsonl"
    with JsonlLogger(path) as first:
        first.log({"iter": 1})
    with JsonlLogger(path) as second:
        second.log({"iter": 2})
    assert [r["iter"] for r in read_jsonl(path)] == [1, 2]


def test_logger_survives_nan(tmp_path):
    """발산해도 로그는 살아야 한다. 대신 숫자 자리에 `NaN` 리터럴을 쓰지 않는다."""
    path = tmp_path / "m.jsonl"
    with JsonlLogger(path) as logger:
        logger.log({"kl": float("nan"), "loss_pi": float("inf")})
    raw = path.read_text(encoding="utf-8")
    assert "NaN" not in raw and "Infinity" not in raw
    assert read_jsonl(path)[0] == {"schema": SCHEMA_VERSION, "kl": "nan", "loss_pi": "inf"}


def test_logger_rejects_closed(tmp_path):
    logger = JsonlLogger(tmp_path / "m.jsonl")
    logger.close()
    with pytest.raises(RuntimeError, match="닫힌"):
        logger.log({"iter": 1})


def test_read_jsonl_skips_truncated_last_line(tmp_path):
    """프로세스가 write 도중 죽으면 마지막 줄이 잘린다. 그 줄만 버린다."""
    path = tmp_path / "m.jsonl"
    path.write_text('{"iter": 1}\n{"iter": 2, "los\n', encoding="utf-8")
    assert read_jsonl(path) == [{"iter": 1}]


# ═══════════════════════════════════════════════════════════════════════════
# 런 디렉터리
# ═══════════════════════════════════════════════════════════════════════════


def test_run_dir_paths(tmp_path):
    run = RunDir.create("track-a-seed1", root=tmp_path)
    assert run.path.is_dir()
    assert run.metrics_path.name == "metrics.jsonl"
    assert run.evals_path.name == "evals.jsonl"
    assert run.checkpoint_path(5_000_000).name == "ckpt-5000000.pt"
    assert run.checkpoint_path("final").name == "ckpt-final.pt"
    assert run.eval_report_path("final").name == "eval-final.json"


def test_run_dir_write_json_handles_numpy(tmp_path):
    run = RunDir.create("r", root=tmp_path)
    path = run.write_json(run.config_path, {"seed": np.int64(3), "hist": np.arange(3)})
    assert json.loads(path.read_text(encoding="utf-8")) == {"seed": 3, "hist": [0, 1, 2]}


def test_run_dir_create_is_idempotent(tmp_path):
    """재개한 런이 같은 디렉터리를 다시 연다."""
    a = RunDir.create("same", root=tmp_path)
    b = RunDir.create("same", root=tmp_path)
    assert a.path == b.path


def test_metrics_and_evals_are_separate_files(tmp_path):
    """평가를 `metrics.jsonl` 에 섞으면 줄마다 스키마가 달라진다 (plan.md §11)."""
    run = RunDir.create("r", root=tmp_path)
    assert run.metrics_path != run.evals_path
