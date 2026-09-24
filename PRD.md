# PRD — 분석 + 리플레이 + 경기 러너 (ROADMAP Phase 4)

## 1. 개요

학습된 정책과 FSM 의 경기를 **컴팩트한 리플레이로 기록하고**, MySQL 에 적재해 통계를 뽑고,
브라우저에서 **원본 게임 그래픽으로 재생**한다. 기준선 두 벌 — FSM vs FSM, Track A vs FSM —
을 먼저 만들어 Phase 6·7 의 셀프플레이를 눈으로 디버깅할 도구로 쓴다.

브라우저의 경기 러너는 **슬롯마다 입력원을 꽂는 구조**다. 리플레이는 "입력원이 기록된 바이트인 경기" 이고,
같은 러너에 FSM · 사람을 꽂으면 라이브 경기가 된다. 정책(가중치) 입력원은 Phase 5 가 같은 자리에 꽂는다.

**이 작업의 본질은 뷰어가 아니라 "재생된 경기가 실제로 치러진 경기와 같다" 는 증명이다.**
Kotlin 이 치른 경기를 브라우저의 JS 가 다시 계산해 보여 준다. 두 계산이 한 프레임이라도
갈라지면 뷰어는 **존재하지 않은 경기**를 보여 주고, 그것은 뷰어가 없는 것보다 나쁘다.

## 2. 배경

조사로 확인한 사실. 근거는 `plan.md` §2 에 파일·줄과 함께 있다.

1. **경기를 만드는 경로가 둘이고 RNG 규약이 다르다.**
   - `GameEvaluator.playGame` — 게임당 `XorShift32(seed)` **하나**. M2-e(FSM vs FSM 799/800)가 이 경로다.
   - `PikaEnv` — **랠리마다** `deriveSeed(baseSeed, envIndex, rallyCounter)` 로 다시 시드. Python 평가
     (`evaluate.py`, Track A 2,400게임)가 이 경로다. `rallyCounter` 는 게임을 넘어 계속 증가한다.
   → 리플레이 형식은 **두 규약을 모두** 담아야 한다. 하나로 통일하면 M2-e 수치가 재현되지 않는다.
2. **FSM 의 입력은 저장할 필요가 없다.** FSM 은 엔진 안에서 RNG 로 결정하므로 `(시드, 외부 입력)`
   만 있으면 다시 나온다 (Phase 1 이 FSM 동치까지 증명). FSM vs FSM 한 게임의 리플레이는 **헤더뿐**이다.
3. **JS 물리는 이미 증명된 오라클이다.** `upstream/.../physics.js` 가 Phase 1 의 정답이었고
   `tools/js-oracle` 에 RNG 주입(`xorshift32.mjs`)과 상태 해시(`spec.mjs`)가 있다.
   브라우저에서 같은 파일을 돌리면 물리 동치는 새로 증명할 필요가 없다. **새로 증명할 것은 그 위의
   경기 규칙층**(득점·서브·시드 재설정·truncation·boldness 고정)뿐이다.
4. **⚠️ 업스트림 렌더러가 물리와 같은 RNG 를 소비한다.** `cloud_and_wave.js` 는 구름·파도를 그릴 때
   전역 `rand()` 를 부른다 (17~20, 70~71, 83, 87행). 원작 그대로 붙이면 **그리는 행위가 경기를 바꾼다.**
5. **업스트림은 라이선스가 없다** (`package.json` 의 `UNLICENSED`, `scripts/fetch-upstream.sh` 머리말).
   뷰어는 받아 둔 `upstream/` 을 빌드 시점에 참조하고, 결과물을 커밋·배포하지 않는다.
6. **크기.** Track A vs FSM 게임 ≈ 15 랠리 × 122.3 프레임 ≈ **1,835 프레임** → 외부 슬롯 1개 × 1바이트
   ≈ 1.8 KB. FSM vs FSM 게임 ≈ 15,275 프레임이지만 입력 0 바이트 → 헤더 수십 바이트.
   기준선 전체(800 + 2,400게임) ≈ **4.5 MB**. 저장 공간은 문제가 아니다.

