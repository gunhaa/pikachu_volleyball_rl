/**
 * 에피소드 하네스 — 고정 T 프레임 + 라운드 자동 리셋. (plan.md §6.2)
 *
 * ⚠️ 이것은 하네스 코드지 `pikavolley.js` 의 포팅이 아니다.
 *    점수·게임종료·슬로모션은 다루지 않는다 (ROADMAP Phase 2).
 *    다만 "누가 다음 서브를 하는가" 만은 업스트림과 동일하게 판정한다.
 *
 * ⚠️ Kotlin 하네스가 이 순서를 그대로 따라야 한다.
 *    특히 리셋 순서 player1 → player2 → ball 은 RNG 소비 순서를 결정한다.
 */
'use strict';

import {
  GROUND_HALF_WIDTH,
  PikaPhysics,
  PikaUserInput,
} from '../../upstream/src/resources/js/physics.js';
import { setCustomRng } from '../../upstream/src/resources/js/rand.js';
import { xorshift32, toCustomRng } from './xorshift32.mjs';
import { generatorFor, isComputerControlled } from './inputs.mjs';

/** 입력 스트림 시드를 물리 스트림 시드에서 갈라낸다. 두 스트림은 독립이다. */
export const INPUT_SEED_SALT = 0x9e3779b9;

/**
 * 어느 쪽이 다음 서브를 하는가.
 *
 * 업스트림은 `ball.x` 가 아니라 `ball.punchEffectX` 를 읽는다 (pikavolley.js:374).
 * 공이 땅에 닿는 프레임에 `punchEffectX = ball.x` 로 세팅되므로 값은 같지만,
 * 업스트림이 실제로 읽는 필드를 그대로 읽는다.
 *
 * @return {boolean} true 면 player2 가 서브 (= player2 가 득점)
 */
export function nextServeIsPlayer2(ball) {
  return ball.punchEffectX < GROUND_HALF_WIDTH;
}

/** 라운드 리셋. 순서가 RNG 소비 순서를 결정한다. */
export function resetRound(physics, isPlayer2Serve) {
  physics.player1.initializeForNewRound();
  physics.player2.initializeForNewRound();
  physics.ball.initializeForNewRound(isPlayer2Serve);
}

/**
 * 에피소드 하나를 돌린다.
 *
 * @param {{seed: number, frames: number, gen: string}} opts
 * @param {function(Object, boolean, Array, number): void} onFrame
 *        (physics, isBallTouchingGround, inputs, frameIndex) — 리셋 **전** 상태로 호출된다
 */
export function runEpisode({ seed, frames, gen }, onFrame) {
  // RNG 주입은 반드시 생성자 호출보다 먼저. Player 생성자가 이미 rand() 를 소비한다.
  const physicsRng = xorshift32(seed);
  setCustomRng(toCustomRng(physicsRng));

  const inputRng = xorshift32((seed ^ INPUT_SEED_SALT) >>> 0);
  const fill = generatorFor(gen);
  const byComputer = isComputerControlled(gen);

  const physics = new PikaPhysics(byComputer, byComputer);
  const inputs = [new PikaUserInput(), new PikaUserInput()];

  for (let f = 0; f < frames; f++) {
    fill(inputRng, inputs, physics);
    const isBallTouchingGround = physics.runEngineForNextFrame(inputs);

    // 해시는 리셋 전 상태로 뜬다. 리셋 효과는 다음 프레임에서 검증된다.
    onFrame(physics, isBallTouchingGround, inputs, f);

    if (isBallTouchingGround) {
      resetRound(physics, nextServeIsPlayer2(physics.ball));
    }
  }
}

/**
 * 라운드 리셋만 검증하는 프로브. **physicsEngine 을 한 번도 돌리지 않는다.**
 *
 * `initializeForNewRound` 은 엔진과 독립이므로, 엔진 포팅이 끝나기 전에도
 * 리셋 순서와 RNG 소비 패턴을 검증할 수 있다.
 * 서브권은 물리에 의존하지 않도록 `i % 2 === 0` 으로 고정한다.
 *
 * @param {{seed: number, resets: number, gen: string}} opts
 * @param {function(Object, number): void} onState (physics, resetIndex) — 리셋 **전** 상태
 */
export function runResetProbe({ seed, resets, gen }, onState) {
  const physicsRng = xorshift32(seed);
  setCustomRng(toCustomRng(physicsRng));

  const byComputer = isComputerControlled(gen);
  const physics = new PikaPhysics(byComputer, byComputer);

  for (let i = 0; i < resets; i++) {
    onState(physics, i);
    resetRound(physics, i % 2 === 0);
  }
}
