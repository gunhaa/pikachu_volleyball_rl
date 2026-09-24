# PRD — 라이브 대전: 정책 입력원 (ROADMAP Phase 5)

## 1. 개요

학습한 체크포인트를 ONNX 로 내보내고, 브라우저의 경기 러너에 **정책 입력원(`PolicySource`)** 으로 꽂는다.
Phase 4 의 `GameRunner` 에 이미 사람 · FSM · 리플레이가 꽂히므로, 이것으로 네 입력원이 임의 조합으로 대전한다.
정책이 낀 라이브 경기도 Phase 4 경로로 검증 · 적재 · 재생된다.

**이 작업의 본질은 브라우저 추론이 아니라 "브라우저의 정책이 평가받은 그 정책과 같은 수를 둔다" 는 증명이다.**
관측이 1비트 달라도, 결정 시점이 1프레임 어긋나도, 추론 결과가 동률 근처에서 뒤집혀도 정책은 조용히
다른 게임을 한다. 그 사실은 승률 하락으로만 드러나고, Phase 6 에서 "이 체크포인트가 이상하다" 고 꽂아 봤을 때
**도구가 틀린 것인지 정책이 틀린 것인지** 구별할 수 없게 된다.

## 2. 배경

조사 · 측정으로 확인한 사실. 근거는 `plan.md` §2 에 파일 · 줄 · 수치와 함께 있다.

1. **Phase 2 관측 골든은 JS 가 그대로 대조할 수 없다.** 골든 체인은 관측 + 보상 + terminated/truncated 를
   한 해시로 묶고 (`EnvGolden.kt:127-150`), 벡터 환경의 autoreset 까지 포함한다. JS 에 보상 계산을 이식하지
   않으면 같은 해시가 나올 수 없다. → 같은 11 케이스 구성에서 **관측만** 뽑은 새 골든을 Kotlin 이 만든다.
2. **골든 11 케이스는 실제 정책이 쓰는 구성을 덮지 않는다.** Track A 체크포인트는 41차원(진영 플래그 on,
   `env_client.py:56`)이고 절반은 오른쪽 진영(미러 on)에서 평가된다. 그런데 골든의 진영 플래그 케이스는
   왼쪽 외부 슬롯 하나뿐이다 (`EnvGolden.kt` `side-flag` = `EXTERNAL_VS_FSM`). **"진영 플래그 + 오른쪽 + 미러"
   조합은 어느 골든도 밟지 않는다.** → 케이스 2개를 더한다.
3. **argmax 여유가 추론 오차의 약 3배밖에 안 되는 관측이 있다.** Track A 3시드 평가 관측(고유 51,000개)에서
   top1−top2 로짓 차의 최솟값은 **1.1 × 10⁻⁵**, 백엔드 간 로짓 오차 최대는 **3.8 × 10⁻⁶ ~ 7.5 × 10⁻⁶** 이다.
   측정 범위에서 argmax 불일치는 **0건**이지만 보장은 아니다 — 특히 Phase 6 의 다른 체크포인트에 대해서는.
4. **`onnxruntime-web` 의 추론은 비동기만 있다** (`session.run` 이 Promise). Phase 4 의 `GameRunner.step()` 과
   `InputSource.decide()` 는 동기다. → 러너에 "결정 전 준비" 단계를 연다 (`plan.md` §7).
5. **ONNX export 는 exporter 에 따라 바이트 결정론이 갈린다.** torch 2.14 기본(dynamo) exporter 는 같은
   체크포인트를 두 번 내보내면 **다른 바이트**가 나오고, 레거시(TorchScript) exporter 는 같은 바이트가 나온다.
   ROADMAP M5-d 가 "export 결과가 바이트 단위로 같다는 보장은 없다" 고 쓴 이유가 측정으로 확인됐다.
6. **M5-b 의 정답은 이미 디스크에 있다.** `runs/baselines/track-a-seed{0,1,2}/` 에 Kotlin 평가 경기 2,400개가
   `(envIndex, gameInEnv)` · 체크포인트 SHA-256 과 함께 기록돼 있다 (Phase 4 M4-d). 게임 g 의 첫 랠리 시드는
   `deriveSeed(baseSeed, envIndex, 앞선 게임들의 랠리 수 합)` 으로 다시 유도된다.
