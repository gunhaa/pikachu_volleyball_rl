#!/usr/bin/env bash
#
# physics.js 의 분기 커버리지를 측정한다. (tasks.md P7 / M1-b)
#
# 커버리지는 "얼마나 테스트했나" 가 아니라 "**어디를 아직 안 봤나**" 를 알려주는 도구다.
# 세 생성기를 각각 돌려 V8 커버리지를 한 디렉터리에 모으고, c8 이 합집합을 낸다.
#
# 사용법
#   scripts/coverage.sh                 # 생성기당 300 시드
#   scripts/coverage.sh 1..1000         # 시드 범위 지정
#
set -euo pipefail

cd "$(dirname "$0")/.."
SEEDS="${1:-1..300}"
FRAMES="${FRAMES:-600}"
TMP="coverage/tmp"
TARGET='upstream/src/resources/js/physics.js'

if [ ! -f "$TARGET" ]; then
  echo "upstream/ 이 없습니다. scripts/fetch-upstream.sh 를 먼저 실행하세요." >&2
  exit 2
fi
if [ ! -d tools/js-oracle/node_modules/c8 ]; then
  echo "c8 이 없습니다. (cd tools/js-oracle && npm install)" >&2
  exit 2
fi

rm -rf coverage
mkdir -p "$TMP"

for gen in uniform biased fsm; do
  printf '  %-8s seeds=%s T=%s ... ' "$gen" "$SEEDS" "$FRAMES"
  NODE_V8_COVERAGE="$TMP" node tools/js-oracle/run.mjs \
    --seeds "$SEEDS" --frames "$FRAMES" --gen "$gen" --mode frame-hash \
    > /dev/null 2>/dev/null
  echo '완료'
done

# (d) 표적 케이스 — 시드 축이 다르다. 케이스 전체를 돈다.
printf '  %-8s cases=all ... ' targeted
NODE_V8_COVERAGE="$TMP" node tools/js-oracle/run.mjs --gen targeted --mode frame-hash \
  > /dev/null 2>/dev/null
echo '완료' 

echo
npx --prefix tools/js-oracle c8 report \
  --temp-directory="$TMP" \
  --include="$TARGET" \
  --reporter=text \
  --reporter=html \
  --reports-dir=coverage

echo
echo "HTML 리포트: coverage/index.html"
