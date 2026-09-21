# plan — engine-port (ROADMAP Phase 0~1)

`PRD.md` 의 요구사항을 **어떻게** 달성할지에 대한 기술 계획.

---

## 1. 접근 방식

**테스트를 포팅보다 먼저 만든다.**

일반적인 포팅은 "옮긴 뒤 검증"이지만, 여기서는 순서를 뒤집는다.
JS 오라클과 비교 하네스를 먼저 세우면, 포팅 작업이 **매 함수마다 즉시 검증되는 루프**가 된다.
1032줄을 다 옮긴 뒤 "어딘가 다르다"를 마주하는 것과, 함수 하나 옮길 때마다 확인하는 것은
디버깅 비용이 자릿수로 다르다.

```
State Spec 확정 → JS 오라클 → 비교 하네스 → 포팅(함수 단위 반복) → 커버리지 보강
```

---

## 2. 검증된 전제 — 왜 완전 일치가 가능한가

`physics.js` 를 전수 조사한 결과다. M1-a(해시 100% 일치)가 성립하는 근거.

| 조사 항목 | 결과 |
|---|---|
| 나눗셈 | **7곳 전부 `\| 0` 으로 즉시 절삭.** 원본 주석에도 `// integer division` 명시 |
| `Math.*` 사용 | **`Math.abs` 뿐.** `sqrt`/`sin`/`cos`/`pow` 없음 |
| 소수점 리터럴 | **0건** |

```js
408:  let futureFineRotation = ball.fineRotation + ((ball.xVelocity / 2) | 0);
421:  ball.rotation = (ball.fineRotation / 10) | 0;   // integer division
691:  ball.xVelocity = -((Math.abs(ball.x - playerX) / 3) | 0);
```

→ **모든 게임 상태가 32비트 정수다.** 부동소수점 반올림 차이가 원천적으로 없으므로,
   같은 입력에 같은 숫자가 나오고, 같은 숫자면 같은 해시가 나온다.

숫자를 일치시키는 조건은 세 가지이고 전부 통제 가능하다.

1. 상태가 전부 `Int` — 위에서 확인
2. RNG 를 동일 구현으로 교체 — `setCustomRng()` 주입 지점이 이미 있음 (§4)
3. 같은 입력 시퀀스 — 테스트가 주입

---

## 3. 정수 의미 보존 규칙

| JS | Kotlin | 비고 |
|---|---|---|
| 상태 필드 전부 | **`Int` (32비트)** | ⚠️ **`Long` 금지.** `\| 0` 의 int32 래핑까지 재현해야 한다 |
| `(a / b) \| 0` | `a / b` | 둘 다 **0 방향 절삭**. 일치 |
| `a % b` | `a % b` | 둘 다 **피제수 부호**를 따름. 일치 |
| `Math.abs(x)` | `kotlin.math.abs(x)` | 일치 |

> Python 이었다면 여기서 깨졌다. Python `//` 는 floor division 이라 `-5 // 2 == -3` 인데
> JS/Kotlin 은 둘 다 `-2` 다. 언어 선택이 맞아떨어진 지점.

---

## 4. 결정론 확보 — RNG 교체

업스트림 `rand.js` 는 기본적으로 `Math.random()` 을 쓰지만 `setCustomRng()` 주입 지점을 제공한다.

```js
export function rand() {            // [0, 32767] 정수 반환
  if (customRng === null) return Math.floor(32768 * Math.random());
  return Math.floor(32768 * customRng());
}
```

**xorshift32 를 JS·Kotlin 양쪽에 동일 구현**하고 주입한다.
정수 시프트/XOR 연산만 쓰므로 두 언어에서 비트 단위로 같은 수열이 나온다.

사용처: `computerBoldness = rand() % 5` (라운드 시작마다), FSM 의사결정 일부.
→ 시드가 같으면 FSM 의 행동까지 완전히 재현된다.

---

## 5. 비교 전략

