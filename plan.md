# plan — rl-env-grpc (ROADMAP Phase 2)

`PRD.md` 의 요구사항을 **어떻게** 달성할지에 대한 기술 계획.

## 1. 접근 방식

Phase 1 은 "테스트를 포팅보다 먼저" 로 갔고, 그 덕에 포팅은 첫 실행부터 일치했다.
Phase 2 는 성격이 다르다. **비교할 정답(oracle)이 없다.** 관측 설계에는 정답이 없고,
보상에도 없다. 그래서 다른 축을 잡는다.

> **결정론을 먼저 세우고, 그 위에 의미론을 얹는다.**

관측이 맞는지는 증명할 수 없지만, **어제의 관측과 오늘의 관측이 같은지**는 증명할 수 있다.
그래서 `env` 가 동작하자마자 관측·보상의 골든 해시를 박고(P4), 그 뒤의 모든 변경은
"의도한 변경인가" 를 해시가 묻게 만든다. Phase 1 의 골든 회귀와 같은 장치이고,
같은 규칙이 적용된다 — **깨졌을 때 골든을 재생성하는 것은 검증을 무력화하는 것이다.**

순서를 하나 더 뒤집는다. **처리량 측정을 gRPC 보다 먼저 한다**(P5 → P6).
§2.1 이 보인 대로 엔진은 예산의 0.6% 만 쓴다. gRPC 를 다 만든 뒤 "느리다" 를 마주하면
어디가 느린지 알 수 없다. `env` 단독 처리량을 먼저 확정해 두면, 종단 숫자와의 간극이
곧 RPC 계층의 비용이 된다. 뺄셈이 가능한 상태를 먼저 만든다.

## 2. 검증된 전제 — 측정 절차와 원시 수치

`PRD.md` §2 의 근거. 전부 이 저장소에서 직접 돌려 얻은 값이다.
측정 코드는 `engine-kotlin/conformance` 의 임시 테스트로 작성해 실행 후 제거했고,
P5 에서 `engine-kotlin/env` 의 정식 벤치로 재작성한다 (재측정 가능해야 한다).

### 2.1 처리량 (M-series 8코어, JVM 21, 워밍업 후 2.4M 프레임)

```
uniform 1-thread   frames=2,400,000  0.243s  9,874,117 step/s
biased  1-thread   frames=2,400,000  0.295s  8,138,657 step/s
fsm     1-thread   frames=2,400,000  0.292s  8,223,771 step/s
```

절차: 시드별 600프레임 에피소드 4,000개, 착지 시 즉시 라운드 리셋.
JIT 워밍업으로 에피소드 1,000개를 먼저 돌린 뒤 계측.

**산수 (전제가 바뀌면 여기서 다시 계산한다).**

| 항목 | 값 |
|---|---|
| 목표 | 50,000 env-step/s |
| 배치 N | 256 |
| → 배치 스텝 예산 | `256 / 50,000` = **5.12 ms** |
| 엔진 비용 (fsm 8.1M) | `256 / 8.1e6` = **31.6 µs** (예산의 0.62%) |
| 관측 1개 (41 × f32) | 164 B |
| Step 응답 (N=256, 슬롯 2) | `512 × 164` ≈ **84 KB** |
| 초당 RPC | `50,000 / 256` ≈ **195 회/s** |
| 초당 대역폭 | ≈ **16 MB/s** — 루프백/UDS 에서 무시 가능 |

→ 대역폭은 문제가 아니다. **호출당 지연이 문제다.** 5.12 ms 안에 직렬화 + 왕복 +
Python 정책 forward 가 들어가야 한다. MLP forward(배치 512) 가 CPU 에서 0.2~1 ms,
UDS 왕복이 0.1~0.3 ms 수준이므로 여유는 있지만, **호출 오버헤드가 곱해지는 구조**
(스텝마다 파이썬 객체 수천 개 생성 등) 를 만들면 즉시 날아간다. → §7.4.

### 2.2 진영 비대칭 (FSM vs FSM, 시드 0..399 × 첫서브 2가지 = 800 게임)

```
p1서브→p1득점 8404 / p1서브→p2득점 3195 | p2서브→p1득점 3594 / p2서브→p2득점 974
첫서브 p1 일 때 p1승 399/400 | 첫서브 p2 일 때 p1승 400/400
```

- p1 총 득점 11,998 / p2 총 득점 4,169.
- **서브권은 유리하다** (서브한 쪽이 다음 득점할 확률: p1 8404/11599 = 72%,
  p2 974/4568 = 21%). 그런데 양쪽 다 서브권을 가졌을 때조차 p1 이 훨씬 잘한다.
- 첫 서브를 p2 에게 줘도 결과가 안 바뀐다 → **초기 조건이 아니라 구조적 차이**다.

구조적 원인은 코드에 있다 (`PhysicsEngine.kt:420-425`).

```kotlin
// 좌우가 비대칭이다 (`< BALL_RADIUS` vs `> GROUND_WIDTH`).
if (futureBallX < BALL_RADIUS /* 20 */ || futureBallX > GROUND_WIDTH /* 432 */) {
    ball.xVelocity = -ball.xVelocity
}
```

공의 가동 폭 `[20, 432]` 의 중심은 **226** 이고 네트는 **216** 이다.
오른쪽 코트가 10px(반코트 기준) 넓다. 플레이어 가동 범위는 p1 `[32,184]`,
p2 `[248,400]` 으로 216 대칭이므로, **공만 비대칭**이다.

