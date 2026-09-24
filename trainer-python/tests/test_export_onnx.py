"""ONNX export — 결정론 · 자기 검증 · 레지스트리. (Phase 5 P3, M5-d)

단위 테스트는 가짜 체크포인트(무작위 초기화 망)와 무작위 관측으로 돈다 — 실제 체크포인트(`runs/`)는
커밋 대상이 아니다. 서버 경로(`collect_observations`)는 짧은 평가 한 번으로 본다.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnx
import pytest
import torch
from torch import nn

from pika_trainer import export_onnx as ex
from pika_trainer.net import ActorCritic
from pika_trainer.obs_spec import ObsOptions, ObsSpec


def _save_ckpt(path: Path, *, seed: int, obs_dim: int = 41) -> Path:
    torch.manual_seed(seed)
    net = ActorCritic(obs_dim, ex.ACTION_COUNT)
    # 정책 헤드 gain 0.01 이면 로짓이 전부 0 근처라 argmax 여유가 비현실적으로 작다. 키운다.
    # 편향도 채운다 — 초기화 그대로(전부 0)면 같은 모양의 0 편향 둘을 exporter 가 한 initializer 로
    # 합치고 Identity 노드를 끼운다. 학습된 체크포인트에는 없는 모양이다.
    with torch.no_grad():
        net.actor[4].weight.mul_(100)
        for layer in (net.actor[0], net.actor[2], net.actor[4]):
            layer.bias.uniform_(-0.1, 0.1)
    torch.save({"model": net.state_dict(), "obs_dim": obs_dim, "action_count": ex.ACTION_COUNT, "hidden": 128}, path)
    return path


@pytest.fixture
def ckpt(tmp_path: Path) -> Path:
    return _save_ckpt(tmp_path / "ckpt.pt", seed=0)


@pytest.fixture
def obs() -> np.ndarray:
    return np.random.default_rng(0).uniform(-1, 1, size=(2000, 41)).astype(np.float32)


def _export(ckpt: Path, tmp_path: Path, obs: np.ndarray, label: str = "p", **kw):
    out = tmp_path / "policies"
    return ex.export_checkpoint(ckpt, label, observations=obs, out_dir=out, registry=out / "registry.jsonl", **kw), out


def test_default_layout_is_policy_layout():
    assert ex.default_layout() == ObsOptions(include_expected_landing=True, include_side_flag=True)
    assert ex.parse_layout("landing,side_flag") == ex.default_layout()
    assert ex.parse_layout("none") == ObsOptions(False, False)
    with pytest.raises(ValueError):
        ex.parse_layout("landing,sideflag")


def test_same_checkpoint_same_sha(ckpt: Path, tmp_path: Path, obs: np.ndarray):
    """M5-d — 같은 체크포인트 두 번 export → 같은 SHA-256 (메타데이터 포함)."""
    a = tmp_path / "a.onnx"
    b = tmp_path / "b.onnx"
    net = ex.load_net(ckpt)
    for p in (a, b):
        ex.write_onnx(net, p, checkpoint_sha="x" * 64, layout_hash="y" * 64)
    assert a.read_bytes() == b.read_bytes()

    row1, out = _export(ckpt, tmp_path, obs)
    row2, _ = _export(ckpt, tmp_path, obs)
    assert row1["onnx_sha256"] == row2["onnx_sha256"]
    lines = (out / "registry.jsonl").read_text().splitlines()
    assert len(lines) == 1, "같은 label 재export 는 줄을 덧붙이지 않는다"


def test_metadata_and_graph(ckpt: Path, tmp_path: Path, obs: np.ndarray):
    row, out = _export(ckpt, tmp_path, obs)
    model = onnx.load(str(out / "p.onnx"))
    meta = [(p.key, p.value) for p in model.metadata_props]
    assert [k for k, _ in meta] == sorted([ex.META_CHECKPOINT, ex.META_OBS_DIM, ex.META_LAYOUT, ex.META_ACTIONS])
    meta = dict(meta)
    assert meta[ex.META_CHECKPOINT] == row["checkpoint_sha256"] == ex.sha256_file(ckpt)
    assert meta[ex.META_OBS_DIM] == "41"
    assert meta[ex.META_ACTIONS] == "18"
    assert meta[ex.META_LAYOUT] == ObsSpec.load().layout_hash(ex.default_layout())
    assert [n.op_type for n in model.graph.node] == ["Gemm", "Tanh", "Gemm", "Tanh", "Gemm"]
    assert model.opset_import[0].version == ex.OPSET
    assert [i.name for i in model.graph.input] == ["obs"]
    assert [o.name for o in model.graph.output] == ["logits"]
    assert row["max_logit_error"] <= ex.MAX_LOGIT_ERROR
    assert row["margin"]["observations"] == len(obs)


def test_tampered_structure_fails_and_leaves_nothing(ckpt: Path, tmp_path: Path, obs: np.ndarray, monkeypatch):
    """export 되는 그래프가 평가기의 망과 다르면 (Tanh → ReLU) 자기 검증이 실패하고 파일이 남지 않는다."""
    def tampered(net):
        return nn.Sequential(net.actor[0], nn.ReLU(), net.actor[2], nn.ReLU(), net.actor[4])

    monkeypatch.setattr(ex, "export_module", tampered)
    with pytest.raises(ex.ExportError, match="자기 검증 실패"):
        _export(ckpt, tmp_path, obs)
    out = tmp_path / "policies"
    assert list(out.iterdir()) == []


def test_layout_dim_mismatch_fails(tmp_path: Path, obs: np.ndarray):
    """obs_dim 에서 레이아웃을 추론하지 않는다 — 40차원 체크포인트를 기본(41) 레이아웃으로 내보내면 실패."""
    ckpt40 = _save_ckpt(tmp_path / "c40.pt", seed=1, obs_dim=40)
    with pytest.raises(ex.ExportError, match="obs_dim"):
        _export(ckpt40, tmp_path, obs[:, :40])
    row, _ = _export(ckpt40, tmp_path, obs[:, :40], layout=ex.parse_layout("landing"))
    assert row["obs_dim"] == 40


def test_registry_label_rules(ckpt: Path, tmp_path: Path, obs: np.ndarray):
    row, out = _export(ckpt, tmp_path, obs)
    other = _save_ckpt(tmp_path / "other.pt", seed=5)
    with pytest.raises(ex.ExportError, match="다른 체크포인트"):
        _export(other, tmp_path, obs)
    assert (out / "p.onnx").read_bytes() and ex.sha256_file(out / "p.onnx") == row["onnx_sha256"]

    # 같은 체크포인트인데 레지스트리의 ONNX SHA 가 다르다 = 결정론 깨짐.
    reg = out / "registry.jsonl"
    bad = dict(row, onnx_sha256="0" * 64)
    reg.write_text(json.dumps(bad) + "\n")
    with pytest.raises(ex.ExportError, match="결정론"):
        _export(ckpt, tmp_path, obs)


def test_collect_observations_from_server(env_target: str, ckpt: Path):
    """평가 경기 관측 수집 — 양 진영, 고유 관측, 41차원."""
    net = ex.load_net(ckpt)
    obs = ex.collect_observations(net, env_target, games_per_side=2, num_envs=4)
    assert obs.dtype == np.float32 and obs.shape[1] == 41 and len(obs) > 100
    assert len(np.unique(obs, axis=0)) == len(obs)
    # 진영 플래그(마지막 칸)가 양쪽 다 나온다 — 오른쪽 진영 평가도 들어갔다.
    assert set(np.unique(obs[:, -1]).tolist()) == {-1.0, 1.0}