7. **서버는 라이브 참가자를 바이트에서 유도한다** — External 슬롯은 무조건 `human` 이다 (`Serve.kt:182`).
   정책 경기를 적재하려면 이 규칙을 넓혀야 한다.

## 3. 요구사항

### 3.1 기능 요구사항

| ID | 요구사항 |
|---|---|
| FR-1 | **결정 관측 골든** (Kotlin) — EnvGolden 11 케이스 구성 + 진영 플래그 조합 2 케이스에서, 외부 슬롯이 **행동을 결정하는 순간의 관측**만 체인 해시로 묶는다. 환경별 리플레이와 함께 커밋한다 |
| FR-2 | JS `ObsEncoder` — `proto/obs_spec.proto` 의 레이아웃 · 정규화 상수를 따른다. float32 의미(연산마다 반올림, `-0` 금지)까지 Kotlin 과 같다 |
| FR-3 | JS 가 `obs_spec.proto` 를 읽어 필드 목록 · 레이아웃 해시를 대조한다 (Kotlin · Python 과 같은 방식, 세 번째 소비자) |
| FR-4 | JS `deriveSeed` + `DerivedSeeds(baseSeed, envIndex, startRally)` 시드 공급원 — Kotlin `PikaEnv.deriveSeed` 와 비트 일치 |
| FR-5 | ONNX export 도구 (`pika_trainer/export_onnx.py`) — 정책 헤드(actor)만, 입력 `obs[B, obs_dim]` → 출력 `logits[B, 18]`. 메타데이터에 체크포인트 SHA-256 · obs_dim · 관측 레이아웃 해시를 싣는다 |
| FR-6 | export 직후 자기 검증 — onnxruntime(Python)으로 다시 읽어 torch 와 로짓 · argmax 를 대조한다. 실패하면 파일을 남기지 않는다 |
| FR-7 | 정책 레지스트리 `runs/policies/registry.jsonl` — `(label, 체크포인트 경로 · SHA-256, ONNX SHA-256, obs_dim, 레이아웃 해시, torch · onnx · opset 버전, git)` 한 줄씩 |
| FR-8 | `PolicySource` — `kind: 'external'`. 관측 → ONNX → **argmax** → `EdgeTrigger` → 엔진 입력. 결정은 `runEngineForNextFrame` 직전 · 랠리 리셋 후 (Kotlin 과 같은 자리) |
| FR-9 | `GameRunner` 에 비동기 결정 준비 단계 — 기존 동기 경로(리플레이 · FSM · 사람 · 스크립트)는 **동작이 바뀌지 않는다** |
| FR-10 | M5-b 재현 하네스 (Node) — 평가 리플레이 묶음을 받아 각 게임을 `PolicySource + FsmSource + DerivedSeeds` 로 **새로 치르고**, 기록 바이트를 원본과 비교한다. 불일치 시 첫 프레임 · 그 프레임의 로짓 여유를 보고한다 |
| FR-11 | `analysis serve` — 정책 목록(`GET /api/policies`) · ONNX 바이트(`GET /api/policies/<onnx_sha>.onnx`), 라이브 제출에 **External 슬롯별 참가자 주장**을 받는다 (`human` 또는 레지스트리의 ONNX SHA). 레지스트리에 없는 주장은 거절한다 |
| FR-12 | 정책 참가자는 **체크포인트 SHA-256 으로** 적재된다 — 평가 기준선(`track-a-seedN`)과 같은 `participant` 행을 가리킨다 |
| FR-13 | `verify-policy` (Node) — 적재된 리플레이의 정책 슬롯 입력을 ONNX 재추론으로 다시 만들어 전부 일치하는지 본다. 서버가 믿고 받은 참가자 주장을 사후에 검증하는 수단이다 |
| FR-14 | 뷰어 — 설정 화면의 "정책" 활성화 (레지스트리에서 선택), 좌 · 우 어디든 · 정책 vs 정책 포함. 브라우저가 받은 ONNX 의 SHA-256 을 레지스트리와 대조하고 어긋나면 시작하지 않는다 |
| FR-15 | 뷰어 — 시드 모드 "평가 경기 재현" (`baseSeed, envIndex, startRally, 첫 서브`) · 기본은 기존 새 난수 |
| FR-16 | 브라우저 추론 지연 측정 — 프레임당 ms (p50 · p99 · 최대), 첫 세션 생성 시간 |

