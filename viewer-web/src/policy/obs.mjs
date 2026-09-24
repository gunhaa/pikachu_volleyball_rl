/**
 * JS `ObsEncoder` — `GameRunner` 의 물리 상태 → 정책 관측(float32). (Phase 5 FR-2, FR-3, plan.md §4)
 *
 * Kotlin `ObsEncoder.kt` 와 **비트 단위로** 같아야 한다 (M5-a). 구현은 proto 주석을 따르고, 결과는
 * Kotlin 이 만든 결정 관측 골든(`engine-kotlin/env/golden/obs/`)과 대조한다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ float32 의미 (plan.md §2.3)
 * ─────────────────────────────────────────────────────────────────────────────
 * Kotlin 은 연산마다 float32 로 반올림한다. JS 는 double 로 계산하므로 **연산마다 `Math.fround`** 를 건다.
 * 단일 연산은 double 계산 후 한 번 반올림해도 같지만, 두 연산 이상을 이어 double 로 한 뒤 마지막에만
 * 반올림하면 달라질 수 있다. 바깥 한 겹(Float32Array 대입)은 생략해도 같지만 구별이 헷갈리므로 전부 적는다.
 *
 * ⚠️ `-0`. Kotlin 의 물리 상태는 전부 `Int` 라 `-0` 이 없다. JS 는 (1) 미러의 부호 반전, (2) 업스트림
 *    물리가 `v = -v` 로 만든 상태 두 곳에서 `-0` 이 생길 수 있고, `-0 / 20` 은 float32 비트 `0x80000000` 이다.
 *    수치로는 같아서 정책은 같은 행동을 하지만 체인 해시는 달라진다. 그래서 **정수 필드는 전부 `| 0` 으로
 *    읽고**(Int 의미론), 부호 반전은 정수 `neg()` 로 한다.
 */
'use strict';

import { NORM, layoutHash, obsDim } from './obs-spec.mjs';
import { sha256, toHex } from '../runner/sha256.mjs';

const f = Math.fround;
/** Int 로 읽는다 — `-0` 과 비정수를 지운다. */
const int = (v) => v | 0;
/** Int 부호 반전. `-0` 이 나오지 않는다. */
const neg = (v) => (-v) | 0;
/** `ObsEncoder.kt` `unit` 과 같은 연산 순서: 정수 뺄셈 → ×2 → ÷(hi-lo) → -1, 연산마다 float32. */
const unit = (v, [lo, hi]) => f(f(f(2 * (v - lo)) / (hi - lo)) - 1);

const STATE_COUNT = 7;

export class ObsEncoder {
  /**
   * @param {Object} [opts]
   * @param {boolean} [opts.includeLanding=true]
   * @param {boolean} [opts.includeSideFlag=false]
   * @param {boolean} [opts.mirror=true] 오른쪽 슬롯의 관측을 왼쪽 시점으로 뒤집는다
   * @param {number} [opts.winningScore=15]
   */
  constructor({ includeLanding = true, includeSideFlag = false, mirror = true, winningScore = 15 } = {}) {
    this.includeLanding = includeLanding;
    this.includeSideFlag = includeSideFlag;
    this.mirror = mirror;
    this.winningScore = winningScore;
    this.dim = obsDim({ includeLanding, includeSideFlag });
    this.layoutHash = layoutHash({ includeLanding, includeSideFlag });
  }

  /**
   * @param {import('../runner/runner.mjs').GameRunner} runner
   * @param {number} slot 0 = player1(왼쪽), 1 = player2(오른쪽)
   * @param {Float32Array} out
   * @param {number} [offset=0]
   * @return {Float32Array} out
   */
  encode(runner, slot, out, offset = 0) {
    const physics = runner.physics;
    const me = slot === 0 ? physics.player1 : physics.player2;
    const opp = slot === 0 ? physics.player2 : physics.player1;
    // 미러링은 오른쪽 슬롯에만 건다. 미러 후 "나" 는 언제나 왼쪽 진영에 있다.
    const flip = this.mirror && slot === 1;
    const meIsLeft = flip ? true : slot === 0;

    let i = offset;
    i = writePlayer(me, meIsLeft, flip, out, i);
    i = writePlayer(opp, !meIsLeft, flip, out, i);
    i = this.writeBall(physics.ball, flip, out, i);
    i = this.writeMatch(runner, slot, out, i);
    if (i !== offset + this.dim) throw new Error(`인코더가 쓴 칸 수(${i - offset})가 dim(${this.dim})과 다릅니다`);
    return out;
  }