여기에 FSM 의 대기 위치 판단도 비대칭이다 (`PhysicsEngine.kt:281-288`):
`ball.expectedLandingPointX >= isPlayer2 * GROUND_WIDTH + GROUND_HALF_WIDTH` 는
p1 에게는 `>= 216` 이지만 p2 에게는 `>= 648` 로 **절대 참이 될 수 없다.**
즉 p2 는 "공이 상대 진영에 떨어질 것 같을 때 코트 중앙으로 복귀" 를 p1 보다 덜 한다.

→ 두 원인 모두 원작의 성질이다. **고치지 않는다.** 대신 평가 설계에 반영한다 (§6.3).

### 2.3 랠리·게임 길이 (각 300 게임, 15점제)

```
fsm-vs-fsm rallies=6,038 mean=758.9 p50=568 p90=1531 p99=2821 max=6011 | game mean=15275 frames
uniform    rallies=7,783 mean=44.8  p50=38  p90=78   p99=146  max=273  | game mean=1163
biased     rallies=7,900 mean=84.7  p50=66  p90=173  p99=320  max=691  | game mean=2229
```

`maxRallyFrames` 기본값을 **3,000** 으로 잡는 근거가 이것이다 — FSM p99(2,821) 위,
max(6,011) 아래. FSM 랠리의 1% 미만이 잘린다. 더 크게 잡으면 truncation 이 희귀해져
부트스트랩 버그가 안 드러나고, 더 작게 잡으면 정상 랠리를 자른다.

### 2.4 FSM 의 powerHit 연속 발화 (1.2M 프레임)

```
p1 powerHit=30,576 (2.55%), 그 중 직전 프레임도 1 인 것=17,908 (58.6%)
p2 powerHit=19,747 (1.65%), 그 중 직전 프레임도 1 인 것= 8,456 (42.8%)
```

덤으로 얻은 것 — FSM 이 실제로 쓰는 행동은 18개 중 **13개**다.

```
(x,y,ph) 분포: (1,0,0)=48.2% (0,0,0)=30.4% (-1,0,0)=18.2% (1,0,1)=1.3%
               (1,-1,1)=0.5% (1,1,1)=0.5% (0,-1,0)=0.4% (1,-1,0)=0.3% ... (나머지 ≈0)
```

`yDirection=1`(아래) 은 **powerHit 과 함께가 아니면 나오지 않는다.** y 방향은 사실상
파워히트의 방향 지정용이다. 행동 공간을 18개로 두되 (FR-4), 이 사실은 Phase 3 이
행동 분포를 볼 때의 기준선이 된다.

### 2.5 `expectedLandingPointX` 는 신경망이 쉽게 대체할 수 없다 (90만 호출)

관측에 넣을지 판단하기 위해 측정했다. `calculateExpectedLandingPointXFor`
(`PhysicsEngine.kt:490`) 은 **공 복사본을 착지할 때까지 굴려 보는 루프**다.
플레이어는 무시하고, 좌우 벽·천장·네트 기둥 반사는 전부 계산한다.

```
루프 반복: mean=22.3 p50=19 p90=37 p99=69 max=116
반사(벽/천장/네트) 포함 호출 = 472,553 / 900,000 (52.5%)
순수 포물선 근사 오차: 정확일치 5.3%  mean=79.6px p50=18 p90=220 p99=920 max=2820 (코트 폭 432)
```

- **평균 22단계 롤아웃**이다. MLP 한 방에 나오는 값이 아니다.
- **호출의 52.5% 가 반사를 포함한다.** 반사는 조건부 분기라서 매끄러운 함수가 아니다.
- 반사를 무시한 포물선 추정은 **중앙값 오차 18px** (플레이어 반폭 32px — 쓸 만함) 이지만
  **p90 에서 220px**, 즉 코트 절반이다. 꼬리에서 완전히 무너진다.
  그리고 랠리의 승패는 바로 그 꼬리(강한 파워히트, 네트 맞고 꺾이는 공) 에서 갈린다.

→ 안 주면 정책은 이 22단계 시뮬레이션을 **스스로 근사해야 한다.** FSM 은 공짜로 받는다.

### 2.6 FSM 은 에이전트보다 **반 프레임** 최신 정보를 본다

호출 순서를 읽으면 나온다 (`PhysicsEngine.kt:29-46`, `127-130`).

```kotlin
fun run(player1, player2, ball, userInputArray): Boolean {
    processCollisionBetweenBallAndWorldAndSetBallPosition(ball)      // ① 공이 먼저 움직인다
    for (i in 0 until 2) {
        calculateExpectedLandingPointXFor(ball)                      // ② 착지점 재계산
        processPlayerMovementAndSetPlayerPosition(player, userInputArray[i], theOtherPlayer, ball)
        //   └─ 이 안에서: if (player.isComputer) letComputerDecideUserInput(player, ball, ...)
    }
```

| | 언제 결정하는가 | 무엇을 보는가 |
|---|---|---|
| 사람 / 에이전트 | `run()` **호출 전** — `userInputArray` 를 채워서 넘긴다 | 프레임 **t−1 종료** 시점의 공 |
| FSM | `run()` **안**, ①② 가 끝난 뒤 | 프레임 **t** 의 공 위치 + 갱신된 `expectedLandingPointX` |

공의 x 속도는 파워히트 시 최대 20px/frame 이므로 **최대 20px 의 정보 우위**다.
게다가 `i = 1`(player2) 차례의 FSM 은 `theOtherPlayer = player1` 이 **이번 프레임 이미
움직인 뒤** 상태를 본다. player2 쪽이 한 겹 더 최신이다.
(그럼에도 §2.2 에서 player2 가 압도적으로 진다 — 코트 비대칭이 이 우위를 덮는다.)

