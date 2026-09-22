#!/usr/bin/env bash
# 세 지점 처리량을 한 번에 잰다. (NFR-3, plan.md §11)
#
#   (a) env 단독        — JVM in-process, gRPC 없음          (M2-b: ≥ 1,000,000)
#   (b) gRPC 루프백     — Kotlin 클라이언트, 더미 행동
#   (c) Python 종단     — 더미 MLP 정책, UDS                  (M2-a: ≥ 50,000)
#
# 숫자 하나만 재면 미달일 때 어디를 고쳐야 할지 알 수 없다. 그래서 셋을 나눠 재고
# 간극으로 읽는다: (a)−(b) = 직렬화 + RPC, (b)−(c) = Python 디코딩 + 정책.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

steps="${STEPS:-500}"
socket="${PIKA_UDS:-${TMPDIR:-/tmp}/pika-bench.sock}"

echo "════════════════════════════════════════════════════════════════════"
echo " (a) env 단독"
echo "════════════════════════════════════════════════════════════════════"
./gradlew --console=plain -q :engine-kotlin:env:benchEnv

echo
echo "════════════════════════════════════════════════════════════════════"
echo " (b) gRPC 루프백 (Kotlin 클라이언트)"
echo "════════════════════════════════════════════════════════════════════"
./gradlew --console=plain -q :engine-kotlin:server:benchLoopback

echo
echo "════════════════════════════════════════════════════════════════════"
echo " (c) Python 종단 (더미 MLP 정책, UDS)"
echo "════════════════════════════════════════════════════════════════════"
./gradlew --console=plain -q :engine-kotlin:server:installDist
rm -f "$socket"
engine-kotlin/server/build/install/server/bin/server --uds "$socket" >/dev/null 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$socket"' EXIT

# 서버가 소켓을 열 때까지 기다린다.
for _ in $(seq 1 100); do
  [ -S "$socket" ] && break
  sleep 0.1
done

cd trainer-python
uv run python -m pika_trainer.bench_client --target "unix://$socket" --steps "$steps"
