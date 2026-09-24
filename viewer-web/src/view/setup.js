/**
 * 대전 설정 — 좌 · 우 슬롯에 입력원을 고른다. (FR-19)
 * 리플레이를 고르면 두 슬롯이 함께 정해진다 (리플레이가 곧 두 입력원이다). 정책은 Phase 5 까지 비활성.
 */
'use strict';

import { el } from '../dom.js';
import { DEFAULT_KEYS } from '../sources/keyboard.mjs';

const OPTIONS = [
  ['human', '사람 (키보드)'],
  ['fsm', 'FSM (컴퓨터)'],
  ['policy', '정책 — Phase 5', true],
];

export function renderSetup(app) {
  const saved = JSON.parse(sessionStorage.getItem('setup') || '{"p1":"human","p2":"fsm","mode":"live"}');
  const slot = (key) => el('select', {}, OPTIONS.map(([v, label, disabled]) => el('option', { value: v, disabled: !!disabled, selected: saved[key] === v }, label)));
  const p1 = slot('p1');
  const p2 = slot('p2');
  const live = el('input', { type: 'radio', name: 'mode', value: 'live', checked: saved.mode !== 'replay' });
  const rep = el('input', { type: 'radio', name: 'mode', value: 'replay', checked: saved.mode === 'replay' });
  const gameId = el('input', { type: 'number', min: 1, placeholder: '게임 #', style: 'width:110px' });
  const startBtn = el('button', { class: 'primary' }, '');
  const replayNote = el('span', { class: 'muted' }, '리플레이가 두 슬롯을 정한다 — ', el('a', { href: '#/list' }, '목록에서 고르기'));

  function sync() {
    const isReplay = rep.checked;
    p1.disabled = p2.disabled = isReplay;
    gameId.style.display = replayNote.style.display = isReplay ? '' : 'none';
    startBtn.textContent = isReplay ? '재생' : '대전 시작';
    sessionStorage.setItem('setup', JSON.stringify({ p1: p1.value, p2: p2.value, mode: isReplay ? 'replay' : 'live' }));
  }
  for (const n of [p1, p2, live, rep]) n.addEventListener('change', sync);
  startBtn.addEventListener('click', () => {
    if (rep.checked) {
      if (gameId.value) location.hash = `#/play/${gameId.value}`;
    } else {
      location.hash = `#/live/${p1.value}/${p2.value}`;
    }
  });

  app.append(el('div', { class: 'panel' },
    el('h2', {}, '대전 설정'),
    el('div', { class: 'row' }, el('label', {}, live, ' 라이브'), el('label', {}, rep, ' 리플레이')),
    el('div', { class: 'row', style: 'margin:14px 0' },
      el('label', {}, '왼쪽 '), p1, el('span', { class: 'muted' }, 'vs'), el('label', {}, '오른쪽 '), p2, gameId, replayNote, startBtn),
    el('p', { class: 'keys' },
      `왼쪽 키: ${DEFAULT_KEYS[0].slice(0, 4).map((k) => k.replace('Key', '')).join(' ')} 이동 · Z 파워히트 · F ↘  |  ` +
      '오른쪽 키: 방향키 이동 · Enter 파워히트 (업스트림 기본 배치)'),
    el('p', { class: 'muted' },
      '라이브 경기는 랠리마다 새 시드(crypto)를 뽑아 리플레이 형식으로 기록되고, 끝나면 서버가 ingest 와 같은 재생 검증을 거쳐 적재한다. ' +
      '원작의 슬로모션 · 소리 · 메뉴는 없다 — 규칙은 학습 환경(env)과 같다.'),
  ));
  sync();
}
