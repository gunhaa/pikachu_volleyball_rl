/** codec.mjs ↔ Kotlin ReplayCodec — 같은 바이트. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { decodeReplay, encodeReplay } from '../src/runner/codec.mjs';
import { GOLDEN_DIR } from './paths.mjs';

const files = readdirSync(GOLDEN_DIR).filter((f) => f.endsWith('.pkr')).sort();

test('골든 전부: Kotlin 이 쓴 바이트 → 디코드 → 인코드 = 같은 바이트', () => {
  assert.ok(files.length >= 10);
  for (const f of files) {
    const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, f)));
    assert.deepEqual(encodeReplay(decodeReplay(bytes)), bytes, f);
  }
});

test('magic · 버전 · 길이가 틀리면 예외', () => {
  const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, files[0])));
  const bad = (mutate) => { const b = bytes.slice(); mutate(b); return b; };
  assert.throws(() => decodeReplay(bad((b) => { b[0] = 0; })), /magic/);
  assert.throws(() => decodeReplay(bad((b) => { b[4] = 2; })), /버전/);
  assert.throws(() => decodeReplay(bytes.slice(0, bytes.length - 1)));
  const longer = new Uint8Array(bytes.length + 1); longer.set(bytes);
  assert.throws(() => decodeReplay(longer));
  assert.throws(() => decodeReplay(new Uint8Array(3)));
});
