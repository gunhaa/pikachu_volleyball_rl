/**
 * 경기 러너 — 업스트림 `physics.js` 위의 **경기 규칙층**. 브라우저와 Node 가 같은 파일을 쓴다. (FR-12, FR-16)
 *
 * 러너는 슬롯에 무엇이 꽂혔는지 모른다. 입력원(`src/sources/`)이 매 프레임 입력을 넣고,
 * 러너는 물리를 한 칸 돌린 뒤 득점 · 서브 · 랠리 경계 · 재시드 · boldness · 종료 플래그를 처리한다.
 * 리플레이는 "입력원이 기록된 바이트인 경기" 일 뿐이다.
 *
 * 규칙은 Kotlin `PikaGame.kt` 를 줄 단위로 옮겼다. 각 줄 옆의 `PG:<줄>` 이 대응 줄이다.
 * 랠리 경계의 처리 순서는 `ReplayPlayer.kt` / `PikaEnv.kt` 와 같다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ RNG 는 모듈 전역이다 (plan.md §5.2)
 * ─────────────────────────────────────────────────────────────────────────────
 * `rand.js` 의 `customRng` 는 하나뿐이고, 렌더러(`cloud_and_wave.js`)도 같은 `rand()` 를 부른다.
 * 그래서 **매 step() 시작마다** 물리 RNG 를 다시 건다. "한 번 걸어 두면 된다" 가 아니다.
 * 뷰는 그리기 전에 자기 RNG 를 건다. M4-e(`test/isolation.test.mjs`)가 이것을 검증한다.
 *
 * ⚠️ 랠리 리셋은 **다음 step() 의 시작**에 한다. 그래야 step() 이 끝난 시점의 물리 상태가
 *    "공이 땅에 닿은 프레임" 이고, 화면이 그 프레임을 그릴 수 있다. RNG 소비 순서는
 *    Kotlin 과 같다 — 리셋과 다음 프레임 사이에 물리 RNG 를 쓰는 것이 없다.
 */
'use strict';

import { GROUND_HALF_WIDTH, PikaPhysics, PikaUserInput } from '../../../upstream/src/resources/js/physics.js';
import { setCustomRng } from '../../../upstream/src/resources/js/rand.js';
import { xorshift32, toCustomRng } from '../../../tools/js-oracle/xorshift32.mjs';
import { decodeReplay, OUTCOME } from './codec.mjs';
import { RecordedSeeds } from './seeds.mjs';
import { ReplaySource } from '../sources/replay.mjs';
import { FsmSource } from '../sources/fsm.mjs';

export class GameRunner {
  /**
   * @param {Object} opts
   * @param {Array} opts.sources [p1, p2] 입력원. `kind` 가 'fsm' 이면 decide 를 부르지 않는다.
   * @param {{mode:number, first():number, rallySeed(k:number):(number|null)}} opts.seeds
   * @param {Object} [opts.settings]
   * @param {number} [opts.settings.winningScore=15]
   * @param {boolean} [opts.settings.firstServeIsPlayer2=false]
   * @param {number[]} [opts.settings.fixedBoldness=[-1,-1]]
   * @param {number} [opts.settings.maxRallyFrames=0] 0 = 없음
   * @param {number} [opts.settings.frameLimit=Infinity] 이 프레임에서 멈춘다 (잘린 리플레이)
   * @param {boolean} [opts.settings.edgeTrigger=false] 기록용 정보
   * @param {import('./recorder.mjs').ReplayRecorder} [opts.recorder] 라이브 기록
   * @param {function(GameRunner, boolean): void} [opts.onFrame] 매 물리 프레임 직후 · 랠리 리셋 전
   */
  constructor({ sources, seeds, settings = {}, recorder = null, onFrame = null }) {
    if (!Array.isArray(sources) || sources.length !== 2) throw new Error('입력원은 [p1, p2] 두 개');
    this.sources = sources;
    this.seeds = seeds;
    this.settings = {
      winningScore: 15,
      firstServeIsPlayer2: false,
      fixedBoldness: [-1, -1],
      maxRallyFrames: 0,
      frameLimit: Infinity,
      edgeTrigger: false,
      ...settings,
    };
    this.recorder = recorder;
    this.onFrame = onFrame;
    this.inputs = [new PikaUserInput(), new PikaUserInput()];
    this.reset();
  }

