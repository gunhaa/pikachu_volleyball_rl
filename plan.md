# plan — 라이브 대전: 정책 입력원 (ROADMAP Phase 5)

`PRD.md` 의 요구사항을 **어떻게** 달성할지에 대한 기술 계획.

## 1. 접근 방식

순서를 **"관측 골든 → JS 관측 → export → 정책 입력원 → 평가 경기 재현 → 적재 → 뷰어"** 로 잡는다.

M5-b(평가 경기 2,400개 바이트 일치)는 M5-a(관측 일치)를 포함한다 — 관측이 틀리면 경기가 갈라지므로.
그런데도 M5-a 를 먼저, 따로 세우는 이유는 **M5-b 의 실패는 원인을 가리키지 못하기 때문**이다. 경기가
312 프레임째에 갈라졌다면 범인은 관측 인코딩 · 결정 시점 · 추론 오차 · 엣지 트리거 · 시드 유도 중 어느 것이든
될 수 있다. 관측을 먼저 비트 단위로 고정해 두면 M5-b 에서 남는 용의자는 "추론" 과 "배선" 둘뿐이다.
Phase 4 가 규칙층을 Node 에서 먼저 증명하고 브라우저에 실은 것과 같은 논리다.

두 번째 원칙: **증명은 Node 에서, 확인은 브라우저에서.** 브라우저와 Node 가 같은 모듈 · 같은 ORT wasm 을
쓰게 만들고(NFR-7), 2,400게임 전수는 Node 에서 돌린다. 브라우저에서는 그 등식이 성립함을 6게임으로 확인한다.

세 번째 원칙: **학습 쪽은 건드리지 않는다.** 정답은 Kotlin 이고, 이 작업은 JS 가 그 정답을 따라가게 만드는 일이다.
`ObsEncoder.kt` 를 "JS 에 맞추기 쉽게" 고치고 싶어지는 순간이 올 텐데, 그러면 Track A 체크포인트가 본 관측이
바뀌어 **평가받은 정책이 더 이상 존재하지 않게** 된다 (NFR-1).

## 2. 검증된 전제

### 2.1 env 골든은 관측만 떼어 쓸 수 없다

`EnvGolden.chainFor` (`EnvGolden.kt:127`) 는 매 스텝 `SHA256(h ‖ obs ‖ reward ‖ terminated ‖ truncated)` 를
만든다 (`:141-147`). 관측과 보상이 같은 해시에 섞여 있고, 벡터 환경(N=1~16)의 autoreset 스텝까지 들어간다.
JS 가 이 해시를 재현하려면 보상 5항과 `VectorEnv` 의 스텝 규약을 전부 이식해야 한다 — 브라우저에는
필요 없는 코드를 증명용으로만 만드는 일이다.

→ 같은 케이스 구성으로 **관측만 담은** 새 골든을 Kotlin 이 만든다 (§3). 기존 골든 파일은 그대로 둔다.

### 2.2 골든이 실제 정책 구성을 덮지 않는다

| 사실 | 근거 |
|---|---|
| Track A 체크포인트는 41차원 (진영 플래그 on) | `env_client.py:56` `OBS_INCLUDE_SIDE_FLAG = True`, `for_policy()` 가 기본으로 켠다 (`:103`) |
| 평가의 절반은 오른쪽 진영 = 미러 on | `for_policy()` 가 `swapped_envs = num_envs // 2` (`:105-106`) |
| 골든의 진영 플래그 케이스는 **왼쪽 외부 슬롯 하나** | `EnvGolden.kt` `Case("side-flag", EnvConfig(obs = …includeSideFlag = true))` — 슬롯 기본값 `EXTERNAL_VS_FSM` |
| 미러가 걸리는 케이스(`trackA-right` · `trackB` · `no-mirror`) 는 전부 진영 플래그 off | `EnvGolden.CASES` |

즉 **Track A 정책이 오른쪽에서 보는 관측 — 미러 + 진영 플래그 +1 — 은 어느 골든도 계산하지 않는다.**
진영 플래그는 미러링하지 않는다는 규칙(`ObsEncoder.kt:101`)이 정확히 그 조합에서만 의미를 가진다.
→ `side-flag-right` (`FSM_VS_EXTERNAL`) · `side-flag-both` (`EXTERNAL_VS_EXTERNAL`) 두 케이스를 더한다.

### 2.3 float32 의미

Kotlin 관측은 전부 `Float` 연산이다. `unit(v, lo, hi) = 2f * (v - lo) / (hi - lo) - 1f` (`ObsEncoder.kt:113`) 는
정수 뺄셈 → float 곱 → float 나눗셈 → float 뺄셈, **연산마다 float32 로 반올림**된다.

JS 는 double 로 계산한다. double 로 계산한 뒤 float32 로 한 번 반올림하는 것은 **단일 연산**(+ − × ÷)에
대해서는 float32 연산과 같은 결과를 준다 (double 의 가수 53비트 ≥ 2 × 24 + 2, 이중 반올림 무해 조건).
그러나 여러 연산을 double 로 이어서 한 뒤 마지막에만 반올림하면 **다를 수 있다.** → 연산마다 `Math.fround`.

⚠️ **`-0`.** Kotlin 의 미러는 `Int` 에서 부호를 뒤집는다 — `-p.divingDirection` (`:71`), `-ball.xVelocity` (`:82`).
`Int` 에는 `-0` 이 없다. JS 에서 `-0 / 20` 은 `-0` 이고 float32 비트는 `0x80000000` 이다 (측정: Node 24.11.1).
수치로는 같아서 정책은 같은 행동을 하지만 **체인 해시는 다르다.** M5-a 가 비트 단위이므로 반드시 막는다.

