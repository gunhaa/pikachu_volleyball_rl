"""체크포인트 → 브라우저가 읽는 ONNX. (Phase 5 FR-5 ~ FR-7, M5-d, plan.md §5)

─────────────────────────────────────────────────────────────────────────────
무엇을 내보내는가
─────────────────────────────────────────────────────────────────────────────
**actor 만** — 가치 헤드는 브라우저가 쓰지 않는다. 입력 ``obs (batch, obs_dim)`` float32,
출력 ``logits (batch, 18)``. 메타데이터 4개(체크포인트 SHA · obs_dim · 레이아웃 해시 · 행동 수)를 붙인다.

─────────────────────────────────────────────────────────────────────────────
결정론 (plan.md §2.6)
─────────────────────────────────────────────────────────────────────────────
레거시 TorchScript exporter(``dynamo=False``), opset 17. dynamo exporter 는 같은 체크포인트에서도
실행마다 다른 바이트를 낸다 — "같은 체크포인트 → 같은 SHA" (M5-d) 가 성립하지 않는다.
``DeprecationWarning`` 은 알고 쓴다. torch 가 레거시 exporter 를 지우면 결정론 테스트가 먼저 깨진다.

─────────────────────────────────────────────────────────────────────────────
자기 검증 (plan.md §5.3)
─────────────────────────────────────────────────────────────────────────────
export 직후 onnxruntime(Python)으로 다시 읽어 **평가 경기의 관측**에 대해 평가기의 torch 망과 대조한다.
로짓 오차 ≤ 10⁻⁴, argmax 100%. 실패하면 ONNX 를 남기지 않고 0 이 아닌 코드로 끝난다.
argmax 여유(1위 − 2위 로짓) 분포는 레지스트리 줄에 적는다 — 체크포인트마다 다르기 때문이다.

레이아웃 해시는 체크포인트에 없다. ``obs_dim`` 에서 레이아웃을 **추론하지 않는다** — ``--obs-layout``
(기본: ``EnvOptions.for_policy()`` 의 레이아웃)으로 받고, 그 ``dim`` 이 체크포인트와 다르면 실패한다.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import warnings
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

from .env_client import EnvOptions, PikaVectorEnv
from .evaluate import evaluate_target, load_net, net_policy
from .net import ActorCritic
from .obs_spec import ObsOptions, ObsSpec
from .replay import sha256_file
from .server_process import launch_server, repo_root

OPSET = 17
ACTION_COUNT = 18

#: 로짓 최대 절대 오차 허용치. 측정값 ~7 × 10⁻⁶ 의 10배 이상 — 구조가 틀리면 O(1) 로 튄다.
MAX_LOGIT_ERROR = 1e-4
#: 이보다 작은 여유는 백엔드 오차(≤ 8 × 10⁻⁶ × 2)로 뒤집힐 수 있다 (plan.md §2.4).
RISKY_MARGIN = 1.6e-5

META_CHECKPOINT = "pika.checkpoint_sha256"
META_OBS_DIM = "pika.obs_dim"
META_LAYOUT = "pika.obs_layout_hash"
META_ACTIONS = "pika.action_count"

DEFAULT_REGISTRY = Path("runs/policies/registry.jsonl")


class ExportError(RuntimeError):
    """export 가 결과를 남기면 안 되는 실패."""


# ═══════════════════════════════════════════════════════════════════════════
# 레이아웃
# ═══════════════════════════════════════════════════════════════════════════

_LAYOUT_FLAGS = {"landing": "include_expected_landing", "side_flag": "include_side_flag"}


def default_layout() -> ObsOptions:
    """평가기·학습기가 쓰는 레이아웃 — `EnvOptions.for_policy()` 의 것."""
    return EnvOptions.for_policy().obs_options()


def parse_layout(text: str) -> ObsOptions:
    """``landing,side_flag`` 처럼 **켜진** 플래그의 쉼표 목록. ``none`` 은 둘 다 끔."""
    names = [] if text.strip() in ("", "none") else [t.strip() for t in text.split(",")]
    unknown = [n for n in names if n not in _LAYOUT_FLAGS]
    if unknown:
        raise ValueError(f"알 수 없는 레이아웃 플래그: {unknown} (가능: {sorted(_LAYOUT_FLAGS)}, none)")
    return ObsOptions(**{field: key in names for key, field in _LAYOUT_FLAGS.items()})


# ═══════════════════════════════════════════════════════════════════════════
# export
# ═══════════════════════════════════════════════════════════════════════════


def export_module(net: ActorCritic) -> nn.Module:
    """ONNX 로 나갈 모듈. 자기 검증은 **이것의 ONNX** 를 평가기의 torch 망(`act_greedy`)과 대조한다."""
    return net.actor


def write_onnx(net: ActorCritic, path: Path, *, checkpoint_sha: str, layout_hash: str) -> None:
    """결정론적으로 쓴다 — 같은 입력이면 같은 바이트 (M5-d)."""
    module = export_module(net).eval()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        torch.onnx.export(
            module, (torch.zeros(1, net.obs_dim),), str(path),
            input_names=["obs"], output_names=["logits"],
            dynamic_axes={"obs": {0: "batch"}, "logits": {0: "batch"}},
            opset_version=OPSET, dynamo=False,
        )
    model = onnx.load(str(path))
    meta = {
        META_CHECKPOINT: checkpoint_sha,
        META_OBS_DIM: str(net.obs_dim),
        META_LAYOUT: layout_hash,
        META_ACTIONS: str(net.action_count),
    }
    del model.metadata_props[:]
    for key in sorted(meta):  # 정렬된 순서 — 결정론
        model.metadata_props.add(key=key, value=meta[key])
    onnx.save(model, str(path))


# ═══════════════════════════════════════════════════════════════════════════
# 자기 검증
# ═══════════════════════════════════════════════════════════════════════════


@dataclass(frozen=True)
class Margin:
    """argmax 여유(float64 참값의 1위 − 2위 로짓) 분포."""

    min: float
    p001: float
    p01: float
    median: float
    below_risky: int
    observations: int


@dataclass(frozen=True)
class VerifyReport:
    max_logit_error: float
    argmax_agree: float
    margin: Margin

    @property
    def ok(self) -> bool:
        return self.max_logit_error <= MAX_LOGIT_ERROR and self.argmax_agree == 1.0


def ort_session(path: Path) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), sess_options=opts, providers=["CPUExecutionProvider"])


def margins(net: ActorCritic, obs: np.ndarray) -> np.ndarray:
    """float64 참값의 1위 − 2위. 백엔드 오차와 무관한 "이 관측이 얼마나 동률에 가까운가"."""
    actor64 = copy.deepcopy(export_module(net)).double()
    with torch.no_grad():
        logits = actor64(torch.from_numpy(obs.astype(np.float64))).numpy()
    top2 = np.sort(logits, axis=1)[:, -2:]
    return top2[:, 1] - top2[:, 0]


def verify(net: ActorCritic, path: Path, obs: np.ndarray) -> VerifyReport:
    """ORT(파일) vs 평가기 torch 망. ``obs`` 는 고유 관측 ``(N, obs_dim)`` float32."""
    if obs.ndim != 2 or obs.shape[1] != net.obs_dim or obs.dtype != np.float32:
        raise ValueError(f"관측은 (N, {net.obs_dim}) float32 여야 합니다: {obs.shape} {obs.dtype}")
    net.eval()
    with torch.no_grad():
        tensor = torch.from_numpy(obs)
        ref_logits = net.actor(tensor).numpy()
        ref_action = net.act_greedy(tensor).numpy()
    got = ort_session(path).run(["logits"], {"obs": obs})[0]
    if got.shape != ref_logits.shape:
        return VerifyReport(float("inf"), 0.0, _margin_stats(margins(net, obs)))
    err = float(np.max(np.abs(got - ref_logits))) if len(obs) else 0.0
    agree = float(np.mean(got.argmax(axis=1) == ref_action)) if len(obs) else 1.0
    return VerifyReport(err, agree, _margin_stats(margins(net, obs)))


def _margin_stats(m: np.ndarray) -> Margin:
    if len(m) == 0:
        return Margin(float("nan"), float("nan"), float("nan"), float("nan"), 0, 0)
    return Margin(
        min=float(m.min()),
        p001=float(np.quantile(m, 0.001)),
        p01=float(np.quantile(m, 0.01)),
        median=float(np.median(m)),
        below_risky=int(np.sum(m < RISKY_MARGIN)),
        observations=len(m),
    )


def collect_observations(
    net: ActorCritic, target: str, *, games_per_side: int = 40, num_envs: int = 16, base_seed: int = 0,
) -> np.ndarray:
    """평가 경기를 그대로 돌리며 정책이 본 관측을 모은다 — 고유 관측만.

    `evaluate_target` 에 관측을 가로채는 정책 래퍼를 넘긴다. `evaluate.py` 는 고치지 않는다.
    """
    seen: list[np.ndarray] = []

    def factory(env: PikaVectorEnv):
        if env.obs_dim != net.obs_dim:
            raise ExportError(f"서버 관측 {env.obs_dim}차원 ≠ 망 {net.obs_dim}차원")
        inner = net_policy(net, mode="argmax")

        def policy(obs: np.ndarray) -> np.ndarray:
            seen.append(obs.copy())
            return inner(obs)

        return policy

    evaluate_target(target, factory, games_per_side=games_per_side, num_envs=num_envs, base_seed=base_seed)
    return np.unique(np.concatenate(seen), axis=0).astype(np.float32)


# ═══════════════════════════════════════════════════════════════════════════
# 레지스트리 (plan.md §5.4)
# ═══════════════════════════════════════════════════════════════════════════


def read_registry(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def check_registry(rows: list[dict[str, Any]], row: dict[str, Any]) -> bool:
    """같은 label 이 있으면 결정론을 확인한다. 새 줄을 덧붙여야 하면 True.

    레지스트리가 한 label 에 두 판을 조용히 들고 있는 상태를 만들지 않는다.
    """
    same = [r for r in rows if r["label"] == row["label"]]
    if not same:
        return True
    old = same[-1]
    if old["checkpoint_sha256"] != row["checkpoint_sha256"]:
        raise ExportError(
            f"label '{row['label']}' 은 이미 다른 체크포인트({old['checkpoint_sha256'][:16]}…)를 가리킵니다. "
            "다른 label 을 쓰세요.",
        )
    if old["onnx_sha256"] != row["onnx_sha256"]:
        raise ExportError(
            f"같은 체크포인트인데 ONNX SHA 가 다릅니다 ({old['onnx_sha256'][:16]}… → {row['onnx_sha256'][:16]}…). "
            "export 결정론이 깨졌습니다 — torch · onnx 버전을 확인하세요.",
        )
    return False


def _git_head(root: Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def export_checkpoint(
    checkpoint: Path,
    label: str,
    *,
    observations: np.ndarray,
    out_dir: Path,
    registry: Path,
    layout: ObsOptions | None = None,
) -> dict[str, Any]:
    """export → 자기 검증 → 레지스트리. 검증이 실패하면 파일을 남기지 않는다. 레지스트리 줄을 돌려준다."""
    layout = layout or default_layout()
    spec = ObsSpec.load()
    net = load_net(checkpoint)
    if spec.dim(layout) != net.obs_dim:
        raise ExportError(
            f"레이아웃 {layout} 의 dim {spec.dim(layout)} ≠ 체크포인트 obs_dim {net.obs_dim}. "
            "--obs-layout 을 확인하세요 (obs_dim 에서 레이아웃을 추론하지 않습니다).",
        )
    layout_hash = spec.layout_hash(layout)
    ckpt_sha = sha256_file(checkpoint)

    out_dir.mkdir(parents=True, exist_ok=True)
    final = out_dir / f"{label}.onnx"
    tmp = out_dir / f".{label}.onnx.tmp"
    try:
        write_onnx(net, tmp, checkpoint_sha=ckpt_sha, layout_hash=layout_hash)
        report = verify(net, tmp, observations)
        if not report.ok:
            raise ExportError(
                f"자기 검증 실패: 로짓 오차 {report.max_logit_error:.3g} (≤ {MAX_LOGIT_ERROR:g}), "
                f"argmax 일치 {report.argmax_agree:.6f} (= 1)",
            )
        onnx_sha = sha256_file(tmp)
        root = repo_root()
        row = {
            "label": label,
            "checkpoint": _rel(checkpoint, root),
            "checkpoint_sha256": ckpt_sha,
            "onnx": _rel(final, root),
            "onnx_sha256": onnx_sha,
            "obs_dim": net.obs_dim,
            "obs_layout_hash": layout_hash,
            "opset": OPSET,
            "torch": str(torch.__version__),
            "onnx_pkg": onnx.__version__,
            "max_logit_error": report.max_logit_error,
            "margin": asdict(report.margin),
            "git": _git_head(root),
            "exported_at": dt.datetime.now(dt.UTC).isoformat(timespec="seconds"),
        }
        rows = read_registry(registry)
        append = check_registry(rows, row)
        os.replace(tmp, final)
    finally:
        tmp.unlink(missing_ok=True)
    if append:
        registry.parent.mkdir(parents=True, exist_ok=True)
        with registry.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    return row


def _rel(path: Path, root: Path) -> str:
    path = path.resolve()
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


# ═══════════════════════════════════════════════════════════════════════════
# CLI
# ═══════════════════════════════════════════════════════════════════════════


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="체크포인트의 actor 를 ONNX 로 내보내고 자기 검증 · 레지스트리 등록")
    p.add_argument("checkpoint", type=Path)
    p.add_argument("--label", required=True, help="레지스트리 이름 = 파일 이름 (예: track-a-seed0)")
    p.add_argument("--out-dir", type=Path, default=None, help="기본: <repo>/runs/policies")
    p.add_argument("--registry", type=Path, default=None, help="기본: <repo>/runs/policies/registry.jsonl")
    p.add_argument("--obs-layout", default=None, help="켜진 플래그 목록 'landing,side_flag' | 'none'. 기본: for_policy()")
    p.add_argument("--games", type=int, default=40, help="자기 검증 관측을 모을 진영별 게임 수")
    p.add_argument("--num-envs", type=int, default=16)
    p.add_argument("--seed", type=int, default=0, help="평가 base_seed")
    p.add_argument("--target", default=os.environ.get("PIKA_ENV_TARGET"), help="이미 떠 있는 서버. 없으면 띄운다")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = repo_root()
    out_dir = args.out_dir or root / "runs" / "policies"
    registry = args.registry or out_dir / "registry.jsonl"
    layout = parse_layout(args.obs_layout) if args.obs_layout is not None else None
    net = load_net(args.checkpoint)

    def collect(target: str) -> np.ndarray:
        return collect_observations(net, target, games_per_side=args.games, num_envs=args.num_envs, base_seed=args.seed)

    if args.target:
        obs = collect(args.target)
    else:
        with launch_server() as target:
            obs = collect(target)
    print(f"관측 {len(obs)}개 (고유) — 진영별 {args.games}게임")

    try:
        row = export_checkpoint(
            args.checkpoint, args.label, observations=obs, out_dir=out_dir, registry=registry, layout=layout,
        )
    except ExportError as e:
        print(f"✗ {e}", file=sys.stderr)
        return 1
    m = row["margin"]
    print(
        f"✓ {row['onnx']}  onnx {row['onnx_sha256'][:16]}  ckpt {row['checkpoint_sha256'][:16]}\n"
        f"  로짓 오차 {row['max_logit_error']:.2e} · 여유 최소 {m['min']:.3g} · 0.1% {m['p001']:.3g} · "
        f"1% {m['p01']:.3g} · 중앙 {m['median']:.3g} · < {RISKY_MARGIN:g}: {m['below_risky']}",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
