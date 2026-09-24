/**
 * 평가 경기 재현 — Kotlin 평가(`evaluate_target`)가 치른 정책 vs FSM 경기를 **리플레이를 보지 않고** 다시 치러
 * 기록 바이트를 원본과 비교한다. (Phase 5 FR-10, M5-b, plan.md §8.2 · §8.3)
 *
 * Node 하네스(`test/policy-parity.mjs`) · 축소 픽스처 테스트 · 브라우저(P6) 가 같은 모듈을 쓴다 — 파일 I/O 는
 * 호출자가 `readReplay` 로 넘긴다.
 *
 * 한 게임을 다시 치르는 데 쓰는 것 (plan.md §2.7):
 *   baseSeed · envIndex · gameInEnv       묶음 이름 · manifest
 *   첫 서브 = gameInEnv 가 홀수            `PikaEnv.startGame` (`gameCounter % 2 == 1`)
 *   첫 랠리 번호 = 같은 env 앞 게임들의 랠리 수 합   `PikaEnv.rallyCounter` 는 게임을 넘어 증가한다
 *   정책 슬롯 = manifest 의 external 쪽 (envIndex ≥ 절반이면 p2)
 *
 * ⚠️ 앞 게임의 리플레이는 **랠리 수만** 읽는다. 입력 · 시드를 읽으면 재현이 아니라 재생이 된다.
 */
'use strict';

import { decodeReplay, inputAt } from '../runner/codec.mjs';
import { playLive } from '../runner/live-loop.mjs';
import { DEFAULT_CAP } from '../runner/recorder.mjs';
import { runnerFromReplay } from '../runner/runner.mjs';
import { DerivedSeeds } from '../runner/seeds.mjs';
import { FsmSource } from '../sources/fsm.mjs';
import { PolicySource } from '../sources/policy.mjs';
import { layoutHash } from './obs-spec.mjs';
import { ObsEncoder } from './obs.mjs';

/** `evaluate_target` 기본값 (plan.md §2.7). */
export const EVAL = Object.freeze({ maxRallyFrames: 3000, cap: DEFAULT_CAP, edgeTrigger: true });

/** 로짓 여유가 이보다 작으면 "수치 동률" — 2 × 측정한 백엔드 오차 상한 (PRD §4). */
export const TIE_MARGIN = 1.6e-5;

/** ONNX 메타의 레이아웃 해시로 인코더 구성을 고른다 — 구성을 코드에 박지 않는다. */
export function encoderFor(meta) {
  for (const includeLanding of [true, false]) {
    for (const includeSideFlag of [true, false]) {
      if (layoutHash({ includeLanding, includeSideFlag }) === meta.obsLayoutHash) {
        return () => new ObsEncoder({ includeLanding, includeSideFlag });
      }
    }
  }
  throw new Error(`ONNX 레이아웃 해시 ${meta.obsLayoutHash.slice(0, 16)} 에 맞는 관측 구성이 없습니다`);
}

/** manifest 한 줄 → 정책 슬롯 (0 = p1). 양쪽 다 external 이거나 둘 다 아니면 평가 경기가 아니다. */
export function policySlotOf(entry) {
  const ext = [entry.p1, entry.p2].map((p) => p.kind === 'external');
  if (ext[0] === ext[1]) throw new Error(`${entry.file}: 정책 vs FSM 경기가 아닙니다 (${entry.p1.kind} vs ${entry.p2.kind})`);
  return ext[0] ? 0 : 1;
}

/**
 * manifest 줄들 → env 별로 게임 순서대로 묶는다. `gameInEnv` 는 0 부터 빈틈이 없어야 한다 — 평가는 env 마다
 * 할당량만큼 연속으로 센다 (`_quotas`). 빈틈이 있으면 첫 랠리 번호를 누적할 수 없다.
 * @return {Array<{envIndex:number, games:Object[]}>}
 */
export function groupByEnv(entries) {
  const byEnv = new Map();
  for (const e of entries) {
    if (!byEnv.has(e.envIndex)) byEnv.set(e.envIndex, []);
    byEnv.get(e.envIndex).push(e);
  }
  return [...byEnv.entries()].sort(([a], [b]) => a - b).map(([envIndex, games]) => {
    games.sort((a, b) => a.gameInEnv - b.gameInEnv);
    games.forEach((g, k) => {
      if (g.gameInEnv !== k) throw new Error(`env ${envIndex}: gameInEnv 가 ${k} 가 아니라 ${g.gameInEnv} — 앞 게임이 빠졌습니다`);
    });
    return { envIndex, games };
  });
}

