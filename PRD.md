# PRD — rl-env-grpc (ROADMAP Phase 2)

## 1. 개요

Phase 1 이 동치성을 증명한 `pika.core` 위에 **관측·행동·보상·라운드 관리**를 얹어 RL 환경을
만들고, **배치 스텝 gRPC** 로 Python 학습기와 연결한다.

이 작업의 본질은 "환경 만들기" 가 아니라 **경계 설계**다. 엔진은 이미 증명되어 있으므로
남은 위험은 전부 엔진 바깥에 있다 — 무엇을 보여줄지(관측), 무엇을 시킬지(행동),
언제 끝내는지(에피소드), 어느 쪽에서 재는지(진영), 그리고 그것을 Python 에 얼마나 싸게
건네는지(RPC). 여기서 잘못 고르면 Phase 3~5 의 숫자는 **다른 문제의 정답**이 된다.

## 2. 배경

조사·측정으로 확인한 것만 적는다. 측정 절차와 원시 수치는 `plan.md` §2 에 있다.

### 2.1 엔진은 병목이 아니다

| 입력 생성기 | 단일 스레드 처리량 | 측정 조건 |
|---|---|---|
| uniform | **9,874,117 step/s** | 2.4M 프레임, 워밍업 후, M-series 8코어 |
| biased | **8,138,657 step/s** | 〃 |
| fsm | **8,223,771 step/s** | 〃 |

배치 N=256 일 때 목표 50,000 env-step/s 의 스텝당 예산은 `256 / 50000 = 5.12 ms` 이고,
그중 엔진이 쓰는 시간은 `256 / 8.1e6 = 31.6 µs` — **예산의 0.6%** 다.
→ 처리량 목표는 엔진이 아니라 관측 인코딩·직렬화·RPC 왕복·Python 정책 forward 가 정한다.

### 2.2 좌우 진영이 대칭이 아니다

`PhysicsEngine.kt:420-425` 의 좌우 벽 반사 조건이 비대칭이다.

```kotlin
if (futureBallX < BALL_RADIUS /* 20 */ || futureBallX > GROUND_WIDTH /* 432 */) { ... }
```

네트 중심 216 기준으로 대칭이라면 오른쪽 조건은 `> 412` 여야 한다. 실제 공의 가동 폭은
`[20, 432]` 이고 그 중심은 **226** 이다. 플레이어 가동 범위는 p1 `[32,184]` · p2 `[248,400]`
으로 216 대칭이지만 **공은 아니다.** 원작 그대로 두기로 한 결정은 Phase 1 에 기록되어 있다
(대칭으로 고치면 `expectedLandingPointXWhenPowerHit` 의 루프가 끝나지 않는 사례가 관측됨).

측정된 귀결 — FSM vs FSM, 800 게임 / 16,167 랠리:

| 항목 | 값 |
|---|---|
| p1(왼쪽) 총 득점 | **11,998** |
| p2(오른쪽) 총 득점 | **4,169** |
| p1 게임 승리 | **799 / 800** (첫 서브를 p2 에게 줘도 400/400) |

→ **진영은 교락변수(confounder)다.** 평가는 반드시 양 진영에서 하고 따로 보고해야 한다.

### 2.3 랠리 길이가 상대에 따라 17배 달라진다

| 상대 | 랠리 평균 | p50 | p90 | p99 | 최대 | 15점 게임 평균 |
|---|---|---|---|---|---|---|
| FSM vs FSM | **758.9** | 568 | 1,531 | 2,821 | 6,011 | 15,275 프레임 (≈10분) |
| biased 무작위 | 84.7 | 66 | 173 | 320 | 691 | 2,229 |
| uniform 무작위 | 44.8 | 38 | 78 | 146 | 273 | 1,163 |

→ 에피소드 = 랠리로 두면 학습 초반(랜덤 정책, ~45프레임)과 vs FSM 후반(~760프레임)의
에피소드 길이가 17배 차이난다. **truncation 이 선택이 아니라 필수**이고, 롤아웃 버퍼는
에피소드 경계가 아니라 고정 horizon 으로 잘라야 한다.

### 2.4 `powerHit` 엣지 트리거는 입력 장치의 성질이지 엔진의 성질이 아니다

- `upstream/src/resources/js/keyboard.js:71-77` — 키가 **눌리는 순간**에만 `powerHit = 1`.
- `letComputerDecideUserInput` (`PhysicsEngine.kt:268`) 은 `keyboard.js` 를 거치지 않고
  `PikaUserInput.powerHit` 에 직접 쓴다. **엣지 규칙을 지키지 않는다.**