## 3. 요구사항

### 3.1 기능 요구사항

| ID | 요구사항 |
|---|---|
| FR-1 | 리플레이 형식 v1: `(시드 규약, 시드 목록, 경기 설정, 외부 슬롯 입력, 랠리별 결과)`. 두 시드 규약(게임당 / 랠리당)을 모두 표현한다 |
| FR-2 | Kotlin `ReplayRecorder` — `PikaEnv` 와 `GameEvaluator` 양쪽에서 경기를 기록한다. **기본은 꺼짐** |
| FR-3 | Kotlin `ReplayPlayer` — 리플레이를 다시 돌려 프레임별 상태를 낸다. 기록된 랠리별 결과와 대조한다 |
| FR-4 | 외부 입력은 **엣지 변환 후** 값으로 저장한다. 재생기는 `EdgeTrigger` 를 몰라도 된다 |
| FR-5 | 끝나지 않는 게임 — 기록 상한(60,000 프레임, `MAX_GAME_FRAMES` 와 같은 값)을 넘기면 `ended = false` 로 잘라서 내보낸다 |
| FR-6 | gRPC: `ConfigureRequest.record_replays`, `FetchReplays` RPC. Python `evaluate.py --record-replays <dir>` 가 **집계에 들어간 게임만** 파일로 쓴다 |
| FR-7 | 참가자 식별 — 리플레이의 "상대 구성" 은 슬롯 종류(FSM/External)에 더해 External 슬롯의 **체크포인트 SHA-256** 을 담는다 (Phase 2 기록 3번, M6-d) |
| FR-8 | MySQL 8.4 스키마: 경기 묶음 · 참가자 · 게임(리플레이 BLOB) · 랠리 · 통계. compose 에 서비스 추가 |
| FR-9 | `analysis ingest` — 리플레이 파일을 **재생으로 검증한 뒤** 적재한다. 검증 실패는 적재하지 않고 실패로 끝난다 |
| FR-10 | `analysis baseline-fsm` — FSM vs FSM 800게임을 `GameEvaluator` 규약으로 생성·기록·적재한다 |
| FR-11 | 통계: 랠리 길이 분포, 착지 지점 분포(x, 8px 구간), 파워히트 성공률. **전부 리플레이에서 파생**하며 재계산 가능하다 |
| FR-12 | JS 재생 코어 — 브라우저와 Node 가 **같은 모듈**을 쓴다. 업스트림 `physics.js` + 경기 규칙층 |
| FR-13 | `viewer-web` — 경기 목록(묶음·진영·승패 필터), 재생(재생/정지·속도·임의 프레임 이동·랠리 점프), 통계 화면 |
| FR-14 | 렌더링 RNG 격리 — 구름·파도는 물리와 **다른** RNG 를 쓴다 |
| FR-15 | `analysis serve` — 뷰어용 HTTP API. 읽기(목록 · 리플레이 바이트 · 통계 JSON) + 쓰기 1개(라이브 경기 제출, FR-18) |
| FR-16 | `GameRunner` + `InputSource` — 러너는 슬롯에 무엇이 꽂혔는지 모른다. 구현: `ReplaySource` · `FsmSource` · `KeyboardSource` · `ScriptedSource`(테스트용) |
| FR-17 | 시드 공급원도 교체 가능하다 — 기록된 목록(리플레이) / 새 난수(라이브). 라이브는 랠리 규약으로 매 랠리 시드를 뽑아 기록한다 |
| FR-18 | 라이브 경기는 **같은 리플레이 형식**으로 기록되어 `ingest` 와 같은 검증을 거쳐 적재된다 |
| FR-19 | 대전 설정 화면 — 좌·우 슬롯에 입력원을 고른다 (리플레이 선택 시 두 슬롯이 함께 정해진다). 정책은 Phase 5 까지 비활성 |

### 3.2 비기능 요구사항

