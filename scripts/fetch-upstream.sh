#!/usr/bin/env bash
#
# 업스트림 pikachu-volleyball 를 고정 커밋으로 받아온다.
#
# 업스트림은 라이선스가 부여되지 않았으므로(LICENSE 없음, package.json 은 UNLICENSED)
# 저장소에 커밋하지 않고 이 스크립트로만 재현한다. 커밋 해시를 박아두므로
# 재현성은 submodule 과 동등하다. (plan.md §7)
#
# 멱등: 이미 올바른 커밋이 체크아웃되어 있으면 아무것도 하지 않는다.

set -euo pipefail

UPSTREAM_REPO="https://github.com/gorisanson/pikachu-volleyball.git"
UPSTREAM_COMMIT="0d04dbaf165e4131e26f27f6e9def766f62260b3"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/upstream"

# 오라클이 반드시 필요로 하는 파일 — 받아온 트리의 무결성 확인용
REQUIRED_FILES=(
  "src/resources/js/physics.js"
  "src/resources/js/rand.js"
)

log() { printf '[fetch-upstream] %s\n' "$*"; }
die() { printf '[fetch-upstream] ERROR: %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git 이 필요합니다."

# ── 1. 이미 올바른 상태인가 ────────────────────────────────
if [ -d "$DEST/.git" ]; then
  current="$(git -C "$DEST" rev-parse HEAD 2>/dev/null || echo "")"
  if [ "$current" = "$UPSTREAM_COMMIT" ]; then
    log "이미 고정 커밋 상태입니다: ${UPSTREAM_COMMIT:0:12}"
    exit 0
  fi
  log "다른 커밋(${current:0:12})이 체크아웃되어 있습니다. 고정 커밋으로 맞춥니다."
else
  [ -e "$DEST" ] && die "$DEST 가 git 저장소가 아닙니다. 직접 지운 뒤 다시 실행하세요."
  log "업스트림을 새로 받아옵니다."
  git init --quiet "$DEST"
fi

# ── 2. 고정 커밋만 가져온다 ────────────────────────────────
git -C "$DEST" remote remove origin 2>/dev/null || true
git -C "$DEST" remote add origin "$UPSTREAM_REPO"

# GitHub 는 SHA 지정 fetch 를 지원한다. 전체 히스토리를 받지 않아 빠르다.
if ! git -C "$DEST" fetch --quiet --depth 1 origin "$UPSTREAM_COMMIT" 2>/dev/null; then
  log "SHA 지정 fetch 실패. 전체 히스토리로 대체합니다."
  git -C "$DEST" fetch --quiet origin
fi

git -C "$DEST" checkout --quiet --detach "$UPSTREAM_COMMIT"

# ── 3. 검증 ───────────────────────────────────────────────
head="$(git -C "$DEST" rev-parse HEAD)"
[ "$head" = "$UPSTREAM_COMMIT" ] || die "체크아웃된 커밋이 다릅니다: $head"

for f in "${REQUIRED_FILES[@]}"; do
  [ -f "$DEST/$f" ] || die "필수 파일이 없습니다: upstream/$f"
done

log "완료: ${UPSTREAM_COMMIT:0:12} → $DEST"
