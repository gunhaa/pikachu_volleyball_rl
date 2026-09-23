#!/usr/bin/env bash
# 체크포인트를 게임 단위로 평가한다. (FR-7, M3-a, M3-b / plan.md §9)
#
#   scripts/eval-policy.sh runs/track-a-seed1/ckpt-final.pt
#   scripts/eval-policy.sh --random                       # 기준선 (M3-g)
#   GAMES=400 BOLDNESS=1 scripts/eval-policy.sh runs/.../ckpt.pt
#
# ⚠️ 평가는 **자기 서버 프로세스**를 띄운다 (plan.md §9.3). 서버는 단일 테넌트라서
#    학습 서버에 Configure 하면 그 자리에서 학습 세션이 죽는다. 이미 떠 있는 전용 서버를
#    쓰려면 PIKA_ENV_TARGET 을 준다.
#
# 환경 변수
#   GAMES     진영별 게임 수 (기본 400 = M3-a 의 요구치)
#   NUM_ENVS  벡터 크기 (기본 64. 절반이 오른쪽 진영이다)
#   SEED      서버 base_seed (기본 0)
#   MODE      argmax(기본) | sample
#   BOLDNESS  1 이면 boldness 0~4 진단 축도 돈다 (FR-13)
#   JSON      리포트를 저장할 경로
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

games="${GAMES:-400}"
num_envs="${NUM_ENVS:-64}"
seed="${SEED:-0}"
mode="${MODE:-argmax}"

args=(--games "$games" --num-envs "$num_envs" --seed "$seed" --mode "$mode")

if [ "$#" -eq 0 ]; then
  echo "사용법: $0 <체크포인트.pt> | --random" >&2
  exit 2
fi
if [ "$1" = "--random" ]; then
  args+=(--random)
else
  [ -f "$1" ] || { echo "체크포인트가 없습니다: $1" >&2; exit 2; }
  args+=(--checkpoint "$(cd "$(dirname "$1")" && pwd)/$(basename "$1")")
fi
shift

[ "${BOLDNESS:-0}" = "1" ] && args+=(--boldness)
[ -n "${JSON:-}" ] && args+=(--json "$JSON")
[ -n "${PIKA_ENV_TARGET:-}" ] && args+=(--target "$PIKA_ENV_TARGET")

# 서버를 직접 띄우는 경로면 배포본이 최신이어야 한다 (evaluate.py 가 installDist 를 돌린다).
cd trainer-python
exec uv run python -m pika_trainer.evaluate "${args[@]}" "$@"
