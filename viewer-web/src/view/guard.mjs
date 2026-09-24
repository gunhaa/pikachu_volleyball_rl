/**
 * 그리기가 물리에 닿는 두 지점을 막는다 — Pixi 와 무관한 순수 모듈이라 Node 에서 검증한다. (FR-14, M4-e)
 *
 * 1. RNG: 그리기(구름 · 파도) 전에 뷰 RNG 를 건다. 러너는 매 step 시작에 물리 RNG 를 다시 건다.
 * 2. punchEffectRadius: 업스트림 `GameView.drawPlayersAndBall` 은 `ball.punchEffectRadius -= 2` 로 물리 객체를
 *    직접 줄인다 (view.js, 원작 FUN_00402ee0). 우리 물리는 이 값을 줄이지 않고 State Spec 해시에 넣는다.
 *    그래서 뷰의 값(그림자)을 잠깐 끼워 그리고 곧바로 물리 값을 되돌린다. 물리 엔진은 이 필드를 읽지
 *    않으므로 경기에는 영향이 없고, 그림자 덕분에 펀치 효과는 원작처럼 줄어든다.
 */
'use strict';

import { setCustomRng } from '../../../upstream/src/resources/js/rand.js';
import { advance } from '../runner/live-loop.mjs';

export class DrawGuard {
  constructor(viewRng) {
    this.viewRng = viewRng;
    this.punch = 0;
  }

  /** 뷰를 만들기 전 (GameView 생성자가 구름 10개를 만들며 rand() 를 부른다). */
  beforeCreateView() {
    setCustomRng(this.viewRng);
  }

  /**
   * 러너 한 step. 펀치 효과의 시작을 잡고, 그려지지 않을 프레임이면 뷰가 했을 감소를 흉내 낸다.
   * @param {boolean} willDraw 이 step 직후에 draw 하는가 (배속 재생은 프레임을 건너뛴다)
   */
  step(runner, willDraw) {
    const before = this.before(runner);
    return this.after(runner, willDraw, before, runner.step());
  }

  /** [step] 의 비동기 판 — 정책(비동기 입력원)이 꽂힌 경기. `advance` 가 beginFrame → prepare → step 을 한다. */
  async stepAsync(runner, willDraw) {
    const before = this.before(runner);
    return this.after(runner, willDraw, before, await advance(runner));
  }

  /** @private */
  before(runner) {
    const b = runner.physics.ball;
    return [b.punchEffectX, b.punchEffectY, b.punchEffectRadius];
  }

  /** @private */
  after(runner, willDraw, [x, y, r], result) {
    const b = runner.physics.ball;
    if (b.punchEffectX !== x || b.punchEffectY !== y || (r === 0 && b.punchEffectRadius > 0)) this.punch = b.punchEffectRadius;
    if (!willDraw && this.punch > 0) this.punch = Math.max(0, this.punch - 2);
    return result;
  }

  /** 시크 뒤처럼 이어지지 않는 지점 — 펀치 효과를 지운다. */
  reset() {
    this.punch = 0;
  }

  /** @param {{drawPlayersAndBall, drawScoresToScoreBoards, drawCloudsAndWave}} view 업스트림 GameView 모양 */
  draw(runner, view) {
    const b = runner.physics.ball;
    const saved = b.punchEffectRadius;
    b.punchEffectRadius = this.punch;
    try {
      view.drawPlayersAndBall(runner.physics);
      this.punch = b.punchEffectRadius;
    } finally {
      b.punchEffectRadius = saved; // ⚠️ 물리 값을 되돌린다 — 체인 해시에 들어가는 필드다
    }
    view.drawScoresToScoreBoards(runner.scores);
    setCustomRng(this.viewRng); // ⚠️ 구름 · 파도는 뷰 RNG 로
    view.drawCloudsAndWave();
  }
}
