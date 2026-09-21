/**
 * 표적 케이스 — 입력 생성기 (d). (plan.md §6.4 / tasks.md P7)
 *
 * 케이스 표는 `tools/targeted-cases.txt` 한 곳에만 있다.
 * Kotlin 쪽 짝은 `TargetedCases.kt` 이고, **같은 파일을 읽는다.**
 * 표를 양쪽에 복제하지 않는 이유는 `inputs.mjs` 와 달리 데이터이기 때문이다.
 * 로직은 복제해서 대조할 수 있지만, 데이터를 복제하면 대조할 방법이 없다.
 */
'use strict';

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { GENERATORS } from './inputs.mjs';
import { BASE_FIELDS } from './spec.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
export const CASES_PATH = join(HERE, '..', 'targeted-cases.txt');

export const GEN_TARGETED = 'targeted';

/** snake_case → camelCase. State Spec 이름을 JS 속성명으로 옮긴다. */
const camel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

/**
 * `set <필드> <값>` 하나를 적용한다.
 *
 * bool 필드는 State Spec 규약대로 0/1 로 적고, 여기서 현재 값의 타입을 보고 되돌린다.
 * 존재하지 않는 필드명은 **조용히 무시하지 않고 즉시 실패**시킨다 —
 * 오타 하나가 "표적 케이스인데 아무것도 겨냥하지 않는" 상태를 만든다.
 */
function applySetting(physics, field, value) {
  const dot = field.indexOf('.');
  if (dot < 0) throw new Error(`필드명은 '<객체>.<필드>' 형식이어야 합니다: ${field}`);
  const objName = field.slice(0, dot);
  const key = camel(field.slice(dot + 1));

  const obj = { ball: physics.ball, player1: physics.player1, player2: physics.player2 }[objName];
  if (obj === undefined) throw new Error(`알 수 없는 객체: ${objName} (${field})`);
  if (!(key in obj)) throw new Error(`알 수 없는 필드: ${field}`);

  obj[key] = typeof obj[key] === 'boolean' ? value !== 0 : value;
}

/** 케이스 표를 읽는다. 문법은 `tools/targeted-cases.txt` 상단 주석 참고. */
function parseCases(text) {
  const cases = [];
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/#.*$/, '').trim();
    if (line === '') continue;
    const parts = line.split(/\s+/);

    if (parts[0] === 'case') {
      if (parts.length !== 5) throw new Error(`${i + 1}행: case 는 '<이름> <생성기> <시드> <프레임수>' 4개 인자`);
      const [, name, gen, seed, frames] = parts;
      if (!GENERATORS.includes(gen)) throw new Error(`${i + 1}행: 알 수 없는 생성기 ${gen}`);
      if (!/^\d+$/.test(seed) || !/^\d+$/.test(frames)) throw new Error(`${i + 1}행: 시드·프레임수는 양의 정수`);
      cases.push({ name, gen, seed: Number(seed), frames: Number(frames), setup: [] });
    } else if (parts[0] === 'set') {
      if (cases.length === 0) throw new Error(`${i + 1}행: set 앞에 case 가 없습니다`);
      if (parts.length !== 3) throw new Error(`${i + 1}행: set 은 '<필드> <정수>' 2개 인자`);
      if (!/^-?\d+$/.test(parts[2])) throw new Error(`${i + 1}행: 값은 정수여야 합니다`);
      cases.at(-1).setup.push([parts[1], Number(parts[2])]);
    } else {
      throw new Error(`${i + 1}행: 알 수 없는 지시어 ${parts[0]}`);
    }
  }
  if (cases.length === 0) throw new Error('표적 케이스가 하나도 없습니다');

  const names = new Set();
  for (const c of cases) {
    if (names.has(c.name)) throw new Error(`중복된 케이스 이름: ${c.name}`);
    names.add(c.name);
  }
  return cases;
}

let cached = null;

/** @return {Array<{name: string, gen: string, seed: number, frames: number, setup: Array}>} */
export function loadCases() {
  if (cached === null) cached = parseCases(readFileSync(CASES_PATH, 'utf8'));
  return cached;
}

/**
 * 케이스 번호(1-based) 로 케이스를 찾는다.
 *
 * 번호를 쓰는 이유는 하네스의 "시드" 자리에 그대로 들어가기 때문이다.
 * `E <n>` 마커와 `--seeds 1..15` 표기를 손대지 않고 재사용한다.
 */
export function caseAt(index) {
  const cases = loadCases();
  if (!Number.isInteger(index) || index < 1 || index > cases.length) {
    throw new Error(`케이스 번호는 1..${cases.length} 이어야 합니다 (받은 값: ${index})`);
  }
  return cases[index - 1];
}

/** 케이스의 `setup` 을 적용하는 콜백. `runEpisode` 의 `onCreate` 로 넘긴다. */
export function setupFor(testCase) {
  return (physics) => {
    for (const [field, value] of testCase.setup) applySetting(physics, field, value);
  };
}

/**
 * 케이스 표가 State Spec 과 어긋나지 않는지 확인한다.
 *
 * 모든 base 필드를 실제로 한 번씩 써 보고, 케이스가 쓰는 필드명이 전부 spec 에 있는지 본다.
 * @param {Object} scratchPhysics 아무 PikaPhysics (값이 망가져도 되는 것)
 */
export function assertFieldsMatchSpec(scratchPhysics) {
  const specNames = BASE_FIELDS.map(([n]) => n).filter((n) => n !== 'is_ball_touching_ground');
  for (const name of specNames) applySetting(scratchPhysics, name, 0); // 없으면 여기서 터진다

  const known = new Set(specNames);
  for (const c of loadCases()) {
    for (const [field] of c.setup) {
      if (!known.has(field)) throw new Error(`케이스 ${c.name}: State Spec 에 없는 필드 ${field}`);
    }
  }
  return specNames.length;
}
