#!/usr/bin/env node
/**
 * JS 오라클 CLI — `(시드, 생성기)` → 프레임별 상태 해시를 stdout 으로 스트리밍한다.
 *
 * 프로세스는 한 번만 띄우고 전체 시드를 한 프로세스가 처리한다.
 * 7,000회 spawn 하면 그 오버헤드가 물리 계산보다 커진다. (plan.md §5.2)
 *
 * 사용 예
 *   node run.mjs --seeds 1..3000 --frames 600 --gen uniform --mode frame-hash
 *   node run.mjs --seeds 42 --frames 600 --gen biased --mode full
 *   node run.mjs --seeds 1..200 --frames 600 --gen fsm --mode chain-hash
 *
 * 출력 (frame-hash)
 *   # {"spec":"v1",...}      ← 헤더 1줄. 소비자가 설정 일치를 확인한다.
 *   E 1                      ← 에피소드 시작
 *   3f2a...                  ← 프레임 0 해시 (hex 16자)
 *   ...
 *   E 2
 *   ...
 */
'use strict';

import { once } from 'node:events';
import {
  assertMatchesProto,
  toIntArray,
  packInts,
  frameHash,
  chainStep,
  CHAIN_SEED,
  BASE_FIELDS,
  SOUND_FIELDS,
} from './spec.mjs';
import { runEpisode, runResetProbe } from './harness.mjs';
import { GENERATORS, GEN_UNIFORM } from './inputs.mjs';

const MODES = ['frame-hash', 'chain-hash', 'full', 'reset-probe'];

function parseSeeds(text) {
  const seeds = [];
  for (const part of text.split(',')) {
    const range = part.match(/^(\d+)\.\.(\d+)$/);
    if (range) {
      const [lo, hi] = [Number(range[1]), Number(range[2])];
      if (hi < lo) throw new Error(`잘못된 시드 범위: ${part}`);
      for (let s = lo; s <= hi; s++) seeds.push(s);
    } else if (/^\d+$/.test(part)) {
      seeds.push(Number(part));
    } else {
      throw new Error(`잘못된 시드 표기: ${part} (예: 1..7000 또는 42 또는 1,2,3)`);
    }
  }
  return seeds;
}

function parseArgs(argv) {
  const opts = {
    seeds: '1..10',
    frames: 600,
    gen: GEN_UNIFORM,
    mode: 'frame-hash',
    strict: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--strict') opts.strict = true;
    else if (a.startsWith('--')) {
      const key = a.slice(2);
      if (!(key in opts)) throw new Error(`알 수 없는 옵션: ${a}`);
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} 에 값이 없습니다`);
      opts[key] = key === 'frames' ? Number(v) : v;
    } else throw new Error(`알 수 없는 인자: ${a}`);
  }
  if (!MODES.includes(opts.mode)) throw new Error(`--mode 는 ${MODES.join('|')} 중 하나여야 합니다`);
  if (!GENERATORS.includes(opts.gen)) throw new Error(`--gen 은 ${GENERATORS.join('|')} 중 하나여야 합니다`);
  if (!Number.isInteger(opts.frames) || opts.frames <= 0) throw new Error('--frames 는 양의 정수');
  return opts;
}

/** 에피소드 단위로 모아 쓰고, 백프레셔가 걸리면 drain 을 기다린다. */
async function writeOut(text) {
  if (!process.stdout.write(text)) await once(process.stdout, 'drain');
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const counts = assertMatchesProto(); // 필드 순서가 .proto 와 같은지 먼저 확인

  const seeds = parseSeeds(opts.seeds);
  const intCount = counts.base + (opts.strict ? counts.sound : 0);
  const buf = Buffer.allocUnsafe(intCount * 4);

  const header = {
    spec: 'v1',
    mode: opts.mode,
    gen: opts.gen,
    frames: opts.frames,
    strict: opts.strict,
    seedCount: seeds.length,
    intCount,
    fields: opts.mode === 'full'
      ? [...BASE_FIELDS.map(([n]) => n), ...(opts.strict ? SOUND_FIELDS.map(([n]) => n) : [])]
      : undefined,
  };
  await writeOut(`# ${JSON.stringify(header)}\n`);

  for (const seed of seeds) {
    const lines = [`E ${seed}\n`];
    let chain = CHAIN_SEED;

    const emit = (physics, touching, inputs, f) => {
      const ints = toIntArray(physics, touching, opts.strict);
      packInts(ints, buf);

      switch (opts.mode) {
        case 'reset-probe':
        case 'frame-hash':
          lines.push(frameHash(buf), '\n');
          break;
        case 'chain-hash':
          chain = chainStep(chain, buf);
          break;
        case 'full':
          lines.push(
            JSON.stringify({
              f,
              s: ints,
              i: [
                [inputs[0].xDirection, inputs[0].yDirection, inputs[0].powerHit],
                [inputs[1].xDirection, inputs[1].yDirection, inputs[1].powerHit],
              ],
            }),
            '\n'
          );
          break;
      }
    };

    if (opts.mode === 'reset-probe') {
      // 엔진을 돌리지 않는다. 리셋 전 상태만 덤프한다.
      runResetProbe({ seed, resets: opts.frames, gen: opts.gen }, (physics, i) =>
        emit(physics, false, [{ xDirection: 0, yDirection: 0, powerHit: 0 },
                              { xDirection: 0, yDirection: 0, powerHit: 0 }], i)
      );
    } else {
      runEpisode({ seed, frames: opts.frames, gen: opts.gen }, emit);
    }

    if (opts.mode === 'chain-hash') lines.push(`C ${chain.toString('hex')}\n`);
    await writeOut(lines.join(''));
  }
}

main().catch((err) => {
  process.stderr.write(`[js-oracle] ${err.stack ?? err}\n`);
  process.exit(1);
});
