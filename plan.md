# plan — 분석 + 리플레이 + 경기 러너 (ROADMAP Phase 4)

`PRD.md` 의 요구사항을 **어떻게** 달성할지에 대한 기술 계획.

## 1. 접근 방식

순서를 **"형식 → Kotlin 왕복 → JS 동치 → 배관 → 적재·통계 → 기준선 → 뷰어"** 로 잡는다.
뷰어가 가장 눈에 띄지만 가장 나중이다.

이유는 Phase 1 과 같다. 뷰어는 두 번째 구현(JS)이 첫 번째 구현(Kotlin)과 같은 경기를 계산한다는
가정 위에 서 있다. 그 가정을 **화면을 보고** 확인하려 하면 틀린 것을 찾는 데 몇 시간이 들고,
찾아도 "몇 프레임째부터" 인지 모른다. 프레임별 체인 해시가 먼저 있으면 첫 불일치 프레임이 즉시 나온다.
그래서 JS 재생 코어를 **Node 에서** 먼저 증명하고(M4-b), 그 모듈을 그대로 브라우저에 싣는다.

두 번째 원칙: **리플레이가 단일 진실 공급원이다.** 랠리 · 통계 테이블은 전부 리플레이를 재생해서
파생한다. 통계 정의를 바꾸거나 지표를 더해도(Phase 8) 리플레이만 있으면 다시 계산된다.
그래서 DB 스키마 마이그레이션 도구가 필요 없다 (§6.3).

세 번째 원칙: **리플레이는 라이브의 특수한 경우다.** 러너는 슬롯마다 입력원을 받아 매 프레임 물리를 한 칸
돌릴 뿐이고, 입력이 기록된 바이트에서 오든 키보드에서 오든 모른다. 그래서 M4-b 로 증명한 규칙층이
라이브에서도 그대로 정답이다 — 라이브를 위해 다시 증명할 것은 "입력원이 입력을 넣는 자리" 하나뿐이다 (§5.4).

## 2. 검증된 전제

### 2.1 두 가지 시드 규약

| 경로 | 시드 | 첫 서브 | truncation | 쓰는 곳 |
|---|---|---|---|---|
| `GameEvaluator.playGame` (`GameEvaluator.kt:103`) | `XorShift32(seed)` 하나로 게임 전체 | 인자 `firstServeIsPlayer2` | `maxRallyFrames` (평가 기본 0 = 없음) | M2-e FSM vs FSM 800게임, Kotlin 기준선 테스트 |
| `PikaEnv` (`PikaEnv.kt:84-122`) | 게임 시작 **및 매 랠리** `XorShift32(deriveSeed(baseSeed, envIndex, rallyCounter))` | `gameCounter % 2 == 1` | `maxRallyFrames` 기본 3,000 | Python `evaluate.py` → Track A 2,400게임 |

`rallyCounter` 는 게임을 넘어서도 증가하므로(`PikaEnv.kt:61`) 한 게임만 떼어 재생하려면 시드를
다시 유도할 게 아니라 **시드 자체를 저장**해야 한다. 랠리당 4바이트다.

⚠️ `PikaEnv` 에서 시드가 들어가는 **순서**: 게임 시작은 "RNG 교체 → `PikaGame` 생성(Player 생성자가
`rand()` 2회 소비)", 이후 랠리는 "RNG 교체 → `startNextRally()`" (`PikaEnv.kt:112-121`).
재생기가 이 순서를 그대로 따라야 한다.

### 2.2 FSM 입력은 저장하지 않는다

FSM 슬롯의 입력은 엔진이 덮어쓴다 (`PhysicsEngine.kt:129`, `PikaGame.step` 주석). FSM 은 엔진 안에서
RNG 로 결정하므로 `(시드, External 입력)` 이 같으면 다시 같은 결정을 한다. 그 동치는 Phase 1 이
**JS 오라클과 대조해** 증명했다.

### 2.3 JS 쪽에 이미 있는 것