> **해시는 디버깅에 쓸모가 없다.** 불일치가 나와도 "어딘가 다르다"까지만 알려주고
> 어느 프레임의 어느 필드인지는 원리적으로 알려줄 수 없다. 그래서 2단으로 간다.

### 5.0 전제 — 해시는 목표가 아니라 비교 수단이다

포팅의 목표는 **상태 숫자를 일치시키는 것**이고, 해시는 그것을 싸게 확인하는 도구다.

> ⚠️ "해시 맞추기"를 목표로 착각하면, 안 맞을 때 직렬화 순서나 해시 대상 필드를 손대고 싶어진다.
> 그것은 검증을 통과시키는 게 아니라 **무력화하는 것**이다.
> State Spec 은 `proto/` 에 못박아두는 **계약**이고, 불일치 시 고칠 곳은 항상 포팅 코드 쪽이다.

입력도 저장하지 않는다. **시드 하나로 입력 시퀀스까지 재현**되므로 테스트 케이스는 정수 하나다.

```
seed = 42
  └─▶ xorshift32 로 입력 시퀀스 생성 (T 프레임 × 2 플레이어)
        ├─▶ JS      physics.js → 프레임별 상태 → 체인 해시 → H_js   ← 정답
        └─▶ Kotlin  core       → 프레임별 상태 → 체인 해시 → H_kt   ← 맞춰야 할 것
```

### 5.1 State Spec — 무엇을 해싱하는가

해싱 대상은 코드나 객체가 아니라 **상태 숫자들의 정규 직렬화 결과**다.
`proto/state_spec.proto` 에 한 번만 정의하고 JS 오라클과 Kotlin 이 함께 참조한다.

```
StateSpec v1 (고정 순서)
  ball   : x, y, xVelocity, yVelocity, fineRotation, rotation,
           punchEffectRadius, punchEffectX, punchEffectY,
           isPowerHit, expectedLandingPointX,
           previousX, previousPreviousX, previousY, previousPreviousY
  player1: x, y, yVelocity, state, frameNumber, divingDirection,
           lyingDownDurationLeft, isCollisionWithBallHappened,
           normalStatusArmSwingDirection, delayBeforeNextFrame,
           computerBoldness, computerWhereToStandBy, isWinner, gameEnded
  player2: (동일)
  frame  : isBallTouchingGround

직렬화: 위 순서대로 Int 배열 → little-endian 4바이트 → SHA-256
```

- `isBallTouchingGround` (`physicsEngine` 의 반환값) 는 **구현하며 추가**했다. 이유는 두 가지다.
  (1) 하네스의 라운드 리셋 트리거라서, 이 값이 갈라지면 다음 프레임부터 양쪽이 다른 랠리를
  진행하게 되어 불일치 원인이 흐려진다. (2) 포함하면 검사가 **더 엄격해질 뿐 느슨해지지 않는다.**

- `sound` 필드는 **1차 비교에서 제외** (렌더링 전용, 물리에 영향 없음).
  물리 엔진이 세팅하는 값이므로 **포함하는 엄격 모드**를 옵션으로 둔다.
- `boolean` 은 `0/1` 정수로 고정 인코딩한다 (언어별 직렬화 차이 방지).

### 5.2 실행 구조 — Node 자식 프로세스 + 파이프 스트리밍

Kotlin 테스트가 **Node 오라클을 자식 프로세스로 띄우고, stdout 파이프로 프레임별 해시를 받아
자기 계산 결과와 즉시 비교**한다. 두 구현이 lockstep 으로 나란히 전진한다.

```kotlin
// engine-kotlin/conformance/src/test/kotlin/ConformanceTest.kt
val proc = ProcessBuilder("node", "tools/js-oracle/run.mjs",
                          "--seeds", "1..7000", "--mode", "frame-hash").start()

proc.inputStream.bufferedReader().useLines { lines ->
    for (line in lines) {
        if (line.startsWith("E ")) {           // 새 에피소드 시작
            seed = line.drop(2).toLong(); kt = PikaPhysics(false, false); frame = 0
        } else {
            kt.runEngineForNextFrame(inputs[frame])
            assertEquals(line, sha256(StateSpec.serialize(kt)),
                         "seed=$seed frame=$frame")   // ← 첫 불일치가 여기서 바로 잡힌다
            frame++
        }
    }
}
```

