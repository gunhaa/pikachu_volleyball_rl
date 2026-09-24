/**
 * Phase 5 P6 — 사후 검증 `verifyPolicyReplay` (FR-13). 서버가 믿고 받은 "이 슬롯은 정책" 주장을 ONNX 재추론으로 확인한다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decodeReplay, encodeReplay } from '../src/runner/codec.mjs';
import { playLive } from '../src/runner/live-loop.mjs';
import { DerivedSeeds } from '../src/runner/seeds.mjs';
import { PolicyModel } from '../src/policy/model.mjs';
import { encoderFor } from '../src/policy/parity.mjs';
import { verifyPolicyReplay } from '../src/policy/verify.mjs';
import { FsmSource } from '../src/sources/fsm.mjs';
import { PolicySource } from '../src/sources/policy.mjs';
import { ScriptedSource, hashedActions } from '../src/sources/scripted.mjs';

const DIR = join(import.meta.dirname, 'fixtures', 'policy');
const model = await PolicyModel.load(new Uint8Array(readFileSync(join(DIR, 'track-a-seed0.onnx'))));
const read = (file) => new Uint8Array(readFileSync(join(DIR, 'track-a-seed0', file)));

test('평가 리플레이의 정책 슬롯 = ONNX 재추론 100% (양 진영)', async () => {
  for (const [file, slot] of [['e000-g0001.pkr', 0], ['e032-g0000.pkr', 1]]) {
    const models = slot === 0 ? [model, null] : [null, model];
    const { slots, replay } = await verifyPolicyReplay(read(file), models);
    assert.equal(slots.length, 1);
    assert.equal(slots[0].slot, slot);
    assert.equal(slots[0].frames, replay.frameCount);
    assert.equal(slots[0].matched, replay.frameCount, file);
    assert.equal(slots[0].firstMismatch, null);
  }
});

test('입력 한 바이트를 바꾸면 그 프레임에서 잡힌다 (정책이 두지 않은 수)', async () => {
  const r = decodeReplay(read('e000-g0000.pkr'));
  const f = 700;
  r.inputs[f] = (r.inputs[f] + 6) % 18; // x 방향을 바꾼다
  // 재생은 기록 바이트대로 진행한다 (결과 대조는 서버의 재생 검증 몫) — 첫 불일치는 정확히 f.
  const { slots } = await verifyPolicyReplay(encodeReplay(r), [model, null]);
  assert.equal(slots[0].firstMismatch.frame, f);
  assert.equal(slots[0].firstMismatch.recorded, r.inputs[f]);
  assert.ok(slots[0].matched < slots[0].frames);
});

test('정책 vs 정책 라이브 경기 → 두 슬롯 모두 100%', async () => {
  const make = () => new PolicySource(model, encoderFor(model.meta)());
  const { bytes, replay } = await playLive({
    sources: [make(), make()],
    seeds: new DerivedSeeds(7, 3, 0),
    settings: { maxRallyFrames: 3000, edgeTrigger: true },
    cap: 4000,
  });
  const { slots } = await verifyPolicyReplay(bytes, [model, model]);
  assert.deepEqual(slots.map((s) => [s.slot, s.matched, s.frames]), [[0, replay.frameCount, replay.frameCount], [1, replay.frameCount, replay.frameCount]]);
});

test('사람 경기를 정책이라고 주장하면 불일치가 난다', async () => {
  // 정책 슬롯이 아닌 쪽(p2 FSM)에 정책을 주장하면 거절, 정책 없는 주장도 거절
  await assert.rejects(verifyPolicyReplay(read('e000-g0000.pkr'), [null, model]), /FSM 슬롯/);
  await assert.rejects(verifyPolicyReplay(read('e000-g0000.pkr'), [null, null]), /정책 슬롯이 없습니다/);
  // 사람 흉내: 정책이 아닌 입력원이 실제로 치른(= 서버 재생 검증을 통과할) 경기를 정책 경기라고 주장한다
  const { bytes } = await playLive({
    sources: [new ScriptedSource(hashedActions(9, { powerHitPercent: 30 })), new FsmSource()],
    seeds: new DerivedSeeds(0, 0, 0),
    settings: { maxRallyFrames: 3000, edgeTrigger: true },
    cap: 3000,
  });
  const { slots } = await verifyPolicyReplay(bytes, [model, null]);
  assert.notEqual(slots[0].firstMismatch, null);
  assert.ok(slots[0].matched < slots[0].frames * 0.5, `${slots[0].matched}/${slots[0].frames}`);
});