### 2.4 argmax 여유 — 측정

Track A 3시드를 평가기 그대로(`evaluate_target`, argmax, 16 env) 진영별 100게임 돌리며 정책이 본 관측을 모았다.
고유 관측은 시드당 15,000 ~ 19,000 개다 (진영별 40게임에서 이미 13,000 ~ 18,000 — 게임이 매우 반복적이다,
Phase 4 기록 "고정 패턴").

| 시드 | 고유 관측 | 여유 최솟값 | 0.1% 분위 | 1% 분위 | 중앙값 | < 2 × 10⁻⁵ | < 10⁻⁴ |
|---|---|---|---|---|---|---|---|
| 0 | 19,261 | **1.11 × 10⁻⁵** | 7.2 × 10⁻⁴ | 6.1 × 10⁻³ | 0.309 | 2 | 6 |
| 1 | 14,999 | 2.86 × 10⁻⁵ | 2.5 × 10⁻⁴ | 3.1 × 10⁻³ | 0.246 | 0 | 5 |
| 2 | 16,699 | 3.74 × 10⁻⁵ | 4.7 × 10⁻⁴ | 3.8 × 10⁻³ | 0.233 | 0 | 5 |

로짓 오차 (float64 참값 대비 최대 절대 오차, 고유 관측 전부):

| 백엔드 | 오차 최대 | argmax ≠ torch 배치 |
|---|---|---|
| torch float32 배치 (평가기가 쓰는 것) | 5.8 ~ 7.5 × 10⁻⁶ | — |
| torch float32 배치 1 | 2.1 ~ 2.4 × 10⁻⁶ | 0 |
| onnxruntime 1.30.0 CPU (Python) | 6.2 ~ 6.8 × 10⁻⁶ | 0 |
| **onnxruntime-web 1.30.0 wasm (Node, 배치 1)** | torch 대비 2.9 ~ 3.8 × 10⁻⁶ | **0 / 50,959** |
| 순차 누적 float32 matmul (순수 JS 구현 흉내) | 6.0 ~ 7.2 × 10⁻⁶ | 0 |
| float64 참값 | 0 | 0 |

읽는 법: 두 로짓의 **차**가 뒤집히려면 각 로짓 오차의 합이 여유를 넘어야 한다. 오차 상한을 8 × 10⁻⁶ 로 잡으면
위험 구간은 여유 < 1.6 × 10⁻⁵ 이고, 여기에 든 관측은 seed 0 의 2개뿐이다 — 그 둘도 뒤집히지 않았다.
**M5-b 가 100% 로 나올 것을 기대하지만, 보장이 아니라 측정이다.** 그래서 하네스가 불일치 프레임의 여유를
보고하게 만든다 (§8.3). Phase 6 의 체크포인트는 이 분포가 다를 수 있다.

측정 스크립트는 이 계획의 산출물이 아니다 — `export_onnx.py` 의 자기 검증(§5.3)이 같은 일을 체크포인트마다 한다.

### 2.5 onnxruntime-web

| 항목 | 값 | 근거 |
|---|---|---|
| 최신 | **1.30.0** (2026-09 기준 npm `latest`), MIT | `npm view onnxruntime-web` |
| 추론 API | `InferenceSession.run()` → **Promise 만** 있다 | ORT JS API. 동기 경로 없음 |
| 지연 (Node 24, wasm, 스레드 1, 배치 1) | p50 **0.009 ~ 0.010 ms** · p99 0.011 ~ 0.036 ms · 최대 4.4 ms (첫 호출) | 시드별 15,000 ~ 19,000 회 |
| 세션 생성 | 첫 번째 206 ms, 이후 2 ms (wasm 컴파일이 첫 번째에만) | 같은 측정 |
| 패키지 | node_modules 139 MB, 실제로 쓰는 `ort-wasm-simd-threaded.wasm` 14 MB | `du`, `ls -la dist/` |
| Node 진입점 | `"."` 의 `node` 조건 → `ort.node.min.mjs`. 브라우저 `"."` → `ort.bundle.min.mjs`. `"./wasm"` 은 양쪽 공통 | `package.json` `exports` |
| ONNX 크기 | 97,498 B (41 → 128 → 128 → 18, 노드 `Gemm Tanh Gemm Tanh Gemm`) | export 결과 |

⚠️ Node 와 브라우저가 기본 진입점(`"."`)을 쓰면 **서로 다른 파일**을 로드한다. NFR-7 을 위해 둘 다
`onnxruntime-web/wasm` 을 import 하고, Node 에서 그 진입점이 실제로 도는지 P3 첫 항목에서 확인한다.

### 2.6 ONNX export 결정론 — 측정

torch 2.14.0 에서 같은 체크포인트(`track-a-seed0/ckpt-final.pt`)를 두 번씩 내보냈다.

| exporter | opset · IR | 1회차 SHA-256(앞 16) | 2회차 | 결정론 | 의존성 |
|---|---|---|---|---|---|
| 레거시 (`dynamo=False`) | 17 · 8 | `7198af293c31d282` | `7198af293c31d282` | ✅ | `onnx` (없으면 `OnnxExporterError: Module onnx is not installed!`) · `DeprecationWarning` |
| dynamo (torch 2.9+ 기본) | 18 · 10 | `660d5fcc5bc694dd` | `b9741e11264e33f7` | ❌ | `onnx` + `onnxscript` |

두 exporter 모두 같은 5-노드 그래프를 만든다. dynamo 쪽의 차이는 그래프가 아니라 부수 정보(이름 · 메타)로 보이지만,
정규화 규칙을 만들어 유지하는 것보다 **결정론인 쪽을 쓰는 편이 싸다.** → 레거시 exporter, opset 17 (§5.1).