**고치지 않는다.** 원작의 구조이고, 사람도 마지막으로 그려진 프레임을 보고 키를 누르므로
**에이전트의 타이밍이 곧 사람의 타이밍**이다. 다만 두 곳에 영향이 있다.

1. **Track A 승률 해석.** FSM 은 약간의 정보 우위를 갖고 있다. 승률을 순수한 실력 차로
   읽으면 정책을 과소평가하게 된다. Phase 3 의 결과 보고에 이 사실을 첨부한다.
2. **장래 배포 (§12).** 학습된 정책을 `letComputerDecideUserInput` 자리에 꽂으면
   **훈련 때와 다른 시점의 관측**을 받는다. 꽂을 자리는 FSM 이 아니라 키보드다.

## 3. 모듈 분할

```
:engine-kotlin:core        물리 + RNG 인터페이스 + XorShift32   (외부 의존성 0, NFR-1)
:engine-kotlin:env         경기 규칙 · 관측 · 행동 · 보상 · 벡터 환경 · 벤치  (core 만 의존)
:engine-kotlin:server      gRPC 서버 (env + grpc-java + protobuf)
:engine-kotlin:conformance (변경 없음)
```

**왜 `env` 와 `server` 를 나누는가** (NFR-2). `env` 가 gRPC 를 모르면 JVM in-process 로
처리량을 잴 수 있고(M2-b), 그 숫자와 종단 숫자(M2-a)의 뺄셈이 RPC 비용이 된다.
합쳐 두면 그 뺄셈이 불가능하다. §1 의 "뺄셈 가능한 상태" 가 여기서 구조가 된다.

### 3.1 `XorShift32` 를 `core` 로 옮긴다

현재 위치는 `engine-kotlin/conformance/.../XorShift32.kt` 인데 `env` 가 써야 한다.
`env` 가 `conformance` 에 의존할 수는 없다 (conformance 는 Node·`upstream/` 경로에 묶여 있다).

| 안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| `core` 로 이동 | `Rand` 가 이미 `core` 에 있다. 순수 함수·의존성 0 이라 NFR-1 위배 아님 | `core` = "physics.js 포팅" 이라는 순수성이 옅어짐 | **채택** |
| 새 `:rng` 모듈 | 개념이 깔끔 | 파일 1개짜리 모듈. Gradle 설정 비용이 이득보다 큼 | 기각 |
| `env` 에 복제 | 이동 없음 | 두 벌이 갈라지면 conformance 와 env 의 난수가 달라진다. **가장 나쁘다** | 기각 |

`core` 로 옮기되 파일 상단에 "이것은 `physics.js` 의 일부가 아니라 우리가 주입하는
결정론 장치" 라고 명시한다. 이동은 **단독 커밋**으로 하고 즉시 `./gradlew build` 로
골든 회귀를 확인한다 (M2-f). 해시가 하나라도 움직이면 되돌린다.

## 4. 경기 규칙 포팅 — `PikaGame`

`pikavolley.js` 의 `round()` 중 **물리에 영향을 주는 부분만** 옮긴다.
렌더링·페이드·슬로모션·메뉴는 전부 버린다.

```kotlin
// engine-kotlin/env/src/main/kotlin/pika/env/PikaGame.kt
class PikaGame(
    private val rand: Rand,
    private val p1IsComputer: Boolean,
    private val p2IsComputer: Boolean,
    val winningScore: Int = 15,
) {
    val physics = PikaPhysics(p1IsComputer, p2IsComputer, rand)
    val scores = intArrayOf(0, 0)
    var isPlayer2Serve = false; private set
    var gameEnded = false; private set

    /** @return 이번 프레임에 랠리가 끝났다면 득점자(0/1), 아니면 null */
    fun step(inputs: Array<PikaUserInput>): Int? {
        val touching = physics.runEngineForNextFrame(inputs)
        if (!touching) return null

        // pikavolley.js:374 — ball.x 가 아니라 punchEffectX 다. 착지 시 ball.x 로 세팅된다.
        val scorer = if (physics.ball.punchEffectX < GROUND_HALF_WIDTH) 1 else 0
        scores[scorer]++
        isPlayer2Serve = (scorer == 1)          // 득점자가 다음 서브
        if (scores[scorer] >= winningScore) {
            gameEnded = true
            physics.player1.isWinner = (scorer == 0); physics.player1.gameEnded = true
            physics.player2.isWinner = (scorer == 1); physics.player2.gameEnded = true
        }
        return scorer
    }

    /** 다음 랠리 준비. 순서가 RNG 소비 순서를 결정한다: player1 → player2 → ball. */
    fun startNextRally() {
        physics.player1.initializeForNewRound()
        physics.player2.initializeForNewRound()
        physics.ball.initializeForNewRound(isPlayer2Serve)
    }
}
```

### 4.1 함정 — 리셋 순서

`initializeForNewRound()` 는 `rand() % 5` 로 `computerBoldness` 를 뽑는다.
player1 → player2 → ball 순서가 곧 RNG 소비 순서다. 바꾸면 조용히 갈라진다.
`Harness.resetRound` (`conformance`) 와 같은 순서이며, 이 계약은 Phase 1 이 검증했다.

### 4.2 함정 — 리셋 대상이 아닌 필드

`Player` 의 `divingDirection` · `lyingDownDurationLeft` · `isWinner` · `gameEnded` ·
`computerWhereToStandBy` 와 `Ball` 의 `expectedLandingPointX` · `rotation` · `fineRotation` ·
`punchEffect*` · `previous*` 는 **라운드를 넘어 살아남는다** (`Physics.kt` 주석).
"리셋이니까 전부 초기화" 로 정리하면 갈라진다. `PikaGame` 은 `initializeForNewRound()` 를
부르기만 하고 직접 필드를 건드리지 않는다.

