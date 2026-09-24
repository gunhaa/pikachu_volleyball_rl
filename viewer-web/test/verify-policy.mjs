/**
 * verify-policy — 적재된(또는 기록된) 경기의 정책 슬롯 입력을 ONNX 재추론으로 다시 만들어 **전부** 같은지 본다.
 * (Phase 5 FR-13, M5-c, plan.md §9.2)
 *
 *   node viewer-web/test/verify-policy.mjs --game-id 123 [--api http://127.0.0.1:8081] [--live-dir runs/live]
 *   node viewer-web/test/verify-policy.mjs --dir runs/live            # manifest 의 정책 경기 전부
 *   node viewer-web/test/verify-policy.mjs runs/live/<sha>.pkr …      # 같은 디렉터리의 manifest 로 참가자를 찾는다
 *
 * 정책 슬롯 = manifest 참가자의 `onnx`(라이브 제출이 남긴다). 없으면 `checkpoint` 로 레지스트리를 찾는다 — 그래서
 * 평가 기준선(`runs/baselines/track-a-seedN`)에도 쓸 수 있다. 한 프레임이라도 다르면 exit 1.
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { PolicyModel } from '../src/policy/model.mjs';
import { verifyPolicyReplay } from '../src/policy/verify.mjs';
import { REPO } from './paths.mjs';

const { values: opt, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    'game-id': { type: 'string' },
    dir: { type: 'string' },
    api: { type: 'string', default: 'http://127.0.0.1:8081' },
    'live-dir': { type: 'string', default: 'runs/live' },
    registry: { type: 'string', default: 'runs/policies/registry.jsonl' },
  },
});
/** 현재 디렉터리 기준으로 있으면 그것, 아니면 저장소 루트 기준. */
const fromRepo = (p) => (isAbsolute(p) || existsSync(resolve(p)) ? resolve(p) : resolve(REPO, p));
const jsonl = (p) => readFileSync(p, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const hex = (s) => s?.replace(/^sha256:/, '');

// ── 레지스트리 · 모델 ────────────────────────────────────────────────────────
const registryPath = fromRepo(opt.registry);
const registry = jsonl(registryPath);
const models = new Map();
async function modelFor(row) {
  if (!models.has(row.onnx_sha256)) {
    const bytes = new Uint8Array(readFileSync(join(dirname(registryPath), basename(row.onnx))));
    if (sha256(bytes) !== row.onnx_sha256) throw new Error(`${row.label}: ONNX 파일 SHA ≠ 레지스트리`);
    models.set(row.onnx_sha256, await PolicyModel.load(bytes));
  }
  return models.get(row.onnx_sha256);
}
/** manifest 참가자 → 레지스트리 줄 (정책이 아니면 null). */
function policyRow(p) {
  if (p.kind !== 'external') return null;
  if (p.onnx) {
    const row = registry.find((r) => r.onnx_sha256 === hex(p.onnx));
    if (!row) throw new Error(`레지스트리에 없는 ONNX: ${p.onnx}`);
    return row;
  }
  return p.checkpoint ? registry.find((r) => r.checkpoint_sha256 === hex(p.checkpoint)) ?? null : null;
}

// ── 대상 모으기: [{name, bytes, entry}] ─────────────────────────────────────
const targets = [];
const manifestOf = (dir) => {
  const path = join(dir, 'manifest.jsonl');
  if (!existsSync(path)) throw new Error(`${path} 가 없습니다`);
  return jsonl(path);
};
if (opt['game-id'] !== undefined) {
  const res = await fetch(`${opt.api}/api/games/${opt['game-id']}/replay`);
  if (!res.ok) throw new Error(`게임 ${opt['game-id']}: HTTP ${res.status} ${await res.text()}`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  const file = `${sha256(bytes)}.pkr`; // serve 는 라이브 원본을 SHA 이름으로 남긴다
  const entry = manifestOf(fromRepo(opt['live-dir'])).find((e) => e.file === file);
  if (!entry) throw new Error(`${opt['live-dir']}/manifest.jsonl 에 ${file} 이 없습니다 — 라이브 경기가 아닙니다`);
  targets.push({ name: `game ${opt['game-id']} (${file.slice(0, 16)})`, bytes, entry });
} else if (opt.dir !== undefined) {
  const dir = fromRepo(opt.dir);
  for (const entry of manifestOf(dir)) {
    if (policyRow(entry.p1) || policyRow(entry.p2)) targets.push({ name: entry.file, bytes: new Uint8Array(readFileSync(join(dir, entry.file))), entry });
  }
} else {
  for (const f of positionals) {
    const path = fromRepo(f);
    const entry = manifestOf(dirname(path)).find((e) => e.file === basename(path));
    if (!entry) throw new Error(`${basename(path)} 이 manifest 에 없습니다`);
    targets.push({ name: basename(path), bytes: new Uint8Array(readFileSync(path)), entry });
  }
}
if (targets.length === 0) {
  console.error('정책 경기가 없습니다. 사용법은 파일 머리 주석을 보세요.');
  process.exit(2);
}

// ── 검증 ─────────────────────────────────────────────────────────────────────
let failed = 0, frames = 0, matched = 0;
for (const t of targets) {
  const rows = [policyRow(t.entry.p1), policyRow(t.entry.p2)];
  if (!rows[0] && !rows[1]) throw new Error(`${t.name}: 정책 슬롯이 없습니다`);
  const { slots } = await verifyPolicyReplay(t.bytes, await Promise.all(rows.map((r) => (r ? modelFor(r) : null))));
  const parts = slots.map((s) => {
    frames += s.frames;
    matched += s.matched;
    const m = s.firstMismatch;
    return `p${s.slot + 1} ${rows[s.slot].label} ${s.matched}/${s.frames}` +
      (m ? ` — 첫 불일치 프레임 ${m.frame}: 기록 ${m.recorded} ≠ 정책 ${m.policy}` : '');
  });
  const ok = slots.every((s) => s.matched === s.frames);
  if (!ok) failed++;
  console.log(`${ok ? '✓' : '✗'} ${t.name} · ${parts.join(' · ')}`);
}
console.log(`\n${targets.length - failed} / ${targets.length} 게임 100% 재현 · 프레임 ${matched} / ${frames}`);
process.exit(failed ? 1 : 0);
