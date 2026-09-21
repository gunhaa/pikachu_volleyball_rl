/**
 * 입력 시퀀스 생성기. (plan.md §6.3)
 *
 * ⚠️ 이 파일의 로직은 Kotlin 하네스에 **한 글자도 다르지 않게** 복제되어야 한다.
 *    입력이 갈라지면 물리가 맞아도 상태가 갈라지고, 원인 파악이 어려워진다.
 *
 * ⚠️ 난수의 나머지 연산은 전부 **부호 없는** uint32 기준이다.
 *    Kotlin 에서는 `r.toUInt() % 100u` 처럼 써야 한다. `Int % 100` 은 음수가 나온다.
 *
 * `biased` 는 공 위치를 읽는다 = 입력이 게임 상태에 의존한다.
 * 프레임 f 의 입력은 프레임 f-1 까지의 상태에서 만들어지고, 그 상태는 이미
 * 해시로 일치가 확인된 뒤이므로 순환 문제는 없다. 첫 불일치는 여전히 그 프레임에서 잡힌다.
 */
'use strict';

export const GEN_UNIFORM = 'uniform';
export const GEN_BIASED = 'biased';
export const GEN_FSM = 'fsm';
export const GENERATORS = [GEN_UNIFORM, GEN_BIASED, GEN_FSM];

const sgn = (v) => (v > 0 ? 1 : v < 0 ? -1 : 0);

/** (a) 균일 무작위 — 넓은 상태 공간 탐색. 플레이어당 3 draw. */
function fillUniform(next, inputs) {
  for (let i = 0; i < 2; i++) {
    const u = inputs[i];
    u.xDirection = (next() % 3) - 1;
    u.yDirection = (next() % 3) - 1;
    u.powerHit = next() % 2;
  }
}

/** (b) 편향 무작위 — 공 쪽으로 가는 경향을 주어 충돌을 유도. 플레이어당 4 draw. */
function fillBiased(next, inputs, physics) {
  const ball = physics.ball;
  for (let i = 0; i < 2; i++) {
    const p = i === 0 ? physics.player1 : physics.player2;
    const u = inputs[i];
    const r1 = next();
    const r2 = next();
    const r3 = next();
    const r4 = next();

    // 70% 확률로 공을 향해 이동
    u.xDirection = r1 % 100 < 70 ? sgn(ball.x - p.x) : (r2 % 3) - 1;

    // 25% 점프(위), 10% 아래
    const y = r3 % 100;
    u.yDirection = y < 25 ? -1 : y < 35 ? 1 : 0;

    // 공이 가까우면 60%, 아니면 5%
    const near = Math.abs(ball.x - p.x) <= 48 && Math.abs(ball.y - p.y) <= 48;
    u.powerHit = r4 % 100 < (near ? 60 : 5) ? 1 : 0;
  }
}

/**
 * (c) FSM vs FSM — 실제 게임 분포.
 *
 * 입력을 만들지 않는다. `PikaPhysics(true, true)` 로 만들면
 * `processPlayerMovementAndSetPlayerPosition` 이 `letComputerDecideUserInput` 을 호출해
 * 이 객체를 덮어쓴다 (세 필드를 먼저 0 으로 밀고 시작하므로 재사용해도 안전하다).
 * 입력 스트림에서 draw 하지 않는다.
 */
function fillFsm() {
  /* no-op */
}

export function isComputerControlled(gen) {
  return gen === GEN_FSM;
}

/** @return {function(function():number, Array, Object): void} */
export function generatorFor(gen) {
  switch (gen) {
    case GEN_UNIFORM:
      return fillUniform;
    case GEN_BIASED:
      return fillBiased;
    case GEN_FSM:
      return fillFsm;
    default:
      throw new Error(`알 수 없는 생성기: ${gen} (가능: ${GENERATORS.join(', ')})`);
  }
}
