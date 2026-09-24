/**
 * 통계 — 랠리 길이 · 착지 x · 파워히트. SVG 를 직접 그린다 (차트 라이브러리 없음). (FR-11, FR-13)
 * 서버는 개수만 준다. 비율은 여기서 계산한다.
 */
'use strict';

import { api } from '../api.js';
import { el } from '../dom.js';

const NS = 'http://www.w3.org/2000/svg';
const COLORS = { scored: 'var(--accent)', truncated: 'var(--trunc)', unfinished: 'var(--warn)', 0: 'var(--p1)', 1: 'var(--p2)' };

function svg(tag, attrs = {}, ...children) {
  const n = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
  for (const c of children.flat()) if (c != null) n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return n;
}

/**
 * 누적 막대. @param {Array<{x:number, parts:Object<string,number>}>} bars
 * @param {{xLabel:(x)=>string, marks?:number[]}} opts marks: 세로 기준선 (x 값)
 */
function stackedBars(bars, series, { xLabel, width = 1000, height = 220, marks = [] }) {
  const pad = { l: 44, r: 8, t: 8, b: 24 };
  const max = Math.max(1, ...bars.map((b) => series.reduce((s, k) => s + (b.parts[k] || 0), 0)));
  const w = (width - pad.l - pad.r) / Math.max(1, bars.length);
  const y = (v) => pad.t + (height - pad.t - pad.b) * (1 - v / max);
  const g = svg('svg', { viewBox: `0 0 ${width} ${height}`, width: '100%', role: 'img' });
  for (const t of [0, 0.5, 1]) {
    g.append(svg('line', { x1: pad.l, x2: width - pad.r, y1: y(max * t), y2: y(max * t), stroke: '#e5e2da' }));
    g.append(svg('text', { x: pad.l - 6, y: y(max * t) + 4, 'text-anchor': 'end' }, Math.round(max * t).toLocaleString()));
  }
  bars.forEach((b, i) => {
    let acc = 0;
    for (const k of series) {
      const v = b.parts[k] || 0;
      if (!v) continue;
      g.append(svg('rect', { x: pad.l + i * w + 0.5, width: Math.max(1, w - 1), y: y(acc + v), height: y(acc) - y(acc + v), fill: COLORS[k] },
        svg('title', {}, `${xLabel(b.x)} · ${k}: ${v.toLocaleString()}`)));
      acc += v;
    }
    const every = Math.ceil(bars.length / 12);
    if (i % every === 0) g.append(svg('text', { x: pad.l + i * w, y: height - 6 }, xLabel(b.x)));
  });
  for (const m of marks) {
    const i = bars.findIndex((b) => b.x >= m);
    if (i >= 0) g.append(svg('line', { x1: pad.l + i * w, x2: pad.l + i * w, y1: pad.t, y2: height - pad.b, stroke: 'var(--ink)', 'stroke-dasharray': '3 3' }));
  }
  return g;
}

function legend(items) {
  return el('div', { class: 'legend muted' }, items.map(([k, label]) => el('span', {}, el('i', { style: `background:${COLORS[k]}` }), label)));
}

export async function renderStats(app, [setArg]) {
  const sets = await api.sets();
  if (!sets.length) { app.append(el('div', { class: 'panel' }, '적재된 경기가 없습니다.')); return; }
  const setSel = el('select', {}, sets.map((s) => el('option', { value: s.name, selected: s.name === setArg }, `${s.name} (${s.games})`)));
  const binSel = el('select', {}, [25, 50, 100, 250].map((b) => el('option', { value: b, selected: b === 50 }, `${b} 프레임 구간`)));
  const body = el('div');
  setSel.addEventListener('change', () => { location.hash = `#/stats/${encodeURIComponent(setSel.value)}`; });
  binSel.addEventListener('change', () => load());

  async function load() {
    const s = await api.stats(setSel.value, Number(binSel.value));
    const t = s.totals;

    const byBucket = new Map();
    for (const r of s.rallyLength) {
      if (!byBucket.has(r.bucket)) byBucket.set(r.bucket, {});
      byBucket.get(r.bucket)[r.outcome] = r.count;
    }
    const maxBucket = Math.max(0, ...byBucket.keys());
    const lengthBars = [];
    for (let x = 0; x <= maxBucket; x += s.bin) lengthBars.push({ x, parts: byBucket.get(x) || {} });

    const landingBars = Array.from({ length: s.landingBins }, (_, i) => ({ x: i * s.landingBin, parts: {} }));
    const scorerKind = {};
    for (const l of s.landing) {
      landingBars[l.bin].parts[l.scorer] = (landingBars[l.bin].parts[l.scorer] || 0) + l.count;
      scorerKind[l.scorer] = l.scorerKind;
    }
    const kindName = (k) => (k === 'fsm' ? 'FSM' : k === 'human' ? '사람' : k === 'external' ? '정책' : '—');

    body.replaceChildren(
      el('div', { class: 'panel' },
        el('div', { class: 'hud' },
          el('span', {}, `랠리 ${t.rallies.toLocaleString()}`),
          el('span', {}, `평균 ${(t.frames / Math.max(1, t.rallies)).toFixed(1)} 프레임`),
          el('span', {}, `랠리당 터치 ${(t.touches / Math.max(1, t.rallies)).toFixed(2)}`))),
      el('div', { class: 'panel' },
        el('h2', {}, '랠리 길이'),
        legend([['scored', '득점'], ['truncated', '잘림 (maxRallyFrames)'], ['unfinished', '미완 (기록 상한)']]),
        stackedBars(lengthBars, ['scored', 'truncated', 'unfinished'], { xLabel: (x) => `${x}` })),
      el('div', { class: 'panel' },
        el('h2', {}, '착지 지점 x (득점 랠리, 8px 구간)'),
        legend([[0, `왼쪽 득점 (${kindName(scorerKind[0])})`], [1, `오른쪽 득점 (${kindName(scorerKind[1])})`]]),
        stackedBars(landingBars, [0, 1], { xLabel: (x) => `${x}`, marks: [216] }),
        el('p', { class: 'muted' }, '점선 = 네트 (x = 216). 득점 판정은 착지 프레임의 ball.punchEffectX 로 한다 — 이 그래프와 같은 필드다.')),
      el('div', { class: 'panel' },
        el('h2', {}, '파워히트'),
        el('table', {},
          el('thead', {}, el('tr', {}, ['친 쪽', '참가자', '횟수', '성공', '성공률'].map((h) => el('th', {}, h)))),
          el('tbody', {}, s.powerHits.map((p) => el('tr', {},
            el('td', {}, p.hitter === 0 ? '왼쪽' : '오른쪽'),
            el('td', {}, kindName(p.hitterKind)),
            el('td', { class: 'num' }, p.total.toLocaleString()),
            el('td', { class: 'num' }, p.success.toLocaleString()),
            el('td', { class: 'num' }, `${((100 * p.success) / Math.max(1, p.total)).toFixed(1)}%`))))),
        el('p', { class: 'muted' }, '성공 = 친 뒤 상대가 공에 닿기 전에 친 쪽의 득점으로 랠리가 끝남. 파워히트 = 터치 직후 ball.isPowerHit.')),
    );
  }

  app.append(el('div', { class: 'panel' }, el('h2', {}, '통계'), el('div', { class: 'row' }, setSel, binSel)), body);
  await load();
}
