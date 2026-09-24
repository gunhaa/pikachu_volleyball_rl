# tasks — 분석 + 리플레이 + 경기 러너 (ROADMAP Phase 4)

관심사 단위로 나눈다. 여기의 P1~P8 은 이 작업 안의 단계이고 ROADMAP 의 Phase 번호와 무관하다.

**의존 관계**
```
P1 ─▶ P2 ─┬─▶ P3 ─┐
          │        ├─▶ P5 ─▶ P6 ─▶ P7 ─▶ P8
          └─▶ P4 ─┘
```

## P1. 리플레이 형식과 Kotlin 왕복

> 경기를 바이트로 적고 다시 같은 경기로 돌린다. (FR-1~5, M4-a 단위판, NFR-1 · NFR-2)

- [x] `env/replay/Replay.kt` — 데이터 클래스, `SeedMode { GAME, RALLY }` (`plan.md` §3.1)
- [x] `ReplayCodec` — 인코드 / 디코드, magic · version 검사, 알 수 없는 버전은 예외
- [x] `ReplayRecorder` — begin/frame/endRally/endGame, 상한 60,000 에서 `ended = false` (§4.1)
- [x] `ReplayPlayer` — 두 시드 규약, `PikaGame` 재사용, 기록된 랠리 결과와 대조 (§4.2)
- [x] `PikaEnv` 배선 — `recorder` 가 null 이면 기존 경로 그대로. autoreset 스텝은 기록하지 않는다 (§3.3)
- [x] `GameEvaluator.playGame` 배선 — 선택 인자 `recorder`
- [x] `VectorEnv` — 끝난 게임 큐 `(envIndex, gameInEnv, Replay)`
- [x] 테스트: 코덱 왕복 (바이트 → 객체 → 바이트 동일)
- [x] 테스트: `PikaEnv` 기록 → 재생 일치 (무작위 External, 진영 좌·우)
- [x] 테스트: `GameEvaluator` FSM vs FSM 기록 → 재생 일치
- [x] 테스트: truncation (`maxRallyFrames = 200`), boldness 고정, 미완 게임 (cap = 500)
- [x] 테스트: 기록을 켜도 관측 · 보상 바이트 동일
- [x] **확인**: `./gradlew :engine-kotlin:env:test` 초록, env 골든 11 · Phase 1 골든 615 불변

## P2. JS 경기 러너 · 입력원 · 교차 언어 동치

> 브라우저가 돌릴 코드가 Kotlin 과 같은 경기를 계산함을 **Node 에서** 증명한다. 리플레이와 라이브가 같은 러너다. (FR-12, FR-14, FR-16~18, M4-b, M4-e, M4-j)

- [x] `engine-kotlin/analysis` 모듈 골격 (`settings.gradle.kts`, `env` · `core` · `conformance` 의존)
- [x] `analysis golden-replays` — 골든 세트 생성 (`plan.md` §7.3) + Kotlin 체인 해시 `chains.json` (1,000 프레임마다 중간값)
- [x] 골든 커밋: `engine-kotlin/env/golden/replay/` (합계 ≥ 100,000 프레임)
- [x] `viewer-web/` 골격 — `package.json` (Node 테스트만 먼저), `@upstream` 경로
- [x] `src/runner/codec.mjs` — 인코더 · 디코더 (라이브 기록에도 쓴다)
- [x] `src/runner/runner.mjs` — `GameRunner`: 규칙층을 `PikaGame.kt` 줄 대응 주석과 함께 옮긴다 (§5.1).
      boldness 고정 · 게임 종료 시 `isWinner` / `gameEnded` 세팅 포함 (§2.3)
- [x] `InputSource` 인터페이스 + `ReplaySource` · `FsmSource` · `ScriptedSource` (§5.1.1). FSM 은 `decide` 를 부르지 않는다
- [x] 시드 공급원 `RecordedSeeds` · `FreshSeeds` (§5.1.2)
- [x] `src/runner/recorder.mjs` — 라이브 경기를 v1 형식으로 기록 (Kotlin `ReplayRecorder` 와 같은 바이트)
- [x] 매 `step()` 시작에 `setCustomRng(physicsRng)` (§5.2)
- [x] `test/conformance.mjs` — 골든 전부 재생, 체인 대조, 첫 불일치 프레임 · 필드 출력
- [x] `test/isolation.mjs` — 프레임 사이에 `rand()` 를 부르는 가짜 렌더러를 끼워도 체인 동일 (M4-e)
- [x] `test/live.mjs` — `ScriptedSource` 라이브 → 기록 → JS 재생 · Kotlin 재생, 세 체인 일치. FSM 조합 포함 (M4-j, §5.4)
- [x] Gradle 테스트에서 Node 호출 (`JsOracleTest` 방식, Node 없으면 **실패**)
- [x] **확인**: `npm test` (viewer-web) · `./gradlew build` 초록, 골든 100% 일치, 라이브 동치 통과

