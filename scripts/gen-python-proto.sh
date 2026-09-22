#!/usr/bin/env bash
# proto/env.proto → Python gRPC stub.
#
# 생성 결과는 **커밋한다.** uv run pytest 가 코드 생성 단계 없이 돌아야 하기 때문이다.
# 대신 tests/test_proto_stubs.py 가 "커밋된 stub 이 .proto 와 같은가" 를 확인한다.
# (stub 을 잊고 .proto 만 고치면 클라이언트가 조용히 옛 계약으로 말하게 된다.)
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root/trainer-python"

uv run python -m grpc_tools.protoc \
  --proto_path="$repo_root/proto" \
  --python_out=src/pika_trainer/pb \
  --pyi_out=src/pika_trainer/pb \
  --grpc_python_out=src/pika_trainer/pb \
  env.proto

# protoc 는 `import env_pb2` 를 절대 임포트로 낸다. 패키지 안에서 돌게 상대 임포트로 바꾼다.
# (protoc 의 오래된 한계다. --python_out 에 패키지 경로를 넣어도 해결되지 않는다.)
python3 - <<'PY'
import pathlib
p = pathlib.Path("src/pika_trainer/pb/env_pb2_grpc.py")
s = p.read_text()
s = s.replace("import env_pb2 as env__pb2", "from . import env_pb2 as env__pb2")
p.write_text(s)
PY

echo "생성 완료: trainer-python/src/pika_trainer/pb/"
