# tasks — rl-env-grpc (ROADMAP Phase 2)

관심사 단위로 나눈 구현 체크리스트.
`ROADMAP.md` 의 Phase 와는 층위가 다르다 — 저쪽은 프로젝트 전체의 Phase 2 하나이고,
여기 P1~P9 는 **그 안의 관심사**다.

설명은 `plan.md` 에 있다. 여기서는 참조만 한다.

**의존 관계**

```
P1 기반 ─▶ P2 경기규칙 ─▶ P3 의미론 ─┬─▶ P4 결정론 ─┐
                                      └─▶ P5 처리량(a) ┴─▶ P6 gRPC ─▶ P7 Python ─▶ P8 종단 ─▶ P9 정리
```

---

## P1. 기반 — 모듈과 RNG 이관

> `env` · `server` 모듈을 세우고 `XorShift32` 를 `core` 로 옮긴다. (NFR-1, NFR-2, M2-f)

- [x] `settings.gradle.kts` 에 `:engine-kotlin:env` · `:engine-kotlin:server` 추가
- [x] `engine-kotlin/env/build.gradle.kts` — `core` 만 의존. gRPC·protobuf 금지 (NFR-2)
- [x] `engine-kotlin/server/build.gradle.kts` — `env` + grpc-java + protobuf
- [x] `gradle/libs.versions.toml` 에 grpc-java · protobuf · protobuf-gradle-plugin 버전 추가
      (`plan.md` §7.2 — **grpc-kotlin 은 쓰지 않는다**)
- [x] `XorShift32.kt` 를 `conformance` → `core` 로 이동 (`plan.md` §3.1).
      파일 상단에 "physics.js 의 일부가 아니라 주입하는 결정론 장치" 명시
- [x] `conformance` 의 import 경로 수정
- [x] **단독 커밋**으로 분리한다 — 골든이 깨지면 즉시 되돌릴 수 있게
- [x] **확인**: `./gradlew build` 통과 (골든 체인 해시 615 에피소드 불변 — **M2-f**)
- [x] **확인**: `./gradlew :engine-kotlin:core:checkNoDependencies` 통과 (NFR-1)

## P2. 경기 규칙 — `PikaGame`

> `pikavolley.js` 의 득점·서브권·게임 종료를 옮긴다. (FR-1, M2-e)

- [x] `PikaGame` — `step()` · `startNextRally()` · `scores` · `isPlayer2Serve` · `gameEnded`
      (`plan.md` §4 스케치)
- [x] 득점 판정은 `ball.punchEffectX < GROUND_HALF_WIDTH` (`ball.x` 가 아니다 — §4)
- [x] 리셋 순서 player1 → player2 → ball 고정 (`plan.md` §4.1)
- [x] 슬로모션 6프레임은 재현하지 않는다. **결정과 이유를 코드 주석에** (`plan.md` §4.3)
- [x] `GameEvaluator` — 15점제 게임을 **양 진영에서** 돌리고 `asLeft` · `asRight` · `sideGap`
      (`plan.md` §6.3, FR-13)
- [x] 단위 테스트: 리셋 순서가 바뀌면 실패하는 테스트 (RNG 소비 순서 고정)
- [x] 단위 테스트: 15점 도달 시 `gameEnded` 와 `isWinner` 가 양쪽 플레이어에 세팅
- [x] **확인**: FSM vs FSM 800 게임 (시드 0..399 × 첫서브 2가지) 에서
      p1 승 **799/800**, 득점 **11,998 : 4,169** 재현 — **M2-e** (`plan.md` §2.2)

## P3. 환경 의미론 — 관측 · 행동 · 보상 · 에피소드

> 정책이 보는 것과 하는 것과 받는 것을 정의한다. (FR-2~FR-8)

**관측**

- [x] `proto/obs_spec.proto` — 관측 레이아웃 단일 정의 (`plan.md` §5.1, NFR-5)
      → **40차원**이다. `plan.md` §5.1 의 "합계 41" 은 산술 오류였다 (15+15+6+4).
        필드 목록이 실체이므로 구현을 40 으로 두고 문서의 유도 수치를 고쳤다.
