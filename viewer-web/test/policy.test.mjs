/**
 * Phase 5 P4 — 정책 입력원 · 비동기 러너 배선. (FR-8, FR-9, NFR-3, NFR-7)
 *
 * 픽스처 `fixtures/policy/track-a-seed0.onnx` 는 `runs/policies/` 의 seed0 export 그대로다 (SHA 는 레지스트리 값).
 * 평가 경기와의 바이트 대조(M5-b)는 P5 의 몫이고, 여기서는 배선이 결정 시점 · 엣지 규칙 · RNG 격리를
 * 지키는지만 본다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Cloud, Wave, cloudAndWaveEngine } from '../../upstream/src/resources/js/cloud_and_wave.js';
import { rand, setCustomRng } from '../../upstream/src/resources/js/rand.js';
import { xorshift32, toCustomRng } from '../../tools/js-oracle/xorshift32.mjs';
import { EdgeTrigger, decodePolicyAction } from '../src/runner/action.mjs';
import { Chain } from '../src/runner/chain.mjs';
import { inputAt } from '../src/runner/codec.mjs';
import { advance, playLive } from '../src/runner/live-loop.mjs';
import { GameRunner, runnerFromReplay, verifyAgainstReplay } from '../src/runner/runner.mjs';
import { DerivedSeeds } from '../src/runner/seeds.mjs';
import { PolicyModel, argmax, readOnnxMetadata } from '../src/policy/model.mjs';
import { ObsEncoder } from '../src/policy/obs.mjs';
import { layoutHash } from '../src/policy/obs-spec.mjs';
import { FsmSource } from '../src/sources/fsm.mjs';
import { PolicySource } from '../src/sources/policy.mjs';

const ONNX = join(import.meta.dirname, 'fixtures', 'policy', 'track-a-seed0.onnx');
const BYTES = new Uint8Array(readFileSync(ONNX));
const SEED0 = {
  onnxSha256: '3d087c0b2201da764ee97b87e70e0868493e6acc62f5c0ce04d5d13f7fa6625a',
  checkpointSha256: '50c77b87dbf34d0de2d51d6103af11b036db0a7d22a7e7052e74a87493024f89',
};
/** Track A 의 관측 구성 = `ObsLayout.for_policy()` (착지점 · 진영 플래그 on). */
const encoder = () => new ObsEncoder({ includeLanding: true, includeSideFlag: true });
const model = await PolicyModel.load(BYTES);

