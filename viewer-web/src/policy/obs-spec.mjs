/**
 * 관측 레이아웃의 JS 쪽 목록 — 세 번째 소비자. (Phase 5 FR-2, plan.md §4.3)
 *
 * 단일 정의는 `proto/obs_spec.proto` 다. 브라우저는 proto 를 읽지 않으므로 필드 이름과 정규화 상수를
 * 여기에 **상수로** 둔다. 그 상수가 proto 와 같은지는 Node 테스트(`test/obs.test.mjs`)가 [parseProto] ·
 * [flattenProto] 로 대조한다. Kotlin `ObsSpec.kt` 를 읽지 않는다 — 세 구현이 서로를 베끼지 않고
 * 모두 proto 하나에 대조된다는 원래 설계를 지킨다.
 *
 * 레이아웃 해시 = SHA-256("\n" 으로 이은 활성 필드 이름) — `ObsSpec.layoutHash` 와 같은 규칙.
 * ONNX 메타데이터의 해시와 다르면 정책 입력원은 만들어지지 않는다.
 */
'use strict';

import { sha256, toHex } from '../runner/sha256.mjs';

/** `PlayerObs` 15개. */
export const PLAYER_FIELDS = Object.freeze([
  'x', 'y', 'y_velocity',
  'state_is_normal', 'state_is_jumping', 'state_is_jumping_and_power_hitting', 'state_is_diving',
  'state_is_lying_down_after_diving', 'state_is_win', 'state_is_lost',
  'frame_number', 'diving_direction', 'lying_down_duration_left',
  'is_collision_with_ball_happened', 'delay_before_next_frame',
]);

/**
 * 정규화 상수 — proto 주석 "정규화 상수" 절의 숫자 그대로. 여기를 바꾸면 관측이 바뀐다.
 * `[lo, hi]` 는 `unit(v, lo, hi)`, 숫자 하나는 `v / scale`.
 */
export const NORM = Object.freeze({
  groundWidth: 432,
  leftX: Object.freeze([32, 184]),
  rightX: Object.freeze([248, 400]),
  playerY: Object.freeze([108, 244]),
  playerYVelocity: 16,
  frameNumber: 5,
  lyingDown: 3,
  delay: 5,
  ballX: Object.freeze([20, 432]),
  ballY: Object.freeze([0, 252]),
  ballVelocity: 20,
});

/**
 * @typedef {Object} ObsOptions
 * @property {boolean} [includeLanding=true]  obs_include_expected_landing
 * @property {boolean} [includeSideFlag=false] obs_include_side_flag
 */

/** @param {ObsOptions} opts */
export function fieldNames({ includeLanding = true, includeSideFlag = false } = {}) {
  const ball = ['x', 'y', 'x_velocity', 'y_velocity', 'is_power_hit'];
  if (includeLanding) ball.push('expected_landing_point_x');
  const match = ['my_score', 'opponent_score', 'score_diff', 'i_am_serving'];
  if (includeSideFlag) match.push('side_flag');
  return [
    ...PLAYER_FIELDS.map((n) => `me.${n}`),
    ...PLAYER_FIELDS.map((n) => `opponent.${n}`),
    ...ball.map((n) => `ball.${n}`),
    ...match.map((n) => `match.${n}`),
  ];
}

/** @param {ObsOptions} opts */
export function obsDim(opts = {}) {
  return fieldNames(opts).length;
}

/** @param {ObsOptions} opts @return {string} hex 64자 */
export function layoutHash(opts = {}) {
  return toHex(sha256(new TextEncoder().encode(fieldNames(opts).join('\n'))));
}

// ─────────────────────────────────────────────────────────────────────────────
// proto 대조 (Node 테스트 전용 — 브라우저는 부르지 않는다)
// ─────────────────────────────────────────────────────────────────────────────

const MESSAGE_RE = /message\s+(\w+)\s*\{([^{}]*)\}/g;
const FIELD_RE = /(\w+)\s+(\w+)\s*=\s*(\d+)\s*;/;
const OPTIONAL_RE = /\/\/\s*@optional:(\w+)/;

/**
 * proto 텍스트 → `{ 메시지: [{type, name, number, optionalFlag}] }`.
 * 주석을 통째로 버리지 않는다 — `@optional:` 태그가 주석에 있다. 직전 주석 줄의 태그를 다음 필드에 붙인다.
 */
export function parseProto(text) {
  const out = {};
  for (const m of text.matchAll(MESSAGE_RE)) {
    const fields = [];
    let pendingFlag = null;
    for (const raw of m[2].split('\n')) {
      const line = raw.trim();
      const opt = OPTIONAL_RE.exec(line);
      if (opt) pendingFlag = opt[1];
      if (line.startsWith('//')) continue;
      const f = FIELD_RE.exec(line.split('//')[0]);
      if (!f) continue;
      fields.push({ type: f[1], name: f[2], number: Number(f[3]), optionalFlag: pendingFlag });
      pendingFlag = null;
    }
    out[m[1]] = fields.sort((a, b) => a.number - b.number);
  }
  return out;
}

/** 파싱 결과를 활성 필드 이름 목록으로 평탄화한다. @param {ObsOptions} opts */
export function flattenProto(messages, { includeLanding = true, includeSideFlag = false } = {}, root = 'Observation') {
  const enabled = {
    obs_include_expected_landing: includeLanding,
    obs_include_side_flag: includeSideFlag,
  };
  const out = [];
  const walk = (msgName, prefix) => {
    const fields = messages[msgName];
    if (!fields) throw new Error(`proto 에 message ${msgName} 이 없습니다`);
    for (const f of fields) {
      if (f.optionalFlag !== null) {
        if (!(f.optionalFlag in enabled)) throw new Error(`알 수 없는 @optional 플래그: ${f.optionalFlag} (${f.name})`);
        if (!enabled[f.optionalFlag]) continue;
      }
      const path = prefix ? `${prefix}.${f.name}` : f.name;
      if (messages[f.type]) walk(f.type, path);
      else if (f.type === 'float') out.push(path);
      else throw new Error(`예상치 못한 타입 ${f.type} (${path}). Obs Spec 은 전부 float 다.`);
    }
  };
  walk(root, '');
  return out;
}