| 파일 | 쓸 것 |
|---|---|
| `upstream/src/resources/js/physics.js` | 물리 + FSM (Phase 1 오라클) |
| `upstream/src/resources/js/rand.js` | `setCustomRng` — **모듈 전역** 주입 |
| `tools/js-oracle/xorshift32.mjs` | `xorshift32(seed)`, `toCustomRng` (Kotlin `nextRand` 와 비트 일치) |
| `tools/js-oracle/spec.mjs` | State Spec 직렬화 + SHA-256 체인 (Kotlin `StateSpec.chainStep` 과 같은 규약) |
| `tools/js-oracle/harness.mjs` | `resetRound` 순서 (player1 → player2 → ball) |

State Spec 에는 `computer_boldness`(99행), `is_winner`(101), `game_ended`(102) 가 들어 있다.
→ JS 규칙층은 boldness 고정과 **게임 종료 시 플래그 세팅**까지 해야 해시가 맞는다 (`PikaGame.kt` 의
`step` 끝부분). 빠뜨리면 **마지막 프레임 하나만** 어긋난다 — 가장 찾기 힘든 형태다.

### 2.4 ⚠️ 렌더러의 RNG 소비

`cloud_and_wave.js` 가 전역 `rand()` 를 부른다:
- `Cloud` 생성자 (17~20행) — `GameView` 생성 시 구름 10개
- `cloudAndWaveEngine` (70~71, 83, 87행) — **매 프레임**

업스트림 게임에서는 이것이 물리와 한 스트림을 나눠 쓴다. 우리 리플레이는 물리 스트림만 기록했으므로,
그대로 그리면 **프레임마다 난수가 새어 나가 FSM 결정이 달라진다.**

### 2.5 수치

| 항목 | 값 | 출처 |
|---|---|---|
| FSM vs FSM 게임당 프레임 | 15,275 (랠리 평균 758.9, p99 2,821, 최대 6,011) | Phase 2 `plan.md` §측정 |
| Track A vs FSM 랠리 평균 | 122.3 프레임 (18.3M 이후) | Phase 3 README 학습 곡선 |
| JS 물리 처리량 (Node) | 1.14M frame/s (물리만) | Phase 1 `plan.md` 290행 |
| 게임 상한 | 60,000 프레임 | `evaluate.py` `MAX_GAME_FRAMES` |

산수:
- Track A 게임 ≈ 15 × 122.3 ≈ 1,835 프레임 → 입력 1,835 B + 헤더 · 시드 15×4 · 랠리 결과 15×5 ≈ **2.0 KB**
- FSM vs FSM 게임: 입력 0 · 게임 규약이라 시드 1개 · 랠리 결과 ≈ 20×5 → **≈ 150 B**
- 임의 프레임 이동 최악: 60,000 / 1.14M ≈ **53 ms** (NFR-3 의 100 ms 안). 평균 게임은 13 ms.
  → 키프레임 스냅숏이 필요 없다. 처음부터 다시 계산한다.

### 2.6 외부 의존성 (2026-09-24 조회)

| 이름 | 버전 | 비고 |
|---|---|---|
| MySQL | `mysql:8.4.11` | ROADMAP 의 8.4 LTS |
| MySQL Connector/J | 9.3.0 | GPLv2 + Universal FOSS Exception (PRD §7) |
| Vite | 8.3.0 | 업스트림은 webpack 4 구성이지만 뷰어는 새로 짠다 |
| `@pixi/*` | **6.5.10** 고정 | 최신은 7.4.3 이지만 `view.js` 가 6 API. 업스트림 `^6.4.2` 와 같은 메이저 |

## 3. 리플레이 형식 v1

### 3.1 무엇을 담는가

재생에 필요한 최소 + **검증용 결과**. 결과를 담는 이유는 재생기가 스스로 틀렸는지 알 수 있게
하기 위해서다 — 결과 없이 입력만 있으면 틀린 재생도 "어떤 경기" 를 그럴듯하게 만들어 낸다.

