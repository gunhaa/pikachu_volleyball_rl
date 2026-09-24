/**
 * 행동 코드 0..17 = (x 3) × (y 3) × (powerHit 2). Kotlin `ActionCodec` 과 같은 배치.
 *
 * 리플레이의 입력 바이트는 **엣지 변환 후** 의 엔진 입력이다 (FR-4). 그래서 [decodeAction] 에는
 * 엣지 트리거가 없다 — 재생은 바이트를 그대로 푼다.
 *
 * 정책의 행동은 다르다. 홀수 = 파워히트 키를 **누르고 있다** (`ActionCodec.powerHitHeld`) 이고, 엔진 입력은
 * 엣지 변환을 거친 값이다. 정책에는 [decodePolicyAction] 을 쓴다 (plan.md §7.3 ⚠️).
 */
'use strict';

export const ACTION_COUNT = 18;

export function encodeAction(xDirection, yDirection, powerHit) {
  return (xDirection + 1) * 6 + (yDirection + 1) * 2 + (powerHit ? 1 : 0);
}

/** @param {number} action @param {{xDirection:number, yDirection:number, powerHit:number}} out */
export function decodeAction(action, out) {
  out.xDirection = Math.floor(action / 6) - 1;
  out.yDirection = Math.floor((action % 6) / 2) - 1;
  out.powerHit = action % 2;
}

/**
 * `ActionCodec.decode(action, out, edge)` 의 JS 판.
 * @param {number} action 0..17
 * @param {{xDirection:number, yDirection:number, powerHit:number}} out
 * @param {EdgeTrigger|null} edge null 이면 누른 상태가 그대로 powerHit (`edgeTriggerPowerHit = false`)
 */
export function decodePolicyAction(action, out, edge) {
  if (!Number.isInteger(action) || action < 0 || action >= ACTION_COUNT) throw new Error(`행동은 0..17 이어야 합니다: ${action}`);
  out.xDirection = Math.floor(action / 6) - 1;
  out.yDirection = Math.floor((action % 6) / 2) - 1;
  const held = action % 2 === 1;
  out.powerHit = edge !== null ? edge.apply(held) : held ? 1 : 0;
}

/**
 * `keyboard.js:71-77` 과 같은 규칙 — 키가 **눌리는 순간**에만 1. Kotlin `EdgeTrigger` 의 JS 판.
 *
 * ⚠️ 랠리가 바뀌면 [reset] 한다 (`PikaEnv.beginRally`). 사람은 랠리 사이에 손을 떼지만 정책은 안 뗀다.
 */
export class EdgeTrigger {
  constructor() {
    this.wasHeld = false;
  }

  /** @param {boolean} held @return {number} 0 | 1 */
  apply(held) {
    const edge = !this.wasHeld && held;
    this.wasHeld = held;
    return edge ? 1 : 0;
  }

  reset() {
    this.wasHeld = false;
  }
}
