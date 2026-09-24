/**
 * 리플레이 재생기 — 재생/정지 · 배속 · 시크(그리지 않고 계산) · 랠리 점프. (FR-13, NFR-3, plan.md §8.2)
 *
 * 로드하자마자 JS 로 끝까지 재생해 체인을 뜨고 서버의 `chain_sha256`(Kotlin 재생 체인)과 대조한다.
 * 어긋나면 경고 배너를 띄운다 — 뷰어가 조용히 존재하지 않은 경기를 보여 주는 일을 런타임에도 막는다.
 */
'use strict';

import { api } from '../api.js';
import { el, participantTag } from '../dom.js';
import { Chain } from '../runner/chain.mjs';
import { runnerFromReplay, verifyAgainstReplay } from '../runner/runner.mjs';
import { Screen, frameClock, loadResources } from './stage.js';

/** `#/play/<id>[/<frame>]` — frame 을 주면 그 프레임에서 연다 (장면 공유 · 스크린샷용). */
export async function renderPlayer(app, [idText, frameText]) {
  const id = Number(idText);
  const [meta, bytes, resources] = await Promise.all([api.game(id), api.replay(id), loadResources()]);

  // ── 1. 체인 대조 (그리지 않고 끝까지) ────────────────────────────────
  const t0 = performance.now();
  const chain = new Chain();
  const check = runnerFromReplay(bytes, { onFrame: (r, t) => chain.step(r.physics, t) });
  let verifyError = null;
  try {
    while (!check.runner.ended) check.runner.step();
    verifyError = verifyAgainstReplay(check.runner, check.replay);
  } catch (e) {
    verifyError = e.message;
  }
  const checkMs = performance.now() - t0;
  const chainOk = verifyError === null && chain.hex() === meta.chain;
  const banner = chainOk
    ? el('div', { class: 'banner ok' }, `체인 일치 ✓ — JS 재생 = Kotlin 재생 (${meta.frames.toLocaleString()} 프레임, ${checkMs.toFixed(0)} ms)`)
    : el('div', { class: 'banner warn' },
      `⚠️ 이 재생은 기록된 경기와 다릅니다 — ${verifyError ?? `체인 불일치 (JS ${chain.hex().slice(0, 12)}… ≠ Kotlin ${meta.chain.slice(0, 12)}…)`}. 화면을 믿지 마세요.`);

  // ── 2. 재생 ─────────────────────────────────────────────────────────
  const { runner, replay } = runnerFromReplay(bytes);
  const rallyStarts = [0];
  for (const f of replay.rallyFrames) rallyStarts.push(rallyStarts.at(-1) + f);
  rallyStarts.pop();

  const mount = el('div', { class: 'stage' });
  const screen = new Screen(mount, resources);
  const clock = frameClock((n) => {
    for (let i = 0; i < n && !runner.ended; i++) screen.step(runner, i === n - 1);
    screen.draw(runner);
    sync();
    if (runner.ended) { clock.stop(); sync(); }
  });

  const playBtn = el('button', { class: 'primary', onclick: () => toggle() }, '▶ 재생');
  const speed = el('select', { onchange: () => { clock.speed = Number(speed.value); } },
    [0.25, 0.5, 1, 2, 4, 8].map((v) => el('option', { value: v, selected: v === 1 }, `×${v}`)));
  const seekBar = el('input', { type: 'range', min: 0, max: replay.frameCount, value: 0 });
  const frameLabel = el('span');
  const rallyLabel = el('span');
  const scoreLabel = el('b');
  const seekLabel = el('span', { class: 'muted' });
  const endLabel = el('span', { class: 'muted' });

  function sync() {
    seekBar.value = runner.frame;
    frameLabel.textContent = `프레임 ${runner.frame.toLocaleString()} / ${replay.frameCount.toLocaleString()}`;
    rallyLabel.textContent = `랠리 ${Math.min(runner.rallyIndex + 1, replay.rallyFrames.length)} / ${replay.rallyFrames.length}`;
    scoreLabel.textContent = `${runner.scores[0]} : ${runner.scores[1]}`;
    playBtn.textContent = clock.running ? '⏸ 정지' : '▶ 재생';
    endLabel.textContent = runner.ended ? (replay.ended ? `끝 — 최종 ${runner.scores.join(' : ')} (DB ${meta.score.join(' : ')})` : '끝 — 기록 상한에서 잘린 미결 게임') : '';
  }

  function seek(frame) {
    const t = performance.now();
    runner.seek(Math.max(0, Math.min(frame, replay.frameCount)));
    const ms = performance.now() - t;
    seekLabel.textContent = `시크 ${ms.toFixed(1)} ms`;
    screen.resetEffects();
    screen.draw(runner);
    sync();
  }

  function toggle() {
    if (clock.running) clock.stop();
    else {
      if (runner.ended) seek(0);
      clock.start();
    }
    sync();
  }

  function jumpRally(delta) {
    // 지금 랠리의 시작 — 이미 시작점에 있으면 이전 랠리로.
    let k = rallyStarts.findLastIndex((s) => s <= runner.frame);
    if (delta < 0 && runner.frame === rallyStarts[k] && k > 0) k--;
    else if (delta > 0) k = Math.min(k + 1, rallyStarts.length - 1);
    seek(rallyStarts[k]);
  }

  seekBar.addEventListener('input', () => { clock.stop(); seek(Number(seekBar.value)); });
  const onKey = (e) => {
    if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement) return;
    if (e.code === 'Space') { e.preventDefault(); toggle(); }
    if (e.code === 'ArrowLeft') jumpRally(-1);
    if (e.code === 'ArrowRight') jumpRally(1);
  };
  window.addEventListener('keydown', onKey);

  app.append(
    el('div', { class: 'panel' },
      el('h2', {}, `#${id} · ${meta.set}`),
      el('div', { class: 'row' }, participantTag(meta.p1), 'vs', participantTag(meta.p2),
        el('span', { class: 'muted' }, `${replay.seedMode === 1 ? '랠리당 시드' : '게임당 시드'} · 첫 서브 ${replay.firstServeIsPlayer2 ? '오른쪽' : '왼쪽'}` +
          `${replay.fixedBoldness.some((b) => b >= 0) ? ` · boldness ${replay.fixedBoldness.join('/')}` : ''}` +
          `${replay.maxRallyFrames ? ` · 랠리 상한 ${replay.maxRallyFrames}` : ''} · ${meta.bytes} B`)),
      el('div', { style: 'margin-top:12px' }, banner),
      mount,
      el('div', { class: 'hud' }, scoreLabel, rallyLabel, frameLabel, seekLabel, endLabel),
      el('div', { class: 'row' },
        playBtn, speed,
        el('button', { onclick: () => jumpRally(-1) }, '◀ 랠리'),
        el('button', { onclick: () => jumpRally(1) }, '랠리 ▶'),
        seekBar),
      el('p', { class: 'keys' }, 'Space 재생/정지 · ← → 랠리 이동 · 시크는 처음부터 다시 계산한다 (그리지 않음)')),
  );
  seek(frameText === 'end' ? replay.frameCount : Number(frameText) || 0);
  window.__pika = { runner, replay, meta, chainOk, seek };

  return () => {
    clock.stop();
    window.removeEventListener('keydown', onKey);
    screen.destroy();
    delete window.__pika;
  };
}