### 3.2 비기능 요구사항

| ID | 요구사항 |
|---|---|
| NFR-1 | **학습 · 평가 경로는 한 줄도 바뀌지 않는다.** `ObsEncoder.kt` · `PikaEnv` · `evaluate.py` 무수정. Phase 1 골든 615 · env 골든 11 · 리플레이 골든 12 **불변** |
| NFR-2 | 리플레이 형식 v1 무변경. 정책 경기도 같은 바이트 형식이고, "누가 뒀는가" 는 manifest · DB 에만 있다 |
| NFR-3 | `PolicySource` 는 JS 전역 `rand()` 를 부르지 않는다 (Phase 4 기록 3번). argmax 만 지원한다 — 샘플링은 범위 밖 |
| NFR-4 | 버전 고정 — `onnxruntime-web` **1.30.0** (정확 일치), `onnx` 1.23.0 · `onnxruntime` 1.30.0 (Python, export 전용 의존성 그룹), opset **17**, 레거시 exporter. ORT 는 `numThreads = 1` |
| NFR-5 | 추론 지연 — 브라우저에서 프레임당 p99 ≤ **5 ms** (25fps 예산 40 ms 의 1/8) |
| NFR-6 | ONNX · 가중치는 로컬 `serve` 에서만 받는다. 뷰어는 여전히 배포 대상이 아니다 (Phase 4 NFR-4) |
| NFR-7 | 브라우저와 Node 가 **같은 ORT 진입점 · 같은 wasm** 을 쓴다 — Node 에서 증명한 것이 브라우저에도 성립해야 한다 |

## 4. 완료 조건 (Exit Criteria)

ROADMAP 의 M5-a~e 를 측정 가능한 형태로 옮긴 것이다. ROADMAP 원문과 달라진 곳은 ⚑ 로 표시하고 이유를 적는다.

| ID | 지표 | 목표치 |
|---|---|---|
| M5-a ⚑ | JS **결정 관측 체인** = Kotlin 결정 관측 체인 | **13 케이스 100%** (EnvGolden 11 구성 + 진영 플래그 × {오른쪽, 양쪽} 2). 미러링 · 진영 플래그 · 착지점 포함 |
| M5-b | 브라우저 정책 vs FSM 경기 = 같은 시드의 Kotlin 평가 경기 | Node: **2,400 / 2,400 게임 리플레이 바이트 일치** (Track A 3시드 × 양 진영 × 400). 브라우저(headless Chrome): 시드별 양 진영 1게임씩 6게임 일치 |
| M5-c | 정책이 낀 라이브 경기의 적재 · 재생 | 정책 vs FSM · 정책 vs 사람 · 정책 vs 정책 각 1게임 이상이 제출 → Kotlin 재생 검증 → 적재 → 뷰어 재생 ✓, 참가자가 체크포인트 SHA-256 으로 기준선과 같은 행. `verify-policy` 로 정책 슬롯 입력 **100% 재현** |
| M5-d | 체크포인트 ↔ ONNX SHA-256 쌍 | Track A 3시드의 쌍이 레지스트리와 ROADMAP 에 기록됨. 같은 체크포인트를 두 번 export 하면 **같은 ONNX SHA-256** |
| M5-e | ROADMAP "이후 Phase 가 반드시 알아야 하는 것" | 최소 항목: JS/Kotlin 관측 차이와 원인, 브라우저 추론 지연(ms), 고정한 ORT · opset 버전, argmax 여유 분포, **Phase 6 착수 전 체크리스트** (M6-c 배선이 아직 없다는 사실 포함) |
| M5-f | 회귀 | `./gradlew build` · pytest · `npm test` 초록, NFR-1 골든 3종 불변 |