| ID | 요구사항 |
|---|---|
| NFR-1 | 기록이 꺼져 있으면 동작·결정론·처리량이 Phase 3 와 같다. 골든은 **한 바이트도** 바뀌지 않는다 |
| NFR-2 | `env` 는 여전히 외부 의존성 0. 리플레이 형식·기록·재생은 `env` 에, DB·HTTP 는 `analysis` 에만 둔다 |
| NFR-3 | 뷰어의 임의 프레임 이동은 처음부터 다시 계산한다. 최악(60,000 프레임) ≤ 100 ms |
| NFR-4 | 업스트림 파일·에셋과 뷰어 빌드 결과물은 커밋하지 않는다. 뷰어는 로컬 전용이다 |
| NFR-5 | DB 는 127.0.0.1 에만 묶는다. 서버와 같은 원칙 (인증 없음) |

## 4. 완료 조건 (Exit Criteria)

| ID | 지표 | 목표치 |
|---|---|---|
| M4-a | Kotlin 왕복: 실제 경기 → 리플레이 → 재생의 랠리별 (득점자, 프레임 수) · 최종 점수 일치 | **100%** — FSM vs FSM 800게임 + Track A vs FSM 2,400게임 |
| M4-b | **JS 재생 ≡ Kotlin 재생**: 프레임별 상태 체인 해시 | **100% 일치** — 골든 세트 (§plan 7.3), 두 시드 규약 · truncation · boldness 고정 · 미완 게임 포함 |
| M4-c | FSM vs FSM 기준선을 **DB 에서** 다시 집계 | M2-e 와 같다: 왼쪽 **799/800**, 득점 **11,998 : 4,169** |
| M4-d | Track A vs FSM 기준선을 DB 에서 다시 집계 | 시드별 `evaluate.py` 리포트와 승수·득점·랠리·프레임이 **정확히** 같다 (3시드 × 양 진영 × 400) |
| M4-e | 렌더링 격리: 그리면서 재생한 체인 해시 = 그리지 않고 재생한 체인 해시 | 일치 |
| M4-f | 기록 꺼짐의 무영향: Phase 1 골든 615 · env 골든 11 불변, M2-a 처리량 | 골든 불변, 처리량 Phase 2 대비 ≥ 95% |
| M4-g | 리플레이 크기 (게임당 평균) | Track A vs FSM ≤ 4 KB, FSM vs FSM ≤ 256 B |
| M4-h | 뷰어: 두 기준선에서 임의 게임을 열어 끝까지 재생, 화면 최종 점수 = DB 점수 · 임의 프레임 이동 ≤ 100 ms | 수동 확인 체크리스트 통과 (스크린샷 첨부) |
| M4-i | 회귀 | `./gradlew build` · pytest · `viewer-web` 테스트 초록 |
| M4-j | **입력원 교체 동치**: `ScriptedSource` 로 치른 라이브 경기 → 기록 → `ReplaySource` 재생 · Kotlin `ReplayPlayer` 재생 | 세 체인 해시 일치 (FSM 조합 포함) |
| M4-k | 사람 vs FSM 라이브를 브라우저에서 한 게임 치르고 제출 → DB 적재 → 뷰어 재생 | 수동 확인 (M4-h 체크리스트에 포함) |

**M4-a · M4-b · M4-e · M4-j 는 타협 불가다.** 한 게임이라도 어긋나면 그 원인을 찾기 전까지 뷰어를
"완료" 라고 부르지 않는다. 골든을 다시 뜨는 것으로 해결하지 않는다 — Phase 1 의 규칙 그대로다.

## 5. 산출물

```
proto/env.proto                          + record_replays, FetchReplays
engine-kotlin/env/.../replay/            Replay · ReplayCodec · ReplayRecorder · ReplayPlayer
engine-kotlin/env/golden/replay/         골든 리플레이 + Kotlin 체인 해시 (M4-b 기준)
engine-kotlin/server/                    기록 배선, FetchReplays
engine-kotlin/analysis/                  (신규) ingest · baseline-fsm · stats · serve · MySQL
deploy/compose/docker-compose.yml        + mysql:8.4.11
deploy/mysql/init/001_schema.sql
trainer-python/.../evaluate.py           + --record-replays, --participant
trainer-python/.../env_client.py         + record_replays, fetch_replays
viewer-web/                              (신규) src/runner (GameRunner · 규칙층) · src/sources (입력원) · src/view · test/
scripts/baseline-replays.sh              기준선 두 벌 생성 · 적재
```