측정 — FSM 1.2M 프레임:

| | powerHit=1 프레임 | 그중 직전 프레임도 1 |
|---|---|---|
| p1 | 30,576 (2.55%) | **17,908 (58.6%)** |
| p2 | 19,747 (1.65%) | **8,456 (42.8%)** |

→ FSM 에 엣지 변환을 적용하면 그 입력의 절반가량이 사라진다. 그것은 **Phase 1 이 증명한
동치성을 깨는 행위**다. 엣지 변환은 에이전트 쪽에만 적용한다.

### 2.5 라운드 종료 규칙

- 득점 판정: `ball.punchEffectX < GROUND_HALF_WIDTH` (`pikavolley.js:374`).
  `punchEffectX` 는 착지 시 `ball.x` 로 세팅된다 (`PhysicsEngine.kt:462`). 즉 착지 x 다.
- **득점한 쪽이 다음 서브** (`pikavolley.js:375-394`). 승점 15.
- 업스트림은 착지 후에도 물리를 **6프레임 더 돌린다** (`slowMotionFramesLeft = 6`,
  `pikavolley.js:397`). 그 6프레임도 RNG 를 소비한다. 차분 테스트 하네스는 즉시 리셋한다.

### 2.6 FSM 은 RNG 를 소비한다

`letComputerDecideUserInput` 의 `rand() % 20` · `rand() % 2`, `decideWhetherInputPowerHit`
진입 시의 `rand() % 2`. → **상대가 FSM 이냐 정책이냐에 따라 난수 스트림이 갈라진다.**
리플레이 재현 키는 `(seed, 입력 시퀀스)` 가 아니라 `(seed, 입력 시퀀스, 상대 구성)` 이다.

### 2.7 Gymnasium 1.x 는 next-step autoreset 이다

Gymnasium 1.x `VectorEnv` 는 `terminated`/`truncated` 가 True 인 **다음 스텝**에 리셋하고,
`final_observation` / `final_info` 는 제거되었다 (공식 문서 `api/vector/` 확인, 1.3.0 기준).
서버가 same-step autoreset 으로 구현하면 PPO 의 부트스트랩이 **조용히** 틀어진다.

### 2.8 의존성 버전 (조회 시점 2026-09-22)

| | 최신 |
|---|---|
| gymnasium / grpcio / torch / numpy | 1.3.0 / 1.84.0 / 2.14.0 / 2.5.3 |
| grpc-java / protobuf-java | 1.84.0 / 4.36.2 |
| grpc-kotlin-stub | **1.4.3** — grpc-java 대비 지연 (`plan.md` §7.2) |

### 2.9 FSM 은 에이전트보다 반 프레임 최신 정보를 본다

에이전트의 입력은 `runEngineForNextFrame` **호출 전**에 정해지고(프레임 t−1 종료 상태 기준),
FSM 은 그 **안에서** 공이 이미 움직인 뒤에 결정한다 (`PhysicsEngine.kt:29-46, 127-130`).
최대 20px 의 정보 우위다. 원작의 구조이므로 고치지 않되, **Track A 승률을 순수한 실력 차로
읽으면 정책을 과소평가**하게 된다. 근거와 귀결은 `plan.md` §2.6.

## 3. 요구사항

### 3.1 기능 요구사항