  /** 프레임 0 전으로. */
  reset() {
    const s = this.settings;
    const seed = this.seeds.first();
    this.useSeed(seed);
    // ⚠️ RNG 를 건 **뒤에** 생성한다 — Player 생성자가 rand() 를 2회 소비한다.
    this.physics = new PikaPhysics(this.sources[0].kind === 'fsm', this.sources[1].kind === 'fsm'); // PG:40
    this.scores = [0, 0];                                   // PG:43
    this.isPlayer2Serve = s.firstServeIsPlayer2;            // PG:46
    this.gameEnded = false;                                 // PG:49
    this.rallyFrames = 0;
    this.rallyIndex = 0;
    this.frame = 0;
    this.pendingRallyStart = false;
    /** @type {{frames:number, outcome:number}[]} 끝난 랠리 */
    this.rallyResults = [];
    this.pinBoldness();                                     // PG:70
    if (s.firstServeIsPlayer2) this.physics.ball.initializeForNewRound(true); // PG:75
    for (const src of this.sources) src.reset?.(this);
    for (const src of this.sources) src.onRallyStart?.(this);
    this.recorder?.beginGame(seed, {
      p1External: this.sources[0].kind !== 'fsm',
      p2External: this.sources[1].kind !== 'fsm',
      firstServeIsPlayer2: s.firstServeIsPlayer2,
      winningScore: s.winningScore,
      fixedBoldness: s.fixedBoldness,
      maxRallyFrames: s.maxRallyFrames,
      edgeTrigger: s.edgeTrigger,
    });
  }

  useSeed(seed) {
    this.physicsRng = toCustomRng(xorshift32(seed));
    setCustomRng(this.physicsRng);
  }

  get ended() {
    return this.gameEnded || this.frame >= this.settings.frameLimit;
  }

  /**
   * 프레임 앞부분: 물리 RNG 를 걸고, 보류된 랠리 리셋을 한다. 이 뒤의 상태가 **결정 시점**이다
   * (Kotlin 의 autoreset 스텝이 내보낸 관측과 같은 상태, plan.md §7.2).
   *
   * 멱등이다. step() 이 맨 앞에서 다시 부르므로 동기 입력원만 꽂힌 경기는 부를 필요가 없다.
   * 비동기 입력원(정책)은 `live-loop.mjs` 의 advance() 가 이것 → prepare → step() 순으로 돈다.
   */
  beginFrame() {
    if (this.ended) throw new Error('경기가 이미 끝났습니다');
    setCustomRng(this.physicsRng); // ⚠️ 매번 — 그 사이에(await 중에도) 렌더러가 자기 RNG 를 걸었을 수 있다
    if (this.pendingRallyStart) this.startNextRally();
  }

  /**
   * 한 프레임.
   * @return {{isBallTouchingGround:boolean, scorer:(number|null), outcome:(number|null)}}
   */
  step() {
    this.beginFrame();

    const inputs = this.inputs;
    for (let i = 0; i < 2; i++) {
      const src = this.sources[i];
      if (src.kind !== 'fsm') src.decide(this, i === 1, inputs[i]); // FSM 은 엔진이 입력을 덮어쓴다
    }
    this.recorder?.frame(inputs); // ⚠️ 물리 **전** — 엔진이 FSM 슬롯 입력을 덮어쓴다

    this.rallyFrames++;                                                    // PG:88
    this.frame++;
    const isBallTouchingGround = this.physics.runEngineForNextFrame(inputs); // PG:89
    let scorer = null;
    if (isBallTouchingGround) {                                            // PG:90
      scorer = this.physics.ball.punchEffectX < GROUND_HALF_WIDTH ? 1 : 0; // PG:95 — ball.x 가 아니다
      this.scores[scorer]++;                                               // PG:96
      this.isPlayer2Serve = scorer === 1;                                  // PG:99
      if (this.scores[scorer] >= this.settings.winningScore) {             // PG:101
        this.gameEnded = true;                                             // PG:102
        this.physics.player1.isWinner = scorer === 0;                      // PG:105
        this.physics.player2.isWinner = scorer === 1;                      // PG:106
        this.physics.player1.gameEnded = true;                             // PG:107
        this.physics.player2.gameEnded = true;                             // PG:108
      }
    }
    // truncation 판정 — ReplayPlayer.kt / GameEvaluator.kt / PikaEnv.kt 와 같은 조건
    const mrf = this.settings.maxRallyFrames;
    const outcome = scorer ?? (mrf > 0 && this.rallyFrames >= mrf ? OUTCOME.TRUNCATED : null);

    this.onFrame?.(this, isBallTouchingGround);

    if (outcome !== null) {
      this.rallyResults.push({ frames: this.rallyFrames, outcome });
      this.recorder?.endRally(outcome);
      if (this.gameEnded) this.recorder?.endGame();
      else this.pendingRallyStart = true;
    }
    return { isBallTouchingGround, scorer, outcome };
  }

