/**
 * M4-e — 렌더링 RNG 격리. (plan.md §2.4, §5.2)
 *
 * 업스트림 `cloud_and_wave.js` 는 구름 생성자와 매 프레임 엔진에서 전역 rand() 를 부른다.
 * 진짜 Cloud · Wave · cloudAndWaveEngine 을 프레임 사이에 끼워 재생해도 체인이 골든과 같아야 한다.
 *
 * 대조군: 자기 RNG 를 걸지 않는 "새는" 렌더러는 FSM 경기를 **바꿔야** 한다. 바뀌지 않는다면
 * 이 테스트는 아무것도 재지 않는 것이다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Cloud, Wave, cloudAndWaveEngine } from '../../upstream/src/resources/js/cloud_and_wave.js';
import { rand, setCustomRng } from '../../upstream/src/resources/js/rand.js';
import { xorshift32, toCustomRng } from '../../tools/js-oracle/xorshift32.mjs';
import { Chain } from '../src/runner/chain.mjs';
import { runnerFromReplay } from '../src/runner/runner.mjs';
import { DrawGuard } from '../src/view/guard.mjs';
import { GOLDEN_DIR } from './paths.mjs';

const golden = JSON.parse(readFileSync(join(GOLDEN_DIR, 'chains.json'), 'utf8'));
const pick = ['fsm-game-s0-l.pkr', 'ext-fsm-rally.pkr', 'boldness-0-4.pkr', 'powerhit.pkr', 'cap-800.pkr'];

/** 뷰가 하는 일을 흉내 낸다: GameView 생성(구름 10개) → 매 프레임 drawCloudsAndWave. */
function replayWithRenderer(bytes, { ownRng }) {
  const chain = new Chain();
  const { runner } = runnerFromReplay(bytes, { onFrame: (r, t) => chain.step(r.physics, t) });
  const viewRng = toCustomRng(xorshift32(0xc0ffee));
  if (ownRng) setCustomRng(viewRng);
  const clouds = Array.from({ length: 10 }, () => new Cloud());
  const wave = new Wave();
  while (!runner.ended) {
    runner.step();
    if (ownRng) setCustomRng(viewRng);
    cloudAndWaveEngine(clouds, wave);
    rand(); rand(); rand(); // 그 밖의 뷰 코드가 부를 수 있는 여분
  }
  return chain.hex();
}

test('진짜 구름 · 파도를 프레임마다 그려도 체인 = 골든 (뷰가 자기 RNG 를 걸 때)', () => {
  for (const file of pick) {
    const g = golden.games.find((x) => x.file === file);
    const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, file)));
    assert.equal(replayWithRenderer(bytes, { ownRng: true }), g.final, file);
  }
});

test('대조군: 자기 RNG 를 걸지 않는 렌더러는 FSM 경기를 바꾼다', () => {
  const file = 'fsm-game-s0-l.pkr';
  const g = golden.games.find((x) => x.file === file);
  const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, file)));
  let leaked;
  try {
    leaked = replayWithRenderer(bytes, { ownRng: false });
  } catch (e) {
    leaked = `발산: ${e.message}`; // 경기가 기록된 범위를 벗어났다 — 역시 "바뀌었다" 이다
  }
  assert.notEqual(leaked, g.final, '새는 렌더러로도 체인이 같았다 — 이 테스트는 격리를 재지 못한다');
});

/**
 * 업스트림 GameView 에서 **물리에 닿는 부분만** 옮긴 가짜 뷰. drawPlayersAndBall 은 view.js 와 같이
 * ball.punchEffectRadius 를 2씩 줄이고, drawCloudsAndWave 는 진짜 cloudAndWaveEngine 을 돈다.
 */
function upstreamLikeView() {
  const clouds = Array.from({ length: 10 }, () => new Cloud());
  const wave = new Wave();
  const shown = [];
  return {
    shown,
    drawPlayersAndBall(physics) {
      const ball = physics.ball;
      if (ball.punchEffectRadius > 0) {
        ball.punchEffectRadius -= 2; // view.js 와 같은 부수 효과
        shown.push(ball.punchEffectRadius);
      }
    },
    drawScoresToScoreBoards() {},
    drawCloudsAndWave() { cloudAndWaveEngine(clouds, wave); },
  };
}

function replayWithGuard(bytes, { guarded, drawEvery = 1 }) {
  const chain = new Chain();
  const { runner } = runnerFromReplay(bytes, { onFrame: (r, t) => chain.step(r.physics, t) });
  const guard = new DrawGuard(toCustomRng(xorshift32(0xbeef)));
  guard.beforeCreateView();
  const view = upstreamLikeView();
  let n = 0;
  while (!runner.ended) {
    const draw = ++n % drawEvery === 0;
    guard.step(runner, draw);
    if (!draw) continue;
    if (guarded) guard.draw(runner, view);
    else { view.drawPlayersAndBall(runner.physics); setCustomRng(guard.viewRng); view.drawCloudsAndWave(); }
  }
  return { hex: chain.hex(), shown: view.shown };
}

test('DrawGuard: punchEffectRadius 를 줄이는 업스트림식 뷰로 그려도 체인 = 골든 (1× · 3× 배속)', () => {
  for (const file of ['powerhit.pkr', 'fsm-game-s1-l.pkr', 'ext-ext-rally.pkr']) {
    const g = golden.games.find((x) => x.file === file);
    const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, file)));
    for (const drawEvery of [1, 3]) {
      const r = replayWithGuard(bytes, { guarded: true, drawEvery });
      assert.equal(r.hex, g.final, `${file} ×${drawEvery}`);
      assert.ok(r.shown.length > 0, '펀치 효과가 한 번은 그려져야 한다 — 그림자가 동작하는가');
      assert.ok(r.shown.every((v) => v >= 0 && v < 20 && v % 2 === 0), `펀치 반경 ${r.shown.slice(0, 5)}`);
    }
  }
});

test('대조군: 가드 없이 그리면 punchEffectRadius 가 물리에 새어 체인이 바뀐다', () => {
  const file = 'powerhit.pkr';
  const g = golden.games.find((x) => x.file === file);
  const bytes = new Uint8Array(readFileSync(join(GOLDEN_DIR, file)));
  assert.notEqual(replayWithGuard(bytes, { guarded: false }).hex, g.final);
});