### 4.3 판단 — 슬로모션 6프레임은 재현하지 않는다

업스트림은 착지 후 `slowMotionFramesLeft = 6` 동안 물리를 더 돌린다 (`pikavolley.js:397`).
그 6프레임에서도 공이 튀고 플레이어가 움직이며 **RNG 를 소비한다.**

| 안 | 판단 |
|---|---|
| 6프레임 재현 | 랠리당 6프레임(평균 0.8%) 을 버리는 계산에 쓴다. 학습 가치 0. 상태 공간에 "이미 끝난 랠리" 라는 의미 없는 구간이 생긴다 |
| **즉시 리셋** | **채택.** 차분 테스트 하네스와 같은 규칙이라 일관된다 |

→ **결과: `env` 는 원작의 프레임 단위 재생이 아니다.** 프레임 물리는 동치이지만
랠리 경계의 프레임 수가 다르다. Phase 6 의 리플레이 뷰어는 **반드시 같은 규칙**을 써야
한다. 이 문장을 `ROADMAP.md` 에도 남긴다 — 이 문서는 이관되면 묻히기 때문이다.

## 5. 환경 의미론

### 5.1 관측

정책 시점(egocentric) 으로 구성한다. `me` 는 이 슬롯이 조종하는 플레이어, `opp` 는 상대.

| 그룹 | 필드 | 차원 | 정규화 |
|---|---|---|---|
| me / opp (각 15) | `x` | 1 | p1 `[32,184]` · p2 `[248,400]` → `[-1,1]` |
| | `y` | 1 | `[108, 244]` → `[-1,1]` (점프 최고점 = 244 − Σ(1..16) = 108) |
| | `yVelocity` | 1 | `/16` |
| | `state` one-hot | 7 | 0..6 |
| | `frameNumber` | 1 | `/5` |
| | `divingDirection` | 1 | 그대로 (−1/0/1) |
| | `lyingDownDurationLeft` | 1 | `/3` |
| | `isCollisionWithBallHappened` | 1 | 0/1 |
| | `delayBeforeNextFrame` | 1 | `/5` |
| ball (6) | `x`, `y` | 2 | `[20,432]` · `[0,252]` → `[-1,1]` |
| | `xVelocity`, `yVelocity` | 2 | `/20` |
| | `isPowerHit` | 1 | 0/1 |
| | `expectedLandingPointX` | 1 | ball.x 와 같은 척도 |
| 경기 (4) | 내 점수 / 상대 점수 / 점수차 | 3 | `/15` |
| | 내가 서브인가 | 1 | 0/1 |
| **합계** | | **41** | |

**제외한 것과 이유.**

| 제외 | 이유 |
|---|---|
| `computerBoldness` | **상대의 은닉 상태.** 넣으면 vs FSM 성적이 부풀고 셀프플레이에서 의미를 잃는다 (FR-2) |
| `computerWhereToStandBy` | 〃 |
| `rotation`, `fineRotation`, `punchEffect*`, `sound.*` | 렌더링 전용. 물리에 영향 없음 |
| `previousX/Y`, `previousPreviousX/Y` | 잔상 효과용. 속도가 이미 관측에 있으므로 정보 중복 |
| `normalStatusArmSwingDirection` | 팔 스윙 애니메이션 선택자 |
| `isWinner`, `gameEnded` | 에피소드(랠리) 밖의 값. 게임 단위는 평가 전용 |

**`expectedLandingPointX` 는 포함한다 — 판단이 필요한 지점이다.**

| 안 | 근거 |
|---|---|
| **포함 (채택, 기본값 on)** | (1) **은닉 정보가 아니다** — 공 상태만의 결정론적 함수이고, 상대의 내부 상태가 들어가지 않는다. (2) **FSM 이 이 값을 공짜로 받는다** (`PhysicsEngine.kt:279`). 빼면 Track A 는 "정책이 FSM 보다 약한가" 가 아니라 "정책이 FSM 보다 적게 보고도 이기는가" 를 재게 된다. (3) §2.5 측정 — 평균 22단계 롤아웃, 52.5% 가 반사 포함, 포물선 근사는 p90 에서 220px(코트 절반) 빗나간다 |
| 제외 | "엔진 내부 조회 없는 순수 RL" 에 가깝다. 단, 위 (2) 때문에 **Track A 의 승률 해석이 바뀐다** — 이 경우 ROADMAP 의 "vs FSM 90%" 는 더 어려운 문제가 된다 |

→ `obsIncludeExpectedLanding: Boolean = true`. 끄면 40차원이 된다.
Phase 3 에서 플래그로 A/B 하고, 끈 결과를 낸다면 **그 사실을 승률과 함께 보고**한다.

**함께 판단한 것 — `expectedLandingPointXWhenPowerHit` 은 넣지 않는다.**
이 함수(`PhysicsEngine.kt:541`) 는 "내가 지금 이 방향으로 파워히트하면 어디 떨어지나" 를
계산하는 **행동 평가**이고, FSM 의 `decideWhetherInputPowerHit` 이 6개 방향을 훑는 데 쓴다.
두 함수의 성격이 다르다.

| | `expectedLandingPointX` | `...WhenPowerHit` |
|---|---|---|
| 무엇인가 | 현재 공 상태의 귀결 (**상태 추정**) | 가정된 행동의 귀결 (**행동 평가**) |
| 정확한가 | 플레이어만 무시, 나머지는 정확 | **일부러 틀리게 짜여 있다** — 네트 기둥 옆면 반사를 계산하지 않는다. 업스트림 주석: 컴퓨터가 실수하도록 의도된 코드 |
| 준다면 | 관측 | Q 함수를 손으로 만들어 주는 것 |