```js
// tools/js-oracle/run.mjs — upstream 의 physics.js 를 그대로 import
import { PikaPhysics } from '../../upstream/src/resources/js/physics.js';
import { setCustomRng } from '../../upstream/src/resources/js/rand.js';

for (const seed of seeds) {
  setCustomRng(xorshift32(seed));
  const physics = new PikaPhysics(false, false);
  console.log(`E ${seed}`);
  for (let f = 0; f < T; f++) {
    physics.runEngineForNextFrame(inputs[f]);
    console.log(sha256(serialize(physics)));   // 프레임당 한 줄
  }
}
```

**설계 포인트**

- **프로세스는 한 번만 띄운다.** 7,000회 spawn 하면 그 오버헤드가 물리 계산보다 커진다.
  한 Node 프로세스가 전체 시드를 처리하고 `E <seed>` 마커로 구분한다.
- **전체 상태가 아니라 해시만 흘린다.** 전체 상태를 JSON 으로 보내면 프레임당 200~400 바이트라
  전송이 병목이 된다. 해시 32 바이트면 전체 약 176MB 이고 파이프를 그냥 흘러갈 뿐 저장하지 않는다.
- **입력은 양쪽이 시드에서 각자 생성한다.** 입력을 파이프로 보내지 않는다 (§4 의 xorshift32).

> 대안으로 GraalJS 로 `physics.js` 를 JVM 안에서 직접 실행하는 방법도 있다 (외부 Node 불필요).
> 다만 레퍼런스 구현을 **실제 V8 에서 돌리는 쪽**이 오라클로서 더 신뢰할 만하고,
> Node 는 `viewer-web` 때문에 어차피 필요하다.

### 5.3 불일치 시 축소

lockstep 이므로 **첫 불일치 프레임 F 가 즉시 나온다. 탐색이 필요 없다.**

1. 실패 메시지에서 `seed`, `frame=F` 확보
2. 해당 시드 하나만 `--mode full` 로 재실행 → 프레임 F 의 전체 상태를 양쪽에서 확보
3. **필드 단위 diff** → 어느 필드가 갈라졌는지 확정
4. `(프레임 F-1 의 상태, 프레임 F 의 입력)` 이 **그대로 단위 테스트**가 된다
   — F-1 까지는 양쪽이 동일하므로 에피소드 전체를 재현할 필요가 없다

→ "7,000 에피소드 중 1건 실패"가 **시드 하나짜리 회귀 테스트**로 축소된다.

**평상시엔 싸게, 문제 생겼을 때만 비싸게.** 전수 검사는 해시만 흘리고,
전체 상태 덤프는 실패한 그 한 에피소드에만 쓴다.

### 5.4 해시 두 종류의 용도 구분

| | **프레임별 해시** | **체인 해시** |
|---|---|---|
| 정의 | `SHA256(state_n)` | `h_n = SHA256(h_{n-1} ‖ state_n)` |
| 비교 단위 | 프레임당 1개 | 에피소드당 1개 (최종값) |
| 불일치 위치 | **즉시** | 이분 탐색 필요 |
| 용도 | **개발 루프 · 전수 차분 검사** | **CI 골든 회귀** |

체인 해시는 에피소드당 32바이트라 **저장에 적합**하다.
CI 는 Node·`upstream/` 클론과 오라클 실행이 필요하므로,
**축소 샘플의 체인 해시를 저장소에 커밋해 회귀만 잡고**, 전수 차분은 로컬에서 돌린다.

| | 로컬 전수 차분 | CI 골든 회귀 |
|---|---|---|
| 범위 | 7,015 에피소드 / 4.2M 프레임 | 615 에피소드 (200시드 × 3생성기 + 표적 15) |
| 방식 | Node lockstep, 프레임별 해시 | 커밋된 체인 해시와 Kotlin 결과 비교 |
| Node 필요 | O | **X** |
| 실행 | `./gradlew conformance` | `./gradlew build` (= CI) |