### 2.7 평가 경기가 이미 기록돼 있다

`scripts/baseline-replays.sh` 가 만든 `runs/baselines/track-a-seed{0,1,2}/` — 각 800 파일 (`e{env}-g{game}.pkr`) +
`manifest.jsonl`. 평가 구성은 `--num-envs 64 --seed <시드> --games 400`, `max_rally_frames = 3000`,
`replay_frame_cap = 60000` 이다 (`evaluate_target` 기본값).

한 게임을 **리플레이를 보지 않고** 다시 치르는 데 필요한 것:

| 값 | 어디서 | 근거 |
|---|---|---|
| `baseSeed` | 묶음 이름의 시드 (`--seed`) | `baseline-replays.sh` |
| `envIndex`, `gameInEnv` | manifest | Phase 4 FR-6 |
| 첫 서브 | `gameInEnv % 2 == 1` | `PikaEnv.startGame` (`gameCounter % 2 == 1`) |
| 첫 랠리 번호 | 같은 env 의 앞선 게임들의 **랠리 수 합** | `rallyCounter` 는 게임을 넘어 증가하고 (`PikaEnv.advanceRally`), 게임 경계에서도 `++` 후 `startGame` |
| 슬롯 구성 | `envIndex ≥ 32` 면 `(Fsm, External)` | `swapped_envs = 32` 는 **뒤쪽** 환경 (`VectorEnv.configFor`) |

⚠️ 앞선 게임의 랠리 수는 리플레이의 `rallyFrames.length` 에서 읽는다. 즉 하네스는 **첫 랠리 번호를 얻기 위해서만**
앞 게임의 리플레이를 본다. 그 게임의 입력 · 시드는 읽지 않는다 — 읽으면 재현이 아니라 재생이 된다.
평가는 행마다 할당량만큼 **연속으로** 센다 (`_quotas`, 400 / 32 = 12.5 → 12 또는 13 게임) 이므로
`gameInEnv` 는 0부터 빈틈이 없다.

### 2.8 서버의 라이브 참가자 규칙

`Serve.submitLive` (`Serve.kt:177`) 는 참가자를 **바이트의 슬롯 플래그에서만** 유도한다 — External 이면
`Participant("human", null, "keyboard")` (`:182`). "요청이 무엇을 주장하든 슬롯 플래그가 진실이다" 가 원칙이다.
이 원칙은 유지하되, 플래그가 External 인 슬롯에 대해서만 "사람인가 어느 정책인가" 의 주장을 받는다 (§9).

## 3. 결정 관측 골든 (FR-1, M5-a)

### 3.1 무엇을 해시하는가 — "결정 관측"

`PikaEnv` 가 내보내는 관측에는 두 종류가 있다.

| 관측 | 정책이 그걸로 행동하는가 |
|---|---|
| 리셋 관측, 일반 스텝의 관측 | ✅ 다음 스텝의 행동이 이것으로 정해진다 |
| **terminal 관측** (랠리의 마지막) | ❌ 다음 스텝은 autoreset — 행동을 **버린다** (`PikaEnv.step` (1)) |

골든은 **물리 프레임을 하나 돌리기 직전, 그 프레임의 행동을 정한 관측**만 담는다. terminal 관측은 PPO 의
가치 부트스트랩에는 쓰이지만 브라우저에서는 어떤 결정에도 쓰이지 않는다. 이렇게 정의하면 JS 쪽은
`decide` 시점에 인코딩한 것만 해시하면 되고, **결정 시점 오류(리셋 전 상태로 결정)도 같은 골든이 잡는다.**

```
h_0 = 0³²
h_n = SHA256(h_{n-1} ‖ slot ‖ obs_n(float32 LE))     // 외부 슬롯마다, 슬롯 0 → 1 순서
```

### 3.2 케이스

`EnvGolden.CASES` 11개의 **구성**(이름 · `EnvConfig` · 프레임 수)을 재사용하고 2개를 더한다. 벡터 크기는 필요 없다 —
환경 i 의 수열은 `(baseSeed, i, k)` 만의 함수이므로 (`PikaEnv` 머리말) 케이스마다 환경을 최대 2개(0, 1)만 뜬다
(`seed-7` 은 원래 1개). `wide`(N=16) 는 그래서 다른 케이스와 같은 모양이 되지만, 구성 목록을 EnvGolden 과
1:1 로 맞춰 두는 편이 "11 케이스 전부" 라는 문장을 검사 가능하게 만든다.

| 추가 케이스 | 구성 | 덮는 것 |
|---|---|---|
| `side-flag-right` | `FSM_VS_EXTERNAL`, `includeSideFlag = true` | 미러 + 진영 플래그 +1 — Track A 오른쪽 평가 그 자체 |
| `side-flag-both` | `EXTERNAL_VS_EXTERNAL`, `includeSideFlag = true` | 두 슬롯이 같은 물리 상태에서 다른 관측 (Track B · 정책 vs 정책) |

행동은 `EnvGolden.ActionSequence` 를 그대로 쓴다 (환경별 독립 RNG). 케이스당 프레임 수도 `EnvGolden` 과 같다.
합계 = 2 환경 × (2,000 × 3 + 1,000 × 9 + 500) ≈ **3만 프레임** (× 1~2 슬롯)이라 `./gradlew build` 안에 들어간다.

### 3.3 JS 는 무엇을 받는가