```
little-endian
magic      "PKRP"
u8         version = 1
u8         flags      bit0 p1External · bit1 p2External · bit2 firstServeIsPlayer2
                      bit3 ended (false = 상한에서 잘림) · bit4 edgeTrigger (정보용, 재생에 안 씀)
u8         seedMode   0 = GAME (시드 1개) · 1 = RALLY (랠리마다)
u8         winningScore
i8 i8      fixedBoldness p1, p2   (-1 = 추첨)
i32        maxRallyFrames         (0 = 없음)
u32        rallyCount
i32 × (seedMode == RALLY ? rallyCount : 1)      seeds
{ i32 frames, i8 outcome } × rallyCount          outcome 0/1 = 득점자, -1 = truncated, -2 = 미완
u8 u8      finalScore p1, p2
u32        frameCount
u8 × frameCount × externalCount                  슬롯별로 연속 배치 (p1 먼저)
```

입력 바이트는 `ActionCodec.encode(x, y, powerHit == 1)` — **엣지 변환 후** 값이다 (FR-4).
같은 18칸을 쓰지만 의미가 "누른 상태" 가 아니라 "엔진이 받은 입력" 이다. 재생기는
`ActionCodec.decode(a, out, edge = null)` 한 줄로 푼다.

### 3.2 형식 밖의 메타데이터

리플레이 바이트는 **경기 자체**만 담는다. 누가 뛰었는지(체크포인트 SHA-256), 어느 평가에서 나왔는지
(`envIndex`, 환경 내 게임 번호, 평가 시드)는 옆에 붙는 JSON 이다.

```jsonc
// <dir>/manifest.jsonl — 게임 한 줄
{"file": "g000123.pkr", "set": "track-a-seed0-eval", "envIndex": 17, "gameInEnv": 3,
 "p1": {"kind": "external", "checkpoint": "sha256:…", "label": "track-a-seed0"},
 "p2": {"kind": "fsm"}, "counted": true, "unresolved": false}
```

분리한 이유: 같은 경기 바이트가 다른 맥락(예: 체크포인트 이름 변경)에서도 같은 해시를 가져야
중복 적재를 막을 수 있다. `game.replay_sha256` 이 유일 키다.

### 3.3 함정

- `maxRallyFrames` 는 **재생이 스스로 판정**한다. 랠리 결과의 `-1` 과 재생 판정이 다르면 검증 실패다.
- `ended = false` 인 게임의 마지막 랠리 outcome 은 `-2`. 재생은 `frameCount` 에서 멈춘다.
- `PikaEnv` 의 autoreset 스텝은 물리가 돌지 않으므로 **프레임으로 기록하지 않는다.**
  (`PikaEnv.step` 의 (1) 분기에서는 recorder 를 부르지 않는다.)

## 4. Kotlin: 기록과 재생 (`env` 모듈)

### 4.1 배관

```kotlin
// env — 외부 의존성 0 (NFR-2)
class ReplayRecorder(val seedMode: SeedMode, val cap: Int = 60_000) {
    fun beginGame(seed: Int, game: PikaGame, config: RecordedConfig)
    fun beginRally(seed: Int)                     // RALLY 모드에서만
    fun frame(inputs: Array<PikaUserInput>)       // runEngineForNextFrame 직전의 입력
    fun endRally(outcome: Int)                    // 0/1/-1
    fun endGame(): Replay?                        // cap 초과 시 ended=false 로 잘린 것
}

// PikaEnv — recorder 가 null 이면 기존 경로와 한 줄도 다르지 않다 (NFR-1)
val scorer = game.step(inputs)
recorder?.frame(inputs)            // ⚠️ step 이후에 기록하면 안 된다 — FSM 이 inputs 를 덮어쓴다
```

