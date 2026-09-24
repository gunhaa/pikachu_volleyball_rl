/**
 * 프레임별 상태 체인 해시 — Kotlin `analysis/ReplayChain.kt` 와 같은 규약. (M4-b)
 *
 *   h_0 = 0x00 × 32,  h_n = SHA256(h_{n-1} ‖ state_n)
 *   state = State Spec 기본 44필드, little-endian int32 (sound 제외)
 *
 * 필드 순서의 진실 공급원은 `proto/state_spec.proto` 다. `tools/js-oracle/spec.mjs` 는
 * `node:fs` · `node:crypto` 를 써서 브라우저에 실을 수 없으므로 목록을 여기 다시 적고,
 * `test/chain.test.mjs` 가 spec.mjs 와 이름 · 값이 같은지 확인한다.
 */
'use strict';

import { sha256, toHex } from './sha256.mjs';

const b = (v) => (v ? 1 : 0);

const BALL = [
  ['x', (o) => o.x], ['y', (o) => o.y], ['x_velocity', (o) => o.xVelocity], ['y_velocity', (o) => o.yVelocity],
  ['fine_rotation', (o) => o.fineRotation], ['rotation', (o) => o.rotation],
  ['punch_effect_radius', (o) => o.punchEffectRadius], ['punch_effect_x', (o) => o.punchEffectX],
  ['punch_effect_y', (o) => o.punchEffectY], ['is_power_hit', (o) => b(o.isPowerHit)],
  ['expected_landing_point_x', (o) => o.expectedLandingPointX], ['previous_x', (o) => o.previousX],
  ['previous_previous_x', (o) => o.previousPreviousX], ['previous_y', (o) => o.previousY],
  ['previous_previous_y', (o) => o.previousPreviousY],
];

const PLAYER = [
  ['x', (o) => o.x], ['y', (o) => o.y], ['y_velocity', (o) => o.yVelocity], ['state', (o) => o.state],
  ['frame_number', (o) => o.frameNumber], ['diving_direction', (o) => o.divingDirection],
  ['lying_down_duration_left', (o) => o.lyingDownDurationLeft],
  ['is_collision_with_ball_happened', (o) => b(o.isCollisionWithBallHappened)],
  ['normal_status_arm_swing_direction', (o) => o.normalStatusArmSwingDirection],
  ['delay_before_next_frame', (o) => o.delayBeforeNextFrame], ['computer_boldness', (o) => o.computerBoldness],
  ['computer_where_to_stand_by', (o) => o.computerWhereToStandBy], ['is_winner', (o) => b(o.isWinner)],
  ['game_ended', (o) => b(o.gameEnded)],
];

export const FIELD_NAMES = [
  ...BALL.map(([n]) => `ball.${n}`),
  ...PLAYER.map(([n]) => `player1.${n}`),
  ...PLAYER.map(([n]) => `player2.${n}`),
  'is_ball_touching_ground',
];

export const INT_COUNT = FIELD_NAMES.length; // 44

/** 상태를 [out] 에 채운다. 순서는 [FIELD_NAMES]. */
export function writeState(physics, isBallTouchingGround, out) {
  let i = 0;
  for (const [, get] of BALL) out[i++] = get(physics.ball);
  for (const [, get] of PLAYER) out[i++] = get(physics.player1);
  for (const [, get] of PLAYER) out[i++] = get(physics.player2);
  out[i++] = b(isBallTouchingGround);
  return out;
}

export class Chain {
  constructor() {
    this.ints = new Int32Array(INT_COUNT);
    // [h_{n-1} (32) | state (176)] 한 버퍼 — 프레임마다 할당하지 않는다.
    this.buf = new Uint8Array(32 + INT_COUNT * 4);
    this.view = new DataView(this.buf.buffer);
    this.hash = new Uint8Array(32);
    this.frames = 0;
  }

  step(physics, isBallTouchingGround) {
    writeState(physics, isBallTouchingGround, this.ints);
    this.buf.set(this.hash, 0);
    for (let i = 0; i < INT_COUNT; i++) this.view.setInt32(32 + i * 4, this.ints[i], true);
    sha256(this.buf, this.hash);
    this.frames++;
  }

  hex() {
    return toHex(this.hash);
  }
}
