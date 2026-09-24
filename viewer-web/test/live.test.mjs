/**
 * M4-j (JS 쪽) — 라이브 경기 → 기록 → ReplaySource 재생: 라이브 중 계산한 체인 = 재생 체인.
 * Kotlin ReplayPlayer 재생과의 대조는 Gradle `JsRunnerConformanceTest` 가 `export-live.mjs` 로 한다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { Chain } from '../src/runner/chain.mjs';
import { SEED_MODE } from '../src/runner/codec.mjs';
import { runnerFromReplay, verifyAgainstReplay } from '../src/runner/runner.mjs';
import { LIVE_CASES, playLive } from './live-matches.mjs';

for (const c of LIVE_CASES) {
  test(`${c.name}: 라이브 체인 = JS 재생 체인, 기록 결과와 재생 결과가 같다`, () => {
    const live = playLive(c);
    assert.equal(live.replay.seedMode, SEED_MODE.RALLY, '라이브는 RALLY 규약');
    assert.equal(live.replay.ended, c.cap === undefined);
    assert.equal(live.replay.frameCount, live.liveFrames);
    assert.equal(live.replay.seeds.length, live.replay.rallyFrames.length);

    const chain = new Chain();
    const { runner, replay } = runnerFromReplay(live.bytes, { onFrame: (r, t) => chain.step(r.physics, t) });
    while (!runner.ended) runner.step();
    assert.equal(chain.hex(), live.liveChain);
    assert.equal(verifyAgainstReplay(runner, replay), null);
  });
}

test('FSM 슬롯의 decide 는 불리지 않는다', () => {
  const c = LIVE_CASES.find((x) => x.name === 'live-scripted-fsm');
  const traps = c.sources.map((f) => {
    const s = f();
    if (s.kind === 'fsm') s.decide = () => { throw new Error('FSM decide 가 불렸다'); };
    return () => s;
  });
  playLive({ ...c, sources: traps });
});

test('라이브 시드는 매 판 새로 뽑힌다 (FreshSeeds 기본값 = crypto)', async () => {
  const { FreshSeeds } = await import('../src/runner/seeds.mjs');
  const s = new FreshSeeds();
  const seen = new Set(Array.from({ length: 8 }, () => s.first()));
  assert.ok(seen.size > 1);
});
