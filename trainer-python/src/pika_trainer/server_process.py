"""엔진 서버 프로세스를 띄운다. (plan.md §9.3)

─────────────────────────────────────────────────────────────────────────────
왜 평가기가 서버를 직접 띄우는가
─────────────────────────────────────────────────────────────────────────────
서버는 **단일 테넌트**다 (`ConfigureReply.session_id`). 평가가 학습 서버에 `Configure` 를
부르면 학습 세션이 무효가 되고, 그 다음 `Step` 이 `FAILED_PRECONDITION` 으로 죽는다.
주기적 평가(P6)는 학습 루프 **안에서** 돌아가므로, 평가는 자기 소켓에 자기 서버를 띄운다.
JVM 하나 더는 16GB 에서 문제되지 않는다.

테스트와 벤치는 이미 떠 있는 서버를 재사용한다 — ``PIKA_ENV_TARGET`` 또는 명시적 인자.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

import grpc

from .pb import env_pb2, env_pb2_grpc

#: 서버 배포본의 위치 (`installDist` 의 산출물).
LAUNCHER = "engine-kotlin/server/build/install/server/bin/server"


def repo_root(start: Path | None = None) -> Path:
    for parent in (start or Path(__file__)).resolve().parents:
        if (parent / "settings.gradle.kts").exists():
            return parent
    raise RuntimeError("저장소 루트를 찾지 못했습니다 (settings.gradle.kts)")


def wait_until_healthy(target: str, timeout_s: float = 60.0) -> None:
    """`Health` 가 응답할 때까지 기다린다. JVM 기동은 1초쯤 걸린다."""
    deadline = time.monotonic() + timeout_s
    last: Exception | None = None
    with grpc.insecure_channel(target) as channel:
        stub = env_pb2_grpc.PikaEnvStub(channel)
        while time.monotonic() < deadline:
            try:
                stub.Health(env_pb2.HealthRequest(), timeout=1.0)
                return
            except grpc.RpcError as e:  # 아직 안 떴다
                last = e
                time.sleep(0.1)
    raise RuntimeError(f"서버가 {timeout_s}s 안에 뜨지 않았습니다: {last}")


@contextmanager
def launch_server(*, build: bool = True, root: Path | None = None) -> Iterator[str]:
    """서버를 임시 UDS 에 띄우고 대상 문자열을 낸다. 블록을 벗어나면 죽인다.

    Args:
        build: `installDist` 를 먼저 돌린다. 끄면 **옛 바이너리로 평가할 수 있다** —
            껐다면 왜 껐는지 알고 있어야 한다.
    """
    root = root or repo_root()
    launcher = root / LAUNCHER
    gradlew = root / "gradlew"

    if build and gradlew.exists():
        subprocess.run([str(gradlew), ":engine-kotlin:server:installDist", "-q"], cwd=root, check=True)
    if not launcher.exists():
        raise RuntimeError(f"서버 배포본이 없습니다: {launcher} (`./gradlew :engine-kotlin:server:installDist`)")

    # ⚠️ UDS 경로에는 약 104바이트 제한이 있다. 짧게 잡는다.
    socket_dir = tempfile.mkdtemp(prefix="pika-eval-")
    socket_path = os.path.join(socket_dir, "s.sock")
    process = subprocess.Popen(
        [str(launcher), "--uds", socket_path],
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
    )
    target = f"unix://{socket_path}"
    try:
        wait_until_healthy(target)
        yield target
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
        shutil.rmtree(socket_dir, ignore_errors=True)