| ID | 요구사항 |
|---|---|
| FR-1 | `pikavolley.js` 의 경기 규칙(득점·서브권·승점 15·게임 종료)을 `env` 에 포팅한다. 물리는 `core` 를 그대로 쓴다 |
| FR-2 | 관측은 정책 시점(egocentric)으로 구성하고, 상대의 은닉 상태(`computerBoldness`)와 렌더링 전용 필드를 포함하지 않는다 |
| FR-3 | 진영 미러링을 제공하되, **비대칭이 존재한다는 사실(§2.2)을 감추지 않는다** — 미러링 on/off 가 설정 가능하고 관측에 진영 플래그를 넣을 수 있다 |
| FR-4 | 행동 공간은 `Discrete(18)` = (xDirection 3) × (yDirection 3) × (powerHit 2) |
| FR-5 | 에이전트의 `powerHit` 은 엣지 트리거로 변환한다. **FSM 입력에는 적용하지 않는다** |
| FR-6 | 보상은 종단 보상(랠리 승/패)과 셰이핑 항을 분리 계산하고, 항별 값을 `info` 로 함께 내보낸다. 셰이핑 가중치 기본값은 0 |
| FR-7 | 에피소드는 랠리 단위다. 랠리 종료는 `terminated`, `maxRallyFrames` 초과는 `truncated` |
| FR-8 | 상대는 슬롯으로 추상화한다 — FSM 슬롯(엔진 내장)과 외부 정책 슬롯(Python 이 행동을 제공)을 모두 지원한다 |
| FR-9 | 벡터 환경은 Gymnasium 1.x 의 **next-step autoreset** 규약을 따른다 |
| FR-10 | `(seed, 행동 시퀀스, 구성)` 이 같으면 관측·보상 바이트가 완전히 동일하다 |
| FR-11 | 배치 스텝 gRPC 서버(`Configure` · `Reset` · `Step` · `Health`)를 제공한다. 관측/행동은 packed `bytes` 로 전송한다 |
| FR-12 | Python `env_client.py` 가 `gymnasium.vector.VectorEnv` 를 구현한다 |
| FR-13 | 평가 경로는 같은 정책을 **양 진영에서** 돌리고 진영별 승률을 따로 보고한다 |
| FR-14 | Track B 용 설정에서 FSM 코드 경로가 실행되지 않음을 런타임 불변식으로 강제한다 |
| FR-15 | `docker compose` 로 엔진 서버를 띄우고 Python 이 붙는다 |

### 3.2 비기능 요구사항

| ID | 요구사항 |
|---|---|
| NFR-1 | `core` 의 외부 의존성 0 (Phase 1 의 `checkNoDependencies`) 을 유지한다. gRPC·protobuf 는 `server` 에만 |
| NFR-2 | `env` 는 gRPC 를 모른다. 서버 없이 JVM in-process 로 벤치·테스트가 가능하다 |
| NFR-3 | 처리량 측정은 **세 지점을 분리**한다 — (a) env 단독 (b) gRPC 루프백 + 더미 정책 (c) Python 종단. 병목을 특정할 수 없는 단일 숫자는 쓰지 않는다 |
| NFR-4 | `./gradlew build` 는 Node·upstream 없이 통과한다 (Phase 1 의 골든 회귀 포함) |
| NFR-5 | 관측 레이아웃은 `proto/` 에 단일 정의하고 Kotlin·Python 양쪽 테스트가 그 파일과 대조한다 (State Spec 과 같은 방식) |

## 4. 완료 조건 (Exit Criteria)

| ID | 지표 | 목표치 |
|---|---|---|
| **M2-a** | Python ↔ Kotlin 종단 처리량 (더미 정책, N=256, UDS) | **≥ 50,000 env-step/s** |
| **M2-b** | `env` 단독 처리량 (관측 인코딩 포함, JVM in-process, 단일 스레드) | **≥ 1,000,000 step/s** |
| **M2-c** | Gymnasium 규약 | `check_env` 통과 + next-step autoreset 동작 테스트 통과 |
| **M2-d** | 결정론 — 같은 `(seed, 행동 시퀀스, 구성)` 재실행 시 관측·보상 바이트 | **완전 일치**. 서버 재시작 후·벡터 크기 변경 후에도 |
| **M2-e** | FSM vs FSM 베이스라인 재현 (800 게임) | p1 승 **799/800**, 득점 11,998 : 4,169 (§2.2) |
| **M2-f** | Phase 1 골든 회귀 | `./gradlew build` 초록 유지 |
| **M2-g** | Track B 차단 | FSM 미사용 구성에서 `letComputerDecideUserInput` 호출 횟수 **0** 임을 테스트가 증명 |

**M2-d · M2-f · M2-g 는 타협하지 않는다.**
- M2-d 가 깨지면 Phase 5 의 두 트랙 비교와 Phase 6 의 리플레이가 성립하지 않는다.
- M2-f 가 깨지면 Phase 1 의 증명이 무효가 된다. **골든을 재생성하는 것은 해결이 아니다.**
- M2-g 가 깨지면 Track B 의 "zero" 주장이 무너진다. 사람의 주의가 아니라 코드가 막아야 한다.

M2-b 의 근거: 엔진만으로 8.1M step/s (§2.1) 이므로, 관측 인코딩이 엔진보다 8배 느려도
통과하는 느슨한 기준이다. 이 조건의 목적은 성능 경쟁이 아니라 **경계 비용을 분리 측정**하는
것이다. M2-a 와 M2-b 의 간극이 곧 RPC 계층이 먹는 비용이다.

## 5. 산출물

