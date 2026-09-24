# 2026-09-24 — replay-analysis (ROADMAP Phase 4)

경기를 컴팩트한 리플레이로 기록하고, **재생으로 검증한 뒤** MySQL 에 적재해 통계를 뽑고, 브라우저에서
원본 그래픽으로 재생하는 작업. 같은 경기 러너에 FSM · 사람을 꽂아 라이브 대전도 한다.

| 문서 | 답하는 질문 |
|---|---|
| [`PRD.md`](PRD.md) | 무엇을 만족해야 완료인가 |
| [`plan.md`](plan.md) | 어떻게 할 것인가 (판단과 근거) |
| [`tasks.md`](tasks.md) | 무엇부터 할 것인가 (P1~P8 체크리스트 · 결과) |

이 작업의 본질은 뷰어가 아니라 **"재생된 경기가 실제로 치러진 경기와 같다" 는 증명**이었다. Kotlin 이
치른 경기를 브라우저의 JS 가 다시 계산해 보여 주므로, 두 계산이 한 프레임이라도 갈라지면 뷰어는
존재하지 않은 경기를 보여 준다. 그래서 브라우저에 싣기 전에 JS 규칙층을 Node 에서 프레임별 체인
해시로 먼저 증명했다 (M4-b) — 첫 시도에 골든 12게임 전부 일치했고, 규칙을 일부러 망가뜨려
(boldness 고정 제거, 리셋 순서 뒤집기) 테스트가 그것을 잡는지도 확인했다.

## 결과

| ID | 지표 | 목표 | 결과 |
|---|---|---|---|
| M4-a | Kotlin 왕복 | 3,200게임 100% | ✅ 전부 재생 검증 후 적재 |
| M4-b | JS ≡ Kotlin 체인 해시 | 골든 100% | ✅ 12게임 · 135,903 프레임 |
| M4-c | FSM vs FSM DB 집계 | 799/800, 11,998:4,169 | ✅ 전 필드 `GameEvaluator` 와 일치 |
| M4-d | Track A DB 집계 = 평가 리포트 | 정확히 일치 | ✅ 3시드 × 양 진영 (프레임까지 Phase 3 `eval-final.json` 과 동일) |
| M4-e | 렌더링 격리 | 체인 동일 | ✅ 구름 · 파도 RNG + `punchEffectRadius` 가드, 대조군 2개 |
| M4-f | 기록 꺼짐 무영향 | 골든 불변, ≥ 95% | ✅ 골든 무변경, M2-a 550,499 (113.7%) |
| M4-g | 리플레이 크기 | ≤ 4 KB / ≤ 256 B | ✅ Track A 1.7 ~ 2.0 KB, FSM vs FSM 129 B |
| M4-h | 뷰어 확인 | 체크리스트 | ✅ headless Chrome 6게임, 60,000 프레임 시크 ≈ 50 ms |
| M4-i | 회귀 | 초록 | ✅ gradlew build · pytest · npm test |
| M4-j | 입력원 교체 동치 | 체인 3개 일치 | ✅ 라이브 6판 → JS 재생 · Kotlin 재생 |
| M4-k | 사람 vs FSM 라이브 | 수동 확인 | ✅ 실제 키 이벤트 (사람 손으로 한 판은 남음) |

## 계획과 달라진 것

1. **`replay_frame_cap` (proto 15)** — Python 의 `max_game_frames` 를 그대로 내려보내 미결 게임과
   잘린 리플레이의 짝을 보장한다. 값의 출처를 하나로 둔다.
2. **파워히트 정의** — `isPowerHit` 의 false → true 가 아니라 **터치 직후 `isPowerHit`**. 엔진이 충돌마다
   덮어쓰므로 파워히트 맞받아치기(true → true)를 전환 기준은 놓친다.
3. **업스트림 뷰가 물리를 쓴다** — `drawPlayersAndBall` 의 `ball.punchEffectRadius -= 2`. 계획은 RNG 만
   걱정했다. `viewer-web/src/view/guard.mjs` 가 막고, 대조군 테스트가 그 필요를 보여 준다.
4. **스키마** — `match_set.kind` 에 `'live'`, 참가자 유일 키는 `identity` 열 (MySQL `UNIQUE` 는 NULL 끼리
   겹쳐도 통과한다), 테스트 DB `pika_test`.
5. **라이브 원본 보존** — DB 는 캐시인데 라이브는 다시 만들 수 없다. `serve` 가 `runs/live/` 에도 남긴다.

## 기준선이 보여 준 것

| | FSM vs FSM | Track A vs FSM (seed 0 / 1 / 2) |
|---|---|---|
| 랠리 평균 | 756.8 프레임 · 터치 25.1 | 122.2 / 104.1 / 107.5 · 터치 4.1 ~ 4.5 |
| 파워히트 성공률 | 9.5% | 정책 50.8% · FSM 0% |
| 착지 x 가 양 끝 48px 안 | 70.4% | 100% / 100% / 51.6% |

정책 파워히트가 세 시드 모두 정확히 23,600회 — 정책 서브 랠리 11,600 × 2 + FSM 서브 랠리 400 × 1.
**Track A 는 모든 랠리를 파워히트 1~2번으로 끝내는 고정 패턴을 찾았다.**

## 재현

```bash
docker compose -f deploy/compose/docker-compose.yml up -d mysql
FRESH=1 scripts/baseline-replays.sh     # 약 1분. 3,200게임 replay_sha256 집합 digest 59a765e3…4334
cd viewer-web && npm test               # M4-b · M4-e · M4-j (upstream/ 필요)
```

뷰어 확인 스크린샷은 업스트림 그래픽이 들어 있어 커밋하지 않았다 (`runs/viewer-check/`).
