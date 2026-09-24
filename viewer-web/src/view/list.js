/** 경기 목록 — 묶음 · 진영 · 승패 · 미결 필터. (FR-13) */
'use strict';

import { api } from '../api.js';
import { el, participantTag } from '../dom.js';

const PAGE = 100;

export async function renderList(app) {
  const sets = await api.sets();
  if (sets.length === 0) {
    app.append(el('div', { class: 'panel' }, '적재된 경기가 없습니다. ', el('code', {}, 'scripts/baseline-replays.sh'), ' 로 기준선을 만드세요.'));
    return;
  }
  const saved = JSON.parse(sessionStorage.getItem('list-filter') || '{}');
  const setSel = el('select', {}, sets.map((s) => el('option', { value: s.name }, `${s.name} (${s.games})`)));
  const side = el('select', {}, el('option', { value: 'any' }, '진영 전부'), el('option', { value: 'left' }, '정책 왼쪽'), el('option', { value: 'right' }, '정책 오른쪽'));
  const winner = el('select', {}, el('option', { value: 'any' }, '승자 전부'), el('option', { value: '0' }, '왼쪽 승'), el('option', { value: '1' }, '오른쪽 승'));
  const unresolved = el('select', {}, el('option', { value: 'any' }, '미결 포함'), el('option', { value: 'false' }, '끝난 게임만'), el('option', { value: 'true' }, '미결만'));
  for (const [sel, key] of [[setSel, 'set'], [side, 'side'], [winner, 'winner'], [unresolved, 'unresolved']]) {
    if (saved[key] && [...sel.options].some((o) => o.value === saved[key])) sel.value = saved[key];
  }
  const tbody = el('tbody');
  const info = el('span', { class: 'muted' });
  const prev = el('button', { onclick: () => load(offset - PAGE) }, '◀');
  const next = el('button', { onclick: () => load(offset + PAGE) }, '▶');
  let offset = 0;

  async function load(off = 0) {
    offset = Math.max(0, off);
    const params = { set: setSel.value, side: side.value, winner: winner.value, unresolved: unresolved.value, limit: PAGE, offset };
    sessionStorage.setItem('list-filter', JSON.stringify(params));
    const res = await api.games(params);
    tbody.replaceChildren(...res.games.map((g) => el('tr', { class: 'click', onclick: () => { location.hash = `#/play/${g.id}`; } },
      el('td', {}, g.id),
      el('td', {}, participantTag(g.p1)),
      el('td', {}, participantTag(g.p2)),
      el('td', { class: 'num' }, `${g.score[0]} : ${g.score[1]}`),
      el('td', {}, g.ended ? (g.winner === 0 ? '왼쪽' : '오른쪽') : el('span', { class: 'tag' }, '미결')),
      el('td', { class: 'num' }, g.rallies),
      el('td', { class: 'num' }, g.frames.toLocaleString()),
      el('td', { class: 'num' }, `${g.bytes} B`),
      el('td', { class: 'muted' }, g.envIndex != null ? `env ${g.envIndex} · #${g.gameInEnv}` : g.gameInEnv != null ? `#${g.gameInEnv}` : ''),
    )));
    info.textContent = `${res.total.toLocaleString()} 게임 중 ${res.total ? offset + 1 : 0}–${offset + res.games.length}`;
    prev.disabled = offset === 0;
    next.disabled = offset + PAGE >= res.total;
  }
  for (const s of [setSel, side, winner, unresolved]) s.addEventListener('change', () => load(0));

  app.append(
    el('div', { class: 'panel' },
      el('h2', {}, '경기 목록'),
      el('div', { class: 'row' }, setSel, side, winner, unresolved, info, prev, next)),
    el('div', { class: 'panel' },
      el('table', {}, el('thead', {}, el('tr', {}, ['#', '왼쪽', '오른쪽', '점수', '승자', '랠리', '프레임', '크기', '출처'].map((h) => el('th', {}, h)))), tbody)),
  );
  await load(saved.offset ?? 0);
}
