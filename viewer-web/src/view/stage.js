/**
 * Pixi 화면 — 업스트림 `GameView` 로 러너의 상태를 그린다. (FR-13, FR-14, plan.md §5.2 · §8.2)
 *
 * 렌더러 설정은 업스트림 `main.js` 를 따른다 (canvas 강제, NEAREST, RESOLUTION 2) — 픽셀 아트가 번지지 않게.
 *
 * 그리기가 물리에 닿는 두 지점(RNG · punchEffectRadius)은 `guard.mjs` 의 DrawGuard 가 막는다.
 */
'use strict';

import { settings } from '@pixi/settings';
import { SCALE_MODES } from '@pixi/constants';
import { Renderer, BatchRenderer, autoDetectRenderer } from '@pixi/core';
import { Prepare } from '@pixi/prepare';
import { Container } from '@pixi/display';
import { Loader } from '@pixi/loaders';
import { SpritesheetLoader } from '@pixi/spritesheet';
import { CanvasRenderer } from '@pixi/canvas-renderer';
import { CanvasSpriteRenderer } from '@pixi/canvas-sprite';
import { CanvasPrepare } from '@pixi/canvas-prepare';
import '@pixi/canvas-display';
import { ASSETS_PATH } from '@upstream/assets_path.js';
import { GameView } from '@upstream/view.js';
import { DrawGuard } from './guard.mjs';
import { xorshift32, toCustomRng } from '../../../tools/js-oracle/xorshift32.mjs';

// 업스트림 경로는 '../resources/assets/images/sprite_sheet.json' 로 박혀 있다. export 객체는 변경 가능하므로
// 로더 호출 **전에** 뷰어 경로로 덮어쓴다. 업스트림 파일은 수정하지 않는다.
ASSETS_PATH.SPRITE_SHEET = `${import.meta.env.BASE_URL}sprite_sheet.json`;

Renderer.registerPlugin('prepare', Prepare);
Renderer.registerPlugin('batch', BatchRenderer);
CanvasRenderer.registerPlugin('prepare', CanvasPrepare);
CanvasRenderer.registerPlugin('sprite', CanvasSpriteRenderer);
Loader.registerPlugin(SpritesheetLoader);
settings.RESOLUTION = 2;
settings.SCALE_MODE = SCALE_MODES.NEAREST;
settings.ROUND_PIXELS = true;

let resourcesPromise = null;

/** 스프라이트 시트를 한 번만 받는다. */
export function loadResources() {
  if (!resourcesPromise) {
    resourcesPromise = new Promise((resolve, reject) => {
      const loader = new Loader();
      loader.add(ASSETS_PATH.SPRITE_SHEET);
      loader.onError.add((err) => reject(new Error(`스프라이트 시트를 못 받았습니다 — upstream/ 이 있습니까? (${err?.message ?? err})`)));
      loader.load((_l, resources) => resolve(resources));
    });
  }
  return resourcesPromise;
}

export class Screen {
  /** @param {HTMLElement} mount */
  constructor(mount, resources) {
    this.renderer = autoDetectRenderer({
      width: 432, height: 304, antialias: false, backgroundColor: 0x000000, backgroundAlpha: 1, forceCanvas: true,
    });
    this.renderer.view.classList.add('game-canvas');
    mount.appendChild(this.renderer.view);
    this.stage = new Container();

    this.guard = new DrawGuard(toCustomRng(xorshift32(0x5eed1234)));
    this.guard.beforeCreateView();
    this.view = new GameView(resources);
    this.view.visible = true;
    this.stage.addChild(this.view.container);
  }

  step(runner, willDraw) {
    return this.guard.step(runner, willDraw);
  }

  /** 정책(비동기 입력원)이 꽂힌 경기의 한 step. */
  stepAsync(runner, willDraw) {
    return this.guard.stepAsync(runner, willDraw);
  }

  resetEffects() {
    this.guard.reset();
  }

  draw(runner) {
    this.guard.draw(runner, this.view);
    this.renderer.render(this.stage);
  }

  destroy() {
    this.renderer.destroy(true);
  }
}

/**
 * 고정 25 fps 틱 (× 배속). 한 번에 몰아 도는 프레임 수에 상한을 둔다 — 탭이 잠들었다 깨어나도 폭주하지 않게.
 * @param {function(number): void} onFrames 이번에 돌 게임 프레임 수
 */
export function frameClock(onFrames) {
  let speed = 1;
  let acc = 0;
  let last = null;
  let raf = 0;
  let running = false;
  const loop = (t) => {
    if (!running) return;
    if (last !== null) acc += Math.min(t - last, 250) * speed;
    last = t;
    const frameMs = 1000 / 25;
    const n = Math.floor(acc / frameMs);
    if (n > 0) {
      acc -= n * frameMs;
      onFrames(n);
    }
    raf = requestAnimationFrame(loop);
  };
  return {
    start() { if (!running) { running = true; last = null; raf = requestAnimationFrame(loop); } },
    stop() { running = false; cancelAnimationFrame(raf); },
    get running() { return running; },
    set speed(v) { speed = v; },
    get speed() { return speed; },
  };
}