⚠️ **기록 시점**: `game.step(inputs)` 안에서 FSM 슬롯의 `inputs[k]` 가 덮어써진다. External 슬롯만
기록하므로 문제는 없지만, 기록은 **step 호출 직전**에 한다. 나중에 FSM 입력까지 기록하고 싶어졌을 때
순서가 뒤집혀 있으면 FSM 의 "이번" 입력이 아니라 다음 프레임에 쓰일 값이 들어간다.

`VectorEnv` 는 끝난 게임을 `(envIndex, gameInEnv, Replay)` 로 큐에 쌓는다. 큐는 `FetchReplays` 가 비운다.

### 4.2 재생

```kotlin
class ReplayPlayer(val replay: Replay) {
    // 게임 규약: rng 하나.  랠리 규약: 랠리 k 시작 전에 rng = XorShift32(seeds[k])
    fun play(onFrame: (PikaGame, frameIndex: Int) -> Unit): PlayResult
}
```

재생은 `PikaGame` 을 **그대로** 쓴다. 규칙을 다시 짜지 않는다 — Kotlin 쪽 규칙층의 정답이 `PikaGame` 이다.

### 4.3 왕복 테스트 (M4-a 의 단위판)

- `PikaEnv` 로 N 게임 (External = 무작위 정책, 시드 고정) → 기록 → 재생 → 랠리별 결과 · 점수 일치
- `GameEvaluator` FSM vs FSM → 기록 → 재생 → 일치
- truncation 이 실제로 일어나는 설정(`maxRallyFrames = 200`), boldness 고정, 미완 게임(cap = 500)
- 기록을 켜도 `PikaEnv` 의 관측·보상이 **바이트 단위로** 같다 (NFR-1)

## 5. JS 경기 러너 (`viewer-web/src/runner/`, `src/sources/`)

### 5.1 구성

```js
// runner.mjs — 브라우저와 Node 가 같은 파일을 import 한다 (FR-12)
import { PikaPhysics, PikaUserInput, GROUND_HALF_WIDTH } from '<upstream>/physics.js';
import { setCustomRng } from '<upstream>/rand.js';
import { xorshift32, toCustomRng } from '<tools/js-oracle>/xorshift32.mjs';

export class GameRunner {
  constructor({ sources: [p1, p2], seeds, settings, recorder })
  reset()                   // 프레임 0 전으로
  step()                    // 한 프레임. 입력원 호출 → 물리 → 규칙층 (득점·서브·랠리 경계·reseed·boldness·종료 플래그) → 기록
  seek(frame)               // reset() 후 step() × frame (§2.5: 최악 53 ms). 리플레이 입력원일 때만 의미가 있다
  get physics / scores / frame / rallyIndex / ended
}
export function runnerFromReplay(bytes)   // ReplaySource × 2 + 기록된 시드 + 기록된 설정
```

규칙층은 `PikaGame.kt` 를 줄 단위로 옮긴다 — 50줄 남짓이다. 각 줄에 대응 Kotlin 줄을 주석으로 단다.

### 5.1.1 입력원 인터페이스 (FR-16)

Kotlin 의 `Slot` + `Controller.decide(game, isPlayer2, out)` (`GameEvaluator.kt`) 와 **같은 모양**이다.
개념 하나를 두 언어가 공유해야 Phase 5 에서 "Kotlin 평가에서 정책이 입력을 넣는 자리 = 브라우저에서 넣는 자리"
를 코드만 읽고 확인할 수 있다.

```js
/** @interface */
class InputSource {
  kind            // 'fsm' | 'external'
  decide(runner, isPlayer2, out)   // external 만. runEngineForNextFrame **직전**에 호출
  onRallyStart()                   // 선택. 엣지 트리거 리셋 등
}
```

| 구현 | kind | decide | 비고 |
|---|---|---|---|
| `ReplaySource(bytes, slot)` | external | 기록된 바이트 → `ActionCodec.decode(a, out, edge=null)` | |
| `FsmSource` | **fsm** | **호출되지 않는다** | 러너가 `new PikaPhysics(isP1Fsm, isP2Fsm)` 로 반영. 엔진이 입력을 덮어쓴다 |
| `KeyboardSource(keys)` | external | `PikaKeyboard.getInput()` 후 값 복사 | 엣지 변환은 `keyboard.js` 가 이미 한다 |
| `ScriptedSource(fn)` | external | 프레임 번호 → 입력 (결정론) | 테스트용. 라이브 경로 검증 (M4-j) |
| `PolicySource` | external | — | **Phase 5** |