/** 정책 vs FSM 한 판 (평가 경기와 같은 설정, plan.md §2.7). */
function policyGame({ policySlot = 1, envIndex = 40, gameInEnv = 1, cap = 60000, onFrame, afterFrame, wrap = (s) => s } = {}) {
  const pol = wrap(new PolicySource(model, encoder()));
  return playLive({
    sources: policySlot === 0 ? [pol, new FsmSource()] : [new FsmSource(), pol],
    seeds: new DerivedSeeds(0, envIndex, 0),
    settings: { firstServeIsPlayer2: gameInEnv % 2 === 1, maxRallyFrames: 3000, edgeTrigger: true },
    cap,
    onFrame,
    afterFrame,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 모델 · 메타데이터
// ─────────────────────────────────────────────────────────────────────────────

test('픽스처 ONNX = 레지스트리의 seed0 export', () => {
  assert.equal(createHash('sha256').update(BYTES).digest('hex'), SEED0.onnxSha256);
});

test('ONNX 메타데이터: pika.* 네 키, 레이아웃 해시 = JS layoutHash(착지점 · 진영 플래그)', () => {
  const props = readOnnxMetadata(BYTES);
  assert.deepEqual([...props.keys()].filter((k) => k.startsWith('pika.')).sort(),
    ['pika.action_count', 'pika.checkpoint_sha256', 'pika.obs_dim', 'pika.obs_layout_hash']);
  assert.deepEqual({ ...model.meta }, {
    checkpointSha256: SEED0.checkpointSha256,
    obsDim: 41,
    obsLayoutHash: layoutHash({ includeLanding: true, includeSideFlag: true }),
    actionCount: 18,
  });
});

test('잘린 ONNX 는 메타데이터 읽기에서 실패한다', () => {
  assert.throws(() => readOnnxMetadata(BYTES.subarray(0, BYTES.length - 7)), /잘렸습니다/);
});

test('argmax: 동률이면 첫 인덱스 (torch 규칙, > 로 훑는다)', () => {
  assert.equal(argmax([1, 3, 3, 2]), 1);
  assert.equal(argmax([5, 5, 5]), 0);
  assert.equal(argmax(new Float32Array([-2, -1, -1.5])), 1);
});

test('추론: 같은 관측 → 같은 로짓 비트 (세션 재사용 · 새 세션)', async () => {
  const obs = new Float32Array(41).map((_, i) => Math.fround(Math.sin(i) * 0.7));
  const a = new Uint32Array((await model.logits(obs)).slice().buffer);
  const b = new Uint32Array((await model.logits(obs)).slice().buffer);
  const other = await PolicyModel.load(BYTES);
  const c = new Uint32Array((await other.logits(obs)).slice().buffer);
  assert.equal(a.length, 18);
  assert.deepEqual(b, a);
  assert.deepEqual(c, a);
});

// ─────────────────────────────────────────────────────────────────────────────
// 행동 디코딩 · 엣지 트리거 (ActionCodec.decode 와 같은 규칙)
// ─────────────────────────────────────────────────────────────────────────────

test('decodePolicyAction: 누른 상태 → 엣지 변환, 리셋하면 다시 1', () => {
  const out = { xDirection: 0, yDirection: 0, powerHit: 0 };
  const edge = new EdgeTrigger();
  const held = [17, 17, 16, 1, 1, 1];      // 누름 · 누름 · 뗌 · 누름 · 누름 · (리셋 후) 누름
  const got = [];
  for (let k = 0; k < held.length; k++) {
    if (k === 5) edge.reset();
    decodePolicyAction(held[k], out, edge);
    got.push(out.powerHit);
  }
  assert.deepEqual(got, [1, 0, 0, 1, 0, 1]);
  decodePolicyAction(17, out, null);
  assert.deepEqual(out, { xDirection: 1, yDirection: 1, powerHit: 1 });
  decodePolicyAction(0, out, null);
  assert.deepEqual(out, { xDirection: -1, yDirection: -1, powerHit: 0 });
  assert.throws(() => decodePolicyAction(18, out, null), /0\.\.17/);
});

// ─────────────────────────────────────────────────────────────────────────────
// PolicySource 생성 · 호출 규약
// ─────────────────────────────────────────────────────────────────────────────

test('레이아웃이 ONNX 메타와 다르면 PolicySource 를 만들 수 없다', () => {
  assert.throws(() => new PolicySource(model, new ObsEncoder({ includeLanding: true, includeSideFlag: false })), /관측 차원/);
  // 차원은 같고 해시만 다른 경우 — 필드 순서가 바뀐 레이아웃을 흉내 낸다
  const fake = { meta: { ...model.meta, obsLayoutHash: '0'.repeat(64) } };
  assert.throws(() => new PolicySource(fake, encoder()), /레이아웃 해시/);
  assert.doesNotThrow(() => new PolicySource(model, encoder()));
});

test('정책이 꽂힌 경기를 동기 step() 으로 돌리면 예외 (prepare 없이 decide)', () => {
  const runner = new GameRunner({ sources: [new PolicySource(model, encoder()), new FsmSource()], seeds: new DerivedSeeds(0, 0, 0), settings: { edgeTrigger: true } });
  assert.throws(() => runner.step(), /prepare 없이 decide/);
});

test('prepare 는 한 번만 쓰인다 — 두 번째 step() 은 다시 예외', async () => {
  const runner = new GameRunner({ sources: [new PolicySource(model, encoder()), new FsmSource()], seeds: new DerivedSeeds(0, 0, 0), settings: { edgeTrigger: true } });
  await advance(runner);
  assert.equal(runner.frame, 1);
  assert.throws(() => runner.step(), /prepare 없이 decide/);
});

test('러너 edgeTrigger 설정과 정책 입력원이 어긋나면 예외', async () => {
  const runner = new GameRunner({ sources: [new FsmSource(), new PolicySource(model, encoder())], seeds: new DerivedSeeds(0, 0, 0), settings: { edgeTrigger: false } });
  await assert.rejects(advance(runner), /edgeTrigger/);
});

test('랠리 리셋 전 상태로 prepare 하면 예외 (결정 시점은 beginFrame 뒤)', async () => {
  const src = new PolicySource(model, encoder());
  const runner = { pendingRallyStart: true, settings: { edgeTrigger: true } };
  await assert.rejects(src.prepare(runner, false), /beginFrame/);
});

// ─────────────────────────────────────────────────────────────────────────────
// 경기
// ─────────────────────────────────────────────────────────────────────────────

for (const policySlot of [0, 1]) {
  test(`정책 vs FSM 한 판 (정책 = ${policySlot === 0 ? 'p1' : 'p2'}): 기록 → 재생 체인 = 라이브 체인, 파워히트는 엣지만`, async () => {
    const live = new Chain();
    const { bytes, replay } = await policyGame({ policySlot, onFrame: (r, t) => live.step(r.physics, t) });
    assert.ok(replay.ended, '15점 게임이 끝나야 한다');
    assert.equal(replay.edgeTrigger, true);
    assert.equal(policySlot === 0 ? replay.p1External : replay.p2External, true);

    const chain = new Chain();
    const { runner } = runnerFromReplay(bytes, { onFrame: (r, t) => chain.step(r.physics, t) });
    while (!runner.ended) runner.step();
    assert.equal(chain.hex(), live.hex());
    assert.equal(verifyAgainstReplay(runner, replay), null);

    // 엣지 트리거: 한 랠리 안에서 파워히트 1 이 두 프레임 연속으로 나오지 않는다. 그리고 적어도 한 번은 친다.
    let f = 0, hits = 0;
    for (const n of replay.rallyFrames) {
      let prev = 0;
      for (let k = 0; k < n; k++, f++) {
        const p = inputAt(replay, 0, f) % 2;
        assert.ok(!(prev === 1 && p === 1), `프레임 ${f}: 파워히트가 연속 1`);
        prev = p;
        hits += p;
      }
    }
    assert.ok(hits > 0, '정책이 파워히트를 한 번도 치지 않았다 — 엣지 검사가 아무것도 재지 않는다');
  });
}

test('같은 시드 · 같은 정책 → 같은 바이트 (정책 경기는 결정론)', async () => {
  const a = await policyGame({ cap: 2000 });
  const b = await policyGame({ cap: 2000 });
  assert.deepEqual(b.bytes, a.bytes);
});

/**
 * NFR-3 · M4-e 형식 — 정책 경기를 렌더링하며 치른 체인 = 렌더링 없이 치른 체인.
 * 렌더링은 프레임 사이와 **추론 await 한가운데** 두 곳에 끼운다. 브라우저에서는 ORT 가 양보하는 동안
 * 다른 작업(그리기)이 돌 수 있기 때문이다.
 */
async function renderedPolicyGame({ ownRng }) {
  const chain = new Chain();
  const viewRng = toCustomRng(xorshift32(0xc0ffee));
  if (ownRng) setCustomRng(viewRng);
  const clouds = Array.from({ length: 10 }, () => new Cloud());
  const wave = new Wave();
  const draw = () => {
    if (ownRng) setCustomRng(viewRng);
    cloudAndWaveEngine(clouds, wave);
    rand(); rand(); rand();
  };
  const wrap = (src) => {
    const prepare = src.prepare.bind(src);
    src.prepare = async (runner, isPlayer2) => { await prepare(runner, isPlayer2); draw(); };
    return src;
  };
  await policyGame({ cap: 3000, onFrame: (r, t) => chain.step(r.physics, t), afterFrame: draw, wrap });
  return chain.hex();
}

test('정책 경기 렌더링 격리: 뷰 RNG 를 끼워도 체인 동일 (프레임 사이 · 추론 await 중)', async () => {
  const plain = new Chain();
  await policyGame({ cap: 3000, onFrame: (r, t) => plain.step(r.physics, t) });
  assert.equal(await renderedPolicyGame({ ownRng: true }), plain.hex());
});

test('대조군: 자기 RNG 를 걸지 않는 렌더러는 정책 vs FSM 경기를 바꾼다', async () => {
  const plain = new Chain();
  await policyGame({ cap: 3000, onFrame: (r, t) => plain.step(r.physics, t) });
  assert.notEqual(await renderedPolicyGame({ ownRng: false }), plain.hex());
});
