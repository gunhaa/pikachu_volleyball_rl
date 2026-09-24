/**
 * 리플레이 형식 v1 ↔ 바이트. Kotlin `pika.env.replay.ReplayCodec` 과 **같은 바이트**. (plan.md §3.1)
 *
 * ```
 * little-endian
 * "PKRP" · u8 version=1 · u8 flags · u8 seedMode · u8 winningScore · i8 i8 fixedBoldness
 * i32 maxRallyFrames · u32 rallyCount · i32 × (RALLY ? rallyCount : 1) seeds
 * { i32 frames, i8 outcome } × rallyCount · u8 u8 finalScore · u32 frameCount
 * u8 × frameCount × externalCount (슬롯별 연속, p1 먼저)
 * ```
 * flags: bit0 p1External · bit1 p2External · bit2 firstServeIsPlayer2 · bit3 ended · bit4 edgeTrigger
 */
'use strict';

export const VERSION = 1;
export const SEED_MODE = Object.freeze({ GAME: 0, RALLY: 1 });
export const OUTCOME = Object.freeze({ TRUNCATED: -1, UNFINISHED: -2 });

const MAGIC = [0x50, 0x4b, 0x52, 0x50]; // "PKRP"
const HEADER_BYTES = 18;

/**
 * @typedef {Object} Replay
 * @property {boolean} p1External
 * @property {boolean} p2External
 * @property {boolean} firstServeIsPlayer2
 * @property {boolean} ended
 * @property {boolean} edgeTrigger
 * @property {number} seedMode SEED_MODE
 * @property {number} winningScore
 * @property {number[]} fixedBoldness [p1, p2], -1 = 추첨
 * @property {number} maxRallyFrames
 * @property {Int32Array} seeds
 * @property {Int32Array} rallyFrames
 * @property {Int8Array} rallyOutcomes
 * @property {number[]} finalScore
 * @property {number} frameCount
 * @property {Uint8Array} inputs
 */

export function externalSlots(r) {
  const out = [];
  if (r.p1External) out.push(0);
  if (r.p2External) out.push(1);
  return out;
}

/** External 슬롯 [order] 의 [frame] 번째 입력 (0..17). */
export function inputAt(r, order, frame) {
  return r.inputs[order * r.frameCount + frame];
}

/**
 * @param {Uint8Array} bytes
 * @return {Replay}
 * @throws magic · 버전 · 길이 · 불변식이 틀리면. 알 수 없는 버전을 "대충 읽는" 경로는 없다.
 */
export function decodeReplay(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const v = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  let p = 0;
  const need = (n) => {
    if (p + n > u8.length) throw new Error(`리플레이 바이트가 모자랍니다 (${u8.length} B)`);
  };
  need(HEADER_BYTES);
  for (let i = 0; i < 4; i++) if (u8[i] !== MAGIC[i]) throw new Error('리플레이가 아닙니다 (magic 불일치)');
  p = 4;
  const version = u8[p++];
  if (version !== VERSION) throw new Error(`알 수 없는 리플레이 버전: ${version} (지원: ${VERSION})`);
  const flags = u8[p++];
  if (flags & ~31) throw new Error(`알 수 없는 플래그 비트: ${flags}`);
  const seedMode = u8[p++];
  if (seedMode !== SEED_MODE.GAME && seedMode !== SEED_MODE.RALLY) throw new Error(`알 수 없는 seedMode: ${seedMode}`);
  const winningScore = u8[p++];
  const fixedBoldness = [v.getInt8(p++), v.getInt8(p++)];
  const maxRallyFrames = v.getInt32(p, true); p += 4;
  const rallyCount = v.getUint32(p, true); p += 4;
  if (rallyCount > (u8.length - p) / 5) throw new Error(`rallyCount 가 잘못됐습니다: ${rallyCount}`);
  const seedCount = seedMode === SEED_MODE.GAME ? 1 : rallyCount;
  need(seedCount * 4);
  const seeds = new Int32Array(seedCount);
  for (let i = 0; i < seedCount; i++) { seeds[i] = v.getInt32(p, true); p += 4; }
  need(rallyCount * 5 + 6);
  const rallyFrames = new Int32Array(rallyCount);
  const rallyOutcomes = new Int8Array(rallyCount);
  for (let i = 0; i < rallyCount; i++) {
    rallyFrames[i] = v.getInt32(p, true); p += 4;
    rallyOutcomes[i] = v.getInt8(p++);
  }
  const finalScore = [u8[p++], u8[p++]];
  const frameCount = v.getUint32(p, true); p += 4;
  const r = {
    p1External: (flags & 1) !== 0,
    p2External: (flags & 2) !== 0,
    firstServeIsPlayer2: (flags & 4) !== 0,
    ended: (flags & 8) !== 0,
    edgeTrigger: (flags & 16) !== 0,
    seedMode, winningScore, fixedBoldness, maxRallyFrames, seeds, rallyFrames, rallyOutcomes, finalScore, frameCount,
    inputs: null,
  };
  const inputBytes = frameCount * externalSlots(r).length;
  if (u8.length - p !== inputBytes) {
    throw new Error(`입력 바이트 수가 맞지 않습니다: 남은 ${u8.length - p}, 기대 ${inputBytes}`);
  }
  r.inputs = u8.slice(p, p + inputBytes);
  validate(r);
  return r;
}