  writeBall(ball, flip, out, i) {
    const x = int(ball.x);
    const xv = int(ball.xVelocity);
    out[i++] = unit(flip ? NORM.groundWidth - x : x, NORM.ballX);
    out[i++] = unit(int(ball.y), NORM.ballY);
    out[i++] = f((flip ? neg(xv) : xv) / NORM.ballVelocity);
    out[i++] = f(int(ball.yVelocity) / NORM.ballVelocity);
    out[i++] = ball.isPowerHit ? 1 : 0;
    if (this.includeLanding) {
      const e = int(ball.expectedLandingPointX);
      out[i++] = unit(flip ? NORM.groundWidth - e : e, NORM.ballX);
    }
    return i;
  }

  writeMatch(runner, slot, out, i) {
    const mine = int(runner.scores[slot]);
    const theirs = int(runner.scores[1 - slot]);
    const ws = this.winningScore;
    out[i++] = f(mine / ws);
    out[i++] = f(theirs / ws);
    out[i++] = f((mine - theirs) / ws);
    out[i++] = runner.isPlayer2Serve === (slot === 1) ? 1 : 0;
    // ⚠️ 진영 플래그는 미러링하지 않는다 — 미러링이 지우는 정보를 되살리는 것이 목적이다.
    if (this.includeSideFlag) out[i++] = slot === 0 ? -1 : 1;
    return i;
  }
}

function writePlayer(p, isLeft, flip, out, i) {
  const px = int(p.x);
  const x = flip ? NORM.groundWidth - px : px;
  out[i++] = unit(x, isLeft ? NORM.leftX : NORM.rightX);
  out[i++] = unit(int(p.y), NORM.playerY);
  out[i++] = f(int(p.yVelocity) / NORM.playerYVelocity);
  const s = p.state;
  for (let k = 0; k < STATE_COUNT; k++) out[i++] = s === k ? 1 : 0;
  out[i++] = f(int(p.frameNumber) / NORM.frameNumber);
  const d = int(p.divingDirection);
  out[i++] = flip ? neg(d) : d; // ⚠️ -0 금지
  out[i++] = f(int(p.lyingDownDurationLeft) / NORM.lyingDown);
  out[i++] = p.isCollisionWithBallHappened ? 1 : 0;
  out[i++] = f(int(p.delayBeforeNextFrame) / NORM.delay);
  return i;
}

if (new Uint8Array(new Float32Array([1]).buffer)[3] !== 0x3f) {
  throw new Error('little-endian 플랫폼이 아닙니다 — ObsChain 의 바이트 규약이 깨진다');
}

/**
 * 결정 관측 체인 — `ObsGolden.kt` 와 같은 규약.
 * `h_n = SHA256(h_{n-1} ‖ slot(u8) ‖ obs_n(float32 LE))`, 외부 슬롯마다 왼쪽 → 오른쪽.
 */
export class ObsChain {
  constructor() {
    this.h = new Uint8Array(32);
    this.pushes = 0;
    this.buf = null;
  }

  /** @param {number} slot @param {Float32Array} obs 이 슬롯의 관측 (dim 칸) */
  push(slot, obs) {
    const bytes = new Uint8Array(obs.buffer, obs.byteOffset, obs.byteLength);
    if (!this.buf || this.buf.length !== 33 + bytes.length) this.buf = new Uint8Array(33 + bytes.length);
    this.buf.set(this.h, 0);
    this.buf[32] = slot;
    this.buf.set(bytes, 33);
    sha256(this.buf, this.h);
    this.pushes++;
  }

  hex() {
    return toHex(this.h);
  }
}
