/**
 * 사후 검증 — 기록된 경기의 정책 슬롯 입력이 정말 그 정책의 수인지 ONNX 재추론으로 본다. (Phase 5 FR-13, plan.md §9.2)
 *
 * 서버는 참가자 주장(`policy:<onnx sha>`)을 믿고 받는다. 이것이 그 주장을 사후에 확인하는 수단이다.
 *
 * 방법: 리플레이를 재생하되 정책 슬롯에 [VerifyingSource] 를 꽂는다. 기록 바이트(`ReplaySource`)가 경기를 진행하고,
 * 같은 결정 시점에 `PolicySource` 가 "정책이라면 넣었을 엔진 입력" 을 계산해 기록과 견준다. 관측 · 엣지 규칙은
 * 라이브 경로와 **같은 코드**다 — 검증기가 따로 규칙을 가질 수 없다.
 */
'use strict';

import { encodeAction } from '../runner/action.mjs';
import { decodeReplay } from '../runner/codec.mjs';
import { advance } from '../runner/live-loop.mjs';
import { runnerFromReplay } from '../runner/runner.mjs';
import { PolicySource } from '../sources/policy.mjs';
import { encoderFor } from './parity.mjs';

class VerifyingSource {
  constructor(recorded, policy, slot) {
    this.kind = recorded.kind;
    this.recorded = recorded;
    this.policy = policy;
    this.slot = slot;
    this.expected = { xDirection: 0, yDirection: 0, powerHit: 0 };
    this.frames = 0;
    this.matched = 0;
    this.firstMismatch = null;
  }

  prepare(runner, isPlayer2) {
    return this.policy.prepare(runner, isPlayer2);
  }

  decide(runner, isPlayer2, out) {
    this.policy.decide(runner, isPlayer2, this.expected);
    this.recorded.decide(runner, isPlayer2, out); // 경기는 기록대로 진행한다
    const want = encodeAction(this.expected.xDirection, this.expected.yDirection, this.expected.powerHit === 1);
    const got = encodeAction(out.xDirection, out.yDirection, out.powerHit === 1);
    this.frames++;
    if (want === got) this.matched++;
    else if (this.firstMismatch === null) this.firstMismatch = { frame: runner.frame, recorded: got, policy: want };
  }

  reset(runner) {
    this.policy.reset(runner);
  }

  onRallyStart(runner) {
    this.policy.onRallyStart(runner);
  }
}

/**
 * @param {Uint8Array} bytes 리플레이
 * @param {Array<import('./model.mjs').PolicyModel|null>} models [p1, p2] — 정책이라고 주장된 슬롯만. 나머지는 null
 * @return {Promise<{slots: Array<{slot:number, frames:number, matched:number, firstMismatch:(Object|null)}>, replay:Object}>}
 */
export async function verifyPolicyReplay(bytes, models) {
  const { edgeTrigger } = decodeReplay(bytes); // wrap 이 runnerFromReplay 안에서 불리므로 먼저 읽는다
  const verifiers = [];
  const { runner, replay } = runnerFromReplay(bytes, {
    wrap: (src, slot) => {
      const model = models[slot];
      if (!model) return src;
      if (src.kind === 'fsm') throw new Error(`p${slot + 1} 은 FSM 슬롯입니다 — 정책일 수 없습니다`);
      const policy = new PolicySource(model, encoderFor(model.meta)(), { edgeTrigger });
      const v = new VerifyingSource(src, policy, slot);
      verifiers.push(v);
      return v;
    },
  });
  if (verifiers.length === 0) throw new Error('정책 슬롯이 없습니다');
  while (!runner.ended) await advance(runner);
  return {
    replay,
    slots: verifiers.map(({ slot, frames, matched, firstMismatch }) => ({ slot, frames, matched, firstMismatch })),
  };
}