구현: `engine-kotlin/conformance/golden/chain-hashes.txt` (48KB, 615줄).
`--write-golden` 으로 재생성한다.

> ⚠️ **골든 값을 만드는 쪽은 JS 오라클이고, 검증하는 쪽은 Kotlin 혼자다.**
> Kotlin 이 계산한 값을 골든으로 쓰면 "구현이 스스로를 증명하는" 동어반복이 된다.
>
> ⚠️ 골든이 깨졌을 때 재생성해서 초록으로 만드는 것은 §5.0 과 같은 실수다.
> 재생성은 State Spec 이나 하네스를 **의도적으로** 바꿨을 때만이다.

---

## 6. 입력 생성 전략

### 6.1 실측 — 무작위 입력은 얕다

업스트림을 실제로 돌려 랠리 길이를 측정했다.

| 입력 | 표본 | 랠리 길이 (중앙값 / 평균 / 최대) |
|---|---|---|
| 균일 무작위 | 2,000 | 42 / **49.5** / 270 프레임 |
| FSM vs FSM | 500 | 555 / **723.5** / 3,344 프레임 |

구현한 하네스(T=600, 300시드)로 재측정한 결과 — 편향 무작위(b)가 의도대로 작동한다.

| 입력 | 랠리 (중앙 / 평균 / 최대) | 충돌 발생 프레임 비율 |
|---|---|---|
| (a) 균일 무작위 | 38 / **44.0** / 232 | 3.7% |
| (b) 편향 무작위 | 61 / **77.4** / 390 | **7.0%** (1.9배) |
| (c) FSM vs FSM | 347 / 359.3 / 597 | 5.6% |

> FSM 의 평균이 위 표(723.5)보다 짧은 것은 **T=600 절단** 때문이다.
> 즉 FSM 모드는 대부분 라운드 종료에 도달하지 못하므로,
> **라운드 전이 분기는 (a)·(b) 가 담당하고 FSM 은 랠리 내부 분기를 담당**하는 분업이 된다.

**무작위 입력의 랠리는 평균 49.5프레임(약 2초)에 끝난다.**
양쪽이 아무렇게나 움직이니 아무도 공을 못 치고 공이 그냥 낙하한다.
즉 무작위 에피소드를 아무리 늘려도 **"공이 떨어져 바닥에 닿는" 구간만 반복**할 뿐,
충돌 처리·파워히트·착지 예측·네트 충돌 같은 복잡한 분기는 거의 밟지 못한다.

> 플레이어 반너비 32px, 공 반지름 20px, 랠리 42프레임 — 무작위 움직임이 공과 만날 확률이 낮다.
> **"랠리가 이어진 상태"라는 상태 공간 전체가 무작위 탐색으로는 사실상 도달 불가다.**
>
> 이는 ROADMAP Phase 4 (Track B) 가 어려운 이유와 같은 구조다.
> 랜덤 정책끼리는 공을 못 쳐서 학습 신호가 없다는 문제를, 여기 차분 테스트에서 먼저 만난다.

→ **에피소드 개수는 커버리지의 대리 지표가 될 수 없다.** 프레임 수와 그것이 밟는 분기를 봐야 한다.

### 6.2 에피소드 정의 — 고정 T 프레임 + 라운드 자동 리셋

"공이 땅에 닿을 때까지"로 잡으면 무작위 에피소드가 49프레임에 끝난다.
대신 **고정 T 프레임을 돌리고, 랠리가 끝나면 하네스가 라운드를 리셋**해 계속 진행한다.

```
매 프레임:
  isBallTouchingGround = physics.runEngineForNextFrame(inputs)
  if (isBallTouchingGround) {
      isPlayer2Serve = <득점 측에 따라 결정>
      ball.initializeForNewRound(isPlayer2Serve)
      player1.initializeForNewRound(); player2.initializeForNewRound()
  }
```