JS 는 `EnvConfig` 를 모른다. 그래서 골든은 **경기를 리플레이로** 넘긴다 — Phase 4 의 `ReplayRecorder` 를
환경마다 달고, 케이스가 끝날 때 진행 중이던 게임은 상한 컷으로 내보낸다. 한 환경이 여러 게임을 치르면
게임마다 리플레이가 하나씩 나온다.

```
engine-kotlin/env/golden/obs/
  chains.json        { cases: [{ name, obs: {includeLanding, includeSideFlag, mirror, winningScore},
                                 envs: [{ envIndex, games: ["<case>-e0-g0.pkr", …], decisions, chain }] }] }
  trackA-e0-g0.pkr …
```

JS 하네스:

```js
for (const env of case.envs) {
  const chain = new ObsChain();
  for (const file of env.games) {
    const { runner } = runnerFromReplay(read(file), {
      wrap: (src, slot) => src.kind === 'fsm' ? src : probe(src, slot, (obs) => chain.push(slot, obs)),
    });
    while (!runner.ended) runner.step();
  }
  assert.equal(chain.hex(), env.chain);
  assert.equal(chain.decisions, env.decisions);
}
// probe = decide() 직전에 encoder.encode(runner, slot, buf) 를 부르고 원래 입력원에 위임
```

`runnerFromReplay` 에 입력원을 감싸는 훅(`wrap`) 하나를 더한다. 이것은 러너의 동작을 바꾸지 않는다.

⚠️ Kotlin 쪽 결정 관측의 정의를 코드로 옮길 때: `PikaEnv.step` 이 `pendingReset` 이면 (autoreset 스텝) 그 스텝에
**들어간 행동은 버려지고** 그 스텝이 **내보낸 관측은 새 랠리의 첫 결정 관측**이다. 생성기는
"물리가 도는 스텝에 들어간 행동을 정한 관측" 을 모은다 — 직전 호출이 내보낸 관측을 들고 있다가, 이번 호출이
`pendingReset` 이 아니면 해시에 넣는다. 게임이 끝난 뒤의 autoreset 은 새 게임의 리셋 관측을 내보내는데,
JS 에서는 **다음 리플레이의 첫 `decide`** 가 그것이다. 두 쪽의 수열이 여기서 맞물린다.

⚠️ 케이스의 마지막 스텝이 terminal 이면 그 뒤의 결정 관측은 없다 (다음 물리 프레임이 없다). 양쪽 모두
"물리 프레임 수 = 결정 수" 로 센다. `decisions` 필드가 그 수를 박아 둔다.

### 3.4 Kotlin 회귀

`ObsGoldenTest` 는 (1) 골든 파일과 다시 계산한 체인이 같은지, (2) 리플레이 바이트가 같은지, (3) 케이스 목록이
`EnvGolden.CASES` 이름을 전부 포함하는지를 본다. 재생성은 `./gradlew :engine-kotlin:env:writeObsGolden` —
EnvGolden 과 같은 규칙(**무엇을 왜 바꿨는지 커밋 메시지에**)이다.

## 4. JS `ObsEncoder` (FR-2, FR-3)

### 4.1 무엇을 읽는가

Kotlin 은 `PikaGame` 을 읽고, JS 는 `GameRunner` 를 읽는다. 필드 대응:

| Kotlin | JS | 비고 |
|---|---|---|
| `game.physics.player1/2` | `runner.physics.player1/2` | 업스트림 필드명 그대로 (`yVelocity`, `frameNumber`, `divingDirection`, `lyingDownDurationLeft`, `isCollisionWithBallHappened`, `delayBeforeNextFrame`, `state`) |
| `game.physics.ball` | `runner.physics.ball` | `x, y, xVelocity, yVelocity, isPowerHit, expectedLandingPointX` |
| `game.scores` | `runner.scores` | 진영 순서 |
| `game.isPlayer2Serve` | `runner.isPlayer2Serve` | |

업스트림은 상수 대부분을 `export` 하지 않는다 (`physics.js` 는 `GROUND_HALF_WIDTH` 만). 정규화 상수는
`obs_spec.proto` 주석에 숫자로 박혀 있으므로 (`proto/obs_spec.proto` "정규화 상수" 절) 그 숫자를 JS 에 둔다.
그 숫자가 proto 주석과 같은지는 테스트가 주석을 파싱해 본다 — 세 번째 구현이 두 번째 구현을 읽지 않는다는
원래 설계(`ObsSpec.kt` 머리말)를 지킨다.

### 4.2 코드 스케치

```js
const f = Math.fround;
const unit = (v, lo, hi) => f(f(f(2 * (v - lo)) / (hi - lo)) - 1);   // ObsEncoder.kt:113 과 같은 연산 순서
const neg = (v) => (-v) | 0;                                          // Int 부호 반전. -0 이 나오지 않는다

function writePlayer(p, isLeft, flip, out, i) {
  const x = flip ? 432 - p.x : p.x;
  out[i++] = isLeft ? unit(x, 32, 184) : unit(x, 248, 400);
  out[i++] = unit(p.y, 108, 244);
  out[i++] = f(p.yVelocity / 16);
  for (let k = 0; k < 7; k++) out[i++] = p.state === k ? 1 : 0;
  out[i++] = f(p.frameNumber / 5);
  out[i++] = flip ? neg(p.divingDirection) : p.divingDirection;      // ⚠️ -0 금지 (§2.3)
  out[i++] = f(p.lyingDownDurationLeft / 3);
  out[i++] = p.isCollisionWithBallHappened ? 1 : 0;
  out[i++] = f(p.delayBeforeNextFrame / 5);
  return i;
}
// ball.x_velocity: f((flip ? neg(b.xVelocity) : b.xVelocity) / 20)
// match: f(mine / ws), f(theirs / ws), f((mine - theirs) / ws), serving, side_flag(-1 | +1, 미러하지 않는다)
```

