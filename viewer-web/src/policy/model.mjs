/**
 * ONNX 정책 모델 — onnxruntime-web(wasm, 스레드 1) 세션 + 메타데이터 + argmax. (Phase 5 FR-8, plan.md §6, §7.3)
 *
 * ⚠️ 진입점은 `onnxruntime-web/wasm` 이다. 기본 진입점(`onnxruntime-web`)은 Node 와 브라우저가 **서로 다른 파일**을
 *    로드한다 (plan.md §2.5). 같은 파일 · 같은 wasm 이어야 Node 에서 증명한 것이 브라우저에도 성립한다 (NFR-7).
 *
 * ⚠️ 스레드 1 · wasm 백엔드만. 스레드 분할과 GPU 백엔드는 누적 순서를 바꿀 수 있다 (plan.md §6).
 *
 * ORT JS API 에는 모델의 `metadata_props` 를 읽는 함수가 없다. 그래서 ModelProto 의 필드 14 만 직접 읽는다
 * ([readOnnxMetadata]) — export 가 넣은 `pika.*` 네 키 (plan.md §5.2).
 */
'use strict';

import * as ort from 'onnxruntime-web/wasm';

ort.env.wasm.numThreads = 1; // ⚠️ 첫 세션 생성 **전에** — 그 뒤에 바꾸면 무시된다

/** export 가 넣는 메타데이터 키. */
export const META = Object.freeze({
  checkpointSha256: 'pika.checkpoint_sha256',
  obsDim: 'pika.obs_dim',
  obsLayoutHash: 'pika.obs_layout_hash',
  actionCount: 'pika.action_count',
});

export class PolicyModel {
  /**
   * @param {Uint8Array|ArrayBuffer} bytes ONNX 파일 내용
   * @return {Promise<PolicyModel>}
   */
  static async load(bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const meta = parseMeta(readOnnxMetadata(u8));
    const session = await ort.InferenceSession.create(u8, { executionProviders: ['wasm'] });
    if (ort.env.wasm.numThreads !== 1) throw new Error(`wasm 스레드 수가 1 이 아닙니다: ${ort.env.wasm.numThreads}`);
    return new PolicyModel(session, meta);
  }

  /** @private */
  constructor(session, meta) {
    if (session.inputNames[0] !== 'obs' || session.outputNames[0] !== 'logits') {
      throw new Error(`입출력 이름이 obs → logits 가 아닙니다: ${session.inputNames} → ${session.outputNames}`);
    }
    this.session = session;
    /** @type {{checkpointSha256:string, obsDim:number, obsLayoutHash:string, actionCount:number}} */
    this.meta = meta;
  }

  /**
   * @param {Float32Array} obs 길이 obsDim
   * @return {Promise<Float32Array>} 로짓 actionCount 개
   */
  async logits(obs) {
    if (obs.length !== this.meta.obsDim) throw new Error(`관측 길이 ${obs.length} ≠ obs_dim ${this.meta.obsDim}`);
    const out = await this.session.run({ obs: new ort.Tensor('float32', obs, [1, obs.length]) });
    return out.logits.data;
  }

  /** @param {Float32Array} obs @return {Promise<number>} 0..actionCount-1 */
  async argmax(obs) {
    return argmax(await this.logits(obs));
  }
}

/**
 * torch `argmax` 와 같은 규칙 — 최댓값이 여럿이면 **첫 인덱스**. 그래서 `>` 로 훑는다 (`>=` 가 아니다, plan.md §7.3 ⚠️).
 * @param {ArrayLike<number>} v
 */
export function argmax(v) {
  let best = 0;
  for (let i = 1; i < v.length; i++) if (v[i] > v[best]) best = i;
  return best;
}

function parseMeta(props) {
  for (const key of Object.values(META)) if (!props.has(key)) throw new Error(`ONNX 메타데이터에 ${key} 가 없습니다`);
  const meta = {
    checkpointSha256: props.get(META.checkpointSha256),
    obsDim: Number(props.get(META.obsDim)),
    obsLayoutHash: props.get(META.obsLayoutHash),
    actionCount: Number(props.get(META.actionCount)),
  };
  if (!Number.isInteger(meta.obsDim) || meta.obsDim <= 0) throw new Error(`obs_dim 이 올바르지 않습니다: ${props.get(META.obsDim)}`);
  if (meta.actionCount !== 18) throw new Error(`action_count 가 18 이 아닙니다: ${meta.actionCount}`);
  return Object.freeze(meta);
}

// ─────────────────────────────────────────────────────────────────────────────
// ONNX ModelProto 의 metadata_props (필드 14, repeated StringStringEntryProto{key=1, value=2})
// ─────────────────────────────────────────────────────────────────────────────

const WIRE = Object.freeze({ VARINT: 0, I64: 1, LEN: 2, I32: 5 });
const utf8 = new TextDecoder('utf-8', { fatal: true });

/**
 * 최상위 필드만 훑는다 — 그래프(필드 7)는 길이만 보고 건너뛴다.
 * @param {Uint8Array} bytes
 * @return {Map<string, string>}
 */
export function readOnnxMetadata(bytes) {
  const props = new Map();
  for (const f of fields(bytes, 0, bytes.length)) {
    if (f.no !== 14 || f.wire !== WIRE.LEN) continue;
    let key = '', value = '';
    for (const e of fields(bytes, f.start, f.end)) {
      if (e.wire !== WIRE.LEN) continue;
      const s = utf8.decode(bytes.subarray(e.start, e.end));
      if (e.no === 1) key = s;
      else if (e.no === 2) value = s;
    }
    if (props.has(key)) throw new Error(`ONNX 메타데이터 키가 중복됩니다: ${key}`);
    props.set(key, value);
  }
  return props;
}

function* fields(b, pos, end) {
  while (pos < end) {
    let tag;
    [tag, pos] = varint(b, pos);
    const no = Math.floor(tag / 8);
    const wire = tag % 8;
    let start = pos;
    if (wire === WIRE.VARINT) [, pos] = varint(b, pos);
    else if (wire === WIRE.I64) pos += 8;
    else if (wire === WIRE.I32) pos += 4;
    else if (wire === WIRE.LEN) {
      let len;
      [len, start] = varint(b, pos);
      pos = start + len;
    } else throw new Error(`protobuf wire type ${wire} 은 지원하지 않습니다 (필드 ${no})`);
    if (pos > end) throw new Error('ONNX 파일이 잘렸습니다');
    yield { no, wire, start, end: pos };
  }
}

/** 곱셈으로 누적한다 — 비트 연산은 32비트에서 잘린다 (그래프 길이는 수 MB 가 될 수 있다). */
function varint(b, pos) {
  let v = 0, mul = 1;
  for (;;) {
    if (pos >= b.length) throw new Error('ONNX 파일이 잘렸습니다');
    const byte = b[pos++];
    v += (byte & 0x7f) * mul;
    if (byte < 0x80) return [v, pos];
    mul *= 128;
  }
}
