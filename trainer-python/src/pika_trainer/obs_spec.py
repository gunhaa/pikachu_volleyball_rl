"""`proto/obs_spec.proto` 파서 — 관측 레이아웃의 Python 쪽 시각. (NFR-5)

⚠️ **레이아웃을 이 파일에 복제하지 않는다.** 필드 이름을 여기에 적어 두면 .proto 와
   갈라질 수 있고, 갈라지는 순간 정책은 뒤섞인 관측을 받으면서도 **조용히** 학습을 계속한다.
   그래서 목록을 읽어 온다.

Kotlin 쪽 짝은 `engine-kotlin/env/.../ObsSpec.kt` 이고, 같은 규칙으로 같은 목록을 만든다.
양쪽이 같은 레이아웃 해시를 내는지는 서버의 `Health` 응답과 대조해서 확인한다
(`env_client.py`). 어긋나면 붙는 순간 실패한다.
"""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path

_MESSAGE_RE = re.compile(r"message\s+(\w+)\s*\{((?:[^{}]|\n)*)\}")
_FIELD_RE = re.compile(r"(\w+)\s+(\w+)\s*=\s*(\d+)\s*;")
_OPTIONAL_RE = re.compile(r"//\s*@optional:(\w+)")


@dataclass(frozen=True)
class ObsOptions:
    """관측 레이아웃을 바꾸는 플래그. 이것이 곧 레이아웃의 신원이다."""

    include_expected_landing: bool = True
    include_side_flag: bool = False


@dataclass(frozen=True)
class _ProtoField:
    type: str
    name: str
    number: int
    optional_flag: str | None


def find_obs_spec_proto() -> Path:
    """`obs_spec.proto` 의 위치.

    1. 환경변수 ``PIKA_OBS_SPEC_PROTO``
    2. 저장소 루트(``settings.gradle.kts`` 가 보이는 곳)의 ``proto/``

    못 찾으면 예외다. **서버가 준 레이아웃을 그냥 믿지 않는다** — 그러면 이 파일의
    존재 이유가 사라진다.
    """
    env = os.environ.get("PIKA_OBS_SPEC_PROTO")
    if env:
        return Path(env)

    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "settings.gradle.kts").exists():
            return parent / "proto" / "obs_spec.proto"
    raise FileNotFoundError(
        "obs_spec.proto 를 찾지 못했습니다. PIKA_OBS_SPEC_PROTO 로 경로를 지정하세요.",
    )


def _parse(text: str) -> dict[str, list[_ProtoField]]:
    out: dict[str, list[_ProtoField]] = {}
    for match in _MESSAGE_RE.finditer(text):
        fields: list[_ProtoField] = []
        pending: str | None = None
        for raw in match.group(2).split("\n"):
            line = raw.strip()
            tag = _OPTIONAL_RE.search(line)
            if tag:
                pending = tag.group(1)
            if line.startswith("//"):
                continue
            field = _FIELD_RE.search(line.split("//")[0])
            if not field:
                continue
            fields.append(_ProtoField(field.group(1), field.group(2), int(field.group(3)), pending))
            pending = None
        out[match.group(1)] = sorted(fields, key=lambda f: f.number)
    return out


class ObsSpec:
    """`obs_spec.proto` 가 정의한 관측 레이아웃."""

    def __init__(self, proto_text: str) -> None:
        self._messages = _parse(proto_text)
        if "Observation" not in self._messages:
            raise ValueError("obs_spec.proto 에 message Observation 이 없습니다")

    @classmethod
    def load(cls, path: Path | None = None) -> ObsSpec:
        return cls((path or find_obs_spec_proto()).read_text(encoding="utf-8"))

    def field_names(self, opts: ObsOptions = ObsOptions()) -> list[str]:
        """평탄화된 필드 이름 순서. float32 배열의 순서가 이것이다."""
        enabled = {
            "obs_include_expected_landing": opts.include_expected_landing,
            "obs_include_side_flag": opts.include_side_flag,
        }
        out: list[str] = []

        def walk(message: str, prefix: str) -> None:
            for field in self._messages[message]:
                if field.optional_flag is not None:
                    if field.optional_flag not in enabled:
                        raise ValueError(f"알 수 없는 @optional 플래그: {field.optional_flag}")
                    if not enabled[field.optional_flag]:
                        continue
                path = f"{prefix}.{field.name}" if prefix else field.name
                if field.type in self._messages:
                    walk(field.type, path)
                elif field.type == "float":
                    out.append(path)
                else:
                    raise ValueError(f"예상치 못한 타입 {field.type} ({path}). Obs Spec 은 전부 float 다.")

        walk("Observation", "")
        return out

    def dim(self, opts: ObsOptions = ObsOptions()) -> int:
        return len(self.field_names(opts))

    def layout_hash(self, opts: ObsOptions = ObsOptions()) -> str:
        """SHA-256("\\n" 으로 이은 활성 필드 이름). Kotlin 쪽과 같은 규칙이다."""
        names = "\n".join(self.field_names(opts))
        return hashlib.sha256(names.encode("utf-8")).hexdigest()