`out` 은 `Float32Array` 다. 마지막 대입이 float32 반올림이므로 `f(...)` 의 바깥 한 겹은 생략해도 같지만,
**중간 연산의 반올림은 생략할 수 없다.** 코드를 읽는 사람이 둘을 구별하지 못하므로 전부 적는다.

⚠️ `(mine - theirs) / ws` 가 0 이면 `+0`, 음수 나눗셈 결과가 0 이 되는 경우는 없다 (정수 차가 0 일 때만 0).
`-0` 이 생길 수 있는 곳은 부호 반전 두 곳뿐이다 — 테스트에 "미러된 정지 공 · 정지 다이빙" 을 명시적으로 넣는다.

### 4.3 레이아웃 해시

`obs-spec.mjs` 가 `obs_spec.proto` 를 읽어 `ObsSpec.fieldNames` 와 같은 목록을 만들고 SHA-256 을 낸다
(`ObsSpec.kt` 의 `parseProto` · `flatten` 과 같은 규칙, `@optional:` 태그 포함). 브라우저는 proto 를 읽지 않는다 —
빌드 시 목록을 상수로 넣고, **Node 테스트가 그 상수와 proto 를 대조한다.** ONNX 메타데이터의 레이아웃 해시(§5.2)와
브라우저 인코더의 해시가 다르면 `PolicySource` 는 만들어지지 않는다.

## 5. ONNX export (FR-5 ~ FR-7, M5-d)

### 5.1 exporter 선택

| 안 | 결정론 | 유지비 | 판단 |
|---|---|---|---|
| **레거시 TorchScript exporter, opset 17** | ✅ (§2.6) | `DeprecationWarning`. torch 가 지우면 깨진다 | **채택.** `uv.lock` 이 torch 를 고정하고, 결정론 테스트가 exporter 변화를 즉시 잡는다 |
| dynamo exporter (기본) | ❌ 실행마다 다른 바이트 | `onnxscript` 추가 | 기각. M5-d 의 "같은 체크포인트 → 같은 SHA" 가 성립하지 않는다 |
| `onnx.helper` 로 그래프 직접 작성 (Gemm/Tanh 5노드) | ✅ (정의상) | 망 구조가 바뀌면 손으로 따라가야 한다 | 예비안. 레거시가 사라지면 이것으로 간다. 수치 검증(§5.3)이 구조 불일치를 잡는다 |

opset 17 인 이유: 레거시 exporter 가 이 그래프에 대해 문제없이 내는 값이고, ORT 1.30 이 지원 범위 안에 둔다.
그래프가 `Gemm` 과 `Tanh` 뿐이라 opset 에 따라 의미가 달라질 연산이 없다.

### 5.2 내보내는 것

```python
torch.onnx.export(
    net.actor, (torch.zeros(1, net.obs_dim),), path,
    input_names=["obs"], output_names=["logits"],
    dynamic_axes={"obs": {0: "batch"}, "logits": {0: "batch"}},
    opset_version=17, dynamo=False,
)
model = onnx.load(path)
for k, v in {
    "pika.checkpoint_sha256": ckpt_sha,  "pika.obs_dim": str(net.obs_dim),
    "pika.obs_layout_hash": layout_hash, "pika.action_count": "18",
}.items():
    model.metadata_props.add(key=k, value=v)       # 정렬된 순서로 넣는다 — 결정론
onnx.save(model, path)
```

- **actor 만** 내보낸다. 가치 헤드는 브라우저가 쓰지 않는다 (PRD §6).
- 레이아웃 해시는 체크포인트에 없다. `obs_dim` 이 41 이면 `(landing on, side flag on)` 레이아웃이라는 것을
  export 도구가 **추론하지 않는다** — 인자로 받거나(`--obs-layout`) 기본값 `for_policy()` 레이아웃을 쓰고,
  그 레이아웃의 `dim` 이 체크포인트 `obs_dim` 과 다르면 실패한다.
- 메타데이터를 넣은 뒤의 파일이 결정론인지(`onnx.save` 재직렬화)도 테스트한다.

### 5.3 자기 검증

export 직후 onnxruntime(Python)으로 다시 읽어, **평가 경기의 관측**에 대해 torch 와 대조한다.

| 검사 | 기준 |
|---|---|
| 로짓 최대 절대 오차 | ≤ 10⁻⁴ (측정값 ~7 × 10⁻⁶ 의 10배 이상 여유 — 구조가 틀리면 O(1) 로 튄다) |
| argmax 일치 | 100% |
| 여유 분포 | 최솟값 · 0.1% 분위 · `< 1.6 × 10⁻⁵` 개수를 레지스트리 줄에 적는다 (§2.4 의 표를 체크포인트마다) |

관측은 서버를 띄워 진영별 N게임(기본 40) 평가하며 모은다 — `evaluate_target` 에 관측을 가로채는 정책 래퍼를
넘기면 되고 `evaluate.py` 는 고치지 않는다. 실패하면 ONNX 를 지우고 0 이 아닌 코드로 끝난다.

### 5.4 레지스트리

```jsonl
{"label": "track-a-seed0", "checkpoint": "runs/track-a-seed0/ckpt-final.pt",
 "checkpoint_sha256": "50c77b87…", "onnx": "runs/policies/track-a-seed0.onnx", "onnx_sha256": "…",
 "obs_dim": 41, "obs_layout_hash": "d696c0b3…", "opset": 17, "torch": "2.14.0", "onnx_pkg": "1.23.0",
 "margin": {"min": 1.1e-05, "p001": 7.2e-04, "below_1_6e-5": 2, "observations": 19261},
 "git": "…", "exported_at": "2026-09-…"}
```

