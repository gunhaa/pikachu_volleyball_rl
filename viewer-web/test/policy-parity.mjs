/**
 * M5-b 하네스 — Kotlin 평가 리플레이 묶음을 `PolicySource + FsmSource + DerivedSeeds` 로 **새로 치러** 바이트를 비교한다.
 * (Phase 5 FR-10, plan.md §8.2 · §8.3) `runs/` 가 필요하다 — 로컬 전용. `npm test` 에는 축소판(`parity.test.mjs`)만.
 *
 *   node viewer-web/test/policy-parity.mjs runs/baselines/track-a-seed0 --base-seed 0 \
 *        [--registry runs/policies/registry.jsonl] [--limit N]
 *
 * 시작 전에 참가자를 대조한다 — manifest 의 체크포인트 SHA = 레지스트리 SHA = ONNX 메타 SHA, ONNX 파일 SHA = 레지스트리.
 * 다른 가중치로 치른 경기와 비교하는 사고를 막는다. 불일치가 하나라도 있으면 exit 1.
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { basename, isAbsolute, join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { PolicyModel } from '../src/policy/model.mjs';
import { formatMismatch, policySlotOf, reproduceSet } from '../src/policy/parity.mjs';
import { REPO } from './paths.mjs';

const { values: opt, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    'base-seed': { type: 'string' },
    registry: { type: 'string', default: 'runs/policies/registry.jsonl' },
    limit: { type: 'string' },
    'max-reports': { type: 'string', default: '5' },
  },
});
if (positionals.length !== 1 || opt['base-seed'] === undefined) {
  console.error('사용법: policy-parity.mjs <리플레이 묶음 디렉터리> --base-seed <n> [--registry …] [--limit N]');
  process.exit(2);
}
const fromRepo = (p) => (isAbsolute(p) ? p : resolve(REPO, p));
const dir = fromRepo(positionals[0]);
const baseSeed = Number(opt['base-seed']);
if (!Number.isInteger(baseSeed)) throw new Error(`--base-seed 는 정수: ${opt['base-seed']}`);
const jsonl = (p) => readFileSync(p, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

// ── 참가자 대조 ───────────────────────────────────────────────────────────────
let entries = jsonl(join(dir, 'manifest.jsonl'));
const claims = new Set(entries.map((e) => {
  const p = policySlotOf(e) === 0 ? e.p1 : e.p2;
  return JSON.stringify([p.label, p.checkpoint]);
}));
if (claims.size !== 1) throw new Error(`manifest 의 정책 참가자가 하나가 아닙니다: ${[...claims]}`);
const [label, checkpoint] = JSON.parse([...claims][0]);
const ckptSha = checkpoint.replace(/^sha256:/, '');

const rows = jsonl(fromRepo(opt.registry)).filter((r) => r.label === label);
if (rows.length !== 1) throw new Error(`레지스트리에 label ${label} 이 ${rows.length} 줄 (1 줄이어야 함)`);
const reg = rows[0];
if (reg.checkpoint_sha256 !== ckptSha) {
  throw new Error(`체크포인트 SHA 불일치 — manifest ${ckptSha.slice(0, 16)} ≠ 레지스트리 ${reg.checkpoint_sha256.slice(0, 16)}. 다른 가중치로 치른 경기입니다`);
}
const onnxBytes = new Uint8Array(readFileSync(fromRepo(reg.onnx)));
if (sha256(onnxBytes) !== reg.onnx_sha256) throw new Error(`ONNX 파일 SHA ≠ 레지스트리 onnx_sha256 (${reg.onnx})`);
const model = await PolicyModel.load(onnxBytes);
if (model.meta.checkpointSha256 !== ckptSha) throw new Error('ONNX 메타의 체크포인트 SHA ≠ manifest');

if (opt.limit !== undefined) {
  // 앞 게임이 빠지면 첫 랠리 번호를 셀 수 없으므로 env 단위로 자른다.
  const envs = [...new Set(entries.map((e) => e.envIndex))].sort((a, b) => a - b).slice(0, Number(opt.limit));
  entries = entries.filter((e) => envs.includes(e.envIndex));
}

console.log(`${basename(dir)} · ${label} · 체크포인트 ${ckptSha.slice(0, 16)} · ONNX ${reg.onnx_sha256.slice(0, 16)} · baseSeed ${baseSeed} · ${entries.length} 게임`);

// ── 재현 ──────────────────────────────────────────────────────────────────────
const t0 = performance.now();
const maxReports = Number(opt['max-reports']);
let ok = 0;
const bad = [];
for await (const g of reproduceSet({
  entries,
  readReplay: (file) => new Uint8Array(readFileSync(join(dir, file))),
  model,
  baseSeed,
  diagnose: true,
})) {
  if (g.mismatch === null) ok++;
  else {
    bad.push(g);
    if (bad.length <= maxReports) for (const line of formatMismatch(g.entry, g.mismatch)) console.log(line);
  }
  const n = ok + bad.length;
  if (n % 100 === 0) console.log(`  … ${n} / ${entries.length} (${((performance.now() - t0) / 1000).toFixed(1)} s)`);
}
const seconds = (performance.now() - t0) / 1000;

console.log(`\n${ok} / ${entries.length} 바이트 일치 · ${seconds.toFixed(1)} s`);
if (bad.length) {
  // PRD §4: 게임 목록을 먼저 — 수치 동률이면 이 목록과 여유 값이 보고 대상이다.
  const kinds = {};
  for (const g of bad) kinds[g.mismatch.verdict ?? g.mismatch.kind] = (kinds[g.mismatch.verdict ?? g.mismatch.kind] ?? 0) + 1;
  console.log(`불일치 ${bad.length} 게임 — ${JSON.stringify(kinds)}`);
  for (const g of bad) {
    const m = g.mismatch;
    console.log(`  ${g.entry.file}\t${m.verdict ?? m.kind}\t${m.frame ?? ''}\t${m.margin?.toExponential(3) ?? ''}`);
  }
  process.exit(1);
}