  /** 다음 랠리. 순서: (RALLY 규약) 재시드 → 기록 → player1 → player2 → ball → boldness. */
  startNextRally() {
    this.pendingRallyStart = false;
    this.rallyIndex++;
    const seed = this.seeds.rallySeed(this.rallyIndex);
    if (seed !== null) {
      this.useSeed(seed);
      this.recorder?.beginRally(seed);
    }
    // ⚠️ 순서가 곧 RNG 소비 순서 (boldness 추첨): player1 → player2 → ball
    this.physics.player1.initializeForNewRound();                 // PG:127
    this.physics.player2.initializeForNewRound();                 // PG:128
    this.physics.ball.initializeForNewRound(this.isPlayer2Serve); // PG:129
    this.pinBoldness();                                           // PG:130
    this.rallyFrames = 0;                                         // PG:131
    for (const src of this.sources) src.onRallyStart?.(this);
  }

  /** 추첨은 막지 않고 뽑은 뒤 덮어쓴다 — 막으면 난수 스트림이 밀린다. */
  pinBoldness() {
    const [b1, b2] = this.settings.fixedBoldness;
    if (b1 >= 0) this.physics.player1.computerBoldness = b1; // PG:147
    if (b2 >= 0) this.physics.player2.computerBoldness = b2; // PG:148
  }

  /** 처음부터 다시 계산해 [frame] 프레임 뒤 상태로 (plan.md §2.5: 최악 ≈ 53 ms). 리플레이에서만 의미가 있다. */
  seek(frame) {
    this.reset();
    while (this.frame < frame && !this.ended) this.step();
  }

  /** 이긴 쪽(0/1). 안 끝났으면 null. */
  get winner() {
    if (!this.gameEnded) return null;
    return this.scores[0] >= this.settings.winningScore ? 0 : 1;
  }
}

/**
 * 리플레이 바이트 → 러너. 슬롯 플래그가 입력원 종류를 정한다 (External = ReplaySource, 아니면 FsmSource).
 *
 * `wrap(src, slot)` 은 만들어진 입력원을 감싸 돌려준다 — 결정 관측을 엿보는 프로브(Phase 5)가 쓴다.
 * 기본값은 그대로 돌려주므로 러너의 동작은 바뀌지 않는다.
 *
 * @param {Uint8Array} bytes
 * @param {{onFrame?: function, wrap?: function(Object, number): Object}} [opts]
 */
export function runnerFromReplay(bytes, { onFrame = null, wrap = (src) => src } = {}) {
  const replay = decodeReplay(bytes);
  let order = 0;
  const sourceFor = (external) => (external ? new ReplaySource(replay, order++) : new FsmSource());
  const sources = [wrap(sourceFor(replay.p1External), 0), wrap(sourceFor(replay.p2External), 1)];
  const runner = new GameRunner({
    sources,
    seeds: new RecordedSeeds(replay),
    settings: {
      winningScore: replay.winningScore,
      firstServeIsPlayer2: replay.firstServeIsPlayer2,
      fixedBoldness: replay.fixedBoldness,
      maxRallyFrames: replay.maxRallyFrames,
      frameLimit: replay.frameCount,
      edgeTrigger: replay.edgeTrigger,
    },
    onFrame,
  });
  return { runner, replay };
}

/**
 * 끝까지 돈 러너를 기록된 결과와 대조한다 — Kotlin `ReplayPlayer` 와 같은 기준.
 * @return {string|null} 불일치 설명, 같으면 null
 */
export function verifyAgainstReplay(runner, replay) {
  const results = runner.rallyResults.slice();
  if (!replay.ended) {
    // 잘린 게임의 마지막(미완) 랠리. 경계에서 잘렸으면 리셋 대기 중이라 0 프레임이다.
    results.push({ frames: runner.pendingRallyStart ? 0 : runner.rallyFrames, outcome: OUTCOME.UNFINISHED });
  }
  if (runner.frame !== replay.frameCount) return `프레임: 기록 ${replay.frameCount} ≠ 재생 ${runner.frame}`;
  if (replay.ended !== runner.gameEnded) return `ended: 기록 ${replay.ended} ≠ 재생 ${runner.gameEnded}`;
  if (results.length !== replay.rallyFrames.length) return `랠리 수: 기록 ${replay.rallyFrames.length} ≠ 재생 ${results.length}`;
  for (let k = 0; k < results.length; k++) {
    if (results[k].frames !== replay.rallyFrames[k] || results[k].outcome !== replay.rallyOutcomes[k]) {
      return `랠리 ${k}: 기록 (${replay.rallyFrames[k]}, ${replay.rallyOutcomes[k]}) ≠ 재생 (${results[k].frames}, ${results[k].outcome})`;
    }
  }
  if (runner.scores[0] !== replay.finalScore[0] || runner.scores[1] !== replay.finalScore[1]) {
    return `점수: 기록 ${replay.finalScore} ≠ 재생 ${runner.scores}`;
  }
  return null;
}