- `runs/policies/` 는 `.gitignore` 대상(`runs/`)이다. 쌍은 ROADMAP 결과 표에도 적는다 (M5-d).
- 같은 label 로 다시 export 하면 줄을 **덧붙이지 않고** 체크포인트 SHA 가 같은지 본다 — 같으면 ONNX SHA 도 같아야 한다
  (결정론). 다르면 실패한다. 레지스트리가 조용히 두 판을 들고 있는 상태를 만들지 않는다.

## 6. 추론 런타임 선택

| 안 | 동기/비동기 | 수치 | 유지비 | 판단 |
|---|---|---|---|---|
| **onnxruntime-web (wasm, 스레드 1)** | 비동기만 | torch 대비 ≤ 3.8 × 10⁻⁶, argmax 불일치 0 (§2.4) | 패키지 139 MB · 러너에 준비 단계 | **채택.** ROADMAP 의 결정이고, 망 구조가 바뀌어도(Phase 6·7) export 만 다시 하면 된다. Phase 9 의 ONNX rollout 과 같은 산출물이다 |
| 순수 JS MLP (가중치 JSON) | 동기 | 순차 누적 흉내로 argmax 불일치 0 | 망 구조 변경마다 JS 재작성 + 재증명 | 기각. 동기라는 장점이 크지만 "export 한 파일이 곧 정책" 이라는 성질을 잃는다 — M5-d 의 해시 쌍이 가리키는 대상이 사라진다 |
| 서버 추론 (WebSocket) | 비동기 + 왕복 지연 | torch 그대로 | 서비스 추가 | 기각 (ROADMAP Phase 5) |

**WebGPU/WebGL 백엔드를 쓰지 않는다** — 백엔드마다 누적 순서와 정밀도가 달라 M5-b 가 사용자의 GPU 의 함수가 된다.
망이 40 → 128 → 128 → 18 이라 wasm 한 스레드로 0.01 ms 다.

스레드 1 인 이유: (1) 결정론 — 스레드 분할이 누적 순서를 바꿀 수 있다. (2) 멀티스레드 wasm 은
`SharedArrayBuffer` 가 필요하고, 그것은 COOP/COEP 헤더(cross-origin isolation)를 요구한다 — vite · serve 설정이
한 겹 늘어난다. 얻는 것이 없다.

## 7. `PolicySource` 와 러너 배선 (FR-8, FR-9)

### 7.1 문제

`GameRunner.step()` (`runner.mjs:110`) 은 한 호출 안에서 ① 물리 RNG 재설정 ② **보류된 랠리 리셋** ③ 입력원 `decide`
④ 물리 한 프레임을 한다. ORT 는 비동기이므로 ③ 에서 기다릴 수 없다. 그렇다고 ③ 전에 미리 추론해 두면
**② 가 아직 안 된 상태**(랠리가 끝난 프레임)의 관측으로 결정하게 된다 — Kotlin 의 결정 시점과 1프레임 어긋난다.

### 7.2 해법 — `beginFrame()` 을 떼어 낸다

```js
// runner.mjs
beginFrame() {                         // 멱등. step() 도 맨 앞에서 부른다
  setCustomRng(this.physicsRng);
  if (this.pendingRallyStart) this.startNextRally();
}
step() {
  if (this.ended) throw …;
  this.beginFrame();                   // ← 기존 ①② 를 옮긴 것. 동작은 같다
  for (…) if (src.kind !== 'fsm') src.decide(this, i === 1, inputs[i]);
  …
}

// 라이브 루프 (브라우저 · Node 하네스 공통) — src/runner/live-loop.mjs
async function advance(runner) {
  runner.beginFrame();
  await Promise.all(runner.sources.map((s, i) => s.prepare?.(runner, i === 1)));
  runner.step();                       // beginFrame 은 두 번째 호출에서 아무것도 안 한다
}
```

```js
// sources/policy.mjs
export class PolicySource {
  kind = KIND.EXTERNAL;
  constructor(model, encoder) { this.model = model; this.encoder = encoder; this.edge = new EdgeTrigger(); this.pending = -1; }
  async prepare(runner, isPlayer2) {
    this.encoder.encode(runner, isPlayer2 ? 1 : 0, this.obs);
    this.pending = await this.model.argmax(this.obs);          // 0..17
    this.preparedAt = runner.frame;
  }
  decide(runner, isPlayer2, out) {
    if (this.preparedAt !== runner.frame) throw new Error('prepare 없이 decide — 비동기 루프를 쓰세요');
    decodeActionHeld(this.pending, out);                        // powerHit = 누른 상태
    out.powerHit = this.edge.apply(out.powerHit === 1);        // keyboard.js 와 같은 엣지 규칙
  }
  onRallyStart() { this.edge.reset(); }                         // PikaEnv.beginRally 와 같은 자리
}
```

- `prepare` 가 없는 입력원만 꽂힌 경기는 기존 동기 `step()` 을 그대로 쓴다 — 리플레이 · seek · Phase 4 테스트 무변경.
- `decide` 가 `prepare` 없이 불리면 **예외**다. 조용히 지난 프레임의 행동을 쓰는 것보다 낫다.
- `onRallyStart` 는 러너가 `reset()` 과 `startNextRally()` 에서 부른다 (`runner.mjs` 기존 동작). Kotlin 도 게임 시작과
  랠리 시작에서 `edges.reset()` 한다 (`PikaEnv.beginRally`). 둘이 같은 자리다.

### 7.3 함정