/** Kotlin `Replay` 의 init 과 같은 불변식. */
function validate(r) {
  const n = r.rallyFrames.length;
  if (n < 1) throw new Error('랠리가 하나도 없습니다');
  if (r.winningScore < 1) throw new Error(`winningScore: ${r.winningScore}`);
  if (r.maxRallyFrames < 0) throw new Error(`maxRallyFrames: ${r.maxRallyFrames}`);
  for (const b of r.fixedBoldness) if (!(b === -1 || (b >= 0 && b <= 4))) throw new Error(`fixedBoldness: ${b}`);
  let sum = 0;
  for (let k = 0; k < n; k++) {
    const o = r.rallyOutcomes[k];
    const last = k === n - 1;
    if (r.rallyFrames[k] < 0) throw new Error(`랠리 ${k} 프레임 수가 음수`);
    sum += r.rallyFrames[k];
    if (o < OUTCOME.UNFINISHED || o > 1) throw new Error(`랠리 ${k} 결과 코드: ${o}`);
    if ((o === OUTCOME.UNFINISHED) !== (last && !r.ended)) throw new Error(`미완(-2) 위치가 잘못됐습니다 (랠리 ${k})`);
  }
  if (sum !== r.frameCount) throw new Error(`랠리 프레임 합 ${sum} ≠ frameCount ${r.frameCount}`);
  for (const a of r.inputs) if (a >= 18) throw new Error(`입력 바이트는 0..17: ${a}`);
}

/** @param {Replay} r @return {Uint8Array} */
export function encodeReplay(r) {
  validate(r);
  const ext = externalSlots(r).length;
  const size = HEADER_BYTES + 4 * r.seeds.length + 5 * r.rallyFrames.length + 2 + 4 + r.frameCount * ext;
  const u8 = new Uint8Array(size);
  const v = new DataView(u8.buffer);
  let p = 0;
  for (const m of MAGIC) u8[p++] = m;
  u8[p++] = VERSION;
  u8[p++] = (r.p1External ? 1 : 0) | (r.p2External ? 2 : 0) | (r.firstServeIsPlayer2 ? 4 : 0) |
    (r.ended ? 8 : 0) | (r.edgeTrigger ? 16 : 0);
  u8[p++] = r.seedMode;
  u8[p++] = r.winningScore;
  v.setInt8(p++, r.fixedBoldness[0]);
  v.setInt8(p++, r.fixedBoldness[1]);
  v.setInt32(p, r.maxRallyFrames, true); p += 4;
  v.setUint32(p, r.rallyFrames.length, true); p += 4;
  for (const s of r.seeds) { v.setInt32(p, s, true); p += 4; }
  for (let k = 0; k < r.rallyFrames.length; k++) {
    v.setInt32(p, r.rallyFrames[k], true); p += 4;
    v.setInt8(p++, r.rallyOutcomes[k]);
  }
  u8[p++] = r.finalScore[0];
  u8[p++] = r.finalScore[1];
  v.setUint32(p, r.frameCount, true); p += 4;
  u8.set(r.inputs.subarray(0, r.frameCount * ext), p);
  p += r.frameCount * ext;
  if (p !== size) throw new Error('인코드 크기 계산이 틀렸습니다');
  return u8;
}
