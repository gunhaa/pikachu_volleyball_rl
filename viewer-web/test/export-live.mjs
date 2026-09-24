/**
 * 라이브 표본을 파일로 떨군다 — Kotlin 이 같은 바이트를 ReplayPlayer 로 재생해 체인을 대조한다 (M4-j).
 *   node viewer-web/test/export-live.mjs <out-dir>
 * <out-dir>/<name>.pkr + <out-dir>/chains.json (골든과 같은 모양, final = 라이브 중 계산한 체인)
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { LIVE_CASES, playLive } from './live-matches.mjs';

const out = process.argv[2];
if (!out) { console.error('사용법: node export-live.mjs <out-dir>'); process.exit(2); }
mkdirSync(out, { recursive: true });
const games = [];
for (const c of LIVE_CASES) {
  const live = playLive(c);
  const file = `${c.name}.pkr`;
  writeFileSync(join(out, file), live.bytes);
  games.push({ file, frames: live.liveFrames, final: live.liveChain });
}
writeFileSync(join(out, 'chains.json'), JSON.stringify({ games }, null, 2) + '\n');
console.log(`라이브 ${games.length} 게임 → ${out}`);
