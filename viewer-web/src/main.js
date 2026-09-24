/**
 * 뷰어 진입점 — 해시 라우터. (FR-13, FR-19)
 *   #/list            경기 목록
 *   #/play/<id>       리플레이 재생
 *   #/setup           대전 설정 (라이브 / 리플레이)
 *   #/live/<p1>/<p2>[/eval/<base>/<env>/<startRally>/<firstServeP2>][?speed=N]
 *                     라이브 대전 (fsm | human | policy:<onnx sha>). eval = 평가 경기 재현 시드 (Phase 5)
 *   #/stats[/<set>]   통계
 */
'use strict';

import { renderList } from './view/list.js';
import { renderPlayer } from './view/player.js';
import { renderSetup } from './view/setup.js';
import { renderLive } from './view/live.js';
import { renderStats } from './view/stats.js';
import { el } from './dom.js';

const app = document.getElementById('app');
let cleanup = null;

async function route() {
  if (cleanup) { cleanup(); cleanup = null; }
  app.replaceChildren();
  const [path, search = ''] = (location.hash || '#/list').split('?');
  const [, name = 'list', ...args] = path.split('/');
  for (const a of document.querySelectorAll('[data-nav]')) {
    a.classList.toggle('active', a.dataset.nav === name || (name === 'play' && a.dataset.nav === 'list') || (name === 'live' && a.dataset.nav === 'setup'));
  }
  const views = { list: renderList, play: renderPlayer, setup: renderSetup, live: renderLive, stats: renderStats };
  const view = views[name] ?? renderList;
  try {
    cleanup = (await view(app, args.map(decodeURIComponent), new URLSearchParams(search))) ?? null;
  } catch (e) {
    app.replaceChildren(el('div', { class: 'banner warn' }, `오류: ${e.message}`));
    console.error(e);
  }
}

window.addEventListener('hashchange', route);
route();
