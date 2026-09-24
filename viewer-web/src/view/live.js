/**
 * 라이브 대전 — 같은 GameRunner 에 FSM · 사람 · 정책 입력원을 꽂는다. (FR-16 ~ FR-18, M4-k, Phase 5 FR-14 ~ FR-16)
 *
 * 시드는 기본 FreshSeeds (RALLY 규약, 랠리마다 crypto). 끝나면 기록된 바이트를 POST /api/live-games 로 내고,
 * 서버가 Kotlin 으로 다시 재생해 검증한 뒤에만 적재한다. 서버 체인 = 라이브 중 계산한 체인이어야 한다.
 *
 * 정책 (Phase 5):
 *   - ORT 는 정책을 고른 경우에만 동적 import 한다 — 리플레이 · 사람 · FSM 경로는 ORT 를 받지 않는다.
 *   - 받은 ONNX 의 SHA-256 = 주장한 SHA (레지스트리), 메타의 체크포인트 = 레지스트리, 레이아웃 = 인코더.
 *     하나라도 어긋나면 시작하지 않는다.
 *   - ORT 는 비동기뿐이라 프레임마다 `await advance` (beginFrame → prepare → step). 틱 콜백은 동기이므로
 *     밀린 프레임을 세어 두고 한 펌프가 순서대로 소화한다 — 두 펌프가 겹치지 않는다.
 *   - 시드 모드 "평가 경기 재현" (`/eval/<base>/<env>/<startRally>/<firstServeP2>`): `DerivedSeeds` 로 Kotlin 평가와
 *     같은 경기를 치른다. 기준선과 같은 경기라 제출하지 않고 바이트 SHA-256 을 보인다 (M5-b 브라우저 확인).
 */
'use strict';

import { api } from '../api.js';
import { el } from '../dom.js';
import { Chain } from '../runner/chain.mjs';
import { SEED_MODE } from '../runner/codec.mjs';
import { ReplayRecorder } from '../runner/recorder.mjs';
import { GameRunner } from '../runner/runner.mjs';
import { DerivedSeeds, FreshSeeds } from '../runner/seeds.mjs';
import { sha256, toHex } from '../runner/sha256.mjs';
import { FsmSource } from '../sources/fsm.mjs';
import { KeyboardSource } from '../sources/keyboard.mjs';
import { Screen, frameClock, loadResources } from './stage.js';

/** 랠리가 끝난 뒤 쉬는 시간 (틱). 물리는 돌지 않는다 — 화면만 멈춘다. */
const RALLY_PAUSE_TICKS = 25;

/** 지연 측정 — 정책 prepare(관측 + 추론) 한 번의 ms. headless 측정이 `window.__pikaLive` 로 읽는다 (plan.md §10.1). */
function percentile(sorted, q) {
  return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] : NaN;
}

/**
 * `policy:<onnx sha>` 입력원들을 만든다. 같은 ONNX 는 세션 하나를 공유하고, 입력원(엣지 · 버퍼)은 슬롯마다 따로.
 * @return {Promise<{sources:Object[], sessionMs:number, labels:string[]}>}
 */
async function makePolicySources(kinds, latency) {
  const [{ PolicyModel }, { PolicySource }, { encoderFor }] = await Promise.all([
    import('../policy/model.mjs'), import('../sources/policy.mjs'), import('../policy/parity.mjs'),
  ]);
  const registry = await api.policies();
  const models = new Map();
  let sessionMs = 0;
  const labels = [];
  const sources = [];
  for (const k of kinds) {
    if (!k.startsWith('policy:')) { sources.push(null); labels.push(null); continue; }
    const sha = k.slice('policy:'.length);
    const entry = registry.find((p) => p.onnx === sha);
    if (!entry) throw new Error(`레지스트리에 없는 정책: ${sha.slice(0, 16)}`);
    if (!models.has(sha)) {
      const bytes = await api.policyOnnx(sha);
      const got = toHex(sha256(bytes));
      if (got !== sha) throw new Error(`받은 ONNX 의 SHA-256 ${got.slice(0, 16)} ≠ 레지스트리 ${sha.slice(0, 16)} — 시작하지 않습니다`);
      const t0 = performance.now();
      const model = await PolicyModel.load(bytes);
      sessionMs += performance.now() - t0;
      if (`sha256:${model.meta.checkpointSha256}` !== entry.checkpoint) throw new Error('ONNX 메타의 체크포인트 ≠ 레지스트리 — 시작하지 않습니다');
      models.set(sha, model);
    }
    const model = models.get(sha);
    const src = new PolicySource(model, encoderFor(model.meta)()); // 레이아웃 · obs_dim 대조는 생성자가 한다
    const prepare = src.prepare.bind(src);
    src.prepare = async (runner, isPlayer2) => {
      const t = performance.now();
      await prepare(runner, isPlayer2);
      latency.push(performance.now() - t);
    };
    sources.push(src);
    labels.push(entry.label);
  }
  return { sources, sessionMs, labels };
}