⚠️ 리플레이의 FSM 슬롯은 `ReplaySource` 가 아니라 `FsmSource` 다. 헤더의 슬롯 플래그가 그것을 정한다.

### 5.1.2 시드 공급원 (FR-17)

| 공급원 | 쓰는 곳 |
|---|---|
| `RecordedSeeds(replay)` | 리플레이. 규약(GAME/RALLY)도 헤더를 따른다 |
| `FreshSeeds()` | 라이브. **RALLY 규약**, 매 랠리 `crypto.getRandomValues` 로 뽑고 기록한다 |

라이브를 RALLY 규약으로 두는 이유: Phase 5 가 "브라우저 경기 = 같은 시드의 Kotlin 평가 경기" 를
검증하려면 Kotlin 평가(`PikaEnv`)와 같은 규약이어야 한다. 그때는 `DerivedSeeds(baseSeed, envIndex)`
(`PikaEnv.deriveSeed` 의 JS 판) 하나만 더하면 된다.

### 5.2 ⚠️ RNG 는 전역이다

`rand.js` 의 `customRng` 는 모듈 전역 하나다. 그래서:

```js
step() {
  setCustomRng(this.physicsRng);   // 매 step 시작마다 다시 건다
  ...
}
// view 쪽
drawCloudsAndWave() { setCustomRng(this.viewRng); view.game.drawCloudsAndWave(); }
new GameView(...)   // 생성자 전에도 viewRng — Cloud 생성자가 rand() 를 부른다
```

"한 번 걸어 두면 된다" 가 아니다. **그리는 코드가 끼어드는 모든 지점 앞에서** 다시 건다.
M4-e 가 이것을 검증한다: 매 프레임 사이에 `rand()` 를 몇 번씩 부르는 가짜 렌더러를 끼워 재생하고,
체인 해시가 안 끼운 것과 같아야 한다.

### 5.3 Node 에서의 동치 검증 (M4-b)

```
analysis golden-replays  →  engine-kotlin/env/golden/replay/*.pkr  +  chains.json (Kotlin 체인 해시)
node viewer-web/test/conformance.mjs  →  runnerFromReplay 로 재생, spec.mjs 로 체인 → chains.json 과 대조
```

Kotlin 쪽 체인은 `conformance` 의 `StateSpec.chainStep` 으로 뜬다 (`StateSpec.kt:149`) — Phase 1 과
같은 해시 규약이라 새 해시를 발명하지 않는다. 첫 불일치 프레임과 필드 이름을 출력한다.
Gradle 테스트가 Node 를 부르는 방식은 `JsOracleTest` 와 같다 (Node 없으면 skip 이 아니라 **실패**,
CI 에는 Node 가 있다).

골든 세트 구성은 §7.3.

### 5.4 라이브 경로의 동치 (M4-j)

라이브에서 새로 생기는 것은 **입력이 러너 바깥에서 매 프레임 들어온다**는 것뿐이다. 그래서 검증도 그것만 한다.

```
ScriptedSource × {FSM, Scripted} 조합 + FreshSeeds(고정 시드로 주입)
  → GameRunner 라이브 경기 → recorder → 리플레이 바이트
  → (1) runnerFromReplay 로 재생한 체인
  → (2) Kotlin ReplayPlayer 로 재생한 체인 (analysis 의 검증 경로)
  → 라이브 중 계산한 체인 = (1) = (2)
```

`KeyboardSource` 는 `ScriptedSource` 와 입력을 얻는 방법만 다르다. 그래서 브라우저 키 이벤트는 자동
테스트하지 않고, 수동 확인(M4-k)으로 끝낸다.