```
proto/
  obs_spec.proto            관측 레이아웃 단일 정의 (NFR-5)
  env.proto                 gRPC 서비스 계약
engine-kotlin/
  core/                     XorShift32 이관 (plan.md §3.1) 외 변경 없음
  env/                      경기 규칙 · 관측 · 행동 · 보상 · 벡터 환경 · 벤치
  server/                   gRPC 서버, packed bytes 직렬화
  conformance/              (변경 없음 — 골든 회귀 유지)
trainer-python/
  src/pika_trainer/
    env_client.py           Gymnasium VectorEnv 구현
    obs_spec.py             obs_spec.proto 파서 (레이아웃 대조용)
  tests/
    test_env_client.py      규약 · 결정론
deploy/compose/
  docker-compose.yml
  engine-server.Dockerfile
scripts/
  bench-env.sh              세 지점 처리량 측정 (NFR-3)
```

## 6. 범위 밖 (Out of Scope)

**이 작업에서 하지 않는다.**

- PPO 구현, 학습 루프, 커리큘럼, 셰이핑 가중치 **튜닝** → Phase 3.
  단, 셰이핑 항의 **계산과 노출**은 범위 안이다 (FR-6). Phase 3 이 가중치만 올리면 되도록 둔다.
- 셀프플레이 리그, 상대 풀 관리, 체크포인트 샘플링 → Phase 4.
  단, 외부 정책 슬롯(FR-8) 은 범위 안이다. 리그가 그 위에 얹힐 자리를 만든다.
- MySQL 스키마, 경기 통계 적재, 리플레이 인코딩, `viewer-web` → Phase 6.
  단, **리플레이 재현 키가 `(seed, 입력, 상대 구성)` 이라는 사실(§2.6)은 지금 문서화**한다.
  나중에 알면 이미 저장한 리플레이를 못 쓴다.
- k3s, 수평 확장, ONNX rollout → Phase 7.
- 엔진의 멀티스레드/벡터화 → **하지 않는다.** §2.1 이 불필요함을 보였다.
  M2-a 가 미달이면 원인은 엔진이 아니므로 엔진을 건드리는 것은 오진이다.
- 좌우 비대칭(§2.2) **수정** → 하지 않는다. 원작을 바꾸는 것이고 Phase 1 의 전제를 깬다.
  이 작업은 비대칭을 **측정 가능하게 드러내는 것**까지만 한다 (FR-3, FR-13, M2-e).

## 7. 리스크

| 리스크 | 대응 |
|---|---|
| **진영 비대칭을 모르고 한쪽에서만 평가** → 승률이 실력이 아니라 진영을 잰다 | FR-13 · M2-e. 평가 경로가 양 진영을 강제하고 진영별로 따로 보고 |
| **엣지 변환을 FSM 에도 적용** → Phase 1 동치성 파괴 | FR-5. FSM 입력은 엔진 내부에서 생성되므로 래퍼가 닿지 않는 구조로 둔다 (`plan.md` §5.2) |
| Gymnasium next-step autoreset 오구현 → PPO 부트스트랩이 조용히 틀어짐 | M2-c 에 autoreset 전용 테스트. `plan.md` §8.3 에 타이밍 표 |
| 관측에 은닉 정보(`computerBoldness`) 유입 → vs FSM 성적이 부풀고 셀프플레이에서 붕괴 | FR-2. `proto/obs_spec.proto` 를 단일 정의로 두고 양쪽 테스트가 대조 (NFR-5) |
| 무한 랠리 (두 정책이 모두 잘해짐) | FR-7 truncation. 기본 3,000 프레임 — 측정된 FSM p99(2,821) 위 (§2.3) |
| 처리량 미달인데 원인을 못 찾음 | NFR-3 세 지점 분리 측정. 단일 숫자는 진단 가치가 0 |
| XorShift32 를 `core` 로 옮기다 골든 해시가 깨짐 | M2-f. 이동은 P1 에서 단독 커밋하고 즉시 `./gradlew build` 로 확인 |
| gRPC 가 예산(5.12 ms/배치) 을 못 맞춤 | UDS 우선, unary → bidi 스트리밍 전환 경로를 `plan.md` §7.3 에 준비 |
| Track B 에서 실수로 FSM 이 섞임 | M2-g. 타입 수준 분리 + 런타임 카운터 (`plan.md` §9) |
| **FSM 의 반 프레임 정보 우위**(§2.9) 를 모르고 Track A 승률을 실력 차로만 해석 | 고치지 않는다(원작 구조). 사실을 `ROADMAP.md` 에 기록하고 Phase 3 결과 보고에 첨부 |