## 6. 범위 밖 (Out of Scope)

| 항목 | 미루는 곳 |
|---|---|
| 학습 **중** 주기 평가의 리플레이 적재 | Phase 6 (M6-c). 이 작업은 그것이 쓸 API(`--record-replays`, `ingest`)까지 만든다 |
| 정책 vs 정책 경기 (Kotlin 평가) | Phase 6. 단, 스키마와 형식은 External 슬롯 두 개를 **지금** 지원한다 (FR-7) |
| 정책 입력원 (`PolicySource`, ONNX, JS `ObsEncoder`) | Phase 5. 이 작업은 그것이 꽂힐 `InputSource` 인터페이스까지 만든다 |
| 온라인(원격) 대전 · 관전 | 하지 않는다. 한 브라우저 · 한 키보드 안의 로컬 대전만 |
| 타격 지점 2D 분포, 행동 분포 등 플레이 스타일 지표 | Phase 8. 통계가 리플레이에서 파생되므로 나중에 **재계산만** 하면 된다 |
| 원작 슬로모션 6프레임 · 소리 · 메뉴 · 인트로 | 하지 않는다. 뷰어는 `env` 규칙을 따른다 (Phase 2 기록 2번) |
| MLflow · 인증 · 외부 배포 · k3s | Phase 9 이후. 뷰어는 업스트림 라이선스 때문에 **배포 대상이 아니다** |
| 스키마 마이그레이션 도구 (Flyway 등) | 도입하지 않는다. DB 는 리플레이 파일에서 재생성 가능하다 (plan §6.3) |

## 7. 리스크

| 리스크 | 대응 |
|---|---|
| JS 규칙층이 Kotlin `PikaGame` 과 미묘하게 갈라진다 (득점 판정 `punchEffectX`, 게임 종료 플래그, 리셋 순서) | 프레임별 체인 해시로 전수 비교 (M4-b). 규칙층을 **먼저** 만들고 뷰어를 나중에 붙인다 |
| 렌더러가 물리 RNG 를 소비 | RNG 격리 (FR-14) + 전용 테스트 (M4-e) |
| 끝나지 않는 게임이 기록 버퍼를 무한히 키운다 | 60,000 프레임 상한에서 잘라 `ended = false` (FR-5) |
| Python 이 세지 않은 게임(할당량 초과 행)의 리플레이가 섞인다 | 리플레이에 `(envIndex, 환경 내 게임 번호)` 를 싣고 Python 이 **집계한 게임만** 고른다 (FR-6). M4-d 가 검증 |
| Pixi 6 은 구버전이다 (현재 7.4.3) | `view.js` 가 6 API 를 쓴다. 업스트림과 같은 6.5.10 으로 고정한다. 올리면 `view.js` 를 고쳐야 하므로 하지 않는다 |
| MySQL Connector/J 는 GPLv2 + Universal FOSS Exception | 로컬 분석 도구로만 쓰고 배포하지 않는다. 문제가 되면 MariaDB Connector/J(LGPL) 로 바꿀 수 있다 — JDBC 라서 코드 변경은 드라이버 한 줄이다 |
| FSM 을 일반 입력원처럼 다뤄 `decide` 를 부른다 → RNG 소비 순서가 깨진다 | FSM 은 `kind = 'fsm'` 으로 physics 생성만 바꾸고 러너가 `decide` 를 **부르지 않는다**. M4-j 가 FSM 조합을 포함한다 |
| 브라우저 키 이벤트는 테스트할 수 없다 | 라이브 경로는 `ScriptedSource` 로 검증하고(M4-j), `KeyboardSource` 는 `PikaKeyboard` 를 감싸는 얇은 층으로만 둔다 |
| 기록이 켜진 평가가 느려진다 | 평가만의 비용이다. 오버헤드를 측정해 기록한다 (학습 처리량에는 영향 없음, NFR-1) |