export async function renderLive(app, [k1 = 'human', k2 = 'fsm', seedMode, ...seedArgs], query = new URLSearchParams()) {
  const kinds = [k1, k2];
  for (const k of kinds) {
    if (!(k === 'human' || k === 'fsm' || /^policy:[0-9a-f]{64}$/.test(k))) throw new Error(`라이브 입력원은 human | fsm | policy:<onnx sha>: ${k}`);
  }
  let evalSeed = null;
  if (seedMode === 'eval') {
    const [baseSeed, envIndex, startRally, firstServeP2] = seedArgs.map(Number);
    if (![baseSeed, envIndex, startRally, firstServeP2].every(Number.isInteger)) throw new Error(`평가 경기 재현 인자: ${seedArgs}`);
    evalSeed = { baseSeed, envIndex, startRally, firstServeIsPlayer2: firstServeP2 === 1 };
  } else if (seedMode !== undefined) {
    throw new Error(`알 수 없는 시드 모드: ${seedMode}`);
  }

  const resources = await loadResources();
  const latency = [];
  const hasPolicy = kinds.some((k) => k.startsWith('policy:'));
  const policy = hasPolicy ? await makePolicySources(kinds, latency) : { sources: [null, null], sessionMs: 0, labels: [null, null] };
  const sources = kinds.map((k, slot) => policy.sources[slot] ?? (k === 'human' ? new KeyboardSource(slot) : new FsmSource()));

  const chain = new Chain();
  let recorded = null;
  const recorder = new ReplayRecorder(SEED_MODE.RALLY, (bytes, replay) => {
    recorded = { bytes, replay, liveChain: chain.hex() };
  });
  const runner = new GameRunner({
    sources,
    seeds: evalSeed ? new DerivedSeeds(evalSeed.baseSeed, evalSeed.envIndex, evalSeed.startRally) : new FreshSeeds(),
    settings: { maxRallyFrames: 3000, edgeTrigger: true, firstServeIsPlayer2: evalSeed?.firstServeIsPlayer2 ?? false },
    recorder,
    onFrame: (r, t) => chain.step(r.physics, t),
  });

  const mount = el('div', { class: 'stage' });
  const screen = new Screen(mount, resources);
  const scoreLabel = el('b', {}, '0 : 0');
  const status = el('span', { class: 'muted' }, '시작을 누르세요');
  const result = el('div');
  const speedSel = el('select', {}, [1, 2, 4, 10, 50].map((v) => el('option', { value: v, selected: Number(query.get('speed') ?? 1) === v }, `${v}×`)));
  let pause = 0;
  let owed = 0;
  let pumping = false;
  let stopped = false;
  /** headless 확인 · 측정용 (plan.md §10.1). */
  const probe = { done: false, sha256: null, frames: 0, score: null, latency, sessionMs: policy.sessionMs, submitted: null, error: null };
  window.__pikaLive = probe;

  async function pump() {
    pumping = true;
    try {
      while (owed > 0 && !recorded && !stopped) {
        owed--;
        if (pause > 0) { pause--; continue; }
        const { outcome } = await screen.stepAsync(runner, owed === 0);
        if (outcome !== null) pause = RALLY_PAUSE_TICKS;
        if (runner.ended) break;
      }
      if (stopped) return;
      screen.draw(runner);
      scoreLabel.textContent = `${runner.scores[0]} : ${runner.scores[1]}`;
      status.textContent = `랠리 ${runner.rallyIndex + 1} · 프레임 ${runner.frame.toLocaleString()}`;
      if (recorded) await finish();
    } catch (e) {
      clock.stop();
      probe.error = e.message;
      result.replaceChildren(el('div', { class: 'banner warn' }, `오류 — ${e.message}`));
      console.error(e);
    } finally {
      pumping = false;
    }
  }

  const clock = frameClock((n) => {
    owed = Math.min(owed + n, 5000); // 추론이 틱보다 느려져도 빚이 끝없이 쌓이지 않게
    if (!pumping) pump();
  });
  clock.speed = Number(speedSel.value);
  speedSel.addEventListener('change', () => { clock.speed = Number(speedSel.value); });

  function latencyText() {
    if (!latency.length) return '';
    const s = [...latency].sort((a, b) => a - b);
    return `추론 지연 p50 ${percentile(s, 0.5).toFixed(3)} · p99 ${percentile(s, 0.99).toFixed(3)} · 최대 ${s[s.length - 1].toFixed(2)} ms ` +
      `(${s.length.toLocaleString()} 회) · 첫 세션 생성 ${policy.sessionMs.toFixed(0)} ms`;
  }

  async function finish() {
    clock.stop();
    const { bytes, replay, liveChain } = recorded;
    Object.assign(probe, { sha256: toHex(sha256(bytes)), frames: replay.frameCount, score: replay.finalScore, chain: liveChain });
    const lat = latencyText();
    if (!replay.ended) {
      result.replaceChildren(el('div', { class: 'banner warn' }, '기록 상한(60,000 프레임)에 닿았습니다 — 끝나지 않은 경기는 제출하지 않습니다.'), lat && el('p', { class: 'muted' }, lat));
      probe.done = true;
      return;
    }
    if (evalSeed) {
      result.replaceChildren(el('div', { class: 'banner ok' },
        `평가 경기 재현 끝 — ${replay.finalScore.join(' : ')} · ${replay.frameCount.toLocaleString()} 프레임 · 리플레이 SHA-256 `, el('code', {}, probe.sha256.slice(0, 16)),
        ' — 기준선 리플레이의 SHA 와 같아야 한다 (제출하지 않는다)'), lat && el('p', { class: 'muted' }, lat));
      status.textContent = `끝 — ${replay.finalScore.join(' : ')}`;
      probe.done = true;
      return;
    }
    status.textContent = `끝 — ${replay.finalScore.join(' : ')} · 제출 중…`;
    try {
      // External 슬롯의 참가자 주장 — 서버는 FSM 슬롯의 주장을 무시한다.
      const claims = {};
      kinds.forEach((k, i) => { if (k !== 'fsm') claims[`p${i + 1}`] = k; });
      const res = await api.submitLive(bytes, claims);
      probe.submitted = res;
      const same = res.chain === liveChain;
      result.replaceChildren(el('div', { class: `banner ${same ? 'ok' : 'warn'}` },
        `${res.inserted ? '적재됨' : '이미 있는 경기'} #${res.id} — 서버(Kotlin) 재생 체인 ${same ? '= 라이브 체인 ✓' : '≠ 라이브 체인 ⚠️'} `,
        el('a', { href: `#/play/${res.id}` }, '재생하기'), ' · ', el('a', { href: '#/list' }, '목록')),
        lat && el('p', { class: 'muted' }, lat),
        hasPolicy ? el('p', { class: 'muted' }, '정책 슬롯의 수는 ', el('code', {}, `node viewer-web/test/verify-policy.mjs --game-id ${res.id}`), ' 로 사후 검증한다.') : null);
      status.textContent = `끝 — ${replay.finalScore.join(' : ')}`;
    } catch (e) {
      probe.error = e.message;
      result.replaceChildren(el('div', { class: 'banner warn' }, `제출 실패 — ${e.message}`));
    }
    probe.done = true;
  }

  const startBtn = el('button', { class: 'primary', onclick: () => { startBtn.disabled = true; startBtn.blur(); clock.start(); } }, '시작');
  const label = (k, slot) => (k === 'human' ? '사람' : k === 'fsm' ? 'FSM' : `정책 ${policy.labels[slot]}`);
  app.append(el('div', { class: 'panel' },
    el('h2', {}, `라이브 — ${label(k1, 0)} vs ${label(k2, 1)}${evalSeed ? ` · 평가 경기 재현 (seed ${evalSeed.baseSeed}, env ${evalSeed.envIndex}, 랠리 ${evalSeed.startRally}~)` : ''}`),
    mount,
    el('div', { class: 'hud' }, scoreLabel, status),
    el('div', { class: 'row' }, startBtn, el('label', {}, '배속 ', speedSel), el('a', { href: '#/setup' }, '설정으로')),
    kinds.includes('human') ? el('p', { class: 'keys' }, '왼쪽: D G R V 이동 · Z 파워히트 · F ↘  |  오른쪽: 방향키 · Enter 파워히트') : null,
    result,
  ));
  screen.draw(runner);
  if (query.get('autostart') === '1') startBtn.click();

  return () => {
    stopped = true;
    clock.stop();
    for (const s of sources) s.dispose?.();
    screen.destroy();
    if (window.__pikaLive === probe) delete window.__pikaLive;
  };
}
