# tasks — engine-port (ROADMAP Phase 0~1)

관심사 단위로 Phase 를 나눈다. (여기의 Phase 는 이 작업 내부 구분이며, ROADMAP 의 Phase 0~7 과는 층위가 다르다.)

**의존 관계**

```
P1 저장소 규칙 ─┬─▶ P2 빌드 골격 ─┐
                │                 ├─▶ P4 JS 오라클 ─▶ P5 비교 하네스 ─▶ P6 포팅 ─▶ P7 커버리지 ─▶ P8 마무리
                └─▶ P3 State Spec ┘
```


---

## 진행 현황 (2026-09-21) — **완료**

| Phase | 상태 | 비고 |
|---|---|---|
| P1 저장소 규칙 | ✅ | `fetch-upstream.sh` 멱등 실행 확인, `git status` 깨끗 |
| P2 빌드 골격 | ✅ | `./gradlew build` · `pytest` 통과 (**M0**) |
| P3 State Spec | ✅ | 기본 44필드 / 엄격 52필드 |
| P4 JS 오라클 | ✅ | `selfcheck.mjs` 전부 통과 |
| P5 비교 하네스 | ✅ | 단위 테스트 25건 전부 통과 |
| P6 포팅 | ✅ | **4,202,280 프레임 전수 일치** (**M1-a**) |
| P7 커버리지 | ✅ | `physics.js` **분기 100%** (**M1-b**, 목표 ≥95%) |
| P8 마무리 | ✅ | 골든 회귀 615 에피소드, Node 없이 검증 |

### 최종 측정치

| 항목 | 값 |
|---|---|
| 전수 차분 | 4,202,280 프레임 / **불일치 0건** / 18.0초 |
| 엄격 모드(sound 포함) | 4,200,000 프레임 / **불일치 0건** / 19.9초 |
| lockstep 처리량 | 약 225~240K frame/s (포팅 전 리셋 프로브 94K 에서 개선) |
| `physics.js` 커버리지 | 문장 100% · **분기 100%** · 함수 100% · 줄 100% |
| 골든 회귀 | 615 에피소드 / 48KB / Kotlin 단독 검증 |

### 포팅 결과 — 첫 실행부터 일치했다

`plan.md` §2 의 전제("모든 상태가 32비트 정수라 부동소수 오차가 원천적으로 없다")가
그대로 성립했다. 비-FSM 물리는 첫 lockstep 실행에서 12,000 프레임이 일치했고,
FSM 도 마찬가지였다. **회귀 테스트로 보존할 최소 재현 케이스가 하나도 생기지 않았다** (NFR-3).

포팅을 2스테이지로 나눈 것이 주효했다 (`plan.md` §8 보강).
`uniform`·`biased` 는 `isComputer=false` 라 FSM 경로를 밟지 않으므로,
FSM 을 `TODO()` 스텁으로 둔 채 나머지 물리를 먼저 전수 검증할 수 있었다.
FSM 은 `rand()` 를 소비하므로 버그가 나면 RNG 스트림 전체가 어긋나는데,
비-FSM 을 먼저 고정해 두면 그 불일치의 원인이 FSM 으로 좁혀진다.

### 계획에서 벗어난 결정

앞선 P1~P5 의 결정 4건은 `plan.md` 에 반영되어 있다. P6~P8 에서 추가된 것은 다음과 같다.

5. **`INFINITE_LOOP_LIMIT` 표적 케이스를 만들지 않았다.** 도달 불가능하기 때문이다.
   `plan.md` §6.4 에 근거를 적었다. 밟을 수 없는 분기에 케이스를 만들면
   "케이스는 있는데 아무것도 겨냥하지 않는" 상태가 된다.
6. **표적 케이스를 공유 데이터 파일로 뒀다** (`tools/targeted-cases.txt`).
   `inputs.mjs` ↔ `InputGenerators.kt` 는 로직이라 양쪽에 복제하고 하네스로 대조하지만,
   케이스 표는 데이터라 복제하면 대조할 방법이 없다. (`plan.md` §6.4)
7. **표적 케이스가 실제로 무언가를 겨냥하는지 검사하는 테스트를 추가했다**
   (`TargetedCasesTest`). 커버리지 100% 는 `game_ended` 분기만 증명한다 —
   `hyper-ball-stuck` 의 `fine_rotation` 을 49 로 바꿔도 100% 는 유지된다.
8. **커버리지 도구로 `c8` 를 `tools/js-oracle` 의 devDependency 로 추가했다.**
   `NODE_V8_COVERAGE` 로 세 생성기 + 표적 케이스의 커버리지를 한 디렉터리에 모아
   합집합을 낸다 (`scripts/coverage.sh`).

---

## P1. 저장소 규칙 — 라이선스 대응

> 업스트림 코드와 원작 에셋이 저장소에 들어오지 않게 만든다. (FR-1, FR-2, FR-3)