- [x] `ObsEncoder` — float32 배열에 **직접 쓴다.** 박싱·중간 리스트 금지 (`plan.md` §11)
- [x] 정규화 상수를 이름 있는 `const` 로. 매직 넘버 금지
- [x] `computerBoldness` · `computerWhereToStandBy` · 렌더링 전용 필드 **제외** (FR-2)
- [x] `obsIncludeExpectedLanding` 플래그 (기본 **on**, `plan.md` §5.1)
- [x] 테스트: 인코더의 필드 목록이 `obs_spec.proto` 와 일치 (Phase 1 의 `StateSpecTest` 방식)
- [x] 테스트: 관측 어디에도 상대의 은닉 상태가 없음 — 상대 `computerBoldness` 를
      0..4 로 바꿔가며 관측 바이트가 불변임을 확인
- [x] `obs_spec.proto` 주석에 **소비자 목록**을 적는다 — Kotlin · Python · **(장래) JS**
      (`plan.md` §12.3)
- [x] `obs_spec.proto` 주석에 **정규화 상수를 숫자로** 박는다. 다른 언어가 Kotlin 코드를
      읽지 않고도 같은 관측을 만들 수 있어야 한다 (`plan.md` §12.3)

**행동**

- [x] `ActionCodec` — `Discrete(18)` ↔ `(xDirection, yDirection, powerHit)` (FR-4)
- [x] `EdgeTrigger` — 에이전트 슬롯에만. 라운드 리셋에서 초기화 (`plan.md` §5.2, FR-5)
- [x] `edgeTriggerPowerHit` 플래그 (기본 on)
- [x] 테스트: 같은 행동을 연속 입력해도 `powerHit` 이 첫 프레임에만 1
- [x] 테스트: 랠리가 바뀌면 `EdgeTrigger` 가 초기화된다

**미러링**

- [x] `mirrorObservations` (기본 on) — `x → 432−x`, `xVelocity → −`, `divingDirection → −`
- [x] `obsIncludeSideFlag` (기본 **off**, `plan.md` §5.3)
- [x] 미러 축이 근사라는 주석을 코드에 남긴다 (`plan.md` §5.3)
- [x] 테스트: 미러링 on 일 때 p1 슬롯과 p2 슬롯의 관측이 **대칭 상태에서** 같다

**보상과 에피소드**

- [x] `RewardTerms` — `rallyWin` · `ballTouch` · `crossedNet` · `opponentMiss` · `timePenalty`
      (`plan.md` §6.1, FR-6)
- [x] `RewardWeights` 기본값: `rallyWin = 1`, 나머지 **0**
- [x] 항별 값을 `info` 로 함께 내보낸다 (합계만 주지 않는다)
- [x] 랠리 = 에피소드. `terminated` = 랠리 종료, `truncated` = `maxRallyFrames` 초과 (FR-7)
- [x] `maxRallyFrames` 기본 **3,000** (`plan.md` §2.3 근거)
- [x] `Slot` — `Fsm` / `External` (`plan.md` §8.1, FR-8)
      → P2 에서 먼저 만들었다. `PikaGame` 이 생성 시점에 `isComputer` 를 정해야 해서
        경기 규칙보다 뒤에 둘 수가 없었다.
- [x] **확인**: `./gradlew :engine-kotlin:env:test` 통과

## P4. 결정론과 골든 회귀

> 이후 모든 변경을 해시가 감시하게 만든다. (FR-10, M2-d, `plan.md` §10)

- [x] `Configure(baseSeed)` → 환경 i 랠리 k 의 시드 유도. **벡터 크기 독립** (`plan.md` §8.3)
- [x] 고정 행동 시퀀스 생성기 (`XorShift32(seed xor ACTION_SALT)`)
- [x] 관측·보상 체인 해시: `h_n = SHA256(h_{n-1} ‖ obs_n ‖ reward_n ‖ flags_n)`
- [x] 골든 파일 커밋 + `--write-golden` 경로 (Phase 1 과 같은 규약)
- [x] **골든 갱신 시 이유를 커밋 메시지에 적는 규칙**을 파일 주석에 명시 (`plan.md` §10)
- [x] 테스트: 같은 `(seed, 행동 시퀀스, 구성)` 재실행 시 바이트 완전 일치 — **M2-d**
- [x] 테스트: 벡터 크기를 1·4·256 으로 바꿔도 환경 0 의 수열이 불변 — **M2-d**
- [x] 테스트: `[External, External]` 구성에서 `isComputer` 가 양쪽 모두 false — **M2-g**
      (`plan.md` §9 — 불변식으로 센다. `core` 는 건드리지 않는다)
