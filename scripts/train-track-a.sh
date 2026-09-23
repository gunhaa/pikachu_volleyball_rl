#!/usr/bin/env bash
# Track A 학습 — 랜덤 초기화 정책이 FSM(컴퓨터)을 이기게 한다. (ROADMAP Phase 3 / plan.md §5, §6)
#
#   scripts/train-track-a.sh                       # 본 학습 (5천만 step, 시드 0)
#   SEED=1 scripts/train-track-a.sh                # 시드만 바꿔 재현 (M3-a 는 시드 3개)
#   STEPS=5000000 scripts/train-track-a.sh         # 짧은 런 — 배선 점검용
#   GAMMA=0.99 RUN_ID=ab-g099 scripts/train-track-a.sh   # γ A/B (plan.md §5.2)
#
# ⚠️ JVM 을 **둘** 띄운다. 서버는 단일 테넌트라서 (ConfigureReply.session_id) 평가가 학습
#    서버에 Configure 하면 그 자리에서 학습 세션이 죽는다 (plan.md §12 함정 5).
#    이미 떠 있는 서버를 쓰려면 PIKA_ENV_TARGET 과 PIKA_EVAL_TARGET 을 **둘 다** 준다.
#
# 환경 변수
#   SEED        학습 시드. 이 하나가 torch·numpy·서버 base_seed 를 전부 정한다 (FR-10)
#   STEPS       총 env-step (기본 50,000,000 — NFR-2 기준 벽시계 60분 이내)
#   GAMMA       할인율 (기본 0.997. A/B 축은 {0.99, 0.997, 0.999} — plan.md §5.2)
#   NUM_ENVS    벡터 크기 (기본 512, 절반이 오른쪽 진영)
#   HORIZON     T (기본 128)
#   THREADS     torch 스레드 (기본 4). **재현성의 일부다** — 바꾸면 M3-d 가 깨진다
#   EVAL_EVERY  주기 평가 간격 env-step (기본 2,000,000. 0 이면 끈다)
#   CKPT_EVERY  체크포인트 간격 env-step (기본 5,000,000)
#   FINAL_GAMES 마지막 평가의 진영별 게임 수 (기본 400 = M3-a 의 요구치)
#   BOLDNESS    1 이면 마지막에 boldness 0~4 진단 축도 돈다 (FR-13)
#   RUN_ID      런 디렉터리 이름 (기본 track-a-g<γ>-s<seed>-<날짜시각>)
#   RESUME      이 체크포인트에서 재개 (서버 env 상태는 복원되지 않는다)
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

args=(
  --seed "${SEED:-0}"
  --total-steps "${STEPS:-50000000}"
  --gamma "${GAMMA:-0.997}"
  --num-envs "${NUM_ENVS:-512}"
  --horizon "${HORIZON:-128}"
  --threads "${THREADS:-4}"
  --eval-every "${EVAL_EVERY:-2000000}"
  --ckpt-every "${CKPT_EVERY:-5000000}"
  --final-games "${FINAL_GAMES:-400}"
)

[ -n "${RUN_ID:-}" ] && args+=(--run-id "$RUN_ID")
[ -n "${RESUME:-}" ] && args+=(--resume "$RESUME")
[ "${BOLDNESS:-0}" = "1" ] && args+=(--final-boldness)
[ -n "${PIKA_ENV_TARGET:-}" ] && args+=(--target "$PIKA_ENV_TARGET")
[ -n "${PIKA_EVAL_TARGET:-}" ] && args+=(--eval-target "$PIKA_EVAL_TARGET")

# 런 디렉터리는 저장소 루트의 runs/ 아래에 둔다 (.gitignore — NFR-6).
args+=(--runs-root "$repo_root/runs")

cd trainer-python
exec uv run python -m pika_trainer.track_a "${args[@]}" "$@"