/**
 * 평가 경기 한 판을 새로 치른다.
 * @param {Object} o
 * @param {import('./model.mjs').PolicyModel} o.model
 * @param {function(): ObsEncoder} o.encoder
 * @param {number} o.baseSeed
 * @param {Object} o.entry manifest 한 줄
 * @param {number} o.startRally 이 게임의 첫 랠리 번호
 * @return {Promise<Uint8Array>}
 */
export async function reproduceGame({ model, encoder, baseSeed, entry, startRally }) {
  const policySlot = policySlotOf(entry);
  const pol = new PolicySource(model, encoder(), { edgeTrigger: EVAL.edgeTrigger });
  const { bytes } = await playLive({
    sources: policySlot === 0 ? [pol, new FsmSource()] : [new FsmSource(), pol],
    seeds: new DerivedSeeds(baseSeed, entry.envIndex, startRally),
    settings: {
      firstServeIsPlayer2: entry.gameInEnv % 2 === 1,
      maxRallyFrames: EVAL.maxRallyFrames,
      edgeTrigger: EVAL.edgeTrigger,
    },
    cap: EVAL.cap,
  });
  return bytes;
}

/**
 * 묶음 전체를 다시 치른다. env 순서 → 게임 순서. 게임마다 결과를 하나씩 낸다.
 *
 * @param {Object} o
 * @param {Object[]} o.entries manifest 줄들
 * @param {function(string): (Uint8Array|Promise<Uint8Array>)} o.readReplay 파일 이름 → 원본 바이트
 * @param {import('./model.mjs').PolicyModel} o.model
 * @param {number} o.baseSeed
 * @param {boolean} [o.diagnose=true] 불일치면 원인을 진단한다
 * @yields {{entry:Object, startRally:number, original:Uint8Array, bytes:Uint8Array, mismatch:(Object|null)}}
 */
export async function* reproduceSet({ entries, readReplay, model, baseSeed, diagnose = true }) {
  const encoder = encoderFor(model.meta);
  for (const { games } of groupByEnv(entries)) {
    let startRally = 0;
    for (const entry of games) {
      const original = await readReplay(entry.file);
      const bytes = await reproduceGame({ model, encoder, baseSeed, entry, startRally });
      let mismatch = equalBytes(bytes, original) ? null : compareReplays(original, bytes);
      if (mismatch && diagnose && mismatch.kind === 'input') {
        mismatch = { ...mismatch, ...(await diagnoseInput({ model, encoder, original, mismatch })) };
      }
      yield { entry, startRally, original, bytes, mismatch };

      // §2.7 — 첫 랠리 번호만 얻는다. 잘린 게임은 Kotlin env 가 기록 밖에서 랠리를 더 쳤으므로 셀 수 없다.
      const r = decodeReplay(original);
      if (!r.ended) throw new Error(`${entry.file}: 잘린 게임 (frameCount ${r.frameCount}) 뒤로는 첫 랠리 번호를 알 수 없습니다`);
      startRally += r.rallyFrames.length;
    }
  }
}

