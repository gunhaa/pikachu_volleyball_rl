/**
 * State Spec — 상태 직렬화와 해시.
 *
 * 필드 순서의 단일 진실 공급원은 `proto/state_spec.proto` 다 (FR-5).
 * 이 모듈은 접근자 목록을 명시적으로 들고 있고, `assertMatchesProto()` 가
 * 그 목록이 .proto 와 일치하는지 검사한다. 어긋나면 즉시 실패한다.
 */
'use strict';

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
export const PROTO_PATH = join(HERE, '..', '..', 'proto', 'state_spec.proto');

const b = (v) => (v ? 1 : 0); // bool → 0/1 고정 인코딩

/** BallState — proto 의 필드 번호 순서와 같아야 한다. */
const BALL_FIELDS = [
  ['x', (o) => o.x],
  ['y', (o) => o.y],
  ['x_velocity', (o) => o.xVelocity],
  ['y_velocity', (o) => o.yVelocity],
  ['fine_rotation', (o) => o.fineRotation],
  ['rotation', (o) => o.rotation],
  ['punch_effect_radius', (o) => o.punchEffectRadius],
  ['punch_effect_x', (o) => o.punchEffectX],
  ['punch_effect_y', (o) => o.punchEffectY],
  ['is_power_hit', (o) => b(o.isPowerHit)],
  ['expected_landing_point_x', (o) => o.expectedLandingPointX],
  ['previous_x', (o) => o.previousX],
  ['previous_previous_x', (o) => o.previousPreviousX],
  ['previous_y', (o) => o.previousY],
  ['previous_previous_y', (o) => o.previousPreviousY],
];

/** PlayerState */
const PLAYER_FIELDS = [
  ['x', (o) => o.x],
  ['y', (o) => o.y],
  ['y_velocity', (o) => o.yVelocity],
  ['state', (o) => o.state],
  ['frame_number', (o) => o.frameNumber],
  ['diving_direction', (o) => o.divingDirection],
  ['lying_down_duration_left', (o) => o.lyingDownDurationLeft],
  ['is_collision_with_ball_happened', (o) => b(o.isCollisionWithBallHappened)],
  ['normal_status_arm_swing_direction', (o) => o.normalStatusArmSwingDirection],
  ['delay_before_next_frame', (o) => o.delayBeforeNextFrame],
  ['computer_boldness', (o) => o.computerBoldness],
  ['computer_where_to_stand_by', (o) => o.computerWhereToStandBy],
  ['is_winner', (o) => b(o.isWinner)],
  ['game_ended', (o) => b(o.gameEnded)],
];

const BALL_SOUND_FIELDS = [
  ['power_hit', (o) => b(o.sound.powerHit)],
  ['ball_touches_ground', (o) => b(o.sound.ballTouchesGround)],
];

const PLAYER_SOUND_FIELDS = [
  ['pipikachu', (o) => b(o.sound.pipikachu)],
  ['pika', (o) => b(o.sound.pika)],
  ['chu', (o) => b(o.sound.chu)],
];

const prefixed = (prefix, fields) =>
  fields.map(([name, get]) => [`${prefix}.${name}`, get]);

/** FrameState 를 평탄화한 순서. 직렬화는 이 순서를 그대로 따른다. */
export const BASE_FIELDS = [
  ...prefixed('ball', BALL_FIELDS),
  ...prefixed('player1', PLAYER_FIELDS),
  ...prefixed('player2', PLAYER_FIELDS),
  ['is_ball_touching_ground', null], // 엔진 반환값 — 인자로 따로 받는다
];

/** SoundSuffix 를 평탄화한 순서. 엄격 모드에서만 뒤에 붙는다. */
export const SOUND_FIELDS = [
  ...prefixed('ball', BALL_SOUND_FIELDS),
  ...prefixed('player1', PLAYER_SOUND_FIELDS),
  ...prefixed('player2', PLAYER_SOUND_FIELDS),
];

export const BASE_INT_COUNT = BASE_FIELDS.length; // 44
export const SOUND_INT_COUNT = SOUND_FIELDS.length; // 8

// ─────────────────────────────────────────────────────────────────────────────
// .proto 대조
// ─────────────────────────────────────────────────────────────────────────────

