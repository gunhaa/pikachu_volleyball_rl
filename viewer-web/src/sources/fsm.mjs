/**
 * FSM(엔진 내장 컴퓨터) 슬롯.
 *
 * ⚠️ decide 가 없다. FSM 은 엔진 **안에서** RNG 로 결정하고 입력을 덮어쓴다
 *    (`physics.js` 의 letComputerDecideUserInput). 러너가 decide 를 부르면 RNG 소비 순서가
 *    깨질 수 있으므로 러너는 kind 만 보고 PikaPhysics 생성 인자를 바꾼다.
 */
'use strict';

import { KIND } from './interface.mjs';

export class FsmSource {
  constructor() {
    this.kind = KIND.FSM;
  }
}
