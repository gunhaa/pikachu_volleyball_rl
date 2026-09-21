# tasks — engine-port (ROADMAP Phase 0~1)

관심사 단위로 Phase 를 나눈다. (여기의 Phase 는 이 작업 내부 구분이며, ROADMAP 의 Phase 0~7 과는 층위가 다르다.)

**의존 관계**

```
P1 저장소 규칙 ─┬─▶ P2 빌드 골격 ─┐
                │                 ├─▶ P4 JS 오라클 ─▶ P5 비교 하네스 ─▶ P6 포팅 ─▶ P7 커버리지 ─▶ P8 마무리
                └─▶ P3 State Spec ┘
```


---

## 진행 현황 (2026-09-21)

**P1~P5 완료.** 다음은 P6(물리 엔진 포팅)부터다.

| Phase | 상태 | 비고 |
|---|---|---|
| P1 저장소 규칙 | ✅ | `fetch-upstream.sh` 멱등 실행 확인, `git status` 깨끗 |
| P2 빌드 골격 | ✅ | `./gradlew build` · `pytest` 통과 (**M0**) |
| P3 State Spec | ✅ | 기본 44필드 / 엄격 52필드 |
| P4 JS 오라클 | ✅ | `selfcheck.mjs` 전부 통과 |
| P5 비교 하네스 | ✅ | 단위 테스트 25건 전부 통과 |
| P6 포팅 | ⬜ | 1단계(상태 구조)만 선행 — 하네스가 돌아가려면 필요했다 |
| P7 커버리지 | ⬜ | |
| P8 마무리 | ⬜ | |

**하네스 검증 근거** — 엔진 없이도 아래가 증명되었다.

- 라운드 리셋 패리티: 3개 생성기 × 50시드 × 20리셋이 JS 와 **완전 일치** (엄격 모드 포함)
- 불일치 특정: no-op 엔진 상대로 `seed=1 frame=0` 과 **갈라진 필드 6개**를 정확히 지목하고
  최소 재현 케이스 코드까지 생성
- xorshift32 가 JS 와 비트 단위 일치, `rand()` 경로에 부동소수 오차 0

**실측치**

| 항목 | 값 |
|---|---|
| 오라클 단독 처리량 | 222K frame/s |
| lockstep 전체 처리량 | 약 94K frame/s (리셋 프로브 기준) |
| 전수 4.2M 프레임 예상 | 1분 내외 |
| 편향 무작위 효과 | 랠리 44 → 77 프레임, 충돌 프레임 3.7% → 7.0% |

**계획에서 벗어난 결정** (근거는 `plan.md` 에 반영)

1. State Spec 에 `is_ball_touching_ground` **추가** — 라운드 리셋 트리거라 포함해야 불일치 원인이
   흐려지지 않는다. 검사가 더 엄격해질 뿐 느슨해지지 않는다.
2. 오라클에 `--mode reset-probe` **추가** — `initializeForNewRound` 은 엔진과 독립이므로
   포팅 전에 하네스를 검증할 수 있다. P5 의 "라운드 리셋 단위 테스트" 를 실제로 성립시킨 수단.
3. `core` 의 "외부 의존성 0" 은 **빌드 태스크로 강제**하되 `kotlin-stdlib` 는 허용 목록에 둔다.
   언어 런타임이라 JDK 와 같은 지위다.
4. `uv` 를 Homebrew 로 설치했다 (ROADMAP 이 지정한 툴체인, 로컬 전용).

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
- [ ] `isCollisionBetweenBallAndPlayerHappened`
- [ ] `processCollisionBetweenBallAndWorldAndSetBallPosition` — 네트·벽·바닥
- [ ] `processPlayerMovementAndSetPlayerPosition` — 점프·다이빙·경직
- [ ] `processCollisionBetweenBallAndPlayer` — 타격·파워히트
- [ ] `calculateExpectedLandingPointXFor` / `expectedLandingPointXWhenPowerHit`
      — `INFINITE_LOOP_LIMIT` (1000) 동작 일치 확인
- [ ] `letComputerDecideUserInput` / `decideWhetherInputPowerHit` — **FSM. 평가 기준이므로 최우선 정확도**
- [ ] `processGameEndFrameFor` + `physicsEngine` 통합
- [ ] `PikaPhysics.runEngineForNextFrame` — 반환값(`isBallTouchingGround`) 포함 일치 (State Spec 에 이미 들어가 있으므로 자동 검증된다)

## P7. 커버리지 + 표적 케이스

> 무작위가 닿지 않는 분기를 찾아 메운다. (FR-10)

- [ ] `physics.js` 분기 커버리지 측정 — c8 또는 istanbul
- [ ] 미도달 분기 목록 추출
- [ ] 표적 케이스 (d) 수동 작성 — `plan.md` §6.4 목록
  - [ ] hyper ball 글리치 (`ball.rotation` 5 고착)
  - [ ] `INFINITE_LOOP_LIMIT` 도달
  - [ ] 네트 기둥 충돌 (`ball.x ≈ 216`)
  - [ ] 다이빙 경직 중 충돌 (`lyingDownDurationLeft > 0`)
  - [ ] 경기 종료 전이 (`state` 5/6, `gameEnded`)
  - [ ] 좌우 경계 (`ball.x` 20 / 432 부근)
- [ ] **확인**: 분기 커버리지 ≥ 95% (M1-b)

## P8. 마무리

- [ ] 전수 실행 — 7,000 에피소드 / 약 4.2M 프레임 (`plan.md` §6.3 배분)
- [ ] 커버리지 결과에 따라 배분 재조정 (비용이 낮으므로 주저 없이 늘린다)
- [ ] **확인**: 상태 해시 불일치 0건 (M1-a)
- [ ] 엄격 모드(`sound` 포함) 1회 실행 — 결과 기록
- [ ] CI 골든 회귀 — 축소 샘플(약 200 시드)의 **체인 해시를 커밋**, Node 없이 검증 (NFR-2)
- [ ] 발견된 불일치의 최소 재현 케이스를 회귀 테스트로 보존
- [ ] `plan.md` · `PRD.md` · `tasks.md` → `history/<YYYY-MM-DD>-engine-port/` 이관

---

## 완료 조건 요약

| ID | 지표 | 목표치 | Phase |
|---|---|---|---|
| M0 | `gradlew build` · `pytest` · `fetch-upstream.sh` | 통과 | P1, P2 |
| **M1-a** | Kotlin ↔ JS 상태 해시 일치율 (7,000 에피소드 / 약 4.2M 프레임) | **100%** | P8 |
| **M1-b** | `physics.js` 분기 커버리지 | **≥ 95%** | P7 |