## 6. 적재와 통계 (`analysis` 모듈)

### 6.1 모듈

`engine-kotlin/analysis` — `env`, `core`, `conformance`(StateSpec) 에 의존. Connector/J 는 여기만.
하위 명령:

| 명령 | 하는 일 |
|---|---|
| `ingest <dir>` | `manifest.jsonl` + `*.pkr` → 재생 검증 → 게임·랠리·통계 적재. 하나라도 실패하면 **트랜잭션 전체 롤백** |
| `baseline-fsm --games 800 --base-seed 0` | `GameEvaluator.evaluate(null, null)` 와 **같은 시드·서브 배치**로 기록 → ingest |
| `golden-replays` | §7.3 골든 세트 생성 (결정론, 커밋 대상) |
| `rebuild-stats [--set]` | 리플레이 BLOB 에서 랠리·통계 테이블을 다시 파생 |
| `serve --port 8081` | 읽기 전용 HTTP (JDK `com.sun.net.httpserver`, 의존성 추가 없음) |

### 6.2 스키마 (`deploy/mysql/init/001_schema.sql`)

```sql
match_set   (id, name UNIQUE, kind ENUM('baseline','eval','selfplay'), created_at, note)
participant (id, kind ENUM('fsm','external'), checkpoint_sha256 CHAR(64) NULL, label,
             UNIQUE(kind, checkpoint_sha256))
game        (id, set_id, p1_id, p2_id, replay_sha256 CHAR(64) UNIQUE, replay MEDIUMBLOB,
             seed_mode, winning_score, max_rally_frames, fixed_boldness_p1, fixed_boldness_p2,
             first_serve_p2, ended, score_p1, score_p2, winner NULL, frames, rallies, truncated,
             chain_sha256 CHAR(64),               -- Kotlin 재생 체인. 뷰어가 대조한다
             env_index NULL, game_in_env NULL)
rally       (game_id, idx, seed NULL, server_p2, outcome, frames, landing_x NULL,
             touches_p1, touches_p2, power_hits_p1, power_hits_p2, PRIMARY KEY(game_id, idx))
power_hit   (game_id, rally_idx, frame, hitter, success)   -- 성공률의 분모·분자를 행으로 남긴다
schema_version (version)                                   -- analysis 가 시작 시 대조
```

"상대 구성" (Phase 2 기록 3번) = `game.p1_id / p2_id` → `participant`. FSM 이냐 정책이냐가 RNG 스트림을
가르므로 **재생에 필수인 정보**는 리플레이 헤더의 슬롯 플래그에 이미 있고, `participant` 는 **누구**인지를 담는다.

### 6.3 마이그레이션 도구를 쓰지 않는 이유

| 선택지 | 판단 |
|---|---|
| Flyway / Liquibase | 버림. 의존성 + 규약이 늘고, 이 DB 는 **캐시**다 |
| `docker-entrypoint-initdb.d` + `schema_version` 대조 | **채택.** 스키마를 바꾸면 볼륨을 지우고 `ingest` 를 다시 돌린다 |

DB 가 캐시일 수 있는 이유: 적재 입력(`*.pkr` + manifest)이 `runs/<run>/replays/` 에 남는다.
⚠️ `runs/` 는 `.gitignore` 대상이다 — 기준선 두 벌은 `scripts/baseline-replays.sh` 로 **결정론적으로
재생성**된다 (FSM vs FSM 은 시드만, Track A 는 체크포인트 + 시드). 체크포인트 SHA-256 을 manifest 에
남기므로 "같은 가중치로 만든 리플레이인가" 가 확인된다 (M6-d 와 같은 장치).

### 6.4 통계 정의

전부 `ReplayPlayer` 의 프레임 콜백에서 계산한다.