/** 아주 작은 .proto 파서. 이 파일의 형태(중첩 없음, 전부 int32)만 다룬다. */
export function parseProto(text) {
  const stripped = text.replace(/\/\/[^\n]*/g, '');
  const messages = new Map();
  const msgRe = /message\s+(\w+)\s*\{([^}]*)\}/g;
  let m;
  while ((m = msgRe.exec(stripped)) !== null) {
    const fields = [];
    const fieldRe = /(\w+)\s+(\w+)\s*=\s*(\d+)\s*;/g;
    let f;
    while ((f = fieldRe.exec(m[2])) !== null) {
      fields.push({ type: f[1], name: f[2], number: Number(f[3]) });
    }
    fields.sort((a, c) => a.number - c.number);
    messages.set(m[1], fields);
  }
  return messages;
}

/** 메시지를 leaf 경로 목록으로 펼친다. */
export function flattenProto(messages, root) {
  const out = [];
  const walk = (msgName, prefix) => {
    const fields = messages.get(msgName);
    if (!fields) throw new Error(`.proto 에 message ${msgName} 이 없습니다`);
    for (const f of fields) {
      const path = prefix ? `${prefix}.${f.name}` : f.name;
      if (messages.has(f.type)) walk(f.type, path);
      else if (f.type === 'int32') out.push(path);
      else throw new Error(`예상치 못한 타입 ${f.type} (${path}). State Spec 은 전부 int32 다.`);
    }
  };
  walk(root, '');
  return out;
}

/**
 * 이 모듈의 필드 목록이 .proto 와 일치하는지 확인한다.
 * @throws 불일치 시
 */
export function assertMatchesProto(protoText = readFileSync(PROTO_PATH, 'utf8')) {
  const messages = parseProto(protoText);
  const check = (root, actual) => {
    const expected = flattenProto(messages, root);
    const got = actual.map(([name]) => name);
    if (expected.length !== got.length || expected.some((e, i) => e !== got[i])) {
      throw new Error(
        `State Spec 불일치 (${root})\n` +
          `  .proto : ${expected.join(', ')}\n` +
          `  spec.mjs: ${got.join(', ')}`
      );
    }
  };
  check('FrameState', BASE_FIELDS);
  check('SoundSuffix', SOUND_FIELDS);
  return { base: BASE_FIELDS.length, sound: SOUND_FIELDS.length };
}

// ─────────────────────────────────────────────────────────────────────────────
// 직렬화 · 해시
// ─────────────────────────────────────────────────────────────────────────────

/** 프레임 상태를 Int 배열로 만든다. */
export function toIntArray(physics, isBallTouchingGround, strict) {
  const { ball, player1, player2 } = physics;
  const src = { ball, player1, player2 };
  const out = new Array(BASE_INT_COUNT + (strict ? SOUND_INT_COUNT : 0));
  let i = 0;
  for (const [name, get] of BASE_FIELDS) {
    if (get === null) out[i++] = b(isBallTouchingGround);
    else out[i++] = get(src[name.slice(0, name.indexOf('.'))]);
  }
  if (strict) {
    for (const [name, get] of SOUND_FIELDS) {
      out[i++] = get(src[name.slice(0, name.indexOf('.'))]);
    }
  }
  return out;
}

/** Int 배열 → little-endian 4바이트 이어붙이기. */
export function packInts(ints, buf) {
  const target = buf ?? Buffer.allocUnsafe(ints.length * 4);
  for (let i = 0; i < ints.length; i++) target.writeInt32LE(ints[i] | 0, i * 4);
  return target;
}

/** 프레임별 해시 — SHA-256 앞 8바이트를 hex 16자로. (plan.md §6.3) */
export function frameHash(bytes) {
  return createHash('sha256').update(bytes).digest('hex').slice(0, 16);
}

/** 체인 해시 — h_n = SHA256(h_{n-1} ‖ state_n). 절단하지 않는다. */
export function chainStep(prev32, bytes) {
  return createHash('sha256').update(prev32).update(bytes).digest();
}

export const CHAIN_SEED = Buffer.alloc(32, 0);