- [x] **확인**: `./gradlew build` 가 Node·`upstream/` 없이 통과 (NFR-4)

## P5. 처리량 (a) — `env` 단독

> gRPC 를 만들기 **전에** 바닥 숫자를 확정한다. (M2-b, NFR-3, `plan.md` §1·§11)

- [x] `VectorEnv` (Kotlin) — N개 `PikaGame` 을 배열로 보유, 배치 스텝
      → P4 에서 먼저 만들었다. "벡터 크기를 바꿔도 환경 0 의 수열이 불변" 을 시험하려면
        벡터 환경이 있어야 했다.
- [x] 버퍼 재사용: 스텝마다 배열 할당 금지
- [x] `EnvBench` — JVM in-process, 워밍업 포함, N 을 바꿔가며 측정
- [x] 결과를 `plan.md` §2.1 의 예산표와 함께 출력 ("예산의 몇 %")
- [x] **확인**: 단일 스레드 ≥ **1,000,000 step/s** — **M2-b**
- [x] **확인**: 엔진 단독(8.1M step/s) 대비 감속 배수를 기록. 10배 넘으면 인코더를 프로파일

## P6. gRPC 계약과 서버

> Python 이 붙을 수 있는 표면을 만든다. (FR-11, `plan.md` §7)

- [x] `proto/env.proto` — `Configure` · `Reset` · `Step` · `Health` (`plan.md` §7.1)
- [x] 관측·행동·보상은 **packed `bytes`** (`repeated float` 금지 — `plan.md` §7.1)
- [x] `Health` 가 obs 레이아웃 해시·버전·누적 처리량을 돌려준다
- [x] `protobuf-gradle-plugin` 으로 Kotlin 측 코드 생성 (grpc-java stub)
- [x] 서버는 **Unix domain socket** 우선, TCP 도 지원 (`plan.md` §7.3)
- [x] 서버는 디스크 상태를 남기지 않는다 (`plan.md` §8.3)
- [x] Kotlin 클라이언트로 루프백 벤치 (b) — 더미 행동
- [x] **확인**: (a) − (b) 간극을 기록. 이것이 직렬화 + RPC 비용이다 (NFR-3)

## P7. Python 클라이언트와 Gymnasium 규약

> `gymnasium.vector.VectorEnv` 를 구현한다. (FR-9, FR-12, M2-c)

- [x] `trainer-python/pyproject.toml` 에 의존성 추가 —
      gymnasium 1.3.0 · grpcio 1.84.0 · numpy 2.5.3 (torch 는 Phase 3)
- [x] `obs_spec.py` — `proto/obs_spec.proto` 파서. 레이아웃을 코드에 복제하지 않는다 (NFR-5)
- [x] `env_client.py` — `VectorEnv` 구현. `np.frombuffer` + 버퍼 재사용 (`plan.md` §7.4)
- [x] 채널은 한 번만 생성. 스텝마다 새 객체 생성 금지 (`plan.md` §7.4)
- [x] **next-step autoreset** 구현 (`plan.md` §8.2)
- [x] 테스트: `terminated=1` 인 스텝의 obs ≠ `Reset` 직후 obs,
      **그 다음** 스텝의 obs = `Reset` 직후 obs — **M2-c** (`plan.md` §8.2)
- [x] 테스트: `gymnasium.utils.env_checker` 계열 검사 통과 — **M2-c**
- [x] 테스트: 클라이언트의 obs 레이아웃 해시 == `Health` 가 준 해시. 어긋나면 즉시 실패
- [x] 테스트: Python 에서 본 결정론 (같은 시드·행동 → 같은 관측 바이트) — **M2-d**
- [x] **확인**: `uv run pytest` 통과

## P8. 종단 처리량과 compose

> 목표 숫자를 실제로 재고, 재현 가능하게 만든다. (M2-a, FR-15)