- 한 시드가 여러 랠리를 커버하고, `initializeForNewRound` 와 서브권 전환 등
  **라운드 전이 코드**까지 밟는다
- `computerBoldness = rand() % 5` 가 라운드마다 재추첨되므로 **RNG 동기화도 함께 검증**된다
- ⚠️ 이 리셋 로직은 **하네스 코드**이며 JS·Kotlin 양쪽에 동일해야 한다.
  `pikavolley.js` 의 점수·게임종료 관리를 이식하는 것이 아니다 (그것은 ROADMAP Phase 2)

### 6.3 배분

오라클 처리량 실측: **물리만 1.14M frame/s, 물리+직렬화+SHA256 0.76M frame/s.**

> 구현 후 재측정: 문자열 생성과 파이프 쓰기까지 포함하면 오라클 단독 **222K frame/s**,
> Kotlin lockstep 비교까지 묶으면 **약 94K frame/s** (리셋 프로브 기준, 엔진이 붙으면 더 낮아진다).
> 전량 4.2M 프레임이 **1분 내외**로, 여전히 "비용이 사실상 없다" 는 판단은 유지된다.

비용이 사실상 없으므로 커버리지가 부족하면 주저 없이 늘린다.

| 전략 | 에피소드 | T | 프레임 | 목적 |
|---|---|---|---|---|
| (a) 균일 무작위 | 3,000 | 600 | 1.8M | 넓은 상태 공간 탐색 |
| (b) 편향 무작위 | 2,000 | 600 | 1.2M | 공 쪽으로 가는 경향을 주어 **충돌을 유도** |
| (c) FSM vs FSM | 2,000 | 600 | 1.2M | **실제 게임 분포** 재현 |
| (d) 표적 케이스 | 15 | 60~300 | 2,280 | **미도달 분기** 직격 (§6.4) |
| **합계** | **7,015** | | **4,202,280** | |

실제 실행 결과: **불일치 0건 / 18.0초** (엄격 모드 19.9초).
커버리지가 100% 로 나왔으므로 배분을 늘리지 않았다.

파이프 트래픽: 4.2M 프레임 × 17바이트(64비트 해시 hex + 개행) ≈ **71MB**. 흘러갈 뿐 저장하지 않는다.

> 스트리밍 해시는 SHA-256 앞 **64비트(hex 16자)로 절단**한다.
> 4.2M 회 비교에서 충돌 확률은 약 `4.2e6 × 2^-64 ≈ 2e-13` 으로 무시 가능하고,
> 파이프 트래픽이 1/4 로 준다. 적대적 상황이 아니라 **차이 탐지**가 목적이므로 충분하다.

### 6.4 표적 케이스 — (d)

코드를 읽고 미리 뽑아둔 희귀 경로. 커버리지 리포트의 미도달 분기를 보고 조정했다.

**케이스 표는 `tools/targeted-cases.txt` 한 곳에만 둔다.** JS 오라클(`targeted.mjs`) 과
Kotlin 하네스(`TargetedCases.kt`) 가 같은 파일을 읽는다.

> `inputs.mjs` ↔ `InputGenerators.kt` 는 **로직**이라 양쪽에 복제하고 하네스로 대조할 수 있다.
> 케이스 표는 **데이터**다. 복제하면 대조할 방법이 없고, 어긋나도 "표적이 안 맞았나 보다" 로
> 조용히 지나간다. 데이터는 공유하고 로직은 복제한다.

케이스 = `(이름, 기본 입력 생성기, 시드, 프레임 수, 초기 상태 override)`.
override 는 `PikaPhysics` 생성 직후·프레임 0 이전에 State Spec 필드명으로 적용한다.