행동 평가는 정책이 학습해야 할 바로 그것이다. 주면 문제가 사라진다. 그리고 이 함수는
**의도적으로 부정확**해서, 주면 정책에게 FSM 의 실수 습관까지 물려주게 된다.

**레이아웃은 `proto/obs_spec.proto` 에 단일 정의한다** (NFR-5). State Spec 과 같은 방식 —
protobuf 인코딩은 쓰지 않고, 필드 순서를 정의하는 계약으로만 쓴다. Kotlin 인코더와
Python 디코더가 각자 자기 목록을 이 파일과 대조하는 테스트를 가진다. 어긋나면 실패한다.

### 5.2 행동과 `powerHit`

행동 공간은 `Discrete(18)`.

| 안 | 판단 |
|---|---|
| **`Discrete(18)`** | **채택.** 단일 categorical 이라 PPO 의 log-prob·엔트로피·KL 계산이 단순하다. 18 은 작다 |
| `MultiDiscrete([3,3,2])` | 파라미터가 적고 축별 일반화가 되지만, 축이 독립이라는 가정이 틀리다 — §2.4 에서 `yDirection=1` 은 `powerHit=1` 과만 함께 나온다 |

디코딩: `x = a / 6 - 1`, `y = (a % 6) / 2 - 1`, `powerHit = a % 2`.

**엣지 변환은 에이전트 슬롯에만 건다.** 구조로 막는다 — FSM 은 엔진 내부에서
`userInput` 을 직접 덮어쓰므로 (`PhysicsEngine.kt:129`) 래퍼가 닿을 곳이 없다.
"FSM 에는 적용하지 않기로 한다" 가 아니라 **적용할 수가 없는 구조**다.

```kotlin
// engine-kotlin/env/.../ActionCodec.kt
class EdgeTrigger {                       // keyboard.js:71-77 과 같은 규칙
    private var wasDown = false
    fun apply(held: Boolean): Int {
        val edge = if (!wasDown && held) 1 else 0
        wasDown = held
        return edge
    }
}
```

⚠️ **`EdgeTrigger` 의 상태는 라운드 리셋에서 초기화한다.** 안 하면 이전 랠리 마지막
프레임의 키 상태가 새 랠리로 새어 들어간다. `keyboard.js` 에는 이 리셋이 없지만
(사람은 랠리 사이에 손을 뗀다) 우리 에이전트는 안 뗀다.

설정 `edgeTriggerPowerHit: Boolean = true`. Phase 3 이 A/B 할 수 있게 끌 수 있다.

### 5.3 미러링 — 대칭이 아닌 것을 대칭인 척하지 않는다

셀프플레이의 표준은 "관측을 항상 왼쪽 시점으로 정규화" 다. 그런데 §2.2 가 보인 대로
**이 게임은 좌우 대칭이 아니다.** 미러링한 정책은 두 진영에서 실제로 다르게 행동한다.

채택하는 절충:

1. 관측은 미러링한다 (`mirrorObservations = true`). 샘플 효율이 크게 오르고,
   p2 슬롯의 `x → 432 − x`, `xVelocity → −xVelocity`, `divingDirection → −` 로 충분하다.
2. **관측에 진영 플래그를 넣을 수 있게 한다** (`obsIncludeSideFlag`, 기본 **off**).
   기본을 off 로 두는 이유: 켜면 정책이 두 진영을 따로 학습해 버려서 미러링의 이득이
   사라진다. 켜는 것은 Phase 3 이 "미러링만으로 부족하다" 를 **측정으로** 보인 뒤다.
3. **평가는 항상 양 진영에서 돌리고 따로 보고한다** (FR-13). 이건 설정이 아니라 규칙이다.

```kotlin
// 미러 축은 네트(216)다. 플레이어 가동 범위는 216 대칭이지만 공의 가동 폭
// [20,432] 의 중심은 226 이다. 즉 미러링은 근사이고, 그 오차가 §2.2 의 비대칭이다.
private fun mirrorX(x: Int) = GROUND_WIDTH - x
```

이 주석을 코드에 남긴다. 나중에 "미러링했는데 왜 진영별 승률이 다르지?" 를 마주할 사람이
읽을 문장이다.

## 6. 보상

### 6.1 구조

```kotlin
// engine-kotlin/env/.../Reward.kt
data class RewardTerms(
    val rallyWin: Float,      // 랠리 승 +1 / 패 -1                        (종단)
    val ballTouch: Float,     // 내가 공을 쳤다                            (셰이핑)
    val crossedNet: Float,    // 내가 친 공이 네트를 넘어갔다              (셰이핑)
    val opponentMiss: Float,  // 상대 진영에 착지                          (셰이핑, rallyWin 과 중복)
    val timePenalty: Float,   // 프레임당 -ε                               (셰이핑)
) {
    fun total(w: RewardWeights) = rallyWin * w.rallyWin + ballTouch * w.ballTouch + ...
}
```

**Phase 2 는 항을 계산해 내보내기만 한다. 기본 가중치는 `rallyWin = 1`, 나머지 전부 0.**
튜닝은 Phase 3 의 일이다 (`PRD.md` §6). 항별 값을 `info` 로 함께 내보내는 이유는
Phase 3 의 어닐링이 "지금 보상의 몇 %가 셰이핑에서 왔는가" 를 봐야 하기 때문이다.
합계만 주면 그 질문에 답할 수 없다.

### 6.2 함정 — 셰이핑이 없으면 Track B 는 아예 학습이 안 된다

