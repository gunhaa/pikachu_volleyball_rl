/**
 * JS 관측 · 시드 유도 = Kotlin. (Phase 5 P2, M5-a)
 *
 * 정답은 Kotlin 이 만든 `engine-kotlin/env/golden/obs/` 다 — 결정 관측 체인(chains.json) + 리플레이,
 * 레이아웃 해시 · deriveSeed 표(constants.json). 필드 목록과 정규화 상수는 Kotlin 이 아니라 proto 에 대조한다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { REPO, OBS_GOLDEN_DIR } from './paths.mjs';
import { NORM, fieldNames, flattenProto, layoutHash, obsDim, parseProto } from '../src/policy/obs-spec.mjs';
import { ObsChain, ObsEncoder } from '../src/policy/obs.mjs';
import { runnerFromReplay, verifyAgainstReplay } from '../src/runner/runner.mjs';
import { DerivedSeeds, deriveSeed } from '../src/runner/seeds.mjs';
import { SEED_MODE } from '../src/runner/codec.mjs';

const PROTO = readFileSync(join(REPO, 'proto', 'obs_spec.proto'), 'utf8');
const CHAINS = JSON.parse(readFileSync(join(OBS_GOLDEN_DIR, 'chains.json'), 'utf8'));
const CONSTANTS = JSON.parse(readFileSync(join(OBS_GOLDEN_DIR, 'constants.json'), 'utf8'));
const COMBOS = [
  { includeLanding: true, includeSideFlag: false },
  { includeLanding: false, includeSideFlag: false },
  { includeLanding: true, includeSideFlag: true },
  { includeLanding: false, includeSideFlag: true },
];

// ─── 레이아웃 ───────────────────────────────────────────────────────────────

test('필드 목록 = obs_spec.proto 평탄화 (네 플래그 조합, @optional 포함)', () => {
  const messages = parseProto(PROTO);
  for (const opts of COMBOS) assert.deepEqual(fieldNames(opts), flattenProto(messages, opts), JSON.stringify(opts));
});

test('레이아웃 해시 · dim = Kotlin ObsSpec (네 조합)', () => {
  assert.equal(CONSTANTS.layouts.length, 4);
  for (const k of CONSTANTS.layouts) {
    const opts = { includeLanding: k.includeLanding, includeSideFlag: k.includeSideFlag };
    assert.equal(layoutHash(opts), k.layoutHash, JSON.stringify(opts));
    assert.equal(obsDim(opts), k.dim, JSON.stringify(opts));
  }
  // env-chain-hashes.txt 의 16자 열과도 맞는다 (기본 · no-landing · side-flag).
  const envGolden = readFileSync(join(REPO, 'engine-kotlin', 'env', 'golden', 'env-chain-hashes.txt'), 'utf8');
  const col = (name) => envGolden.split('\n').find((l) => l.startsWith(`${name} `)).split(/\s+/)[3];
  assert.equal(layoutHash(COMBOS[0]).slice(0, 16), col('trackA'));
  assert.equal(layoutHash(COMBOS[1]).slice(0, 16), col('no-landing'));
  assert.equal(layoutHash(COMBOS[2]).slice(0, 16), col('side-flag'));
});

test('정규화 상수 = proto 주석의 숫자', () => {
  const num = (re) => {
    const m = re.exec(PROTO);
    assert.ok(m, `proto 주석에서 못 찾음: ${re}`);
    return m.slice(1).map(Number);
  };
  assert.deepEqual(num(/me\/opponent\.x\s*:\s*unit\(x, (\d+), (\d+)\)/), NORM.leftX);
  assert.deepEqual(num(/^\/\/\s+unit\(x, (\d+), (\d+)\) 오른쪽 진영 기준/m), NORM.rightX);
  assert.deepEqual(num(/me\/opponent\.y\s*:\s*unit\(y, (\d+), (\d+)\)/), NORM.playerY);
  assert.deepEqual(num(/me\/opponent\.y_velocity\s*:\s*v \/ (\d+)/), [NORM.playerYVelocity]);
  assert.deepEqual(num(/me\/opponent\.frame_number\s*:\s*v \/ (\d+)/), [NORM.frameNumber]);
  assert.deepEqual(num(/me\/opponent\.lying_down_duration_left\s*:\s*v \/ (\d+)/), [NORM.lyingDown]);
  assert.deepEqual(num(/me\/opponent\.delay_before_next_frame\s*:\s*v \/ (\d+)/), [NORM.delay]);
  assert.deepEqual(num(/ball\.x, ball\.expected_landing_point_x\s*:\s*unit\(x, (\d+), (\d+)\)/), NORM.ballX);
  assert.deepEqual(num(/ball\.y\s*:\s*unit\(y, (\d+), (\d+)\)/), NORM.ballY);
  assert.deepEqual(num(/ball\.x_velocity, ball\.y_velocity\s*:\s*v \/ (\d+)/), [NORM.ballVelocity]);
  assert.deepEqual(num(/x\s+→ (\d+) - x/), [NORM.groundWidth]);
});

// ─── -0 ─────────────────────────────────────────────────────────────────────

function fakeRunner({ divingDirection = 0, xVelocity = 0 } = {}) {
  const player = (x) => ({
    x, y: 244, yVelocity: 0, state: 0, frameNumber: 0, divingDirection, lyingDownDurationLeft: -1,
    isCollisionWithBallHappened: false, delayBeforeNextFrame: 0,
  });
  return {
    physics: {
      player1: player(36), player2: player(396),
      ball: { x: 56, y: 0, xVelocity, yVelocity: 1, isPowerHit: false, expectedLandingPointX: 56 },
    },
    scores: [0, 0],
    isPlayer2Serve: false,
  };
}

const bits = (arr, i) => new Uint32Array(arr.buffer, arr.byteOffset + i * 4, 1)[0];
const IDX = { meDiving: 11, oppDiving: 15 + 11, ballXv: 32 };

test('-0 금지: 미러된 정지 공 · 정지 다이빙 → float32 비트가 +0', () => {
  const enc = new ObsEncoder({ includeSideFlag: true });
  const out = enc.encode(fakeRunner(), 1, new Float32Array(enc.dim));
  for (const [name, i] of Object.entries(IDX)) assert.equal(bits(out, i), 0, `${name} 가 -0`);
});

test('-0 금지: 업스트림 상태가 이미 -0 이어도 (미러 없음 · 미러) +0', () => {
  const enc = new ObsEncoder();
  for (const slot of [0, 1]) {
    const out = enc.encode(fakeRunner({ divingDirection: -0, xVelocity: -0 }), slot, new Float32Array(enc.dim));
    for (const [name, i] of Object.entries(IDX)) assert.equal(bits(out, i), 0, `slot ${slot} ${name} 가 -0`);
  }
});

// ─── M5-a: 결정 관측 체인 ─────────────────────────────────────────────────────

/** decide 직전에 관측을 인코딩해 체인에 넣고 원래 입력원에 위임한다. */
function probe(src, slot, encoder, chain) {
  const buf = new Float32Array(encoder.dim);
  return {
    kind: src.kind,
    decide(runner, isPlayer2, out) {
      chain.push(slot, encoder.encode(runner, slot, buf));
      src.decide(runner, isPlayer2, out);
    },
    reset: (r) => src.reset?.(r),
    onRallyStart: (r) => src.onRallyStart?.(r),
  };
}