| 지표 | 정의 |
|---|---|
| 랠리 길이 | 랠리의 물리 프레임 수. outcome 별(득점 / truncated / 미완)로 나눈다 |
| 착지 지점 | 득점 랠리의 착지 프레임 `ball.punchEffectX` (득점 판정과 **같은 필드**, `PikaGame.kt` step). 8px 구간 × 54칸. 서브권자 · 득점자별 |
| 파워히트 | 충돌 처리에서 `ball.isPowerHit` 가 **false → true** 로 바뀐 프레임 + 그 프레임에 충돌한 플레이어 |
| 파워히트 성공 | 그 파워히트 이후 **상대가 공에 닿기 전에** 랠리가 친 쪽의 득점으로 끝남 |
| 터치 | `isCollisionWithBallHappened` 의 false → true (`PikaEnv` 의 `ball_touch` 항과 같은 정의) |

⚠️ 파워히트 판정에 `ball.sound.powerHit` 를 쓰지 않는다. 소리 플래그는 프레임마다 소비·리셋되는
부수 효과라 판정 기준으로 쓰면 렌더러의 존재 여부에 따라 값이 달라질 수 있다.
`isPowerHit` 는 순수 물리 상태다 (`PhysicsEngine.kt:111-113`).

## 7. 기록 배관 (server · Python)

### 7.1 proto

```proto
message ConfigureRequest { ... optional bool record_replays = 14; }   // 기본 false
rpc FetchReplays (FetchReplaysRequest) returns (FetchReplaysReply);
message FetchReplaysRequest { int64 session_id = 1; }
message FetchReplaysReply   { repeated RecordedGame games = 1; }
message RecordedGame { int32 env_index = 1; int32 game_in_env = 2; bytes replay = 3; }
```

`obs_layout_hash` 는 관측 레이아웃의 해시이므로 바뀌지 않는다. 기존 필드 번호는 건드리지 않는다.

### 7.2 Python

```python
# evaluate.py
env = PikaVectorEnv(EnvOptions.for_policy(..., record_replays=True))
report = evaluate_policy(env, policy, games_per_side=400, recorder=ReplaySink(out_dir, set_name, participants))
```

`evaluate_policy` 는 이미 행마다 "센 게임" 을 판정한다 (`consumed[row] += 1` 지점). 그 순간
`(row, game_in_env)` 를 `counted` 로 표시하고, 루프 중간중간 `fetch_replays()` 로 받은 게임과 짝짓는다.
할당량을 채운 뒤에도 계속 도는 행의 게임은 `counted = false` 로 쓰거나 버린다.
**미결(dead) 게임은 `unresolved = true` 로 쓴다** — 뷰어로 볼 가치가 가장 큰 게임이다.

⚠️ 행 `row` 와 서버 `envIndex` 가 같은 번호인지 확인한다 (`swapped_envs` 는 인덱스 뒤쪽 절반).
진영은 리플레이의 슬롯 플래그가 말해 주므로 manifest 에 따로 적지 않는다 — 두 곳에 쓰면 어긋난다.

### 7.3 골든 세트 (M4-b)

| 케이스 | 목적 |
|---|---|
| FSM vs FSM, GAME 규약, 시드 0·1, 서브 좌·우 | M2-e 경로 |
| External(무작위) vs FSM, RALLY 규약, 진영 좌·우 | Track A 평가 경로 |
| External vs External, RALLY 규약 | Phase 6 대비 (FSM RNG 소비 없음) |
| `maxRallyFrames = 150` — truncation 다수 | 무득점 랠리 경계 |
| `fixedBoldness = (0, 4)` | boldness 고정 · 진영별 값 |
| cap = 800 에서 잘린 미완 게임 | `ended = false` |
| 파워히트가 많은 입력 (powerHit 비율 높은 무작위) | `isPowerHit` 경로 |

게임 수는 적게(10여 개), 프레임은 충분히 — 합계 ≥ 100,000 프레임. 체인 해시는 게임별 최종값 +
1,000 프레임마다 중간값을 남겨 첫 불일치 구간을 좁힌다.

## 8. 뷰어 (`viewer-web`)

### 8.1 구성

