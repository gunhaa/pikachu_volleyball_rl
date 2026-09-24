/**
 * 뷰어 빌드 설정. (plan.md §8.1)
 *
 * ⚠️ 업스트림 파일 · 에셋은 저장소에 없다 (라이선스 미부여, NFR-4). `scripts/fetch-upstream.sh` 로 받은
 *    `upstream/` 을 **빌드 시점에** 참조만 한다. 결과물(dist/)은 .gitignore 대상이고 배포하지 않는다.
 */
import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const upstream = resolve(here, '../upstream/src/resources');

export default defineConfig({
  // 스프라이트 시트만 서빙한다 (/sprite_sheet.json, /sprite_sheet.png). 소리는 쓰지 않는다.
  publicDir: resolve(upstream, 'assets/images'),
  resolve: {
    alias: [
      { find: '@upstream', replacement: resolve(upstream, 'js') },
      // 업스트림 view.js 의 bare import('@pixi/…') 를 이 패키지의 node_modules 로 푼다 —
      // upstream/ 에는 node_modules 가 없다. 한 벌만 쓰게 되어 Pixi 인스턴스가 갈라지지 않는다.
      { find: /^@pixi\/(.*)$/, replacement: resolve(here, 'node_modules/@pixi/$1') },
    ],
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    fs: { allow: [resolve(here, '..')] },
    proxy: { '/api': 'http://127.0.0.1:8081' },
  },
  preview: {
    host: '127.0.0.1',
    proxy: { '/api': 'http://127.0.0.1:8081' },
  },
  build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1500 },
});