export function equalBytes(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

const HEADER_FIELDS = ['p1External', 'p2External', 'firstServeIsPlayer2', 'edgeTrigger', 'seedMode', 'winningScore', 'maxRallyFrames'];

/**
 * 두 리플레이의 **첫** 차이를 원인 쪽에서 가까운 순서로 찾는다 (plan.md §8.3):
 *   header → seeds(공통 접두부) → input(정책 슬롯, 공통 접두부) → result
 *
 * 시드는 랠리 번호만의 함수라 경기가 갈라져도 **공통 접두부는 같아야 한다.** 거기서 다르면 입력을 볼 것도 없이
 * 시드 유도 · 첫 랠리 번호 쪽이다.
 *
 * @return {{kind:string, detail:string, frame?:number, original?:number, reproduced?:number}}
 */
export function compareReplays(originalBytes, reproducedBytes) {
  const a = decodeReplay(originalBytes);
  const b = decodeReplay(reproducedBytes);
  for (const k of HEADER_FIELDS) {
    if (a[k] !== b[k]) return { kind: 'header', detail: `헤더 ${k}: 원본 ${a[k]} ≠ 재현 ${b[k]} — 경기 구성(첫 서브 · 슬롯 · 설정) 쪽` };
  }
  if (a.fixedBoldness.join() !== b.fixedBoldness.join()) {
    return { kind: 'header', detail: `헤더 fixedBoldness: 원본 ${a.fixedBoldness} ≠ 재현 ${b.fixedBoldness}` };
  }
  const ns = Math.min(a.seeds.length, b.seeds.length);
  for (let k = 0; k < ns; k++) {
    if (a.seeds[k] !== b.seeds[k]) {
      return { kind: 'seeds', rally: k, detail: `랠리 ${k} 시드: 원본 ${a.seeds[k]} ≠ 재현 ${b.seeds[k]} — 시드 유도 · 첫 랠리 번호 쪽 (입력 이전)` };
    }
  }
  // 정책 슬롯은 헤더가 같으니 양쪽에서 External 순서 0 이다 (정책 vs FSM).
  const nf = Math.min(a.frameCount, b.frameCount);
  for (let f = 0; f < nf; f++) {
    const x = inputAt(a, 0, f), y = inputAt(b, 0, f);
    if (x !== y) {
      return { kind: 'input', frame: f, original: x, reproduced: y, detail: `프레임 ${f} 정책 입력: 원본 ${x} ≠ 재현 ${y}` };
    }
  }
  return {
    kind: 'result',
    detail: `입력은 공통 ${nf} 프레임 동안 같은데 결과가 다릅니다 — 원본 (${a.frameCount} 프레임, ${a.finalScore}, ended ${a.ended}) · ` +
      `재현 (${b.frameCount} 프레임, ${b.finalScore}, ended ${b.ended}) — 규칙층 · FSM 쪽`,
  };
}

/**
 * 첫 입력 불일치 프레임을 판정한다 (plan.md §8.3). 원본 리플레이를 그 프레임까지 재생해 **결정 관측**을 얻고,
 * 그 관측에 대한 로짓으로 원본 행동과 재현 행동을 견준다. 그 프레임까지 입력 · 시드가 같으므로 관측도 같다.
 *
 * 원본 바이트는 엣지 변환 **후** 값이다 — powerHit 0 은 "안 누름" 일 수도 "누른 채 억제됨" 일 수도 있다.
 * 그래서 원본의 원시 행동은 후보 집합이다: powerHit 1 → {a}, 0 → {a, a+1}.
 *
 *   top1 ∈ 후보                          → bug:edge  원시 행동은 원본과 양립 — 엣지 변환(리셋 시점 · 누른 상태) 쪽
 *   top2 ∈ 후보 ∧ 여유 < TIE_MARGIN       → tie       수치 동률 (PRD §4: 멈추고 보고)
 *   그 밖                                → bug       관측 · 결정 시점 쪽
 */
export async function diagnoseInput({ model, encoder, original, mismatch }) {
  const f = mismatch.frame;
  const enc = encoder();
  const obs = new Float32Array(enc.dim);
  let slot = -1;
  const { runner } = runnerFromReplay(original, {
    wrap: (src, s) => {
      if (src.kind === 'fsm') return src;
      slot = s;
      const decide = src.decide.bind(src);
      src.decide = (r, isPlayer2, out) => {
        if (r.frame === f) enc.encode(r, s, obs); // decide 는 beginFrame 뒤 — 결정 시점 상태
        decide(r, isPlayer2, out);
      };
      return src;
    },
  });
  while (runner.frame <= f) runner.step();
  const logits = Array.from(await model.logits(obs));
  const order = logits.map((v, i) => i).sort((i, j) => logits[j] - logits[i] || i - j);
  const [top1, top2] = order;
  const margin = logits[top1] - logits[top2];
  const candidates = mismatch.original % 2 === 1 ? [mismatch.original] : [mismatch.original, mismatch.original + 1];
  const bestCandidate = candidates.reduce((p, q) => (logits[q] > logits[p] ? q : p));
  let verdict;
  if (candidates.includes(top1)) verdict = 'bug:edge';
  else if (candidates.includes(top2) && margin < TIE_MARGIN) verdict = 'tie';
  else verdict = 'bug';
  return {
    slot,
    obs: Array.from(obs),
    logits,
    top1, top2, margin,
    candidates,
    candidateGap: logits[top1] - logits[bestCandidate],
    verdict,
  };
}

/** 진단 결과를 사람이 읽을 줄들로. */
export function formatMismatch(entry, m) {
  const lines = [`✗ ${entry.file} (env ${entry.envIndex}, game ${entry.gameInEnv}) — ${m.kind}: ${m.detail}`];
  if (m.verdict) {
    const verdictText = {
      tie: `수치 동률 — 여유 ${m.margin.toExponential(2)} < ${TIE_MARGIN} (PRD §4: 멈추고 보고)`,
      'bug:edge': '버그 — argmax 는 원본과 양립한다. 파워히트 엣지 변환(리셋 시점 · 누른 상태) 쪽',
      bug: `버그 — 원본 행동이 top1 · top2 가 아니거나 여유가 크다. 관측 · 결정 시점 쪽`,
    }[m.verdict];
    lines.push(
      `    슬롯 p${m.slot + 1} · 원본 후보 {${m.candidates}} · JS top1 ${m.top1} top2 ${m.top2} · 여유 ${m.margin.toExponential(3)} · ` +
      `top1 − 최선 후보 ${m.candidateGap.toExponential(3)}`,
      `    판정: ${verdictText}`,
    );
  }
  return lines;
}
