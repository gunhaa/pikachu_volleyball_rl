/**
 * xorshift32 — JS·Kotlin 이 비트 단위로 동일한 수열을 내는 정수 PRNG.
 *
 * 시프트/XOR 만 쓰므로 두 언어에서 같은 결과가 나온다. (plan.md §4)
 */
'use strict';

/** xorshift 는 상태 0 에서 영원히 0 이다. 시드가 0 이면 이 값으로 대체한다. */
export const ZERO_SEED_REPLACEMENT = 0x9e3779b9;

/**
 * @param {number} seed 32비트 정수
 * @return {function(): number} 호출마다 uint32 를 반환
 */
export function xorshift32(seed) {
  let x = seed >>> 0;
  if (x === 0) x = ZERO_SEED_REPLACEMENT;
  return function next() {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return x;
  };
}

/**
 * rand.js 의 `setCustomRng` 에 넣을 함수를 만든다.
 *
 * `rand()` 는 `Math.floor(32768 * customRng())` 를 계산한다.
 * `u / 2^32` 도 `× 2^15` 도 2의 거듭제곱 스케일링이라 double 에서 오차가 없고,
 * 결과는 항상 `u >>> 17` 과 정확히 같다.
 * → Kotlin 은 `nextUInt() ushr 17` 정수 연산만으로 같은 값을 얻는다.
 *
 * @param {function(): number} next uint32 생성기
 */
export function toCustomRng(next) {
  return () => next() / 4294967296; // 2^32
}
