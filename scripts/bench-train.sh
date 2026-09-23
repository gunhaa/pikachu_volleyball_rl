#!/usr/bin/env bash
# P1 학습 예산 측정. (M3-e, NFR-1, NFR-5 / plan.md §3)
#
#   (a) 정책 forward     N × H
#   (b) 스레드 수        1 / 4 / 8
#   (c) 업데이트 한 반복 배치 65,536 · 에폭 4 · 미니배치 8
#   (d) MPS              참고용
#   (e) 종단 한 반복     실제 서버(UDS)로 롤아웃 + 업데이트
#
# `bench-env.sh` 가 **환경**의 처리량을 재고 이쪽이 **학습**의 처리량을 잰다.
# 둘을 나눠 두는 이유는 같다 — 숫자 하나만 재면 미달일 때 어디를 고칠지 알 수 없다.
#
# ⚠️ (e) 는 서버를 **재사용**한다. 매 반복 새 서버를 띄우면 JVM JIT 워밍업 구간만
#    재게 되고 정상 상태를 영영 못 본다 (plan.md §3.4).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

socket="${PIKA_UDS:-${TMPDIR:-/tmp}/pika-bench-train.sock}"
repeats="${REPEATS:-3}"

./gradlew --console=plain -q :engine-kotlin:server:installDist
rm -f "$socket"
engine-kotlin/server/build/install/server/bin/server --uds "$socket" >/dev/null 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; rm -f "$socket"' EXIT

for _ in $(seq 1 100); do
  [ -S "$socket" ] && break
  sleep 0.1
done

cd trainer-python

echo "════════════════════════════════════════════════════════════════════"
echo " (a)(b)(c)(d) torch 단독"
echo "════════════════════════════════════════════════════════════════════"
uv run python -m pika_trainer.bench_train

echo
echo "════════════════════════════════════════════════════════════════════"
echo " (e) 종단 한 반복 — ${repeats}회 (JIT 워밍업이 보이도록 같은 서버를 재사용한다)"
echo "════════════════════════════════════════════════════════════════════"
for i in $(seq 1 "$repeats"); do
  echo "[$i/$repeats]"
  uv run python -m pika_trainer.bench_train \
    --target "unix://$socket" --sizes 512 --hiddens 128 --threads 4 --skip-mps \
    | sed -n '/(e) 종단/,$p'
done
