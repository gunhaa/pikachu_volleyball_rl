/**
 * 사람 입력원 — 업스트림 `PikaKeyboard` 를 감싸는 얇은 층. (FR-16, plan.md §5.1.1)
 *
 * 엣지 변환(파워히트는 누르는 순간만 1)은 `keyboard.js` 의 getInput() 이 이미 한다. 그래서 여기서
 * 복사하는 값은 "엔진이 받는 입력" 이고, 리플레이에는 그 값이 그대로 적힌다 (FR-4).
 *
 * 키 배치는 업스트림 기본값 (`pikavolley.js` 의 keyboardArray) — 두 사람이 한 키보드를 나눈다.
 * 브라우저 키 이벤트는 자동 테스트하지 않는다. 라이브 경로의 동치는 ScriptedSource 로 증명했고 (M4-j),
 * 이것은 입력을 얻는 방법만 다르다 — 수동 확인(M4-k)으로 끝낸다.
 */
'use strict';

import { PikaKeyboard } from '@upstream/keyboard.js';
import { KIND } from './interface.mjs';

export const DEFAULT_KEYS = [
  ['KeyD', 'KeyG', 'KeyR', 'KeyV', 'KeyZ', 'KeyF'], // player1: ← → ↑ ↓ 파워히트 ↘
  ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter'], // player2
];

export class KeyboardSource {
  /** @param {number} slot 0 = player1, 1 = player2 */
  constructor(slot, keys = DEFAULT_KEYS[slot]) {
    this.kind = KIND.EXTERNAL;
    this.keyboard = new PikaKeyboard(...keys); // 생성자가 keydown/keyup 을 구독한다
  }

  decide(runner, isPlayer2, out) {
    this.keyboard.getInput();
    out.xDirection = this.keyboard.xDirection;
    out.yDirection = this.keyboard.yDirection;
    out.powerHit = this.keyboard.powerHit;
  }

  /** 화면을 떠날 때 반드시 부른다 — 안 부르면 다른 화면에서도 키를 삼킨다. */
  dispose() {
    this.keyboard.unsubscribe();
  }
}
