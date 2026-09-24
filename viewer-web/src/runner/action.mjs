/**
 * 행동 코드 0..17 = (x 3) × (y 3) × (powerHit 2). Kotlin `ActionCodec` 과 같은 배치.
 *
 * 리플레이의 입력 바이트는 **엣지 변환 후** 의 엔진 입력이다 (FR-4). 그래서 여기에는
 * 엣지 트리거가 없다 — 재생은 바이트를 그대로 푼다.
 */
'use strict';

export function encodeAction(xDirection, yDirection, powerHit) {
  return (xDirection + 1) * 6 + (yDirection + 1) * 2 + (powerHit ? 1 : 0);
}

/** @param {number} action @param {{xDirection:number, yDirection:number, powerHit:number}} out */
export function decodeAction(action, out) {
  out.xDirection = Math.floor(action / 6) - 1;
  out.yDirection = Math.floor((action % 6) / 2) - 1;
  out.powerHit = action % 2;
}