- ⚠️ **argmax 동률 규칙.** torch `argmax` 는 최댓값이 여럿이면 **첫 인덱스**다. JS 는 `>` 로 훑어야(≥ 가 아니라) 같다.
  정확한 동률은 측정에서 나온 적 없지만, 규칙이 다르면 동률이 나오는 순간 갈라진다.
- ⚠️ **엣지 트리거의 입력은 "누른 상태"다.** 행동 코드 홀수 = 누름 (`ActionCodec.powerHitHeld`). 기존
  `decodeAction` (`action.mjs`) 은 리플레이용이라 그 비트를 그대로 `powerHit` 에 쓴다 — 정책에 쓰면 엣지 변환이 빠진다.
  `ActionCodec.decode(action, out, edge)` 와 같은 모양의 함수를 따로 둔다.
- ⚠️ **ORT 가 `rand()` 를 부르지 않는지.** 부를 이유는 없지만 M4-e 격리 테스트와 같은 형식으로 "정책 경기를
  렌더링하며 치른 체인 = 렌더링 없이 치른 체인" 을 한 번 본다.
- ⚠️ **두 정책이 같은 ONNX 를 쓰면** 세션을 공유해도 된다(ORT 세션은 상태가 없다). 입력원 객체(엣지 트리거 · 버퍼)는
  슬롯마다 따로다.

## 8. 평가 경기 재현 하네스 (FR-4, FR-10, M5-b)

### 8.1 `DerivedSeeds`

```js
export function deriveSeed(baseSeed, envIndex, rallyIndex) {     // PikaEnv.kt:268 의 비트 판
  let h = baseSeed | 0;
  h = fmix32(h ^ Math.imul(envIndex + 1, 0x9E3779B9 | 0));
  h = fmix32(h ^ Math.imul(rallyIndex + 1, 0x85EBCA6B | 0));
  return h;
}
const fmix32 = (h) => { h ^= h >>> 16; h = Math.imul(h, 0x85EBCA6B | 0); h ^= h >>> 13;
                        h = Math.imul(h, 0xC2B2AE35 | 0); h ^= h >>> 16; return h | 0; };

export class DerivedSeeds {        // RALLY 규약
  constructor(baseSeed, envIndex, startRally) { … }
  first()       { return deriveSeed(this.baseSeed, this.envIndex, this.startRally); }
  rallySeed(k)  { return deriveSeed(this.baseSeed, this.envIndex, this.startRally + k); }
}
```

⚠️ Kotlin 은 `(envIndex + 1) * -1640531527` 을 **Int 곱셈**(32비트 래핑)으로 한다. JS 에서 `*` 를 쓰면 double 곱이라
2⁵³ 근처에서 정밀도를 잃는다 — `Math.imul` 이어야 한다. `ushr` 는 `>>>`, 결과는 `| 0` 으로 부호 있는 int32.
단위 테스트: Kotlin 에서 뽑은 `(base, env, k) → seed` 표 몇십 줄을 골든으로 대조한다.

### 8.2 하네스

```
node test/policy-parity.mjs runs/baselines/track-a-seed0 --registry runs/policies/registry.jsonl --base-seed 0
```

```js
for (const env of groupByEnv(manifest)) {             // 게임 번호 순서
  let startRally = 0;
  for (const g of env.games) {
    const original = read(g.file);
    const policySlot = g.envIndex >= 32 ? 1 : 0;       // manifest 의 p1/p2 kind 로 확인
    const sources = policySlot === 0 ? [policy(), new FsmSource()] : [new FsmSource(), policy()];
    const bytes = await playLive({ sources, seeds: new DerivedSeeds(base, g.envIndex, startRally),
      settings: { firstServeIsPlayer2: g.gameInEnv % 2 === 1, maxRallyFrames: 3000, frameLimit: 60000, edgeTrigger: true } });
    if (!equal(bytes, original)) report(firstDivergence(bytes, original));
    startRally += decodeReplay(original).rallyFrames.length;   // §2.7 — 첫 랠리 번호만 얻는다
  }
}
```

- 비교 단위는 **리플레이 바이트 전체** — 헤더(플래그 · boldness · 상한) · 시드 목록 · 입력 · 랠리 결과 · 최종 점수가
  전부 같아야 한다. 시드 목록이 같다는 것이 `deriveSeed` JS 판의 증명이기도 하다.
- 참가자(체크포인트 SHA)는 manifest 의 것과 레지스트리의 것이 같아야 시작한다. 다른 가중치로 치른 경기와
  비교하는 사고를 막는다.
- 규모: 2,400게임 × 평균 1,835 프레임 ≈ 440만 결정. ORT 0.01 ms + 물리 ~1 µs → **1분 안팎** 예상. 로컬 전용
  (`runs/` 필요). `npm test` 에는 커밋한 축소 픽스처(seed0 ONNX + 양 진영 2게임씩)만 넣는다.

### 8.3 불일치 보고

첫 불일치가 나면 그 게임을 원본 리플레이로 재생하며 같은 프레임까지 가서:

1. 첫 입력 불일치 프레임 f, 슬롯, 원본 행동 · 재현 행동
2. 프레임 f 의 결정 관측(41 float)과 그 관측에 대한 **로짓 여유** (top1 − top2), 두 행동의 로짓 차
3. 판정: 두 행동이 원본 로짓의 top1 · top2 이고 차가 1.6 × 10⁻⁵ 미만이면 "수치 동률", 아니면 "버그"

입력이 아니라 **시드 목록이 먼저 갈라지면** 시드 유도 · 첫 랠리 번호 · 첫 서브 쪽 문제다 — 입력 비교 전에 본다.

## 9. 라이브 적재 (FR-11 ~ FR-13, M5-c)

