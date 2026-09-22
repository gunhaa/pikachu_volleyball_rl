"""Kotlin 엔진 서버를 띄워 두고 Python 테스트에 넘겨준다.

왜 실제 서버를 띄우는가: 가짜 서버(mock)를 세우면 **계약을 두 번 구현**하게 되고,
그 둘이 맞는지는 아무도 확인하지 않는다. M2-c 가 묻는 것은 "클라이언트가 규약대로
구는가" 가 아니라 "이 서버에 붙은 클라이언트가 규약대로 구는가" 다.

이미 떠 있는 서버를 쓰려면 ``PIKA_ENV_TARGET`` 을 준다 (compose 테스트가 그렇게 한다).
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import time
from collections.abc import Iterator
from pathlib import Path

import grpc
import pytest

from pika_trainer.pb import env_pb2, env_pb2_grpc


def repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "settings.gradle.kts").exists():
            return parent
    raise RuntimeError("저장소 루트를 찾지 못했습니다")


def _wait_until_healthy(target: str, timeout_s: float = 60.0) -> None:
    deadline = time.monotonic() + timeout_s
    last: Exception | None = None
    with grpc.insecure_channel(target) as channel:
        stub = env_pb2_grpc.PikaEnvStub(channel)
        while time.monotonic() < deadline:
            try:
                stub.Health(env_pb2.HealthRequest(), timeout=1.0)
                return
            except grpc.RpcError as e:  # 서버가 아직 안 떴다
                last = e
                time.sleep(0.1)
    raise RuntimeError(f"서버가 {timeout_s}s 안에 뜨지 않았습니다: {last}")


@pytest.fixture(scope="session")
def env_target() -> Iterator[str]:
    """gRPC 대상 문자열. 세션 내내 같은 서버를 쓴다.

    ⚠️ 이 함수에는 `yield` 가 있으므로 **제너레이터**다. `return 값` 은 값을 내지 않고
       그냥 끝난다 (pytest 는 "did not yield a value" 로 실패한다). 외부 서버 경로도
       반드시 yield 해야 한다 — 이 함정을 한 번 밟았다.
    """
    external = os.environ.get("PIKA_ENV_TARGET")
    if external:
        _wait_until_healthy(external)
        yield external
        return

    root = repo_root()
    launcher = root / "engine-kotlin/server/build/install/server/bin/server"
    gradlew = root / "gradlew"

    # ⚠️ **항상** 다시 빌드한다. "있으면 재사용" 으로 두면 옛 서버 바이너리를 상대로
    #    테스트가 돌고, 계약을 바꾼 날 빨간불이 안 뜬다 (한 번 겪었다 — 세션 번호를
    #    추가했는데 테스트는 그 전 배포본과 말하고 있었다).
    #    Gradle 은 up-to-date 면 즉시 끝난다.
    if gradlew.exists():
        subprocess.run(
            [str(gradlew), ":engine-kotlin:server:installDist", "-q"],
            cwd=root, check=True,
        )
    elif not launcher.exists():
        pytest.skip("엔진 서버 배포본도 gradlew 도 없습니다")

    socket_dir = tempfile.mkdtemp(prefix="pika-")
    # UDS 경로에는 길이 제한(약 104바이트)이 있다. 짧게 잡는다.
    socket_path = os.path.join(socket_dir, "s.sock")
    process = subprocess.Popen(
        [str(launcher), "--uds", socket_path],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    target = f"unix://{socket_path}"
    try:
        _wait_until_healthy(target)
        yield target
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
        shutil.rmtree(socket_dir, ignore_errors=True)