§2.3 의 uniform 랠리 평균 44.8 프레임은 "랜덤 정책끼리는 공을 거의 못 친다" 는 뜻이다.
`rallyWin` 만 주면 신호가 랠리당 1개, 그것도 거의 무작위다. ROADMAP Phase 4 가
"셰이핑 필수, 어닐링 훨씬 늦게" 라고 적은 근거가 이것이다. Phase 2 는 그 항을
**미리 만들어 두는 것**까지가 일이다.

### 6.3 평가 지표는 보상이 아니다

`rallyWin` 합계는 학습 신호이고, ROADMAP 의 완료 조건은 **게임 승률**이다.
`GameEvaluator` 를 따로 둔다 — 15점제 게임을 끝까지 돌리고, **양 진영에서** 돌리고,
진영별 승률과 합산 승률을 모두 낸다.

```kotlin
data class EvalResult(
    val asLeft: WinRate,    // 정책이 p1 진영
    val asRight: WinRate,   // 정책이 p2 진영
) {
    val combined: Double get() = (asLeft.rate + asRight.rate) / 2
    /** §2.2 의 진영 효과 크기. 0 에서 멀수록 정책이 진영에 의존한다. */
    val sideGap: Double get() = asLeft.rate - asRight.rate
}
```

M2-e 는 이 장치의 자기 검증이다 — 정책 자리에 FSM 을 넣으면
`asLeft ≈ 1.00`, `asRight ≈ 0.00` 이 나와야 한다 (측정값 799/800).
안 나오면 평가기가 틀린 것이다.

## 7. gRPC 계약

### 7.1 서비스

```protobuf
service PikaEnv {
  rpc Configure (ConfigureRequest) returns (ConfigureReply);  // 벡터 크기·상대 구성·플래그
  rpc Reset     (ResetRequest)     returns (StepReply);       // seed 지정
  rpc Step      (StepRequest)      returns (StepReply);
  rpc Health    (HealthRequest)    returns (HealthReply);     // 처리량·버전·obs 레이아웃 해시
}

message StepRequest {
  // N × slots 개의 행동. uint8 하나당 행동 하나 (Discrete(18) 은 1바이트에 들어간다).
  bytes actions = 1;
}

message StepReply {
  bytes observations = 1;  // float32 LE, N × slots × obsDim, obs_spec.proto 순서
  bytes rewards      = 2;  // float32 LE, N × slots
  bytes terminated   = 3;  // uint8, N
  bytes truncated    = 4;  // uint8, N
  bytes rewardTerms  = 5;  // float32 LE, N × slots × termCount (info 용)
  bytes scores       = 6;  // int32 LE, N × 2
}
```

**왜 `repeated float` 가 아니라 `bytes` 인가.** protobuf 의 `repeated float` 는 Python
에서 리스트로 풀리고, 10,000개 원소면 파이썬 객체 10,000개가 생긴다. §2.1 의 5.12 ms
예산은 그걸 견디지 못한다. `bytes` 로 받으면 `np.frombuffer(reply.observations,
dtype='<f4').reshape(N, slots, obsDim)` 한 줄이고 복사가 한 번뿐이다.

대신 레이아웃이 계약이 되므로 `obs_spec.proto` 가 단일 정의가 되어야 한다 (NFR-5).
`Health` 가 **레이아웃 해시**를 돌려주고 클라이언트가 자기 해시와 대조한다.
버전이 어긋난 서버에 붙으면 관측이 조용히 뒤섞이는 대신 즉시 실패한다.

### 7.2 grpc-kotlin 을 쓰지 않는다

| 안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| **grpc-java (blocking/async stub)** | 최신 릴리스 1.84.0 (2026-09-02). 코드 생성 한 단계 | 코루틴 문법 없음 | **채택** |
| grpc-kotlin-stub | 코루틴 `Flow` API | 최신 릴리스 **1.5.0 (2025-09-16)** — **약 1년 정지**. grpc-java 는 같은 기간에 여러 번 릴리스됐다. 코드 생성기(`protoc-gen-grpc-kotlin`) 가 하나 더 붙는다 | 기각 |

RPC 가 4개뿐이고 전부 요청-응답 형태다. 코루틴이 사줄 것이 없다.
장기 유지보수 면에서 릴리스가 멈춘 코드 생성기를 하나 더 끼우는 쪽이 비싸다.
(버전 조회: 2026-09-22, Maven Central + GitHub Releases)

### 7.3 전송 — UDS 우선, 스트리밍은 예비

1. **1차: Unix domain socket + unary `Step`.** gRPC 는 `unix:///path/to.sock` 을 그대로
   지원한다. TCP 루프백보다 지연이 낮고, Phase 7 에서 주소만 바꾸면 TCP 로 간다.
2. **2차(예비): bidi 스트리밍.** unary 가 예산(5.12 ms) 을 못 맞추면 전환한다.
   스트리밍은 HTTP/2 헤더 처리와 흐름 제어 왕복을 줄인다.

**순서를 이렇게 잡는 이유:** 스트리밍은 상태를 가지므로 재연결·오류 처리·백프레셔가
전부 우리 몫이 된다. 필요 없는데 먼저 도입하면 디버깅 비용만 는다.
전환 여부는 P8 의 **측정**이 정한다. 짐작으로 정하지 않는다.

### 7.4 함정 — 파이썬 쪽에서 예산을 날리는 법