⚑ **M5-a 가 ROADMAP 과 다른 점.** ROADMAP 은 "Kotlin 관측 골든 11 케이스 100%" 인데, 그 골든은 보상과 함께
해시돼 있어 JS 가 관측만으로는 같은 해시를 만들 수 없다 (§2-1). 보상 계산을 JS 에 이식하는 것은 브라우저에
필요 없는 코드를 증명용으로만 만드는 일이다. 그래서 **같은 11 구성에서 관측만 뽑은 골든**으로 대조하고,
실제 정책 구성을 덮지 못하는 공백(§2-2)을 메우는 2 케이스를 더한다. 기존 env 골든은 건드리지 않는다.

**M5-a · M5-b 는 타협 불가다.** M5-b 에서 한 게임이라도 어긋나면 하네스가 낸 첫 불일치 프레임을 본다.
그 프레임의 로짓 여유가 2 × (측정한 백엔드 오차 상한) = **1.6 × 10⁻⁵** 이상이면 수치 문제가 아니라 버그다 —
관측 · 결정 시점 · 엣지 트리거 · 시드 중 무엇이 틀렸는지 찾기 전까지 완료라고 부르지 않는다.
여유가 그보다 작은 **수치 동률**이면 그 게임 목록과 여유 값을 기록하고 사용자와 처리 방법을 정한다 —
조용히 목표를 낮추지 않는다.

**합의 (2026-09-24)** — 검토에서 세 가지를 확정했다: (1) M5-a 를 "관측 전용 골든 13 케이스" 로 바꾼다,
(2) 수치 동률로 M5-b 가 갈라지면 목표를 낮추지 않고 멈춰서 보고한다, (3) 축소 픽스처용 Track A seed0 ONNX(97 KB)를
커밋한다 — 저장소에 가중치를 넣는 첫 사례이고, 우리가 학습한 가중치라 업스트림 라이선스 문제는 없다.

## 5. 산출물

```
engine-kotlin/env/src/main/kotlin/pika/env/ObsGolden.kt     결정 관측 골든 생성 · 검증 (신규)
engine-kotlin/env/src/test/kotlin/pika/env/ObsGoldenTest.kt
engine-kotlin/env/golden/obs/                               13 케이스 × 환경별 리플레이 + chains.json (신규, 커밋)
trainer-python/src/pika_trainer/export_onnx.py              export + 자기 검증 + 레지스트리 (신규)
trainer-python/tests/test_export_onnx.py
trainer-python/pyproject.toml                               + dependency group `export` (onnx, onnxruntime)
engine-kotlin/analysis/.../Serve.kt                         + /api/policies, 라이브 참가자 주장
engine-kotlin/analysis/.../PolicyRegistry.kt                레지스트리 읽기 (신규)
viewer-web/src/policy/obs.mjs                               JS ObsEncoder (신규)
viewer-web/src/policy/obs-spec.mjs                          obs_spec.proto 파서 · 레이아웃 해시 (신규)
viewer-web/src/policy/model.mjs                             ORT 세션 · argmax (신규)
viewer-web/src/sources/policy.mjs                           PolicySource (신규)
viewer-web/src/runner/seeds.mjs                             + deriveSeed, DerivedSeeds
viewer-web/src/runner/runner.mjs                            + beginFrame / prepare 단계
viewer-web/src/view/setup.js · live.js                      정책 선택 · 재현 시드 모드
viewer-web/test/obs.test.mjs                                M5-a
viewer-web/test/policy-parity.mjs                           M5-b 하네스 (runs/ 필요 — 로컬 전용)
viewer-web/test/verify-policy.mjs                           M5-c 사후 검증
viewer-web/test/fixtures/policy/                            Track A seed0 ONNX + 평가 리플레이 4개 (npm test 용 축소판 M5-b)
scripts/export-policies.sh                                  Track A 3시드 export + 레지스트리
ROADMAP.md                                                  Phase 5 결과 · 이후 Phase 가 알아야 하는 것
```

## 6. 범위 밖 (Out of Scope)

