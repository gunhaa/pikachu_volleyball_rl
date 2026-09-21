# PRD — engine-port (ROADMAP Phase 0~1)

## 1. 개요

저장소 기반을 세우고, 업스트림 `physics.js` 를 Kotlin 으로 포팅해
**JS 원본과 동치임을 증명**한다.

이후 모든 RL 학습이 이 엔진 위에서 돌아간다. 엔진이 원본과 다르면
학습 결과 전체가 "원본과 다른 게임"의 결과가 되므로, 이 작업의 본질은
**포팅이 아니라 동치성 증명**이다.

## 2. 배경

- 업스트림: `gorisanson/pikachu-volleyball` — 1997년 원작을 리버스 엔지니어링한 JS 구현
- 고정 커밋: `0d04dbaf165e4131e26f27f6e9def766f62260b3`
- `PikaPhysics.runEngineForNextFrame(userInputArray)` 는 PixiJS/DOM 의존성이 없어
  headless 실행이 가능하다
- `rand.js` 의 `setCustomRng()` 로 RNG 를 주입하면 완전 결정론이 확보된다
- `physics.js` 는 전부 32비트 정수 연산이다 (검증 완료 — `plan.md` §2)

## 3. 요구사항

### 3.1 기능 요구사항

| ID | 요구사항 |
|---|---|
| FR-1 | 업스트림을 고정 커밋으로 받아오는 스크립트를 제공한다 |
| FR-2 | 업스트림 코드와 원작 에셋이 저장소에 커밋되지 않는다 |
| FR-3 | README 에 출처·저작권 고지와 비상업 학습 목적을 명시한다 |
| FR-4 | Kotlin 멀티모듈과 Python 프로젝트가 빌드·테스트된다 |
| FR-5 | 상태 벡터 명세(State Spec)를 `proto/` 에 단일 정의하고 JS·Kotlin 이 함께 참조한다 |
| FR-6 | JS 오라클이 `(시드, 입력 시퀀스)` 로 프레임별 상태를 덤프한다 |
| FR-7 | Kotlin `core` 가 `physics.js` 와 동일한 상태 전이를 계산한다 |
| FR-8 | 결정론적 정수 PRNG 를 JS·Kotlin 양쪽에 비트 단위 동일하게 구현한다 |
| FR-9 | 차분 테스트가 상태 해시로 전수 비교하고, 불일치 시 최초 불일치 프레임과 필드를 특정한다 |
| FR-10 | `physics.js` 분기 커버리지를 측정하고 미도달 분기에 표적 케이스를 추가한다 |
| FR-11 | 에피소드는 고정 T 프레임이며, 랠리 종료 시 하네스가 라운드를 리셋해 계속 진행한다 |

### 3.2 비기능 요구사항

| ID | 요구사항 |
|---|---|
| NFR-1 | Kotlin `core` 는 외부 의존성 0 (순수 함수) |
| NFR-2 | 전수 차분 검사(약 4.2M 프레임)가 로컬에서 분 단위로 완료. CI 는 축소 샘플 골든 회귀로 분리 |
| NFR-3 | 불일치 발생 시 최소 재현 케이스가 단위 테스트로 남는다 |

## 4. 완료 조건 (Exit Criteria)

| ID | 지표 | 목표치 |
|---|---|---|
| **M1-a** | Kotlin ↔ JS 상태 해시 일치율 (7,000 에피소드 / 약 4.2M 프레임, `plan.md` §6.3) | **100%** |
| **M1-b** | JS 오라클 실행 시 `physics.js` 분기 커버리지 | **≥ 95%** |
| M0 | `./gradlew build` · `pytest` 통과, `fetch-upstream.sh` 동작 | 통과 |

**M1-a 는 타협하지 않는다.** 불일치 1건이라도 남으면 이 작업은 완료가 아니다.

### 달성 결과 (2026-09-21)

| ID | 목표치 | 결과 |
|---|---|---|
| **M1-a** | 100% | ✅ **100%** — 4,202,280 프레임 / 불일치 0건 / 18.0초 (엄격 모드도 0건) |
| **M1-b** | ≥ 95% | ✅ **100%** — 문장·분기·함수·줄 전부 |
| M0 | 통과 | ✅ `./gradlew build` · `pytest` · `fetch-upstream.sh` |

NFR-3(최소 재현 케이스 보존) 은 **보존할 대상이 생기지 않았다.** 불일치가 0건이었다.
장치 자체는 구현되어 있고 no-op 엔진 상대로 검증된 상태다.

## 5. 산출물

```
.gitignore                     (완료)
README.md                      출처·저작권 고지
scripts/fetch-upstream.sh      고정 커밋 클론
proto/state_spec.proto         상태 벡터 명세
engine-kotlin/
  core/                        물리 엔진 포팅
  conformance/                 차분 테스트 하네스
  build.gradle.kts, settings.gradle.kts
trainer-python/
  pyproject.toml               (골격만)
tools/js-oracle/               Node 오라클 스크립트 + 커버리지
.github/workflows/ci.yml       build + test
```

## 6. 범위 밖 (Out of Scope)

다음은 **이 작업에서 하지 않는다.** ROADMAP Phase 2 이후로 미룬다.

- 관측/행동/보상 설계 및 `env` 모듈
- gRPC 서버 및 Python 클라이언트
- PPO, 학습 루프, Track A/B
- MySQL 스키마, 분석, 리플레이 뷰어
- docker-compose / k3s 구성
- `pikavolley.js` 의 라운드·점수 관리 이식
  (→ Phase 2. 이 작업은 `physics.js` 프레임 단위 동치성에만 집중한다.
   단, 차분 테스트 하네스가 쓰는 최소 라운드 리셋은 예외 — `plan.md` §6.2)

## 7. 리스크

| 리스크 | 대응 |
|---|---|
| 정수 연산 의미 불일치 (`\| 0`, 음수 나눗셈, 오버플로) | `plan.md` §3 규칙 준수, `Int` 강제 |
| **무작위 입력이 얕다** (랠리 평균 49.5프레임, 실측) | 배분 재조정 + 편향 무작위 + 표적 케이스 (`plan.md` §6) |
| 무작위 입력이 희귀 분기에 미도달 | 커버리지 측정 + 표적 케이스 (FR-10) |
| 불일치 위치 특정 불가 | lockstep 프레임별 해시 비교 — 첫 불일치 즉시 특정 (`plan.md` §5) |
| 업스트림 커밋 변경으로 오라클이 흔들림 | 커밋 해시 고정 (FR-1) |