| 표적 | 케이스 수 | 도달 여부 |
|---|---|---|
| 경기 종료 전이 (`state` 5/6, `gameEnded`) | 3 | ✅ **이것만이 유일한 미도달 분기였다** |
| hyper ball 글리치 (`ball.rotation` 5 고착) | 2 | ✅ |
| `fine_rotation` 보정 분기 (음수 → +50, 초과 → −50) | 2 | ✅ |
| 네트 기둥 충돌 (윗면 / 좌 옆면 / 우 옆면) | 3 | ✅ |
| 다이빙 경직 중 충돌 (`state` 4) | 2 | ✅ |
| 좌우 경계 (`ball.x` 20 / 432 부근) | 2 | ✅ |
| 파워히트 직후 상태 | 1 | ✅ |
| **`INFINITE_LOOP_LIMIT` (1000) 도달** | **0** | ❌ **도달 불가 — 아래 참고** |

#### `INFINITE_LOOP_LIMIT` 은 원작 x 범위에서 죽은 코드다

업스트림 주석이 "원래 범위에서는 항상 곧 끝나는 것으로 보인다" 고 말하는데, 실측으로 확인했다.
두 루프를 그대로 복제해 상태 공간을 훑었다 — `x ∈ [20, 432]`, `y ∈ [−40, 252]`,
`xVelocity ∈ [−20, 20]`, `yVelocity ∈ [−400, 400]`.

| 함수 | 최대 반복 횟수 | 최악 지점 |
|---|---|---|
| `calculateExpectedLandingPointXFor` | **456** | `x=192, y=248, xv=0, yv=-12` |
| `expectedLandingPointXWhenPowerHit` | **694** | `x=192, y=177, yv=-391` (실제 게임에선 불가능한 속도) |

1000 에 도달하는 조합은 **0건**이다. 공이 네트 기둥 근처에서 오래 진동할 수는 있어도
(400회대), yVelocity 가 매 반복 +1 되므로 결국 탈출한다.

→ **표적 케이스를 만들지 않았다.** 밟을 수 없는 분기에 케이스를 만들면
"케이스는 있는데 아무것도 겨냥하지 않는" 상태가 되고, 그건 커버리지 숫자만 지키는 짓이다.
포팅 코드에는 상한을 **그대로 옮겨 두었다** — 나중에 x 범위를 바꾸면 그때는 살아난다.

#### 커버리지 숫자는 목표가 아니다

> 커버리지는 "얼마나 테스트했나" 가 아니라 "**어디를 아직 안 봤나**" 를 알려주는 도구다.

100% 를 찍어도 완전한 증명은 아니다. 실제로 이 작업에서 커버리지가 한 일은 딱 하나다 —
**`gameEnded` 분기가 무작위 입력으로는 원리적으로 도달 불가능하다는 것을 드러낸 것.**
나머지 희귀 경로는 이미 (a)·(b)·(c) 가 수백~수천 번씩 밟고 있었다.

그리고 커버리지 100% 는 표적 케이스가 **여전히 표적을 겨냥하고 있는지**는 보장하지 않는다.
`hyper-ball-stuck` 의 `fine_rotation` 을 49 로 바꿔도 100% 는 유지된다.
그래서 케이스마다 "이것을 겨냥한다" 를 실행 가능한 단언으로 박아두었다 (`TargetedCasesTest`).

## 7. 저장소 규칙 (라이선스 대응)

업스트림은 `LICENSE` 가 없고 `package.json` 이 `UNLICENSED` 다.
**submodule 을 쓰지 않고** `.gitignore` + 고정 커밋 스크립트로 간다.

```bash
# scripts/fetch-upstream.sh
UPSTREAM_COMMIT=0d04dbaf165e4131e26f27f6e9def766f62260b3
git clone https://github.com/gorisanson/pikachu-volleyball.git upstream
git -C upstream checkout "$UPSTREAM_COMMIT"
```

- 커밋 해시를 박아두므로 재현성은 submodule 과 동등하다
- clone 경험이 단순하다 (`--recursive` 불필요)
- 원작 에셋은 파일명으로 직접 차단한다.
  ⚠️ `*.png` 같은 **전역 패턴은 쓰지 않는다** — 이후 Phase 의 학습 곡선 그래프와
  히트맵이 조용히 무시되어 원인 찾기 어려운 문제가 된다
- README 에 출처·저작권 고지와 비상업 목적을 명시한다

---

## 8. 포팅 순서

