/**
 * chain.mjs · sha256.mjs 가 Phase 1 규약(tools/js-oracle/spec.mjs, node:crypto)과 같은가.
 * 브라우저용으로 다시 적은 목록이라 어긋나면 여기서 먼저 걸려야 한다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { sha256, toHex } from '../src/runner/sha256.mjs';
import { Chain, FIELD_NAMES, writeState } from '../src/runner/chain.mjs';
import { BASE_FIELDS, assertMatchesProto, toIntArray, packInts, chainStep, CHAIN_SEED } from '../../tools/js-oracle/spec.mjs';
import { runnerFromReplay } from '../src/runner/runner.mjs';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { GOLDEN_DIR } from './paths.mjs';

test('sha256: node:crypto 와 같다 (길이 0..300, 블록 경계 포함)', () => {
  for (let len = 0; len <= 300; len++) {
    const data = randomBytes(len);
    assert.equal(toHex(sha256(data)), createHash('sha256').update(data).digest('hex'), `len=${len}`);
  }
});

test('필드 이름이 spec.mjs (= state_spec.proto) 와 같다', () => {
  assertMatchesProto();
  assert.deepEqual(FIELD_NAMES, BASE_FIELDS.map(([n]) => n));
});

test('체인: spec.mjs + node:crypto 로 뜬 체인과 프레임마다 같다 (골든 한 게임)', () => {
  const bytes = readFileSync(join(GOLDEN_DIR, 'powerhit.pkr'));
  const chain = new Chain();
  let ref = CHAIN_SEED;
  const ints = new Int32Array(FIELD_NAMES.length);
  const { runner } = runnerFromReplay(bytes, {
    onFrame: (r, touching) => {
      chain.step(r.physics, touching);
      const expected = toIntArray(r.physics, touching, false);
      assert.deepEqual(Array.from(writeState(r.physics, touching, ints)), expected);
      ref = chainStep(ref, packInts(expected));
      assert.equal(chain.hex(), ref.toString('hex'));
    },
  });
  while (!runner.ended) runner.step();
  assert.ok(chain.frames > 1000);
});
