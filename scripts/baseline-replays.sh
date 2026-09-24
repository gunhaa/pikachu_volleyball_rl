#!/usr/bin/env bash
# 기준선 두 벌 — FSM vs FSM 800 게임 + Track A vs FSM 3시드 × 양 진영 400 — 을 기록 · 적재 · 대조한다.
# (Phase 4 P6, FR-10, M4-a · M4-c · M4-d · M4-g)
#
#   scripts/baseline-replays.sh            # runs/baselines/ 에 쓰고 로컬 MySQL 에 적재
#   FRESH=1 scripts/baseline-replays.sh    # DB 볼륨을 지우고 빈 DB 에서 (재생성 결정론 확인용)
#
# **결정론이다.** FSM vs FSM 은 시드만으로, Track A 는 체크포인트 + 시드로 다시 만들어진다.
# runs/ 는 커밋 대상이 아니므로(.gitignore) 이 스크립트가 기준선의 재현 절차 자체다 (plan.md §6.3).
# 체크포인트 SHA-256 은 manifest 에 남는다 — "같은 가중치로 만든 리플레이인가" 를 확인하는 장치.
#
# 대조가 하나라도 어긋나면 멈춘다 (set -e + report --expect 의 exit 1).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

out="${OUT:-runs/baselines}"
compose=(docker compose -f deploy/compose/docker-compose.yml)

if [ "${FRESH:-0}" = "1" ]; then
  "${compose[@]}" rm -sfv mysql >/dev/null
  docker volume rm -f compose_mysql-data >/dev/null
fi
"${compose[@]}" up -d --wait mysql

./gradlew -q :engine-kotlin:analysis:installDist :engine-kotlin:server:installDist
analysis="$repo_root/engine-kotlin/analysis/build/install/analysis/bin/analysis"

rm -rf "$out"
mkdir -p "$out"

echo "── FSM vs FSM 800 게임 (M4-c) ──"
"$analysis" baseline-fsm --games 800 --base-seed 0 --out "$out/fsm-vs-fsm" | tee "$out/fsm-vs-fsm.log"
"$analysis" report --set fsm-vs-fsm-baseline --expect "$out/fsm-vs-fsm/expected.json" | tee "$out/fsm-vs-fsm.report.log"
# M2-e 의 숫자를 직접 본다 — expected.json 도 같은 코드가 만들었으니, 숫자 자체를 한 번 더 박는다.
python3 - "$out/fsm-vs-fsm.report.log" <<'PY'
import json, sys
db = json.loads(open(sys.argv[1]).readline())
left = db["as_left"]
assert (left["wins"], left["games"]) == (799, 800), left
assert (left["points_for"], left["points_against"]) == (11998, 4169), left
print(f"M4-c ✓ 왼쪽 {left['wins']}/{left['games']}, 득점 {left['points_for']}:{left['points_against']}, "
      f"게임당 {db['mean_replay_bytes']:.1f} B")
PY

for seed in 0 1 2; do
  echo "── Track A seed $seed vs FSM, 진영별 400 (M4-d) ──"
  ckpt="$repo_root/runs/track-a-seed$seed/ckpt-final.pt"
  [ -f "$ckpt" ] || { echo "체크포인트가 없습니다: $ckpt" >&2; exit 2; }
  shasum -a 256 "$ckpt" | tee "$out/track-a-seed$seed.ckpt.sha256"
  (cd trainer-python && uv run python -m pika_trainer.evaluate \
      --checkpoint "$ckpt" --games 400 --num-envs 64 --seed "$seed" \
      --record-replays "$repo_root/$out/track-a-seed$seed" --set-name "track-a-seed$seed" \
      --json "$repo_root/$out/track-a-seed$seed.report.json") 2>&1 | tail -5
  "$analysis" ingest "$out/track-a-seed$seed" --kind baseline | tee "$out/track-a-seed$seed.ingest.log"
  "$analysis" report --set "track-a-seed$seed" --expect "$out/track-a-seed$seed.report.json" \
    | tee "$out/track-a-seed$seed.report.log"
done

echo "── 요약 ──"
"$analysis" replay-hashes | tee "$out/replay-hashes.txt"
