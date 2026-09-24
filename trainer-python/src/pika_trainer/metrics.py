"""메트릭 JSONL 과 런 디렉터리. (FR-9, NFR-6 / plan.md §11)

─────────────────────────────────────────────────────────────────────────────
왜 스키마를 지금 고정하는가
─────────────────────────────────────────────────────────────────────────────
Phase 7 이 Track A 와 Track B 의 곡선을 **겹쳐 그린다.** 두 러너가 각자 편한 키를 쓰면
그때 가서 변환기를 쓰게 되고, 변환기는 옛 런을 읽지 못한다. 그래서 키 이름은 여기서
한 번 정하고 (`plan.md` §11), 러너는 중첩 dict 를 주기만 한다 — 평탄화는 로거의 몫이다.

─────────────────────────────────────────────────────────────────────────────
줄마다 flush 한다
─────────────────────────────────────────────────────────────────────────────
학습은 시간 단위로 돌고 **중간에 끊긴다** (Ctrl-C, OOM, 노트북 덮개). 버퍼에 남은 수백
줄이 그때 사라지면 "왜 죽었나" 를 볼 자료가 없다. 반복당 한 줄이므로 flush 비용은
롤아웃 한 번의 1만분의 1 이다.

─────────────────────────────────────────────────────────────────────────────
NaN 을 숫자 자리에 쓰지 않는다
─────────────────────────────────────────────────────────────────────────────
`json.dumps` 는 기본적으로 `NaN` · `Infinity` 를 그대로 뱉는데 그것은 **유효한 JSON 이
아니다** — 다른 언어의 파서가 거부한다. 그렇다고 0 으로 바꾸면 발산을 지워 버린다.
발산했다는 사실이야말로 남겨야 할 기록이므로, 문자열 ``"nan"`` · ``"inf"`` 로 적는다.
읽는 쪽은 숫자가 아닌 것을 보는 순간 알아챈다.
"""

from __future__ import annotations

import json
import math
import subprocess
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TextIO

import numpy as np

#: 키 이름을 바꾸면 올린다. 옛 런을 읽는 코드가 분기할 수 있게 매 줄에 적힌다.
SCHEMA_VERSION = 1

#: 런 디렉터리의 기본 뿌리. `.gitignore` 에 들어 있다 (NFR-6).
DEFAULT_RUNS_ROOT = "runs"


# ═══════════════════════════════════════════════════════════════════════════
# 직렬화
# ═══════════════════════════════════════════════════════════════════════════


