/**
 * 라이브 대전 — 같은 GameRunner 에 FSM · 사람 입력원을 꽂는다. (FR-16 ~ FR-18, M4-k)
 *
 * 시드는 FreshSeeds (RALLY 규약, 랠리마다 crypto). 끝나면 기록된 바이트를 POST /api/live-games 로 내고,
 * 서버가 Kotlin 으로 다시 재생해 검증한 뒤에만 적재한다. 서버 체인 = 라이브 중 계산한 체인이어야 한다.
 */
'use strict';

import { api } from '../api.js';
import { el } from '../dom.js';
import { Chain } from '../runner/chain.mjs';
import { SEED_MODE } from '../runner/codec.mjs';
import { ReplayRecorder } from '../runner/recorder.mjs';
import { GameRunner } from '../runner/runner.mjs';
import { FreshSeeds } from '../runner/seeds.mjs';
import { FsmSource } from '../sources/fsm.mjs';
import { KeyboardSource } from '../sources/keyboard.mjs';
import { Screen, frameClock, loadResources } from './stage.js';

/** 랠리가 끝난 뒤 쉬는 시간 (틱). 물리는 돌지 않는다 — 화면만 멈춘다. */
const RALLY_PAUSE_TICKS = 25;

export async function renderLive(app, [k1 = 'human', k2 = 'fsm']) {
  const kinds = [k1, k2];
  if (!kinds.every((k) => k === 'human' || k === 'fsm')) throw new Error(`라이브 입력원은 human | fsm: ${kinds}`);
  const resources = await loadResources();
  const sources = kinds.map((k, slot) => (k === 'human' ? new KeyboardSource(slot) : new FsmSource()));

  const chain = new Chain();
  let recorded = null;
  const recorder = new ReplayRecorder(SEED_MODE.RALLY, (bytes, replay) => {
    recorded = { bytes, replay, liveChain: chain.hex() };
  });
  const runner = new GameRunner({
    sources,
    seeds: new FreshSeeds(),
    settings: { maxRallyFrames: 3000, edgeTrigger: true },
    recorder,
    onFrame: (r, t) => chain.step(r.physics, t),
  });

  const mount = el('div', { class: 'stage' });
  const screen = new Screen(mount, resources);
  const scoreLabel = el('b', {}, '0 : 0');
  const status = el('span', { class: 'muted' }, '시작을 누르세요');
  const result = el('div');
  let pause = 0;

  const clock = frameClock((n) => {
    for (let i = 0; i < n; i++) {
      if (pause > 0) { pause--; continue; }
      const { outcome } = screen.step(runner, i === n - 1);
      if (outcome !== null) pause = RALLY_PAUSE_TICKS;
      if (runner.ended || recorded) break;
    }
    screen.draw(runner);
    scoreLabel.textContent = `${runner.scores[0]} : ${runner.scores[1]}`;
    status.textContent = `랠리 ${runner.rallyIndex + 1} · 프레임 ${runner.frame.toLocaleString()}`;
    if (recorded) finish();
  });

  async function finish() {
    clock.stop();
    const { bytes, replay, liveChain } = recorded;
    if (!replay.ended) {
      result.replaceChildren(el('div', { class: 'banner warn' }, '기록 상한(60,000 프레임)에 닿았습니다 — 끝나지 않은 경기는 제출하지 않습니다.'));
      return;
    }
    status.textContent = `끝 — ${replay.finalScore.join(' : ')} · 제출 중…`;
    try {
      const res = await api.submitLive(bytes);
      const same = res.chain === liveChain;
      result.replaceChildren(el('div', { class: `banner ${same ? 'ok' : 'warn'}` },
        `${res.inserted ? '적재됨' : '이미 있는 경기'} #${res.id} — 서버(Kotlin) 재생 체인 ${same ? '= 라이브 체인 ✓' : '≠ 라이브 체인 ⚠️'} `,
        el('a', { href: `#/play/${res.id}` }, '재생하기'), ' · ', el('a', { href: '#/list' }, '목록')));
      status.textContent = `끝 — ${replay.finalScore.join(' : ')}`;
    } catch (e) {
      result.replaceChildren(el('div', { class: 'banner warn' }, `제출 실패 — ${e.message}`));
    }
  }

  const startBtn = el('button', { class: 'primary', onclick: () => { startBtn.disabled = true; startBtn.blur(); clock.start(); } }, '시작');
  const label = (k) => (k === 'human' ? '사람' : 'FSM');
  app.append(el('div', { class: 'panel' },
    el('h2', {}, `라이브 — ${label(k1)} vs ${label(k2)}`),
    mount,
    el('div', { class: 'hud' }, scoreLabel, status),
    el('div', { class: 'row' }, startBtn, el('a', { href: '#/setup' }, '설정으로')),
    el('p', { class: 'keys' }, '왼쪽: D G R V 이동 · Z 파워히트 · F ↘  |  오른쪽: 방향키 · Enter 파워히트'),
    result,
  ));
  screen.draw(runner);

  return () => {
    clock.stop();
    for (const s of sources) s.dispose?.();
    screen.destroy();
  };
}