test('결정 관측 골든은 13 케이스 (EnvGolden 11 + 진영 플래그 2)', () => {
  assert.equal(CHAINS.cases.length, 13);
  const names = CHAINS.cases.map((c) => c.name);
  assert.ok(names.includes('side-flag-right') && names.includes('side-flag-both'));
});

for (const c of CHAINS.cases) {
  test(`M5-a 결정 관측 체인 = Kotlin: ${c.name}`, () => {
    const encoder = new ObsEncoder(c.obs);
    assert.equal(encoder.layoutHash, c.layoutHash, '레이아웃 해시');
    assert.equal(encoder.dim, c.dim, 'dim');
    for (const env of c.envs) {
      const chain = new ObsChain();
      let frames = 0;
      let slotCount = 0;
      for (const file of env.games) {
        const { runner, replay } = runnerFromReplay(readFileSync(join(OBS_GOLDEN_DIR, file)), {
          wrap: (src, slot) => (src.kind === 'fsm' ? src : probe(src, slot, encoder, chain)),
        });
        assert.equal(replay.seedMode, SEED_MODE.RALLY);
        slotCount = (replay.p1External ? 1 : 0) + (replay.p2External ? 1 : 0);
        while (!runner.ended) runner.step();
        assert.equal(verifyAgainstReplay(runner, replay), null, `${file} 재생 불일치`);
        frames += runner.frame;
      }
      assert.equal(chain.pushes, frames * slotCount, `e${env.envIndex}: 관측 수`);
      assert.equal(frames, env.decisions, `e${env.envIndex}: 결정 수`);
      assert.equal(chain.hex(), env.chain, `e${env.envIndex}: 체인`);
    }
  });
}

// ─── 시드 유도 ──────────────────────────────────────────────────────────────

test('deriveSeed = Kotlin PikaEnv.deriveSeed (음수 base · 경계값 · 큰 env · k = 0)', () => {
  assert.ok(CONSTANTS.deriveSeed.length >= 200);
  for (const [b, e, k, expected] of CONSTANTS.deriveSeed) {
    assert.equal(deriveSeed(b, e, k), expected, `deriveSeed(${b}, ${e}, ${k})`);
  }
});

test('DerivedSeeds: 첫 랠리 번호부터 이어서 유도한다 (RALLY 규약)', () => {
  const s = new DerivedSeeds(-1, 15, 59);
  assert.equal(s.mode, SEED_MODE.RALLY);
  const at = (k) => CONSTANTS.deriveSeed.find(([b, e, r]) => b === -1 && e === 15 && r === k)[3];
  assert.equal(s.first(), at(59));
  assert.equal(new DerivedSeeds(-1, 15, 0).rallySeed(2), at(2));
  assert.equal(new DerivedSeeds(-1, 15, 1).rallySeed(1), at(2));
});