## P3. 기록 배관 — server 와 Python

> 평가가 치른 경기를 파일로 남긴다. (FR-6, FR-7)

- [x] `proto/env.proto` — `record_replays = 14`, `FetchReplays`, `RecordedGame` (§7.1)
      + `replay_frame_cap = 15` (계획에 없던 추가: Python `max_game_frames` 를 그대로 내려보내 미결 게임과 잘린 리플레이의 짝을 보장)
- [x] `EnvService` — 설정 반영, `FetchReplays` 는 큐를 비운다, 세션 검사
- [x] `gen-python-proto.sh` 재생성
- [x] `env_client.py` — `EnvOptions.record_replays`, `fetch_replays()`
- [x] `evaluate.py` — `ReplaySink`: 센 게임만 `counted = true`, 미결은 `unresolved = true`, `manifest.jsonl` (§3.2, §7.2)
- [x] `--record-replays <dir>` · `--set-name` CLI, 체크포인트 SHA-256 을 manifest 에
- [x] 테스트: 행 번호 ↔ `envIndex` 짝, 할당량 초과 게임 제외, 파일 수 = 센 게임 수
- [x] 테스트: 기록을 켠 평가의 리포트 = 끈 평가의 리포트 (`to_dict()` `==`)
- [x] **확인**: pytest 초록, `EnvServiceTest` 초록

## P4. MySQL 과 적재

> 리플레이를 검증한 뒤에만 DB 에 넣는다. (FR-8, FR-9, NFR-5)

- [x] `deploy/compose` — `mysql:8.4.11`, 127.0.0.1:3306, 볼륨, healthcheck, `init/` 마운트
- [x] `deploy/mysql/init/001_schema.sql` — `plan.md` §6.2
      (보탬: `match_set.kind` 에 `'live'`, 참가자 유일 키는 `identity` 열 — MySQL UNIQUE 는 NULL 끼리 겹쳐도 통과한다. `000_databases.sql` 로 테스트 DB `pika_test`)
- [x] Connector/J 9.3.0 → `libs.versions.toml`, `analysis` 에만
- [x] `analysis` 시작 시 `schema_version` 대조
- [x] `ingest <dir>` — 재생 검증 · Kotlin 체인 계산 · 트랜잭션, `replay_sha256` 중복은 건너뜀
- [x] 테스트: 변조된 리플레이(입력 1바이트 변경)는 적재되지 않고 전체 롤백
- [x] 테스트: 같은 디렉터리를 두 번 적재해도 행 수 불변
- [x] **확인**: `docker compose up -d mysql` → ingest → 행 수 확인

## P5. 통계 파생

> 랠리 · 착지 · 파워히트를 리플레이에서 계산한다. (FR-11)

- [x] `StatsCollector` — `ReplayPlayer` 프레임 콜백 (`plan.md` §6.4 정의 그대로)
      ⚠️ 파워히트만 정의를 바꿨다: `isPowerHit` false→true 가 아니라 **터치 직후 `isPowerHit`**. 엔진이 충돌마다 덮어쓰므로 파워히트를 파워히트로 받아치면(true→true) 전환 기준은 놓친다. 착지 x 는 432 도 나온다 — 마지막 구간에 포함
- [x] `rally` · `power_hit` 적재를 `ingest` 에 연결
- [x] `rebuild-stats [--set]`
- [x] 테스트: 손으로 만든 짧은 시나리오 — 파워히트 성공 1 · 실패 1 (상대 터치) · 착지 x 값
- [x] 테스트: 통계의 랠리 수 · 프레임 합 = 게임 행의 값
- [x] **확인**: `rebuild-stats` 전후 통계 테이블 동일

## P6. 기준선 두 벌

> FSM vs FSM 과 Track A vs FSM 을 적재하고 기존 수치와 대조한다. (FR-10, M4-a, M4-c, M4-d, M4-g)

