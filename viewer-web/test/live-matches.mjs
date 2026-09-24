/**
 * 라이브 경기 표본 (M4-j). `live.test.mjs` 와 `export-live.mjs`(Kotlin 대조용)가 같은 경기를 쓴다.
 *
 * 입력원 조합에 FSM 을 포함한다 — FSM 을 일반 입력원처럼 다뤄 decide 를 부르면 RNG 소비 순서가
 * 깨질 수 있는데, 그것은 FSM 이 낀 경기에서만 드러난다 (PRD §7).
 */
import { xorshift32 } from '../../tools/js-oracle/xorshift32.mjs';
import { Chain } from '../src/runner/chain.mjs';
import { SEED_MODE } from '../src/runner/codec.mjs';
import { ReplayRecorder } from '../src/runner/recorder.mjs';
import { GameRunner } from '../src/runner/runner.mjs';
import { FreshSeeds } from '../src/runner/seeds.mjs';
import { FsmSource } from '../src/sources/fsm.mjs';
import { ScriptedSource, hashedActions } from '../src/sources/scripted.mjs';

const S = (salt, pct) => () => new ScriptedSource(hashedActions(salt, { powerHitPercent: pct }));
const F = () => () => new FsmSource();

export const LIVE_CASES = [
  { name: 'live-scripted-fsm', sources: [S(1, 30), F()], seed: 101, settings: { maxRallyFrames: 3000, edgeTrigger: true } },
  { name: 'live-fsm-scripted', sources: [F(), S(2, 30)], seed: 202, settings: { maxRallyFrames: 3000, firstServeIsPlayer2: true } },
  { name: 'live-scripted-scripted', sources: [S(3, 50), S(4, 10)], seed: 303, settings: { maxRallyFrames: 200 } },
  { name: 'live-fsm-fsm', sources: [F(), F()], seed: 404, settings: { maxRallyFrames: 0 } },
  { name: 'live-boldness', sources: [S(5, 30), F()], seed: 505, settings: { fixedBoldness: [-1, 3], maxRallyFrames: 3000 } },
  { name: 'live-cap', sources: [F(), F()], seed: 606, settings: {}, cap: 700 },
];

/**
 * 라이브 한 판: 러너가 입력원에서 입력을 받아 돌고, 기록기가 적는다.
 * @return {{bytes: Uint8Array, replay: Object, liveChain: string, liveFrames: number}}
 */
export function playLive(c) {
  const chain = new Chain();
  let result = null;
  const next = xorshift32(c.seed);
  const recorder = new ReplayRecorder(SEED_MODE.RALLY, (bytes, replay) => {
    // 끝난 게임은 마지막 프레임 뒤, 잘린 게임은 cap + 1 번째 프레임 **전** 에 불린다.
    result = { bytes, replay, liveChain: chain.hex(), liveFrames: chain.frames };
  }, c.cap);
  const runner = new GameRunner({
    sources: c.sources.map((f) => f()),
    seeds: new FreshSeeds(() => next() | 0),
    settings: c.settings,
    recorder,
    onFrame: (r, t) => chain.step(r.physics, t),
  });
  while (result === null) {
    if (runner.ended) throw new Error(`${c.name}: 기록 없이 경기가 끝났습니다`);
    runner.step();
  }
  return result;
}