```
viewer-web/
  package.json            vite 8.3.0, @pixi/* 6.5.10 (업스트림 main.js 가 쓰는 것과 같은 목록)
  vite.config.js          alias @upstream → ../upstream/src/resources/js
                          server.fs.allow ['..'], proxy /api → http://127.0.0.1:8081
  src/runner/             §5.1 GameRunner · 규칙층 · 코덱 · 기록기 (Node 테스트와 공유)
  src/sources/            §5.1.1 Replay · Fsm · Keyboard · Scripted
  src/view/player.js      GameView + GameRunner, 25 fps 틱. 리플레이: 속도 ×0.25~×8 · 시크바 · 랠리 점프
                          라이브: 실시간 1× 고정, 종료 시 POST /api/live-games
  src/view/setup.js       대전 설정 — 좌·우 입력원 선택 (FR-19)
  src/view/list.js        묶음 · 진영 · 승패 필터
  src/view/stats.js       랠리 길이 히스토그램, 착지 x 분포, 파워히트 표 — SVG 직접 (차트 라이브러리 없음)
  test/conformance.mjs    M4-b
  test/isolation.mjs      M4-e
  test/live.mjs           M4-j
```

### 8.2 함정

- `ASSETS_PATH.SPRITE_SHEET` 가 `'../resources/assets/images/sprite_sheet.json'` 상대 경로로 박혀 있다
  (`assets_path.js`). 모듈의 export 객체는 변경 가능하므로 로더 호출 **전에** 뷰어 경로로 덮어쓴다.
  업스트림 파일은 수정하지 않는다.
- `GameView` 는 Pixi `Loader` 로 받은 `resources` 를 요구한다. 업스트림 `main.js` 의 로딩 절차
  (canvas renderer 등록, `SCALE_MODES.NEAREST`)를 따라야 픽셀 아트가 번지지 않는다.
- 화면의 점수판은 `drawScoresToScoreBoards(scores)` 로 **우리가** 넣는다. 슬로모션·페이드는 부르지 않는다.
- 라이브 두 사람은 한 키보드를 나눈다 — 업스트림 기본 배치(`pikavolley.js` 의 `keyboardArray`)를 따른다.
- 라이브 경기 제출은 `serve` 의 유일한 쓰기 경로다. 서버는 받은 바이트를 `ingest` 와 **같은 검증**으로
  재생하고, 통과해야만 적재한다. 브라우저를 신뢰하지 않는다 — 규칙층이 틀려 있으면 여기서 드러난다.
- 시크 중에는 그리지 않는다. 계산만 하고 마지막 프레임만 그린다.
- 재생 시작 시 서버의 `chain_sha256` 과 JS 체인을 **끝까지 계산해 대조**하고 어긋나면 화면에 경고를
  띄운다 (≤ 53 ms). 뷰어가 조용히 다른 경기를 보여 주는 일을 런타임에도 막는다.

## 9. 작업 순서

| 순서 | 내용 | 정확성 |
|---|---|---|
| 1 | 형식 · Kotlin recorder/player · 왕복 테스트 | ⚠️ 이후 전부의 기반 |
| 2 | 골든 세트 · JS 러너 · 입력원 · Node 동치 (M4-b) · RNG 격리 (M4-e) · 라이브 동치 (M4-j) | ⚠️ **타협 불가** |
| 3 | proto · server 배선 · Python `--record-replays` | 행 ↔ envIndex 짝 |
| 4 | MySQL · `analysis ingest` (검증 후 적재) | 롤백 |
| 5 | 통계 파생 | 정의 §6.4 |
| 6 | 기준선: FSM vs FSM 800 (M4-c), Track A 2,400 (M4-d) | 수치 대조 |
| 7 | `serve` + 뷰어 + 라이브 대전 화면 (M4-h, M4-k) | |
| 8 | 회귀 · 처리량 (M4-f) · 문서 · 이관 | |

3 과 4 는 2 가 끝난 뒤 병렬로 할 수 있다.
