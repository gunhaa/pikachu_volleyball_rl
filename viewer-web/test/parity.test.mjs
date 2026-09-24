/**
 * Phase 5 P5 — 평가 경기 재현 축소판 (M5-b 의 npm test 몫). 전수 2,400 게임은 `policy-parity.mjs` (runs/ 필요).
 *
 * 픽스처 `fixtures/policy/track-a-seed0/` = `runs/baselines/track-a-seed0/` 의 4 게임 그대로:
 *   e000-g0000 · e000-g0001   정책 = p1 (미러 없음), g1 은 첫 서브 p2 · 첫 랠리 번호 > 0
 *   e032-g0000 · e032-g0001   정책 = p2 (미러 + 진영 플래그 +1)
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decodeReplay, encodeReplay, inputAt } from '../src/runner/codec.mjs';
import { PolicyModel } from '../src/policy/model.mjs';
import { compareReplays, diagnoseInput, encoderFor, groupByEnv, reproduceSet } from '../src/policy/parity.mjs';

const DIR = join(import.meta.dirname, 'fixtures', 'policy');
const SET = join(DIR, 'track-a-seed0');
const model = await PolicyModel.load(new Uint8Array(readFileSync(join(DIR, 'track-a-seed0.onnx'))));
const entries = readFileSync(join(SET, 'manifest.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const read = (file) => new Uint8Array(readFileSync(join(SET, file)));

test('M5-b 축소판: 평가 경기 4개를 새로 치르면 원본과 바이트 일치 (양 진영 · 첫 랠리 번호 누적)', async () => {
  assert.equal(entries[0].p1.checkpoint, `sha256:${model.meta.checkpointSha256}`);
  const seen = [];
  for await (const g of reproduceSet({ entries, readReplay: read, model, baseSeed: 0 })) {
    assert.equal(g.mismatch, null, `${g.entry.file}: ${g.mismatch?.detail}`);
    seen.push([g.entry.file, g.startRally]);
  }
  const g0Rallies = decodeReplay(read('e000-g0000.pkr')).rallyFrames.length;
  assert.deepEqual(seen, [
    ['e000-g0000.pkr', 0], ['e000-g0001.pkr', g0Rallies],
    ['e032-g0000.pkr', 0], ['e032-g0001.pkr', decodeReplay(read('e032-g0000.pkr')).rallyFrames.length],
  ]);
});

test('groupByEnv: 앞 게임이 빠지면 첫 랠리 번호를 셀 수 없으므로 실패', () => {
  assert.throws(() => groupByEnv(entries.filter((e) => e.file !== 'e032-g0000.pkr')), /앞 게임이 빠졌습니다/);
});

// ─────────────────────────────────────────────────────────────────────────────
// 불일치 판정 (plan.md §8.3) — 원본 바이트를 변조해 판정기가 원인 쪽을 가리키는지
// ─────────────────────────────────────────────────────────────────────────────

const ORIGINAL = read('e032-g0001.pkr');
const mutate = (fn) => { const r = decodeReplay(ORIGINAL); fn(r); return encodeReplay(r); };

test('판정 순서: 헤더 → 시드 → 입력 → 결과', () => {
  assert.equal(compareReplays(ORIGINAL, mutate((r) => { r.firstServeIsPlayer2 = !r.firstServeIsPlayer2; })).kind, 'header');
  const seeds = compareReplays(ORIGINAL, mutate((r) => { r.seeds[3] ^= 1; r.inputs[5] ^= 2; }));
  assert.equal(seeds.kind, 'seeds', '시드가 입력보다 먼저');
  assert.equal(seeds.rally, 3);
  const input = compareReplays(ORIGINAL, mutate((r) => { r.inputs[5] ^= 2; }));
  assert.deepEqual([input.kind, input.frame], ['input', 5]);
  assert.equal(compareReplays(ORIGINAL, mutate((r) => { r.finalScore[0] ^= 1; })).kind, 'result');
});

/** 원본 입력을 변조한 것을 "원본" 으로 두고, 진짜 원본을 "재현" 으로 두어 진단한다. */
async function diagnoseMutated(pick, change) {
  const r = decodeReplay(ORIGINAL);
  let f = 0;
  while (!pick(inputAt(r, 0, f))) f++;
  const fake = mutate((m) => { m.inputs[f] = change(m.inputs[f]); });
  const mismatch = compareReplays(fake, ORIGINAL);
  assert.deepEqual([mismatch.kind, mismatch.frame], ['input', f]);
  return diagnoseInput({ model, encoder: encoderFor(model.meta), original: fake, mismatch });
}

test('판정 bug:edge — 원본 파워히트 1 을 0 으로 (argmax 는 원본과 양립)', async () => {
  const d = await diagnoseMutated((a) => a % 2 === 1, (a) => a - 1);
  assert.equal(d.verdict, 'bug:edge');
  assert.equal(d.slot, 1);
  assert.equal(d.candidateGap, 0);
});

test('판정 bug — 원본 방향을 바꾸면 원본 행동이 top1 이 아니다 (관측 · 결정 시점 쪽)', async () => {
  const d = await diagnoseMutated((a) => a % 2 === 0 && a < 12, (a) => a + 6);
  assert.equal(d.verdict, 'bug');
  assert.ok(d.candidateGap > 0);
  assert.equal(d.obs.length, 41);
  assert.equal(d.obs[40], 1, '진영 플래그 = 오른쪽 (+1, 미러되지 않는다)');
});
