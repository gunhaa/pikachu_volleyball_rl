/**
 * 기록된 입력 바이트를 내는 입력원. 상태가 없다 — 러너의 프레임 번호로 읽으므로 seek 이 자유롭다.
 *
 * 바이트는 엣지 변환 **후** 의 엔진 입력이다 (FR-4). 그대로 푼다.
 */
'use strict';

import { decodeAction } from '../runner/action.mjs';
import { inputAt } from '../runner/codec.mjs';
import { KIND } from './interface.mjs';

export class ReplaySource {
  /** @param {Object} replay decodeReplay 결과 @param {number} order External 슬롯 순서 (0 = 첫 External) */
  constructor(replay, order) {
    this.kind = KIND.EXTERNAL;
    this.replay = replay;
    this.order = order;
  }

  decide(runner, isPlayer2, out) {
    decodeAction(inputAt(this.replay, this.order, runner.frame), out);
  }
}
