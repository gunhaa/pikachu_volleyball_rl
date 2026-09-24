/**
 * 시드 공급원. (FR-17, plan.md §5.1.2)
 *
 * 러너는 시드가 기록에서 오는지 새 난수인지 모른다. 공급원이 알려 주는 것은 두 가지다:
 *   first()        게임 시작 시드
 *   rallySeed(k)   k(≥1) 번째 랠리 시작 시드. GAME 규약이면 null (시드를 다시 걸지 않는다)
 */
'use strict';

import { SEED_MODE } from './codec.mjs';

/** 리플레이에 기록된 시드. 규약(GAME/RALLY)도 헤더를 따른다. */
export class RecordedSeeds {
  constructor(replay) {
    this.mode = replay.seedMode;
    this.seeds = replay.seeds;
  }

  first() {
    return this.seeds[0];
  }

  rallySeed(k) {
    if (this.mode === SEED_MODE.GAME) return null;
    if (k >= this.seeds.length) throw new Error(`랠리 ${k} 의 시드가 기록에 없습니다`);
    return this.seeds[k];
  }
}

/** `crypto.getRandomValues` 로 int32 하나. */
export function cryptoInt32() {
  return crypto.getRandomValues(new Int32Array(1))[0];
}

/**
 * 라이브용 — **RALLY 규약**, 매 랠리 새 시드를 뽑는다. 뽑은 시드는 러너가 기록기에 넘긴다.
 *
 * RALLY 규약인 이유: Phase 5 가 "브라우저 경기 = 같은 시드의 Kotlin 평가 경기" 를 검증하려면
 * Kotlin 평가(`PikaEnv`) 와 같은 규약이어야 한다.
 *
 * @param {function(): number} [next] int32 생성기. 테스트는 결정론 생성기를 주입한다.
 */
export class FreshSeeds {
  constructor(next = cryptoInt32) {
    this.mode = SEED_MODE.RALLY;
    this.next = next;
  }

  first() {
    return this.next() | 0;
  }

  rallySeed() {
    return this.next() | 0;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Kotlin 평가 경기의 시드 유도 (Phase 5 FR-4, plan.md §8.1)
// ─────────────────────────────────────────────────────────────────────────────

/** murmur3 finalizer — `PikaEnv.fmix32` 의 비트 판. */
function fmix32(h) {
  h ^= h >>> 16;
  h = Math.imul(h, 0x85EBCA6B | 0);
  h ^= h >>> 13;
  h = Math.imul(h, 0xC2B2AE35 | 0);
  h ^= h >>> 16;
  return h | 0;
}

/**
 * 환경 [envIndex] 의 랠리 [rallyIndex] 시드 — `PikaEnv.deriveSeed` 와 같다.
 *
 * ⚠️ Kotlin 은 `(envIndex + 1) * 0x9E3779B9` 를 **Int 곱셈**(32비트 래핑)으로 한다. JS 의 `*` 는 double 곱이라
 *    2⁵³ 근처에서 정밀도를 잃는다 — `Math.imul` 이어야 한다. `ushr` 는 `>>>`, 결과는 `| 0` (부호 있는 int32).
 */
export function deriveSeed(baseSeed, envIndex, rallyIndex) {
  let h = baseSeed | 0;
  h = fmix32(h ^ Math.imul((envIndex + 1) | 0, 0x9E3779B9 | 0));
  h = fmix32(h ^ Math.imul((rallyIndex + 1) | 0, 0x85EBCA6B | 0));
  return h;
}

/**
 * Kotlin 평가 경기를 다시 치를 때의 시드 — **RALLY 규약**. 게임의 첫 랠리 번호는 환경의 누적 랠리 수다
 * (`PikaEnv.rallyCounter` 는 게임을 넘어서도 계속 증가한다).
 */
export class DerivedSeeds {
  /** @param {number} baseSeed @param {number} envIndex @param {number} startRally 이 게임의 첫 랠리 번호 */
  constructor(baseSeed, envIndex, startRally) {
    this.mode = SEED_MODE.RALLY;
    this.baseSeed = baseSeed | 0;
    this.envIndex = envIndex;
    this.startRally = startRally;
  }

  first() {
    return deriveSeed(this.baseSeed, this.envIndex, this.startRally);
  }

  rallySeed(k) {
    return deriveSeed(this.baseSeed, this.envIndex, this.startRally + k);
  }
}