| 항목 | 미루는 곳 |
|---|---|
| 정책 샘플링 모드 (브라우저) | 하지 않는다. 필요해지면 자기 PRNG 로 (Phase 4 기록 3번). 평가의 주 지표가 argmax 이므로 M5-b 도 argmax 만 |
| Kotlin 에서의 정책 vs 정책 평가 | Phase 6 (`league.py`). 브라우저 정책 vs 정책은 **되지만** Kotlin 대조 대상이 없으므로 M5-c 의 `verify-policy` 로만 검증한다 |
| 학습 중 평가 리플레이 적재 (M6-c) | Phase 6. 이 작업은 **배선이 아직 없다는 사실**을 M5-e 체크리스트에 적는 것까지 |
| 서버에 프레임마다 추론을 묻는 방식 (WebSocket) | 쓰지 않는다 (ROADMAP Phase 5) |
| ONNX rollout (학습 추론을 ONNX 로) | Phase 9, 병목이 측정된 뒤에만 |
| 관측 · 행동 규약 변경 (행동 미러링 등) | 하지 않는다. Phase 3 기록 5번 — 바꾸면 골든이 깨지고 Track B 와 예산 비교가 무너진다 |
| 가치 헤드(critic)의 export | 하지 않는다. 브라우저는 행동만 필요하다. 가치 시각화가 필요해지면 그때 출력 하나를 더한다 |
| WebGPU · WebGL 실행 백엔드 | 쓰지 않는다. wasm 단일 스레드만 — 망이 작아 이득이 없고, 백엔드마다 부동소수 결과가 달라 M5-b 가 백엔드의 함수가 된다 |

## 7. 리스크

| 리스크 | 대응 |
|---|---|
| JS 관측이 float32 의미에서 갈라진다 (연산 순서 · 이중 반올림 · `-0`) | 연산마다 `Math.fround`, 부호 반전은 정수에서 하고 `\| 0` 으로 `-0` 제거 (`plan.md` §4.2). M5-a 가 비트 단위로 잡는다 |
| 결정 시점이 1프레임 어긋난다 (랠리 리셋 전 관측으로 결정) | 러너의 `beginFrame()` 이 리셋을 먼저 적용하고 그 뒤에 준비 단계를 연다 (§7.2). M5-a 골든이 **결정 시점의** 관측만 담으므로 시점 오류도 잡는다 |
| argmax 동률 근처에서 백엔드 간 결정이 뒤집힌다 | 측정 범위에선 0건 (§2.4). 하네스가 불일치 프레임의 여유를 보고해 버그와 수치 동률을 가른다 (§4 규칙). Phase 6 체크포인트마다 여유 분포를 다시 재도록 M5-e 에 적는다 |
| 브라우저 wasm 과 Node wasm 의 결과가 다르다 | 같은 진입점 · 같은 wasm (NFR-7), 스레드 1, SIMD 는 ORT 기본. headless Chrome 6게임으로 확인 (M5-b 브라우저 절반) |
| 레거시 exporter 가 torch 업그레이드로 사라진다 | `uv.lock` 이 torch 를 고정한다. export 결정론 테스트가 exporter 변경을 즉시 잡는다. 사라지면 `onnx.helper` 로 5-노드 그래프를 직접 쓰는 안이 있다 (`plan.md` §5.1 표) |
| 비동기 준비 단계가 기존 동기 경로를 바꾼다 | 준비 단계는 `prepare` 를 가진 입력원이 있을 때만 쓰인다. Phase 4 테스트(`npm test` 20개) · M4-j 체인이 그대로 초록이어야 한다 |
| ORT 가 전역 상태나 `Math.random` 을 써서 물리 RNG 를 건드린다 | ORT 는 업스트림 `rand()` 를 모른다. 그래도 M4-e 와 같은 격리 테스트를 정책 경기에 한 번 더 건다 |
| 서버가 거짓 참가자 주장을 받는다 (사람 경기를 정책 경기로) | 레지스트리에 없는 ONNX SHA 는 거절 (FR-11). 있는 SHA 로 거짓 주장한 경기는 `verify-policy` 가 잡는다 (FR-13). 로컬 단일 사용자 도구라 그 이상은 하지 않는다 |
| `onnxruntime-web` 패키지가 크다 (node_modules 139 MB, 실제 로드 wasm 14 MB) | 로컬 도구라 수용한다. 뷰어는 정책을 고를 때만 ORT 를 동적 import 한다 — 리플레이만 보는 경로는 무게가 늘지 않는다 |
