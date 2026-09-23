"""Kotlin 엔진 서버를 띄워 두고 Python 테스트에 넘겨준다.

왜 실제 서버를 띄우는가: 가짜 서버(mock)를 세우면 **계약을 두 번 구현**하게 되고,
그 둘이 맞는지는 아무도 확인하지 않는다. M2-c 가 묻는 것은 "클라이언트가 규약대로
구는가" 가 아니라 "이 서버에 붙은 클라이언트가 규약대로 구는가" 다.

이미 떠 있는 서버를 쓰려면 ``PIKA_ENV_TARGET`` 을 준다 (compose 테스트가 그렇게 한다).

⚠️ 기동 로직은 `pika_trainer.server_process` 에 있다 — **평가기(`evaluate.py`)가 같은 것을
   필요로 하기 때문이다** (plan.md §9.3). 테스트 전용 사본을 따로 두면 둘이 갈라지고,
   "테스트에서는 되는데 평가에서는 안 되는" 차이가 생긴다.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import pytest

from pika_trainer.server_process import launch_server, repo_root, wait_until_healthy


def _repo_root() -> Path:
    return repo_root(Path(__file__))


@pytest.fixture(scope="session")
def env_target() -> Iterator[str]:
    """gRPC 대상 문자열. 세션 내내 같은 서버를 쓴다.

    ⚠️ 이 함수에는 `yield` 가 있으므로 **제너레이터**다. `return 값` 은 값을 내지 않고
       그냥 끝난다 (pytest 는 "did not yield a value" 로 실패한다). 외부 서버 경로도
       반드시 yield 해야 한다 — 이 함정을 한 번 밟았다.
    """
    external = os.environ.get("PIKA_ENV_TARGET")
    if external:
        wait_until_healthy(external)
        yield external
        return

    # ⚠️ **항상** 다시 빌드한다 (`build=True`). "있으면 재사용" 으로 두면 옛 서버 바이너리를
    #    상대로 테스트가 돌고, 계약을 바꾼 날 빨간불이 안 뜬다 (한 번 겪었다 — 세션 번호를
    #    추가했는데 테스트는 그 전 배포본과 말하고 있었다). Gradle 은 up-to-date 면 즉시 끝난다.
    try:
        with launch_server(root=_repo_root()) as target:
            yield target
    except RuntimeError as e:
        pytest.skip(str(e))
