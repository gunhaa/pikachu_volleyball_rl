/**
 * 대전 설정 — 좌 · 우 슬롯에 입력원을 고른다. (FR-19, Phase 5 FR-14 · FR-15)
 * 리플레이를 고르면 두 슬롯이 함께 정해진다 (리플레이가 곧 두 입력원이다).
 *
 * 정책은 `GET /api/policies` (serve 의 레지스트리) 에서 고른다 — 좌 · 우 독립이라 정책 vs 정책도 된다.
 * 시드 모드 "평가 경기 재현" 은 Kotlin 평가 경기와 같은 시드 · 첫 서브로 치른다 (`DerivedSeeds`, plan.md §2.7).
 */
'use strict';

import { api } from '../api.js';
import { el } from '../dom.js';
import { DEFAULT_KEYS } from '../sources/keyboard.mjs';

const BASE_OPTIONS = [
  ['human', '사람 (키보드)'],
  ['fsm', 'FSM (컴퓨터)'],
];

export async function renderSetup(app) {
  const saved = JSON.parse(sessionStorage.getItem('setup') || '{"p1":"human","p2":"fsm","mode":"live","seed":"fresh"}');
  let policies = [];
  let policyNote = '';
  try {
    policies = await api.policies();
    if (policies.length === 0) policyNote = '등록된 정책이 없습니다 (serve --policies runs/policies)';
  } catch (e) {
    policyNote = `정책 목록을 못 받았습니다 — ${e.message}`;
  }
  const options = [
    ...BASE_OPTIONS,
    ...policies.map((p) => [`policy:${p.onnx}`, `정책 — ${p.label} (${p.onnx.slice(0, 8)})`]),
  ];
  const slot = (key) => el('select', {}, options.map(([v, label]) => el('option', { value: v, selected: saved[key] === v }, label)));
  const p1 = slot('p1');
  const p2 = slot('p2');
  const live = el('input', { type: 'radio', name: 'mode', value: 'live', checked: saved.mode !== 'replay' });
  const rep = el('input', { type: 'radio', name: 'mode', value: 'replay', checked: saved.mode === 'replay' });
  const gameId = el('input', { type: 'number', min: 1, placeholder: '게임 #', style: 'width:110px' });
  const startBtn = el('button', { class: 'primary' }, '');
  const replayNote = el('span', { class: 'muted' }, '리플레이가 두 슬롯을 정한다 — ', el('a', { href: '#/list' }, '목록에서 고르기'));

  // 시드 모드 (라이브만)
  const fresh = el('input', { type: 'radio', name: 'seed', value: 'fresh', checked: saved.seed !== 'eval' });
  const evalMode = el('input', { type: 'radio', name: 'seed', value: 'eval', checked: saved.seed === 'eval' });
  const num = (key, placeholder, width = 80) => el('input', { type: 'number', placeholder, value: saved[key] ?? '', style: `width:${width}px` });
  const baseSeed = num('baseSeed', 'baseSeed', 90);
  const envIndex = num('envIndex', 'env');
  const startRally = num('startRally', '첫 랠리 #', 100);
  const firstServeP2 = el('input', { type: 'checkbox', checked: !!saved.firstServeP2 });
  const evalFields = el('span', { class: 'row' }, baseSeed, envIndex, startRally, el('label', {}, firstServeP2, ' 첫 서브 오른쪽'));
  const seedRow = el('div', { class: 'row' }, el('span', {}, '시드'), el('label', {}, fresh, ' 새 난수'), el('label', {}, evalMode, ' 평가 경기 재현'), evalFields);

  function sync() {
    const isReplay = rep.checked;
    p1.disabled = p2.disabled = isReplay;
    gameId.style.display = replayNote.style.display = isReplay ? '' : 'none';
    seedRow.style.display = isReplay ? 'none' : '';
    evalFields.style.display = evalMode.checked ? '' : 'none';
    startBtn.textContent = isReplay ? '재생' : '대전 시작';
    sessionStorage.setItem('setup', JSON.stringify({
      p1: p1.value, p2: p2.value, mode: isReplay ? 'replay' : 'live', seed: evalMode.checked ? 'eval' : 'fresh',
      baseSeed: baseSeed.value, envIndex: envIndex.value, startRally: startRally.value, firstServeP2: firstServeP2.checked,
    }));
  }
  for (const n of [p1, p2, live, rep, fresh, evalMode, baseSeed, envIndex, startRally, firstServeP2]) n.addEventListener('change', sync);
  startBtn.addEventListener('click', () => {
    if (rep.checked) {
      if (gameId.value) location.hash = `#/play/${gameId.value}`;
      return;
    }
    let hash = `#/live/${encodeURIComponent(p1.value)}/${encodeURIComponent(p2.value)}`;
    if (evalMode.checked) {
      const ints = [baseSeed, envIndex, startRally].map((n) => Number(n.value));
      if (![baseSeed, envIndex, startRally].every((n) => n.value !== '') || !ints.every(Number.isInteger)) {
        alert('평가 경기 재현: baseSeed · env · 첫 랠리 번호를 정수로 넣으세요');
        return;
      }
      hash += `/eval/${ints.join('/')}/${firstServeP2.checked ? 1 : 0}`;
    }
    location.hash = hash;
  });

  app.append(el('div', { class: 'panel' },
    el('h2', {}, '대전 설정'),
    el('div', { class: 'row' }, el('label', {}, live, ' 라이브'), el('label', {}, rep, ' 리플레이')),
    el('div', { class: 'row', style: 'margin:14px 0' },
      el('label', {}, '왼쪽 '), p1, el('span', { class: 'muted' }, 'vs'), el('label', {}, '오른쪽 '), p2, gameId, replayNote, startBtn),
    seedRow,
    policyNote ? el('p', { class: 'muted' }, policyNote) : null,
    el('p', { class: 'keys' },
      `왼쪽 키: ${DEFAULT_KEYS[0].slice(0, 4).map((k) => k.replace('Key', '')).join(' ')} 이동 · Z 파워히트 · F ↘  |  ` +
      '오른쪽 키: 방향키 이동 · Enter 파워히트 (업스트림 기본 배치)'),
    el('p', { class: 'muted' },
      '라이브 경기는 랠리마다 새 시드(crypto)를 뽑아 리플레이 형식으로 기록되고, 끝나면 서버가 ingest 와 같은 재생 검증을 거쳐 적재한다. ' +
      '정책은 브라우저에서 ONNX(onnxruntime-web, wasm 단일 스레드)로 돈다 — 받은 ONNX 의 SHA-256 을 레지스트리와 대조한 뒤에만 시작한다. ' +
      '"평가 경기 재현" 은 Kotlin 평가와 같은 시드로 치르며 제출하지 않는다 (기준선과 같은 경기다).'),
  ));
  sync();
}