def to_jsonable(value: Any) -> Any:
    """numpy 스칼라·배열과 비유한 float 를 JSON 이 받는 모양으로.

    ⚠️ `np.int64` 는 `json.dumps` 가 **거부한다** (`int` 의 서브클래스가 아니다).
       `action_hist` 가 `np.bincount` 의 결과이므로 이 변환이 없으면 첫 반복에서 죽는다.
    """
    if isinstance(value, np.generic):
        value = value.item()
    elif isinstance(value, np.ndarray):
        return [to_jsonable(v) for v in value.tolist()]

    if isinstance(value, float) and not math.isfinite(value):
        return "nan" if math.isnan(value) else ("inf" if value > 0 else "-inf")
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Mapping):
        return {str(k): to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [to_jsonable(v) for v in value]
    # 스케줄 객체 같은 것 — `repr` 이 곧 기록이다 (`Linear(0.0003 → 0)`).
    return repr(value)


def flatten(row: Mapping[str, Any], prefix: str = "") -> dict[str, Any]:
    """중첩 dict 를 점 표기로 편다. `{"shaping_w": {"rally_win": 1}}` → `shaping_w.rally_win`.

    리스트는 **펴지 않는다** — `action_hist[18]` 은 한 값이고, 18개 키로 흩어 놓으면
    Phase 7 의 플롯이 열을 18번 읽어야 한다.
    """
    out: dict[str, Any] = {}
    for key, value in row.items():
        name = f"{prefix}{key}"
        if isinstance(value, Mapping):
            out.update(flatten(value, f"{name}."))
        else:
            out[name] = to_jsonable(value)
    return out


def git_commit(root: Path | None = None) -> str:
    """현재 커밋. 워킹트리가 더러우면 ``+dirty`` 를 붙인다 (plan.md §11).

    체크포인트에 이 값이 들어간다. `+dirty` 가 붙은 체크포인트는 **재현 불가능**하다는
    뜻이고, 그 사실을 나중에 추측하는 것보다 그때 적어 두는 편이 싸다.
    """
    def run(*args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=root, capture_output=True, text=True, check=True,
        ).stdout.strip()

    try:
        head = run("rev-parse", "HEAD")
        return f"{head}+dirty" if run("status", "--porcelain") else head
    except (subprocess.CalledProcessError, OSError):
        return "unknown"


# ═══════════════════════════════════════════════════════════════════════════
# 로거
# ═══════════════════════════════════════════════════════════════════════════


class JsonlLogger:
    """한 줄에 한 레코드. **append 로 연다** — 재개한 런이 앞의 기록을 지우지 않는다."""

    def __init__(self, path: str | Path, *, schema_version: int = SCHEMA_VERSION) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.schema_version = schema_version
        self._file: TextIO | None = self.path.open("a", encoding="utf-8")
        self.count = 0

    def log(self, row: Mapping[str, Any]) -> dict[str, Any]:
        """평탄화·직렬화해서 한 줄 쓴다. 쓴 내용을 돌려준다 (테스트와 콘솔 출력이 쓴다)."""
        if self._file is None:
            raise RuntimeError(f"닫힌 로거입니다: {self.path}")
        record = {"schema": self.schema_version, **flatten(row)}
        # ⚠️ `allow_nan=False` — `to_jsonable` 을 빠져나간 비유한 값이 있으면 **여기서
        #    죽는 것이 맞다.** 조용히 `NaN` 리터럴을 쓰면 파일 전체가 다른 언어에서 못 읽힌다.
        self._file.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
        self._file.flush()
        self.count += 1
        return record

    def close(self) -> None:
        if self._file is not None:
            self._file.close()
            self._file = None

    def __enter__(self) -> JsonlLogger:
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    """기록을 되읽는다. 빈 줄은 건너뛴다 (중단된 런의 마지막 줄이 잘려 있을 수 있다)."""
    rows: list[dict[str, Any]] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            # 프로세스가 write 도중에 죽으면 마지막 줄이 잘린다. 그 줄만 버린다.
            continue
    return rows


# ═══════════════════════════════════════════════════════════════════════════
# 런 디렉터리
# ═══════════════════════════════════════════════════════════════════════════


@dataclass(frozen=True)
class RunDir:
    """`runs/<run-id>/` 의 경로 규약 (plan.md §11).

    ::

        runs/track-a-seed1/
        ├── config.json      하이퍼파라미터 · 시드 · git 해시 (사람이 읽는 용도)
        ├── metrics.jsonl    반복당 한 줄 (FR-9)
        ├── evals.jsonl      평가 한 번당 한 줄
        └── ckpt-<steps>.pt  가중치 + 옵티마이저 + 재현 정보 (FR-8)

    평가 기록을 `metrics.jsonl` 에 섞지 않는다. 평가는 수십 반복에 한 번이므로 같은
    파일에 두면 **줄마다 스키마가 달라지고**, Phase 7 이 매 줄에서 키 존재를 확인해야 한다.
    """

    path: Path

    @classmethod
    def create(cls, run_id: str, *, root: str | Path = DEFAULT_RUNS_ROOT) -> RunDir:
        path = Path(root) / run_id
        path.mkdir(parents=True, exist_ok=True)
        return cls(path)

    @property
    def metrics_path(self) -> Path:
        return self.path / "metrics.jsonl"

    @property
    def evals_path(self) -> Path:
        return self.path / "evals.jsonl"

    @property
    def config_path(self) -> Path:
        return self.path / "config.json"

    def checkpoint_path(self, env_steps: int | str) -> Path:
        """`ckpt-<env_steps>.pt`. 문자열을 주면 그대로 쓴다 (`"final"`)."""
        return self.path / f"ckpt-{env_steps}.pt"

    def eval_report_path(self, env_steps: int | str) -> Path:
        return self.path / f"eval-{env_steps}.json"

    def write_json(self, path: Path, obj: Any) -> Path:
        path.write_text(
            json.dumps(to_jsonable(obj), indent=2, ensure_ascii=False, allow_nan=False),
            encoding="utf-8",
        )
        return path

    @contextmanager
    def logger(self, path: Path) -> Iterator[JsonlLogger]:
        logger = JsonlLogger(path)
        try:
            yield logger
        finally:
            logger.close()
