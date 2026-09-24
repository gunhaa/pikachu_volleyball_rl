#!/usr/bin/env bash
# Track A 3시드의 최종 체크포인트를 ONNX 로 내보내고 레지스트리에 등록한다. (Phase 5 P3, M5-d)
#
#   scripts/export-policies.sh              # runs/policies/track-a-seed{0,1,2}.onnx + registry.jsonl
#   GAMES=100 scripts/export-policies.sh    # 자기 검증 관측을 더 모은다 (진영별 게임 수)
#
# **결정론이다.** 같은 체크포인트면 같은 ONNX SHA-256 이 나오고, 다시 돌리면 레지스트리가 그것을 확인한다
# (같은 label · 같은 체크포인트인데 SHA 가 다르면 실패). runs/ 는 커밋 대상이 아니므로 이 스크립트가
# ONNX 의 재현 절차 자체다. 체크포인트 ↔ ONNX SHA 쌍은 ROADMAP 결과 표에도 적는다.
#
# 자기 검증이 하나라도 실패하면 멈춘다 (set -e + export_onnx 의 exit 1). 실패한 ONNX 는 남지 않는다.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

games="${GAMES:-40}"

./gradlew -q :engine-kotlin:server:installDist

for seed in 0 1 2; do
  ckpt="$repo_root/runs/track-a-seed$seed/ckpt-final.pt"
  [ -f "$ckpt" ] || { echo "체크포인트가 없습니다: $ckpt" >&2; exit 2; }
  echo "── track-a-seed$seed"
  (cd trainer-python && uv run python -m pika_trainer.export_onnx "$ckpt" \
      --label "track-a-seed$seed" --games "$games")
done

echo
echo "레지스트리: runs/policies/registry.jsonl"