- `reply.observations` 를 `bytes` 로 받아 놓고 `list(...)` 로 풀기 → 즉시 사망.
- 스텝마다 `np.zeros(...)` 새로 할당 → GC 압력. **버퍼를 재사용한다.**
- `grpc.insecure_channel()` 을 스텝마다 열기 → 채널은 한 번만 만든다.
- Python 3.11 + grpcio 의 GIL: `Step` 호출이 블로킹이면 그 동안 정책 forward 를 못 한다.
  → 더블 버퍼링(두 벡터 환경을 번갈아 스텝) 이 예비책. P8 에서 필요성을 측정한다.

## 8. 벡터 환경과 Gymnasium 규약

### 8.1 슬롯

상대를 "슬롯" 으로 추상화한다 (FR-8).

```kotlin
sealed interface Slot {
    /** 엔진 내장 FSM. 행동을 받지 않고, 관측도 내보내지 않는다. */
    data object Fsm : Slot
    /** 외부 정책. Python 이 행동을 주고 관측을 받는다. */
    data object External : Slot
}
```

- Track A: `[External, Fsm]` (와 진영 반전 `[Fsm, External]`).
- Track B: `[External, External]` — 두 슬롯 모두 Python 이 행동을 준다.
  상대 풀 관리와 체크포인트 샘플링은 **Python 쪽**에 둔다 (Phase 4 의 리그가 그 자리에 얹힌다).

**왜 상대 정책을 서버에 넣지 않는가.** 넣으면 Phase 4 가 ONNX 로딩·체크포인트 관리·
리그 스케줄링을 전부 Kotlin 으로 다시 만들어야 한다. Python 이 이미 그 도구를 갖고 있다.
배치 크기가 2배가 되지만 §2.1 의 대역폭 계산상 문제가 되지 않는다.

### 8.2 오토리셋

Gymnasium 1.x 는 **next-step autoreset** 이다 (`PRD.md` §2.7).

| 스텝 | 반환 obs | terminated | 서버 내부 |
|---|---|---|---|
| t | 랠리 마지막 관측 | **1** | 리셋하지 **않는다** |
| t+1 | **새 랠리 첫 관측** | 0 | 이 호출 **시작 시점**에 리셋. 이 스텝의 action 은 **무시** |

⚠️ t+1 의 보상은 0 이고 action 은 버려진다. same-step autoreset(구 API) 으로 만들면
PPO 가 "종료 상태의 value" 로 새 에피소드 첫 관측을 쓰게 되어 **조용히** 틀어진다.
테스트로 못 박는다 (M2-c): `terminated=1` 인 스텝의 obs 가 `Reset` 직후 obs 와
**다름**을, 그 다음 스텝의 obs 가 `Reset` 직후 obs 와 **같음**을 확인한다.

### 8.3 시드와 결정론

- `Configure(baseSeed)` → 환경 i 의 랠리 k 는 `XorShift32(hash(baseSeed, i, k))`.
  **벡터 크기를 바꿔도 환경 i 의 수열이 안 바뀐다** (M2-d).
- `EdgeTrigger` 상태, `RewardTerms` 누적, 점수는 전부 리셋에서 초기화한다.
- 서버는 상태를 디스크에 남기지 않는다. 재시작 후 같은 `Configure` 면 같은 결과.

## 9. Track B 차단 — 코드가 막는다

`PRD.md` M2-g 는 "주의해서 안 쓴다" 로는 달성되지 않는다. 두 겹으로 막는다.

1. **타입**: 슬롯 구성이 `[External, External]` 이면 `PikaPhysics(false, false, rand)` 로
   생성되고, `processPlayerMovementAndSetPlayerPosition` 의
   `if (player.isComputer) letComputerDecideUserInput(...)` 분기에 **들어갈 수 없다**.
2. **런타임 카운터**: `env` 가 `fsmDecisionCount` 를 세고 `Health` 로 노출한다.
   Track B 구성에서 이 값이 0 이 아니면 테스트가 실패한다.

카운터를 세려면 `core` 를 건드려야 하는데 NFR-1(의존성 0) 과 Phase 1 의 동치성 때문에
조심스럽다. → **`core` 는 건드리지 않고** `env` 가 `isComputer` 플래그의 개수로 센다.
`isComputer=true` 인 플레이어가 하나라도 있으면 그 환경은 FSM 을 쓴 것이다.
이건 셈이 아니라 불변식이고, 더 싸고 더 확실하다.

## 10. 결정론과 회귀 장치

Phase 1 의 골든 회귀를 그대로 한 층 위에 복제한다.

| Phase 1 | Phase 2 |
|---|---|
| `physics.js` ↔ `core` 프레임 해시 | 없음 (비교할 정답이 없다) |
| 체인 해시 골든 615 에피소드 | **관측·보상 체인 해시 골든** — 고정 시드 · 고정 행동 시퀀스 |
| `./gradlew build` 가 본다 | 〃 |

고정 행동 시퀀스는 별도 RNG(`XorShift32(seed xor ACTION_SALT)`) 로 만든다.
체인 해시는 `h_n = SHA256(h_{n-1} ‖ obs_n ‖ reward_n ‖ flags_n)`, Phase 1 과 같은 규약.

**이 골든이 깨지면** — 관측 레이아웃·정규화 상수·보상 항·리셋 순서 중 하나가 바뀐 것이다.
의도한 변경이면 골든을 갱신하고 **무엇을 왜 바꿨는지 커밋 메시지에 적는다.**
의도하지 않았으면 원인을 찾는다. Phase 1 과 달리 여기서는 골든 갱신이 정당할 때가 있다
(설계가 확정 전이므로). 그래서 **갱신 시 이유를 적는 것**이 규칙이 된다.

## 11. 처리량 측정 — 세 지점 (NFR-3)

