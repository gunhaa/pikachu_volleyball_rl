/**
 * 정책 입력원 — 관측 → ONNX → argmax → EdgeTrigger → 엔진 입력. (Phase 5 FR-8, plan.md §7.2)
 *
 * Kotlin 평가에서 정책이 입력을 넣는 자리와 **같은 자리**에서 넣는다:
 *   결정 관측 = 랠리 리셋 **후** 상태 (`GameRunner.beginFrame()` 뒤) — `PikaEnv` 의 autoreset 스텝이 내보낸 관측
 *   엣지 리셋 = 게임 시작 · 랠리 시작 (`onRallyStart`) — `PikaEnv.beginRally`
 *
 * ORT 는 비동기뿐이라 결정이 두 단계다. `prepare`(비동기: 관측 · 추론) → `decide`(동기: 러너 step() 안).
 * 이 순서는 `live-loop.mjs` 의 advance() 가 지킨다. prepare 없이 decide 가 불리면 **예외** — 지난 프레임의
 * 행동을 조용히 다시 쓰는 것보다 낫다. 그래서 정책이 꽂힌 경기는 동기 step() 루프(seek 포함)로 돌 수 없다.
 *
 * ⚠️ JS 전역 `rand()` 를 부르지 않는다 (NFR-3). argmax 만 — 샘플링은 범위 밖이다.
 * ⚠️ 두 슬롯이 같은 모델을 쓰면 모델(세션)은 공유해도 된다. 이 객체(엣지 · 버퍼)는 슬롯마다 따로 만든다.
 */
'use strict';

import { EdgeTrigger, decodePolicyAction } from '../runner/action.mjs';
import { KIND } from './interface.mjs';

export class PolicySource {
  /**
   * @param {import('../policy/model.mjs').PolicyModel} model
   * @param {import('../policy/obs.mjs').ObsEncoder} encoder
   * @param {Object} [opts]
   * @param {boolean} [opts.edgeTrigger=true] Kotlin `EnvConfig.edgeTriggerPowerHit` (기본 true)
   */
  constructor(model, encoder, { edgeTrigger = true } = {}) {
    // ⚠️ 레이아웃이 다른 관측을 넣어도 ORT 는 길이만 맞으면 돌아간다 — 틀린 정책이 조용히 뛴다. 여기서 막는다.
    if (encoder.dim !== model.meta.obsDim) {
      throw new Error(`관측 차원: 인코더 ${encoder.dim} ≠ ONNX obs_dim ${model.meta.obsDim}`);
    }
    if (encoder.layoutHash !== model.meta.obsLayoutHash) {
      throw new Error(`관측 레이아웃 해시: 인코더 ${encoder.layoutHash.slice(0, 16)} ≠ ONNX ${model.meta.obsLayoutHash.slice(0, 16)}`);
    }
    this.kind = KIND.EXTERNAL;
    this.model = model;
    this.encoder = encoder;
    this.edge = edgeTrigger ? new EdgeTrigger() : null;
    this.obs = new Float32Array(encoder.dim);
    this.pending = -1;
    this.preparedAt = -1;
  }

  /** 결정 시점의 관측으로 행동을 정해 둔다. `runner.beginFrame()` 뒤에 부른다. */
  async prepare(runner, isPlayer2) {
    if (runner.pendingRallyStart) throw new Error('랠리 리셋 전 관측입니다 — beginFrame() 뒤에 prepare 하세요');
    if (runner.settings.edgeTrigger !== (this.edge !== null)) {
      // 헤더의 edgeTrigger 는 "이 경기의 정책 입력이 엣지 변환을 거쳤는가" 의 기록이다. 어긋나면 기록이 거짓말을 한다.
      throw new Error(`러너 edgeTrigger(${runner.settings.edgeTrigger}) ≠ 정책 입력원(${this.edge !== null})`);
    }
    const frame = runner.frame;
    this.encoder.encode(runner, isPlayer2 ? 1 : 0, this.obs);
    this.pending = await this.model.argmax(this.obs);
    this.preparedAt = frame;
  }

  decide(runner, isPlayer2, out) {
    if (this.preparedAt !== runner.frame) throw new Error('prepare 없이 decide — 비동기 루프(live-loop.mjs)를 쓰세요');
    this.preparedAt = -1; // 한 번만 쓴다
    decodePolicyAction(this.pending, out, this.edge);
  }

  reset() {
    this.preparedAt = -1;
  }

  /** 러너가 게임 시작(reset) · 랠리 시작(startNextRally) 에서 부른다 — `PikaEnv.beginRally` 와 같은 자리. */
  onRallyStart() {
    this.edge?.reset();
  }
}