- [x] `.gitignore` 작성 — `upstream/`, 원작 에셋(파일명 특정), 빌드/학습 산출물
- [x] `scripts/fetch-upstream.sh` — 고정 커밋 `0d04dbaf...` 클론, 멱등 실행
- [x] `README.md` — 출처·저작권 고지, 비상업 학습 목적, 프로젝트 개요, 셋업 절차
- [x] `scripts/fetch-upstream.sh` 실행 후 `git status` 가 깨끗한지 확인

## P2. 빌드 골격

> `gradlew build` 와 `pytest` 가 도는 최소 골격. (FR-4)

- [x] Gradle 멀티모듈 — `settings.gradle.kts`, 루트 `build.gradle.kts` (Kotlin/JVM 21)
- [x] `engine-kotlin/core` 모듈 — **외부 의존성 0** (NFR-1)
- [x] `engine-kotlin/conformance` 모듈 — 테스트 프레임워크(kotest 또는 JUnit5)
- [x] `trainer-python/pyproject.toml` — `uv` 기반 골격, `pytest` 만
- [x] `.github/workflows/ci.yml` — `gradlew build` + `pytest`
- [x] **확인**: `./gradlew build` · `pytest` 통과 (M0)

## P3. State Spec — 비교 대상 정의

> 해시가 같아지려면 직렬화 규약을 양쪽이 공유해야 한다. (FR-5)

- [x] `proto/state_spec.proto` — 필드 목록·순서 고정 (`plan.md` §5.1)
- [x] 직렬화 규약 문서화 — Int 배열 → little-endian 4바이트 → SHA-256
- [x] `boolean` → `0/1` 고정 인코딩 규칙 명시
- [x] `sound` 필드 제외 (기본) / 포함(엄격 모드) 플래그 설계
- [x] 프레임별 해시 — `SHA256(state_n)`, 전수 차분 검사용
- [x] 체인 해시 — `h_n = SHA256(h_{n-1} ‖ state_n)`, CI 골든 회귀용

## P4. JS 오라클

> `(시드, 입력 시퀀스)` → 프레임별 상태를 뱉는 정답 생성기. (FR-6, FR-8)

- [x] `tools/js-oracle/` — Node 스크립트, `upstream/` 의 `physics.js` 를 직접 import
- [x] xorshift32 구현 + `setCustomRng()` 주입
- [x] 입력 시퀀스 포맷 정의 (`xDirection`, `yDirection`, `powerHit` × 2 플레이어 × T 프레임)
- [x] **라운드 자동 리셋** 구현 — 랠리 종료 시 `initializeForNewRound`, 서브권 전환 (`plan.md` §6.2)
- [x] 해시는 SHA-256 앞 **64비트(hex 16자)** 로 절단
- [x] `--mode frame-hash` — 프레임당 해시 한 줄 stdout 스트리밍, `E <seed>` 에피소드 마커
- [x] `--mode full` — State Spec 순서대로 전체 상태 JSON Lines (실패 에피소드 드릴다운용)
- [x] `--seeds 1..N --frames T` — **한 프로세스가 전체 시드 처리** (spawn 오버헤드 회피)
- [x] **확인**: 같은 시드 2회 실행 → 동일 출력 (결정론 자체 검증)

## P5. 비교 하네스

> 2단 비교 구조. 해시로 훑고, 깨지면 위치를 특정한다. (FR-9, NFR-3)

- [x] Kotlin xorshift32 — JS 구현과 **비트 단위 일치** 단위 테스트
- [x] `ProcessBuilder` 로 Node 오라클 기동 + stdout 파이프 스트리밍 읽기
- [x] lockstep 비교 루프 — 프레임별 해시 대조, 첫 불일치에서 `seed`/`frame` 과 함께 실패
- [x] 드릴다운: 실패 시드만 `--mode full` 재실행 → 프레임 F 필드 단위 diff 출력
- [x] 최소 재현 케이스 자동 생성 — `(F-1 상태, F 입력)` → 단위 테스트 코드
- [x] Node 프로세스 비정상 종료·stderr 처리 (오라클 실패를 불일치로 오인하지 않게)
- [x] 입력 생성기 (a) 균일 무작위 / (b) **편향 무작위** / (c) FSM vs FSM (`plan.md` §6.3)
- [x] 하네스 라운드 리셋이 JS 와 **동일한지** 단위 테스트 (하네스 버그를 물리 불일치로 오인 방지)
- [x] `--mode reset-probe` — 엔진 없이 리셋만 대조. 위 테스트를 포팅 전에 성립시키는 수단
- [x] `./gradlew conformance` 실행기 — 전수 검사는 `build` 와 분리 (NFR-2)

## P6. 물리 엔진 포팅

> `plan.md` §8 의 의존 순서대로. 각 단계마다 P5 하네스로 즉시 검증. (FR-7)