`physics.js` 의 의존 관계를 따라 **아래에서 위로** 올라간다.
각 단계마다 해당 함수만 격리 비교할 수 있도록 오라클에 훅을 둔다.

| 순서 | 대상 | 비고 |
|---|---|---|
| 1 | 상수, `PikaUserInput`, `Player`, `Ball` 필드/초기화 | 상태 구조 먼저 |
| 2 | `isCollisionBetweenBallAndPlayerHappened` | 순수 판정 함수 |
| 3 | `processCollisionBetweenBallAndWorldAndSetBallPosition` | 네트·벽·바닥 충돌 |
| 4 | `processPlayerMovementAndSetPlayerPosition` | 점프·다이빙·경직 |
| 5 | `processCollisionBetweenBallAndPlayer` | 타격·파워히트 |
| 6 | `calculateExpectedLandingPointXFor`, `expectedLandingPointXWhenPowerHit` | 루프 상한 주의 |
| 7 | `letComputerDecideUserInput`, `decideWhetherInputPowerHit` | **FSM** |
| 8 | `processGameEndFrameFor`, `physicsEngine` 통합 | 전체 결합 |

**7번(FSM)은 학습 대상이 아니라 평가 기준이므로 반드시 정확해야 한다.**
FSM 이 원본과 다르면 Track A 의 승률과 Track B 의 held-out 평가가 모두 무의미해진다.

### 8.1 검증은 2스테이지로 나눈다

lockstep 하네스는 엔진 전체가 있어야 도는 것처럼 보이지만, 그렇지 않다.
`uniform`·`biased` 생성기는 `PikaPhysics(false, false)` 로 만들어지므로
`letComputerDecideUserInput` 을 **한 번도 호출하지 않는다.**

| 스테이지 | 포팅 범위 | 검증 수단 | FSM |
|---|---|---|---|
| A | 1~6, 8 (FSM 제외 전부) | `--gen uniform` · `--gen biased` (+ `--strict`) | `TODO()` 스텁 |
| B | 7 (FSM) | `--gen fsm` | 포팅 |

이렇게 나누는 이유는 **디버깅 표면적**이다.
FSM 은 `rand()` 를 소비한다 (`rand() % 20`, `rand() % 2`). 여기에 버그가 있으면
난수 스트림 자체가 한 칸 밀려 **그 시점 이후 모든 프레임이 갈라진다.**
물리 버그처럼 "이 필드가 1 다르다" 로 나타나지 않고 "갑자기 전부 다르다" 로 나타난다.
비-FSM 물리를 먼저 4.2M 프레임으로 고정해 두면, 스테이지 B 의 불일치는 원인이 FSM 으로 좁혀진다.

스테이지 A 에서 스텁을 `TODO()` 로 둔 것도 의도다. 0 을 반환하는 조용한 스텁이었다면
"FSM 경로를 안 탄다" 는 전제가 깨져도 모르고 지나간다. 크게 터지면 전제가 검증된다.

### 8.2 FSM 포팅에서 조심할 곳

| 위치 | 함정 |
|---|---|
| `rand() % 20 === 0` | **`else if` 안에 있다.** 앞 조건(착지점이 멀다)이 참이면 소비되지 않는다. 두 `if` 로 풀면 난수가 어긋난다 |
| `decideWhetherInputPowerHit` 진입 | `rand() % 2` 를 **조건과 무관하게 매번** 소비한다. 그 결과로 y 방향 탐색 순서만 바뀐다 (`-1,0,1` / `1,0,-1`) |
| `Number(player.isPlayer2)` | 진영 경계 계산에 bool 을 정수로 쓴다. Kotlin 에는 암묵 변환이 없으므로 명시적으로 옮긴다 |
| `expectedLandingPointXWhenPowerHit` 의 네트 처리 | `calculateExpectedLandingPointXFor` 와 **다르다.** 기둥 옆면 반사를 계산하지 않는다. 업스트림 주석에 따르면 컴퓨터가 실수하도록 의도된 코드다 — **고치면 FSM 이 원본보다 강해진다** |
