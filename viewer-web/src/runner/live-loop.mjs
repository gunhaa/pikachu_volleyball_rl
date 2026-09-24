/**
 * 비동기 라이브 루프 — 브라우저와 Node 하네스가 같은 함수로 정책 경기를 치른다. (Phase 5 FR-9, plan.md §7.2)
 *
 * 한 프레임 = beginFrame() → 입력원 prepare (있는 것만, 슬롯 순서대로 await) → step().
 * prepare 가 있는 입력원이 없으면 기존 동기 step() 과 같다.
 *
 * ⚠️ prepare 를 병렬(Promise.all)로 부르지 않는다. 두 슬롯이 한 ORT 세션을 공유할 수 있고, 한 세션에
 *    겹친 run() 을 wasm 백엔드가 받아 주는지에 결정론을 걸지 않는다. 추론은 0.01 ms 라 잃는 것이 없다.
 */
'use strict';

import { SEED_MODE } from './codec.mjs';
import { ReplayRecorder } from './recorder.mjs';
import { GameRunner } from './runner.mjs';

/**
 * @param {GameRunner} runner
 * @return {Promise<{isBallTouchingGround:boolean, scorer:(number|null), outcome:(number|null)}>}
 */
export async function advance(runner) {
  runner.beginFrame();
  for (let i = 0; i < 2; i++) {
    const src = runner.sources[i];
    if (src.prepare) await src.prepare(runner, i === 1);
  }
  return runner.step();
}

/**
 * 라이브 한 판을 치르고 기록된 바이트를 돌려준다.
 *
 * @param {Object} opts
 * @param {Array} opts.sources [p1, p2]
 * @param {Object} opts.seeds `DerivedSeeds` · `FreshSeeds` 등 (RALLY 규약)
 * @param {Object} [opts.settings] GameRunner settings
 * @param {number} [opts.cap] 기록 프레임 상한 (`replay_frame_cap`) — 넘으면 잘린 게임으로 끝낸다
 * @param {function(GameRunner, boolean): void} [opts.onFrame]
 * @param {function(GameRunner): (void|Promise<void>)} [opts.afterFrame] 프레임 사이 (그리기 · 양보)
 * @return {Promise<{bytes: Uint8Array, replay: Object, runner: GameRunner}>}
 */
export async function playLive({ sources, seeds, settings = {}, cap, onFrame = null, afterFrame = null }) {
  let result = null;
  const recorder = new ReplayRecorder(SEED_MODE.RALLY, (bytes, replay) => { result = { bytes, replay }; }, cap);
  const runner = new GameRunner({ sources, seeds, settings, recorder, onFrame });
  while (result === null) {
    if (runner.ended) throw new Error('기록 없이 경기가 끝났습니다');
    await advance(runner);
    if (afterFrame) await afterFrame(runner);
  }
  return { ...result, runner };
}