- [x] `scripts/bench-env.sh` — (a)·(b)·(c) 세 지점을 한 번에 측정·출력 (NFR-3)
- [x] 더미 정책(랜덤 또는 고정 MLP) 으로 (c) 측정, N 을 64·256·1024 로 스윕
- [x] **확인**: N=256, UDS 에서 ≥ **50,000 env-step/s** — **M2-a**
- [x] 미달이면: (a)(b)(c) 간극으로 원인을 특정한 뒤에만 손댄다.
      **엔진 병렬화는 오진이다** (`PRD.md` §6, `plan.md` §2.1)
      → 미달이 아니었다 (9.7배). 손대지 않았다.
- [x] 미달이 RPC 계층이면 bidi 스트리밍 전환 (`plan.md` §7.3 예비안)
      → **전환하지 않았다.** unary 로 예산의 10.3% 만 쓴다. 측정이 필요 없다고 말했다.
- [x] `deploy/compose/engine-server.Dockerfile` — JVM 21 런타임
- [x] `deploy/compose/docker-compose.yml` — 엔진 서버 + 볼륨(UDS 공유)
- [x] **확인**: `docker compose up` 후 Python 테스트가 컨테이너 서버에 붙는다

## P9. 정리 — 문서와 CI

> 다음 Phase 가 기대도 되는 것을 남긴다.

- [x] `.github/workflows/ci.yml` 에 `:engine-kotlin:env` · `:engine-kotlin:server` 테스트 포함.
      처리량 벤치는 CI 에 넣지 않는다 (머신마다 다르다)
- [x] `README.md` — 서버 실행법, 벤치 실행법, obs 레이아웃 확인법
- [x] `ROADMAP.md` Phase 2 결과 기록. **여기에 반드시 남길 것**:
      - 진영 비대칭 (`plan.md` §2.2) — 영구 제약이다
      - 슬로모션 6프레임 미재현 (`plan.md` §4.3) — Phase 6 리플레이가 같은 규칙을 써야 한다
      - 리플레이 재현 키 = `(seed, 입력 시퀀스, 상대 구성)` (`PRD.md` §2.6)
      - **FSM 의 반 프레임 정보 우위** (`plan.md` §2.6) — Track A 승률 해석에 필요하다
      - 학습된 정책을 게임에 꽂는 자리는 **FSM 이 아니라 키보드** (`plan.md` §12.1)
- [x] 측정 수치(§2.1~2.4) 를 `env` 의 정식 벤치/테스트로 재현 가능하게 남겼는지 확인
- [x] **확인**: `PRD.md` §4 의 M2-a ~ M2-g 전부 달성 표시
- [x] 세 문서를 `history/<YYYY-MM-DD>-rl-env-grpc/` 로 이관 (+ `README.md` 요약)

---

## 완료 조건 요약

| ID | 지표 | 목표치 | Phase | 결과 |
|---|---|---|---|---|
| **M2-a** | Python ↔ Kotlin 종단 처리량 (N=256, UDS, 더미 정책) | ≥ 50,000 env-step/s | P8 | ✅ **484,371** (9.7배) |
| **M2-b** | `env` 단독 처리량 (관측 인코딩 포함, 단일 스레드) | ≥ 1,000,000 step/s | P5 | ✅ **5.1M~7.2M** |
| **M2-c** | Gymnasium 규약 + next-step autoreset | 테스트 통과 | P7 | ✅ |
| **M2-d** | 결정론 (재실행·재시작·벡터 크기 변경) | 바이트 완전 일치 | P4, P7 | ✅ Kotlin·Python 양쪽 |
| **M2-e** | FSM vs FSM 베이스라인 재현 (800 게임) | p1 799/800, 11,998 : 4,169 | P2 | ✅ 8개 수치 전부 일치 |
| **M2-f** | Phase 1 골든 회귀 유지 | `./gradlew build` 초록 | P1 (이후 계속) | ✅ 615 에피소드 불변 |
| **M2-g** | Track B 에서 FSM 코드 경로 미실행 | 불변식 테스트 통과 | P4 | ✅ |

**타협 불가: M2-d · M2-f · M2-g** (`PRD.md` §4).
