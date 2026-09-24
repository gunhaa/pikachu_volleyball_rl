/**
 * 라이브 경기를 리플레이 v1 로 적는다 — Kotlin `ReplayRecorder` 와 같은 호출 순서 · 같은 바이트. (FR-18)
 *
 *   beginGame(seed, config) { frame(inputs)… endRally(outcome) beginRally(seed) }… endGame()
 *
 * 끝난 리플레이(인코드된 바이트 + 객체)는 sink 로 나간다. 상한 [cap] 의 경계도 Kotlin 과 같다:
 * cap + 1 번째 frame() 에서 잘라 ended = false.
 */
'use strict';

import { encodeAction } from './action.mjs';
import { encodeReplay, OUTCOME } from './codec.mjs';

export const DEFAULT_CAP = 60000;

export class ReplayRecorder {
  /**
   * @param {number} seedMode SEED_MODE
   * @param {function(Uint8Array, Object): void} sink (bytes, replay)
   * @param {number} [cap]
   */
  constructor(seedMode, sink, cap = DEFAULT_CAP) {
    this.seedMode = seedMode;
    this.sink = sink;
    this.cap = cap;
    this.config = null;
  }

  get recording() {
    return this.config !== null;
  }

  /**
   * @param {number} seed
   * @param {{p1External:boolean, p2External:boolean, firstServeIsPlayer2:boolean, winningScore:number,
   *          fixedBoldness:number[], maxRallyFrames:number, edgeTrigger?:boolean}} config
   */
  beginGame(seed, config) {
    this.config = config;
    this.slots = [];
    if (config.p1External) this.slots.push(0);
    if (config.p2External) this.slots.push(1);
    this.seeds = [seed | 0];
    this.rallyFrames = [];
    this.rallyOutcomes = [];
    this.current = 0;
    this.scores = [0, 0];
    this.inputs = this.slots.map(() => []);
    this.frameCount = 0;
  }

  beginRally(seed) {
    if (!this.recording) return;
    if (this.seedMode !== 1) throw new Error('GAME 규약에서는 랠리 시드를 적지 않습니다');
    if (this.seeds.length !== this.rallyFrames.length) throw new Error('beginRally 는 endRally 뒤에 한 번만');
    this.seeds.push(seed | 0);
  }

  /** 물리 한 프레임의 입력. runEngineForNextFrame **직전**에 부른다. */
  frame(inputs) {
    if (!this.recording) return;
    if (this.frameCount === this.cap) {
      this.finish(false);
      return;
    }
    for (let k = 0; k < this.slots.length; k++) {
      const u = inputs[this.slots[k]];
      this.inputs[k].push(encodeAction(u.xDirection, u.yDirection, u.powerHit === 1));
    }
    this.frameCount++;
    this.current++;
  }

  endRally(outcome) {
    if (!this.recording) return;
    this.rallyFrames.push(this.current);
    this.rallyOutcomes.push(outcome);
    if (outcome >= 0) this.scores[outcome]++;
    this.current = 0;
  }

  endGame() {
    if (!this.recording) return;
    this.finish(true);
  }

  finish(ended) {
    const c = this.config;
    if (!ended) {
      this.rallyFrames.push(this.current);
      this.rallyOutcomes.push(OUTCOME.UNFINISHED);
    }
    const inputs = new Uint8Array(this.frameCount * this.slots.length);
    this.inputs.forEach((arr, k) => inputs.set(arr, k * this.frameCount));
    const replay = {
      p1External: c.p1External,
      p2External: c.p2External,
      firstServeIsPlayer2: c.firstServeIsPlayer2,
      ended,
      edgeTrigger: !!c.edgeTrigger,
      seedMode: this.seedMode,
      winningScore: c.winningScore,
      fixedBoldness: [...c.fixedBoldness],
      maxRallyFrames: c.maxRallyFrames,
      seeds: Int32Array.from(this.seedMode === 0 ? this.seeds.slice(0, 1) : this.seeds),
      rallyFrames: Int32Array.from(this.rallyFrames),
      rallyOutcomes: Int8Array.from(this.rallyOutcomes),
      finalScore: [...this.scores],
      frameCount: this.frameCount,
      inputs,
    };
    this.config = null;
    this.sink(encodeReplay(replay), replay);
  }
}