- [ ] `analysis baseline-fsm --games 800 --base-seed 0` — `GameEvaluator.evaluate` 와 같은 시드 · 서브 배치
- [ ] **M4-c**: DB 집계 = 왼쪽 799/800, 득점 11,998 : 4,169
- [ ] Track A 체크포인트 SHA-256 기록 (`runs/track-a-seed{0,1,2}/ckpt-final.pt`)
- [ ] `evaluate.py --record-replays` × 3시드 (진영별 400)
- [ ] **M4-d**: DB 집계 = 각 시드 `evaluate.py` 리포트 (승수 · 득점 · 랠리 · 프레임 정확히)
- [ ] **M4-a**: 3,200게임 전부 재생 검증 통과 (ingest 로그)
- [ ] **M4-g**: 게임당 평균 크기 — Track A ≤ 4 KB, FSM vs FSM ≤ 256 B
- [ ] 평가 기록 오버헤드 측정 (기록 켬 / 끔 시간 비)
- [ ] `scripts/baseline-replays.sh` — 위 과정을 한 번에, 결정론적으로 재생성
- [ ] **확인**: 스크립트를 빈 DB 에서 다시 돌려 `replay_sha256` 집합 동일

## P7. 뷰어와 라이브 대전

> 브라우저에서 임의 경기를 원본 그래픽으로 재생하고, 같은 화면에서 사람 · FSM 라이브 대전을 한다. (FR-13, FR-15, FR-19, M4-h, M4-k)

- [ ] `analysis serve` — `/api/sets`, `/api/games?set&side&winner&unresolved`, `/api/games/{id}/replay`, `/api/stats/{set}`
- [ ] Vite 8.3.0 + `@pixi/*` 6.5.10, `@upstream` alias, `/api` 프록시
- [ ] 에셋 로딩 — `ASSETS_PATH.SPRITE_SHEET` 덮어쓰기, `main.js` 의 렌더러 설정 따르기 (§8.2)
- [ ] `GameView` 생성 전 · `drawCloudsAndWave` 전 `setCustomRng(viewRng)` (§5.2)
- [ ] 재생기: 재생/정지, 속도, 시크바(그리지 않고 계산), 랠리 점프, 현재 점수 · 랠리 · 프레임 표시
- [ ] 로드 시 체인 대조, 불일치면 경고 배너 (§8.2)
- [ ] 목록 화면, 통계 화면 (SVG)
- [ ] `KeyboardSource` — `PikaKeyboard` 를 감싼다, 두 사람 키 배치는 업스트림 기본값
- [ ] 대전 설정 화면 — 좌·우 입력원 선택 (리플레이 · FSM · 사람, 정책은 비활성 표시)
- [ ] 라이브 종료 시 `POST /api/live-games` → 서버가 `ingest` 와 같은 검증 후 적재 (§8.2)
- [ ] **M4-k**: 사람 vs FSM 한 게임 → 제출 → 목록에 나타남 → 재생 시 체인 경고 없음
- [ ] **M4-h**: 두 기준선에서 임의 게임 각 3개 — 끝까지 재생, 최종 점수 = DB, 60,000 프레임 게임 시크 ≤ 100 ms. 스크린샷 첨부
- [ ] **확인**: `npm run build` 성공, 결과물이 `.gitignore` 대상인지 확인 (NFR-4)

## P8. 회귀 · 문서 · 이관

- [ ] **M4-f**: Phase 1 골든 · env 골든 불변, `bench-env.sh` M2-a ≥ Phase 2 의 95%
- [ ] **M4-i**: `./gradlew build` · pytest · `npm test` 초록
- [ ] `README.md` — 리플레이 · 뷰어 실행법
- [ ] `ROADMAP.md` Phase 4 결과 + "이후 Phase 가 반드시 알아야 하는 것"
- [ ] `history/<완료일>-replay-analysis/` 로 세 문서 이관 + README

---

## 완료 조건 요약

| ID | 지표 | 목표치 | Phase |
|---|---|---|---|
| M4-a | Kotlin 왕복 | 3,200게임 100% | P1 · P6 |
| M4-b | JS ≡ Kotlin 체인 해시 | 골든 100% | P2 |
| M4-c | FSM vs FSM DB 집계 | 799/800, 11,998:4,169 | P6 |
| M4-d | Track A DB 집계 = 평가 리포트 | 정확히 일치 | P6 |
| M4-e | 렌더링 RNG 격리 | 체인 동일 | P2 |
| M4-f | 기록 꺼짐 무영향 | 골든 불변, 처리량 ≥ 95% | P8 |
| M4-g | 리플레이 크기 | ≤ 4 KB / ≤ 256 B | P6 |
| M4-h | 뷰어 수동 확인 | 체크리스트 | P7 |
| M4-i | 회귀 | 초록 | P8 |
| M4-j | 입력원 교체 동치 (라이브 → 기록 → 재생) | 체인 3개 일치 | P2 |
| M4-k | 사람 vs FSM 라이브 제출 · 재생 | 수동 확인 | P7 |