- [x] 정수 의미 보존 규칙 확인 — 전 필드 `Int`, `Long` 사용 금지 (`plan.md` §3)
- [x] 상수 + `PikaUserInput` + `Player`/`Ball` 필드·초기화 — **P5 선행**. ⚠️ `initializeForNewRound` 이 `divingDirection`·`lyingDownDurationLeft`·`computerWhereToStandBy`·`isWinner`·`gameEnded` 를 **리셋하지 않는다** (업스트림 생성자 순서)
- [x] `isCollisionBetweenBallAndPlayerHappened`
- [x] `processCollisionBetweenBallAndWorldAndSetBallPosition` — 네트·벽·바닥
- [x] `processPlayerMovementAndSetPlayerPosition` — 점프·다이빙·경직
- [x] `processCollisionBetweenBallAndPlayer` — 타격·파워히트
- [x] `calculateExpectedLandingPointXFor` / `expectedLandingPointXWhenPowerHit`
      — `INFINITE_LOOP_LIMIT` (1000) 동작 일치 확인
- [x] `letComputerDecideUserInput` / `decideWhetherInputPowerHit` — **FSM. 평가 기준이므로 최우선 정확도**
- [x] `processGameEndFrameFor` + `physicsEngine` 통합
- [x] `PikaPhysics.runEngineForNextFrame` — 반환값(`isBallTouchingGround`) 포함 일치 (State Spec 에 이미 들어가 있으므로 자동 검증된다)

## P7. 커버리지 + 표적 케이스

> 무작위가 닿지 않는 분기를 찾아 메운다. (FR-10)

- [x] `physics.js` 분기 커버리지 측정 — `c8` (`scripts/coverage.sh`)
- [x] 미도달 분기 목록 추출 — **미도달 분기는 정확히 하나였다**: `player.gameEnded === true`
      (`physics.js:633`). 물리 엔진은 `gameEnded` 를 스스로 세팅하지 않는다 — 그건
      `pikavolley.js` 의 일이다(ROADMAP Phase 2). 즉 **어떤 입력 시퀀스로도 도달할 수 없다.**
- [x] 표적 케이스 (d) 수동 작성 — `tools/targeted-cases.txt` 에 15건 (`plan.md` §6.4)
  - [x] hyper ball 글리치 (`ball.rotation` 5 고착) — 2건. 고착 지속 프레임 수를 테스트가 센다
  - [x] `INFINITE_LOOP_LIMIT` 도달 — **도달 불가로 확인. 케이스를 만들지 않았다** (`plan.md` §6.4)
  - [x] 네트 기둥 충돌 (`ball.x ≈ 216`) — 윗면 / 왼쪽 옆면 / 오른쪽 옆면 3건
  - [x] 다이빙 경직 중 충돌 (`lyingDownDurationLeft > 0`) — 2건 (p1 / p2)
  - [x] 경기 종료 전이 (`state` 5/6, `gameEnded`) — 3건 (p1 승 / p2 승 / 공중 선언)
  - [x] 좌우 경계 (`ball.x` 20 / 432 부근) — 2건
  - [x] (추가) `fine_rotation` 보정 분기 양쪽 2건, 파워히트 직후 상태 1건
- [x] 표적 케이스가 **실제로 겨냥하는지** 확인하는 테스트 (`TargetedCasesTest`)
- [x] **확인**: 분기 커버리지 **100%** (M1-b, 목표 ≥95%). 문장·함수·줄도 전부 100%

## P8. 마무리

- [x] 전수 실행 — 7,015 에피소드 / **4,202,280 프레임** (`plan.md` §6.3 배분 + 표적 15건)
- [x] 커버리지 재조정 — **불필요**. 300시드 배분으로 이미 100% 라 늘릴 이유가 없었다
- [x] **확인**: 상태 해시 불일치 **0건** (M1-a)
- [x] 엄격 모드(`sound` 포함) 1회 실행 — 4,200,000 프레임 불일치 0건 / 19.9초
- [x] CI 골든 회귀 — 축소 샘플 615 에피소드의 **체인 해시를 커밋**
      (`engine-kotlin/conformance/golden/chain-hashes.txt`). `upstream/` 을 숨기고
      `./gradlew build --rerun-tasks` 가 통과하는 것으로 Node 비의존을 확인했다 (NFR-2)
- [x] 발견된 불일치의 최소 재현 케이스를 회귀 테스트로 보존 — **보존할 것이 없다.**
      불일치가 0건이었다 (NFR-3 의 장치는 구현되어 있고 no-op 엔진 상대로 검증된 상태)
- [x] `plan.md` · `PRD.md` · `tasks.md` → `history/2026-09-21-engine-port/` 이관

---

## 완료 조건 요약

| ID | 지표 | 목표치 | Phase | 결과 |
|---|---|---|---|---|
| M0 | `gradlew build` · `pytest` · `fetch-upstream.sh` | 통과 | P1, P2 | ✅ 통과 |
| **M1-a** | Kotlin ↔ JS 상태 해시 일치율 (7,000 에피소드 / 약 4.2M 프레임) | **100%** | P8 | ✅ **100%** (4,202,280 프레임) |
| **M1-b** | `physics.js` 분기 커버리지 | **≥ 95%** | P7 | ✅ **100%** |

**세 조건 모두 달성. 이 작업은 완료다.**
