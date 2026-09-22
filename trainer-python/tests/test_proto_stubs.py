"""커밋된 gRPC stub 이 `proto/env.proto` 와 같은지 확인한다.

stub 은 생성물이지만 커밋한다 — `uv run pytest` 가 코드 생성 단계 없이 돌아야 하기 때문이다.
대신 이 테스트가 staleness 를 잡는다. .proto 만 고치고 stub 을 잊으면 클라이언트는
**옛 계약으로 말하면서도 조용히 동작**한다. 새 필드가 그냥 안 보일 뿐이다.
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from .conftest import repo_root

STUBS = ["env_pb2.py", "env_pb2_grpc.py", "env_pb2.pyi"]


def test_stubs_match_proto() -> None:
    grpc_tools = pytest.importorskip("grpc_tools", reason="grpcio-tools 는 dev 의존성이다")
    del grpc_tools

    root = repo_root()
    committed = root / "trainer-python/src/pika_trainer/pb"

    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            [
                sys.executable, "-m", "grpc_tools.protoc",
                f"--proto_path={root / 'proto'}",
                f"--python_out={tmp}", f"--pyi_out={tmp}", f"--grpc_python_out={tmp}",
                "env.proto",
            ],
            check=True,
        )
        regenerated = Path(tmp)
        # scripts/gen-python-proto.sh 가 하는 상대 임포트 치환을 똑같이 적용한다.
        grpc_file = regenerated / "env_pb2_grpc.py"
        grpc_file.write_text(
            grpc_file.read_text().replace("import env_pb2 as env__pb2", "from . import env_pb2 as env__pb2"),
        )

        for name in STUBS:
            assert (committed / name).read_text() == (regenerated / name).read_text(), (
                f"{name} 이 proto/env.proto 와 어긋났습니다. "
                "scripts/gen-python-proto.sh 를 다시 돌리고 커밋하세요."
            )
