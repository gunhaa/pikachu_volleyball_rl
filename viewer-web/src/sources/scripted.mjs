/**
 * 결정론 함수로 입력을 내는 입력원. 라이브 경로 검증용 (M4-j, plan.md §5.4).
 *
 * `KeyboardSource` 와는 입력을 얻는 방법만 다르다 — 러너 바깥에서 매 프레임 입력이 들어온다는
 * 점이 같으므로, 라이브 경로의 동치는 이것으로 증명하고 키보드는 수동 확인으로 끝낸다.
 */
'use strict';

import { decodeAction } from '../runner/action.mjs';
import { KIND } from './interface.mjs';

export class ScriptedSource {
  /** @param {function(GameRunner, boolean): number} fn 행동 코드 0..17 */
  constructor(fn) {
    this.kind = KIND.EXTERNAL;
    this.fn = fn;
  }

  decide(runner, isPlayer2, out) {
    decodeAction(this.fn(runner, isPlayer2), out);
  }
}

/** 프레임 번호 · 슬롯 · salt 의 해시로 행동을 고른다 (상태 없음 — seek 해도 같은 입력). */
export function hashedActions(salt, { powerHitPercent = 50 } = {}) {
  return (runner, isPlayer2) => {
    let h = Math.imul((runner.frame + 1) ^ salt, 0x9e3779b1) ^ (isPlayer2 ? 0x5bd1e995 : 0);
    h = Math.imul(h ^ (h >>> 15), 0x85ebca6b);
    h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
    h = (h ^ (h >>> 16)) >>> 0;
    const x = h % 3;
    const y = (h >>> 8) % 3;
    const p = (h >>> 16) % 100 < powerHitPercent ? 1 : 0;
    return x * 6 + y * 2 + p;
  };
}