### 9.1 참가자 주장

```
POST /api/live-games?p1=policy:<onnx_sha256>&p2=fsm
POST /api/live-games?p1=human&p2=policy:<onnx_sha256>
```

| 슬롯 플래그 | 주장 | 결과 |
|---|---|---|
| FSM | 무엇이든 | `Participant.FSM` (주장 무시 — 기존 원칙) |
| External | 없음 · `human` | `Participant("human", null, "keyboard")` (기존과 같다) |
| External | `policy:<sha>` 이고 레지스트리에 있음 | `Participant("external", "sha256:<ckpt>", label)` — **체크포인트** SHA 로 |
| External | `policy:<sha>` 인데 레지스트리에 없음 | 400. 적재하지 않는다 |

체크포인트 SHA 로 적재하는 이유: `participant.identity` 가 `external:<ckpt sha>` 이면 평가 기준선
(`track-a-seedN` 묶음)의 참가자와 **같은 행**이 된다 (`Ingest.Participant.identity`, `001_schema.sql` participant 주석).
"이 정책의 평가 경기와 라이브 경기" 를 한 쿼리로 모을 수 있다. ONNX SHA 는 manifest 에 함께 남긴다
(`runs/live/manifest.jsonl` 에 `onnx` 필드 추가 — ingest 는 모르는 필드를 무시한다, 확인 항목).

### 9.2 사후 검증 `verify-policy`

서버는 주장을 **믿고** 받는다 — 정책이 실제로 그 수를 뒀는지는 ONNX 를 돌려야 알 수 있는데 Kotlin 에 ORT 를
들이는 것은 이 작업의 범위 밖이다. 대신 Node 도구가 적재된 경기를 다시 본다:

```
node test/verify-policy.mjs --game-id 123     # 또는 runs/live/<sha>.pkr + manifest
```

리플레이를 재생하면서 정책 슬롯의 매 프레임에 결정 관측을 만들고 ONNX argmax → 엣지 트리거를 거친 값이
기록된 입력과 같은지 본다. 정책 vs 정책이면 두 슬롯 모두. M5-c 의 "100% 재현" 이 이것이다.

## 10. 뷰어 (FR-14 ~ FR-16)

- 설정 화면: 정책 옵션을 켜고 `GET /api/policies` 목록에서 고른다. 좌 · 우 독립 — 정책 vs 정책 포함.
- ORT 는 **정책을 고른 경우에만** 동적 `import('onnxruntime-web/wasm')`. 리플레이 · 사람 · FSM 경로의 번들은 그대로다.
- ONNX 를 받으면 SHA-256(`src/runner/sha256.mjs`, 이미 있다)을 계산해 레지스트리와 대조하고, 메타데이터의 레이아웃
  해시 · obs_dim 을 인코더와 대조한다. 어긋나면 시작하지 않고 이유를 화면에 쓴다.
- 라이브 루프 (`live.js`) 의 `frameClock` 콜백은 동기다. 틱마다 `await advance(runner)` 를 돌리되, 한 틱에 여러 프레임을
  따라잡아야 할 때(`n > 1`) 도 순서대로 기다린다. 추론 0.01 ms 라 25fps 예산에 영향이 없다 — 지연은 P6 에서 잰다.
- 시드 모드: 기본 새 난수(`FreshSeeds`). "평가 경기 재현" 은 `(baseSeed, envIndex, startRally, 첫 서브)` 를 받는다 —
  M5-b 브라우저 확인과 "평가에서 이상했던 그 경기를 직접 붙어 보기" (Phase 6 용도) 가 같은 기능이다.
- 제출은 기존 `api.submitLive(bytes)` 에 참가자 주장 쿼리를 더한다.

### 10.1 지연 측정 (FR-16, M5-e)

headless Chrome (Phase 4 M4-h 와 같은 방식)으로 정책 vs FSM 한 게임을 돌리며 `prepare` 한 번의 시간을
`performance.now()` 로 모은다. 첫 세션 생성 시간(wasm 컴파일 포함)은 따로 적는다. ROADMAP 에 p50 · p99 · 최대를 기록한다.

## 11. 작업 순서

| 순서 | 무엇 | 왜 이 순서 | 정확해야 하는 곳 |
|---|---|---|---|
| 1 | 결정 관측 골든 (Kotlin) | 검증 수단이 먼저 | ★ 결정 관측의 정의 (§3.3 autoreset) |
| 2 | JS `ObsEncoder` · 레이아웃 해시 · `deriveSeed` → M5-a | 추론 없이 증명 가능한 부분을 먼저 닫는다 | ★ float32 · `-0` (§2.3, §4.2) |
| 3 | ONNX export · 자기 검증 · 레지스트리 → M5-d | 1·2 와 독립 — 병행 가능 | ★ 결정론 (§2.6) |
| 4 | 러너 `beginFrame` · 라이브 루프 · `PolicySource` | 1~3 이 모두 있어야 의미가 있다 | ★ 결정 시점 (§7.1), 엣지 입력 (§7.3) |
| 5 | M5-b 하네스 · 2,400게임 | 여기서 처음으로 "정책이 같은 수를 두는가" 를 본다 | ★ 첫 랠리 번호 (§2.7) |
| 6 | serve 참가자 주장 · `verify-policy` → M5-c 배관 | 경기가 맞다는 것이 증명된 뒤에 적재 | |
| 7 | 뷰어 UI · 브라우저 M5-b 6게임 · 지연 측정 · M5-c 실경기 | 가장 눈에 띄지만 가장 나중 | |
| 8 | 회귀 · ROADMAP 기록(M5-e) · history 이관 | | |
