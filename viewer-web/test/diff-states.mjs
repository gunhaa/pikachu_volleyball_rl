/**
 * 두 상태 덤프(`프레임 v0 v1 … v43` 줄)를 비교해 첫 불일치 프레임 · 필드를 출력한다.
 *   node viewer-web/test/diff-states.mjs <js.txt> <kt.txt>
 */
import { readFileSync } from 'node:fs';
import { FIELD_NAMES } from '../src/runner/chain.mjs';

const [a, b] = process.argv.slice(2).map((p) => readFileSync(p, 'utf8').trim().split('\n'));
for (let i = 0; i < Math.min(a.length, b.length); i++) {
  const x = a[i].split(' ').map(Number);
  const y = b[i].split(' ').map(Number);
  if (x[0] !== y[0]) { console.log(`프레임 번호가 다릅니다: ${x[0]} vs ${y[0]}`); process.exit(1); }
  const diffs = FIELD_NAMES.map((n, k) => [n, x[k + 1], y[k + 1]]).filter(([, p, q]) => p !== q);
  if (diffs.length) {
    console.log(`첫 불일치 프레임 ${x[0]}`);
    for (const [n, p, q] of diffs) console.log(`  ${n}: JS ${p} ≠ Kotlin ${q}`);
    process.exit(1);
  }
}
console.log(a.length === b.length ? '같다' : `줄 수가 다릅니다: ${a.length} vs ${b.length}`);