| 지점 | 무엇을 재는가 | 도구 | 기대 |
|---|---|---|---|
| (a) `env` 단독 | 물리 + 관측 인코딩 + 보상 | JVM in-process 벤치 | ≥ 1M step/s (M2-b) |
| (b) gRPC 루프백 | (a) + 직렬화 + 왕복. **Kotlin 클라이언트, 더미 행동** | JVM 벤치 | — |
| (c) Python 종단 | (b) + Python 디코딩 + 더미 정책 forward | `scripts/bench-env.sh` | ≥ 50k env-step/s (M2-a) |

(a)−(b) = 직렬화·RPC 비용. (b)−(c) = Python 쪽 비용.
숫자 하나만 재면 미달일 때 어디를 고쳐야 할지 알 수 없다.
§2.1 의 예산표를 함께 출력해 "예산의 몇 %" 로 보고한다.

**참조점**: 엔진만 = 8.1M step/s. (a) 가 이보다 10배 이상 느리면 관측 인코딩이
범인이다 — float 박싱, 스텝마다 배열 할당, one-hot 을 `List` 로 만들기 같은 것.

## 12. 장래 배포를 위해 지금 지켜둘 것

ROADMAP 밖의 이야기이지만, **지금 한 줄이면 되고 나중엔 비싼** 것이 몇 개 있어 적어둔다.
학습이 끝난 정책을 원작 JS 게임(또는 `viewer-web`)에 넣어 사람과 대전시키는 시나리오다.

### 12.1 꽂는 자리는 FSM 이 아니라 키보드다

```js
// upstream/src/resources/js/pikavolley.js
const isBallTouchingGround = this.physics.runEngineForNextFrame(this.keyboardArray);
//                                                              └─ keyboardArray[1] 을 교체한다
```

`getInput()` 을 가진 `PolicyController` 를 `keyboardArray[1]` 자리에 넣고 `isComputer` 는
`false` 로 둔다. 이유는 §2.6 — 이 자리가 **훈련 때와 같은 타이밍**이다.
덤으로 `keyboard.js` 의 역할이 곧 powerHit 엣지 변환(§5.2)이므로, `EdgeTrigger` 로직이
그대로 재사용된다. 에이전트는 애초에 사람 대신 두도록 훈련된 것이니 사람 자리가 맞다.

### 12.2 추론은 브라우저가 직접 한다. 백엔드는 배포만 한다

정책이 41 → 256 → 256 → 18 MLP 라면 파라미터 **81,170개**, float32 로 **317 KB** 다.
프레임당 약 81,000 MAC, 25 FPS 면 초당 200만 MAC — 행렬곱 3번이라 **순수 JS 로 짜도
프레임 예산 40ms 중 마이크로초만 쓴다.** ONNX Runtime 같은 런타임조차 필요 없다.

| 방식 | 판단 |
|---|---|
| 브라우저가 직접 추론 | **채택 후보.** 왕복 없음, 오프라인 동작 |
| 백엔드가 매 프레임 추론 | 25 FPS = 프레임당 40ms 인데 인터넷 왕복이 들어간다. 200만 MAC 때문에 서버를 부를 이유가 없다 |
| 백엔드는 가중치 배포만 | **채택 후보.** 체크포인트 버전·난이도별 모델 목록·다운로드 |

### 12.3 그래서 `obs_spec.proto` 의 소비자는 셋이 된다

JS 가 자기 게임 상태에서 **41차원 관측을 Kotlin 과 똑같이** 뽑아야 한다. 하나라도
어긋나면 정책이 엉뚱하게 둔다 — 필드 순서, 정규화 상수, 미러링 방향, 엣지 변환,
`expectedLandingPointX` 포함 여부.

Phase 1 의 `state_spec.proto` 가 JS·Kotlin 을 묶었던 것과 같은 구조다. 지금 할 일은
**`obs_spec.proto` 를 언어 중립으로 쓰는 것**뿐이다.

- 소비자 목록을 파일 주석에 적는다 — Kotlin · Python · **(장래) JS**.
  레이아웃을 바꿀 사람이 JS 를 떠올리게 된다.
- **정규화 상수를 주석에 숫자로 박는다.** JS 가 재구현할 때 Kotlin 코드를 읽지 않아도 되게.

이 둘이 §12 에서 Phase 2 범위 안에 있는 유일한 작업이다. 나머지는 전부 장래의 일이다.

## 13. 작업 순서

`tasks.md` 의 Phase 와 대응한다.

| 순서 | Phase | 왜 이 순서인가 |
|---|---|---|
| 1 | P1 기반 | `XorShift32` 이동이 골든을 깨면 그 뒤 전부가 흔들린다. 가장 먼저, 단독으로 |
| 2 | P2 경기 규칙 | 관측·보상이 기댈 바닥. M2-e 베이스라인이 여기서 나온다 |
| 3 | P3 의미론 | 관측·행동·보상·에피소드 |
| 4 | P4 결정론 | **의미론 직후.** 이후 모든 변경을 해시가 감시한다 (§1) |
| 5 | P5 처리량 (a) | **gRPC 보다 먼저.** 뺄셈이 가능한 상태를 만든다 (§11) |
| 6 | P6 gRPC | 계약 → 서버 → (b) 측정 |
| 7 | P7 Python | 클라이언트 → Gymnasium 규약 |
| 8 | P8 종단 | (c) 측정 → compose |
| 9 | P9 정리 | ROADMAP 갱신 · CI · history 이관 |

**특히 정확해야 하는 것**: P1 의 골든 유지(M2-f), P3 의 리셋 순서(§4.1),
P4 의 골든 규약(§10), P7 의 오토리셋 타이밍(§8.2).
이 넷은 틀려도 **초록이 나온다.** 그래서 전용 테스트를 붙인다.
