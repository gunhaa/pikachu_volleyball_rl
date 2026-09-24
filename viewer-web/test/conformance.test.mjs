/**
 * M4-b — JS 재생 ≡ Kotlin 재생. 골든 리플레이 전부를 JS 러너로 재생해 체인 해시를 대조한다.
 *
 * 어긋나면 1,000 프레임 단위 중간 해시로 첫 불일치 구간을 좁히고, 그 구간의 JS 상태를
 * 파일로 떨군다. Kotlin 쪽 같은 구간은 `analysis dump-states` 로 떠서
 * `node test/diff-states.mjs <js> <kt>` 로 첫 불일치 프레임 · 필드를 본다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Chain, INT_COUNT, writeState } from '../src/runner/chain.mjs';
import { runnerFromReplay, verifyAgainstReplay } from '../src/runner/runner.mjs';
import { GOLDEN_DIR } from './paths.mjs';

const golden = JSON.parse(readFileSync(join(GOLDEN_DIR, 'chains.json'), 'utf8'));

/** 한 리플레이를 JS 로 재생해 체인 · 중간값 · 러너를 돌려준다. */
export function jsChain(bytes, { interval = golden.interval, dumpFrom = -1, dumpTo = -1 } = {}) {
  const chain = new Chain();
  const checkpoints = [];
  const dump = [];
  const ints = new Int32Array(INT_COUNT);
  const { runner, replay } = runnerFromReplay(bytes, {
    onFrame: (r, touching) => {
      chain.step(r.physics, touching);
      if (chain.frames % interval === 0) checkpoints.push(chain.hex());
      const f = chain.frames - 1;
      if (f >= dumpFrom && f < dumpTo) dump.push(`${f} ${Array.from(writeState(r.physics, touching, ints)).join(' ')}`);
    },
  });
  while (!runner.ended) runner.step();
  return { final: chain.hex(), checkpoints, runner, replay, dump };
}

test(`골든 ${golden.games.length}개 (${golden.totalFrames} 프레임): 체인 해시 100% 일치`, () => {
  let frames = 0;
  for (const g of golden.games) {
    const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, g.file)));
    const js = jsChain(bytes);
    frames += js.runner.frame;
    if (js.final !== g.final) {
      const k = g.checkpoints.findIndex((h, i) => h !== js.checkpoints[i]);
      const from = (k < 0 ? g.checkpoints.length : k) * golden.interval;
      const to = Math.min(from + golden.interval, g.frames);
      const dir = mkdtempSync(join(tmpdir(), 'pika-conformance-'));
      const out = join(dir, `${g.file}.js.txt`);
      writeFileSync(out, jsChain(bytes, { dumpFrom: from, dumpTo: to }).dump.join('\n') + '\n');
      assert.fail(
        `${g.file}: 체인 불일치 — 첫 불일치 구간 [${from}, ${to}) 프레임\n` +
        `  JS 상태: ${out}\n` +
        `  Kotlin : ./gradlew :engine-kotlin:analysis:run --args="dump-states ${join(GOLDEN_DIR, g.file)} --from ${from} --count ${to - from} --out ${join(dir, 'kt.txt')}"\n` +
        `  비교   : node viewer-web/test/diff-states.mjs ${out} ${join(dir, 'kt.txt')}`,
      );
    }
    assert.deepEqual(js.checkpoints, g.checkpoints, g.file);
    assert.equal(verifyAgainstReplay(js.runner, js.replay), null, g.file);
    assert.deepEqual(js.runner.scores, g.score, g.file);
  }
  assert.equal(frames, golden.totalFrames);
});

test('seek: 처음부터 다시 계산한 상태 = 이어서 진행한 상태', () => {
  const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, 'fsm-game-s0-r.pkr')));
  const { runner } = runnerFromReplay(bytes);
  const ints = new Int32Array(INT_COUNT);
  for (const target of [0, 1, 777, 5000, 12345]) {
    const { runner: straight } = runnerFromReplay(bytes);
    while (straight.frame < target) straight.step();
    runner.seek(target);
    assert.equal(runner.frame, target);
    assert.deepEqual(writeState(runner.physics, false, ints.slice()), writeState(straight.physics, false, ints.slice()));
    assert.deepEqual(runner.scores, straight.scores);
  }
});

test('NFR-3: 가장 긴 골든 끝까지 seek ≤ 100 ms (60,000 프레임 환산)', () => {
  const g = golden.games.reduce((a, b) => (a.frames > b.frames ? a : b));
  const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, g.file)));
  const { runner } = runnerFromReplay(bytes);
  runner.seek(g.frames); // 워밍업
  const t0 = performance.now();
  runner.seek(g.frames);
  const ms = performance.now() - t0;
  const per60k = (ms * 60000) / g.frames;
  console.log(`seek ${g.frames} 프레임 ${ms.toFixed(1)} ms → 60,000 프레임 환산 ${per60k.toFixed(1)} ms`);
  assert.ok(per60k <= 100, `${per60k.toFixed(1)} ms`);
});
