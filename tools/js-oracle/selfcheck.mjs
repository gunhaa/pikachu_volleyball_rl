#!/usr/bin/env node
/**
 * 오라클 자체 검증. 포팅을 비교하기 전에 "정답 쪽이 흔들리지 않는가" 부터 본다.
 *
 *   1. spec.mjs 의 필드 순서가 proto/state_spec.proto 와 같은가
 *   2. rand() 경로에 부동소수 오차가 없는가  (floor(32768 * u/2^32) === u >>> 17)
 *   3. 같은 시드를 두 번 돌리면 같은 출력인가 (결정론)
 *   4. 다른 시드는 다른 출력인가 (시드가 실제로 먹히는가)
 *
 * 출력 끝에 Kotlin 쪽 단위 테스트가 하드코딩할 참조 벡터를 찍는다.
 */
'use strict';

import { assertMatchesProto, toIntArray, packInts, frameHash } from './spec.mjs';
import { runEpisode } from './harness.mjs';
import { xorshift32, toCustomRng } from './xorshift32.mjs';
import { GENERATORS } from './inputs.mjs';

let failures = 0;
const ok = (name) => console.log(`  ok   ${name}`);
const fail = (name, detail) => {
  failures++;
  console.log(`  FAIL ${name}\n       ${detail}`);
};

function check(name, fn) {
  try {
    fn();
    ok(name);
  } catch (e) {
    fail(name, e.message);
  }
}

// 1 ───────────────────────────────────────────────────────────────────────────
console.log('State Spec');
check('spec.mjs 필드 순서 == proto/state_spec.proto', () => {
  const c = assertMatchesProto();
  if (c.base !== 44 || c.sound !== 8) throw new Error(`필드 수가 예상과 다름: ${JSON.stringify(c)}`);
});

// 2 ───────────────────────────────────────────────────────────────────────────
console.log('RNG');
check('floor(32768 * u/2^32) === u >>> 17 (전 구간 표본)', () => {
  const next = xorshift32(12345);
  const rng = toCustomRng(next);
  // next() 를 두 번 부르면 안 되므로 값을 가로채서 양쪽을 비교한다.
  const probe = xorshift32(12345);
  for (let i = 0; i < 100000; i++) {
    const expected = probe() >>> 17;
    const actual = Math.floor(32768 * rng());
    if (expected !== actual) throw new Error(`i=${i}: ${actual} != ${expected}`);
  }
});
check('xorshift32 는 0 시드에서 고착되지 않는다', () => {
  const next = xorshift32(0);
  const a = next();
  const bb = next();
  if (a === 0 || a === bb) throw new Error(`0 시드 처리 실패: ${a}, ${bb}`);
});

// 3, 4 ────────────────────────────────────────────────────────────────────────
function episodeHashes(seed, gen, frames = 200) {
  const out = [];
  const buf = Buffer.allocUnsafe(44 * 4);
  runEpisode({ seed, frames, gen }, (physics, touching) => {
    packInts(toIntArray(physics, touching, false), buf);
    out.push(frameHash(buf));
  });
  return out;
}

console.log('결정론');
for (const gen of GENERATORS) {
  check(`${gen}: 같은 시드 2회 → 동일 출력`, () => {
    const a = episodeHashes(7, gen);
    const b = episodeHashes(7, gen);
    if (a.join() !== b.join()) throw new Error('두 번의 실행 결과가 다르다');
  });
  check(`${gen}: 다른 시드 → 다른 출력`, () => {
    const a = episodeHashes(7, gen);
    const b = episodeHashes(8, gen);
    if (a.join() === b.join()) throw new Error('시드가 결과에 영향을 주지 않는다');
  });
}

// 참조 벡터 ────────────────────────────────────────────────────────────────────
console.log('\nKotlin 단위 테스트용 참조 벡터');
const ref = xorshift32(1);
const seq = Array.from({ length: 8 }, () => ref() >>> 0);
console.log(`  xorshift32(1) 처음 8개 uint32: ${seq.join(', ')}`);
const refRand = xorshift32(1);
const rands = Array.from({ length: 8 }, () => refRand() >>> 17);
console.log(`  rand() 상당값 (u >>> 17)     : ${rands.join(', ')}`);
console.log(`  seed=1 uniform 프레임 0..2 해시: ${episodeHashes(1, 'uniform', 3).join(', ')}`);
console.log(`  seed=1 fsm     프레임 0..2 해시: ${episodeHashes(1, 'fsm', 3).join(', ')}`);

console.log(failures === 0 ? '\n전부 통과' : `\n실패 ${failures}건`);
process.exit(failures === 0 ? 0 : 1);
