# tasks — 라이브 대전: 정책 입력원 (ROADMAP Phase 5)

관심사 단위로 나눈다. 여기의 P1~P7 은 이 작업 안의 단계이고 ROADMAP 의 Phase 번호와 무관하다.

**의존 관계**
```
P1 ─▶ P2 ─┐
          ├─▶ P4 ─▶ P5 ─▶ P6 ─▶ P7
P3 ───────┘
```
P3(export)은 P1·P2 와 독립이라 병행할 수 있다.

## P1. 결정 관측 골든 (Kotlin)

> JS 관측을 대조할 정답을 만든다. 정책이 **행동을 정하는 순간의** 관측만 담는다. (FR-1, M5-a 의 기준, NFR-1)

- [x] `env/ObsGolden.kt` — `EnvGolden.CASES` 11 구성 + `side-flag-right` · `side-flag-both` (`plan.md` §3.2)
- [x] 결정 관측 수집 — autoreset 스텝이 내보낸 관측을 "다음 결정" 으로 잇는다, terminal 관측 제외 (§3.1, §3.3 ⚠️)
- [x] 환경별 `ReplayRecorder` (RALLY) — 게임마다 한 파일, 케이스 끝의 진행 중 게임은 상한 컷
- [x] `golden/obs/chains.json` + `.pkr` 생성, `writeObsGolden` Gradle 태스크
- [x] `ObsGoldenTest` — 체인 · 리플레이 바이트 · 케이스 목록이 EnvGolden 이름을 전부 포함 (§3.4)
- [x] 테스트: `side-flag-right` 가 실제로 미러 + 진영 플래그 +1 을 밟는다 (관측 한 개를 꺼내 필드로 확인)
- [x] 테스트: 결정 수 = 물리 프레임 수 (autoreset 스텝을 세지 않는다)
- [x] **확인**: `./gradlew :engine-kotlin:env:test` 초록, `env-chain-hashes.txt` · Phase 1 골든 · 리플레이 골든 **파일 무변경** (`git diff --stat`)

## P2. JS 관측 · 시드 유도 (M5-a)

> 추론 없이 증명할 수 있는 것을 먼저 닫는다. (FR-2 ~ FR-4, M5-a)

- [x] `src/policy/obs-spec.mjs` — `obs_spec.proto` 파서 (`@optional:` 포함) · 필드 목록 · 레이아웃 해시 (§4.3)
- [x] 테스트: 네 플래그 조합의 레이아웃 해시 = Kotlin `ObsSpec.layoutHash` (골든 파일 헤더의 16자 · 레지스트리 값과 대조)
- [x] 테스트: 정규화 상수 = proto 주석의 숫자 (주석 파싱)
- [x] `src/policy/obs.mjs` — `ObsEncoder` (§4.2): 연산마다 `Math.fround`, 부호 반전은 정수 `neg()`
- [x] 테스트: 미러된 정지 공(`xVelocity = 0`) · `divingDirection = 0` → float32 비트가 `+0` (§2.3)
- [x] `runnerFromReplay` 에 입력원 감싸기 훅 `wrap` (동작 무변경)
- [x] `test/obs.test.mjs` — 13 케이스 결정 관측 체인 대조 (§3.3 스케치)
- [x] `src/runner/seeds.mjs` — `deriveSeed` (`Math.imul` · `>>>` · `| 0`) + `DerivedSeeds` (§8.1)
- [x] 테스트: `deriveSeed` — Kotlin 에서 뽑은 표와 대조 (음수 base · 큰 env · k = 0)
- [x] 의도적 파손 확인: `neg()` 를 `-v` 로 · 미러 조건 반전 · 진영 플래그에 미러 적용 → 각각 테스트가 빨개지는지 보고 되돌린다
- [x] **확인**: `npm test` 초록 — **M5-a 13 / 13**

## P3. ONNX export · 레지스트리 (M5-d)

> 체크포인트에서 브라우저가 읽을 파일을 결정론적으로 만든다. (FR-5 ~ FR-7, NFR-4, M5-d)

- [x] `pyproject.toml` — dependency group `export`: `onnx==1.23.0`, `onnxruntime==1.30.0` (`uv.lock` 갱신)
- [x] `export_onnx.py` — actor 만, 레거시 exporter, opset 17, `dynamic_axes`, 메타데이터 4개 (§5.2)
- [x] 레이아웃 인자 (`--obs-layout`, 기본 `for_policy()`), `dim ≠ obs_dim` 이면 실패
- [x] 자기 검증 — 서버 띄워 진영별 40게임 관측 수집 → ORT(Python) vs torch 로짓 ≤ 10⁻⁴ · argmax 100% · 여유 분포 (§5.3)
- [x] 레지스트리 `runs/policies/registry.jsonl` — 같은 label 재export 시 규칙 (§5.4)
- [x] 테스트: 같은 체크포인트 두 번 export → **같은 SHA-256** (메타데이터 포함)
- [x] 테스트: 망 구조를 일부러 바꾼 가짜 체크포인트 → 자기 검증 실패, 파일 안 남음
- [x] `scripts/export-policies.sh` — Track A 3시드 export
- [x] **확인**: pytest 초록, 레지스트리 3줄 · ONNX SHA 3개 · 여유 분포가 `plan.md` §2.4 표와 같은 범위

## P4. 정책 입력원 · 러너 배선

> 브라우저와 Node 가 같은 코드로 정책을 돌린다. (FR-8, FR-9, NFR-3, NFR-7)

- [ ] `viewer-web/package.json` — `onnxruntime-web` **1.30.0** 정확 고정
- [ ] 확인: Node 에서 `onnxruntime-web/wasm` 진입점이 도는가 (§2.5 ⚠️). 안 되면 여기서 멈추고 `plan.md` 를 고친다
- [ ] `src/policy/model.mjs` — 세션 생성(`numThreads = 1`, wasm), 메타데이터 읽기, `argmax` (첫 최댓값, `>` 비교, §7.3)
- [ ] `runner.mjs` — `beginFrame()` 분리, `step()` 이 맨 앞에서 호출 (§7.2)
- [ ] `src/runner/live-loop.mjs` — `advance(runner)` / `playLive(...)` (Node · 브라우저 공용)
- [ ] `action.mjs` — 누른 상태를 내는 디코더 + `EdgeTrigger` JS 판 (§7.3 ⚠️)
- [ ] `src/sources/policy.mjs` — `prepare` · `decide`(prepare 없으면 예외) · `onRallyStart` 엣지 리셋
- [ ] 테스트: Phase 4 테스트 20개 무변경 초록 (`beginFrame` 분리가 동작을 바꾸지 않는다)
- [ ] 테스트: 정책 경기 렌더링 격리 — 뷰 RNG 를 끼워도 체인 동일 (M4-e 와 같은 형식, NFR-3)
- [ ] 테스트: 레이아웃 해시 · obs_dim 이 ONNX 메타와 다르면 `PolicySource` 생성 실패
- [ ] **확인**: `npm test` 초록

## P5. 평가 경기 재현 (M5-b)

> "브라우저의 정책 = 평가받은 정책" 을 2,400게임 전수로 증명한다. (FR-10, M5-b)

- [ ] `test/policy-parity.mjs` — manifest → env 별 게임 순서 → 첫 랠리 번호 누적 → `playLive` → 바이트 비교 (§8.2)
- [ ] 참가자 대조 — manifest 의 체크포인트 SHA = 레지스트리 SHA 가 아니면 시작하지 않는다
- [ ] 불일치 보고 — 시드 목록 먼저, 그다음 첫 입력 불일치 프레임 · 로짓 여유 · 판정 (§8.3)
- [ ] 의도적 파손 확인: 첫 서브 규칙 반전 · 첫 랠리 번호 0 고정 · 엣지 트리거 제거 → 각각 하네스가 원인 쪽을 가리키는지
- [ ] Track A seed 0 · 1 · 2 전수 실행, 소요 시간 기록
- [ ] 축소 픽스처 `test/fixtures/policy/` — seed0 ONNX + 양 진영 2게임씩 리플레이 + manifest 조각, `npm test` 에 편입
- [ ] **확인**: **2,400 / 2,400 바이트 일치** — 불일치가 있으면 `PRD.md` §4 의 규칙대로 (버그면 고치고, 수치 동률이면 멈추고 보고)

## P6. 적재 · 사후 검증 · 뷰어 (M5-c)

> 정책이 낀 라이브 경기가 기록으로 남고, 그 기록이 정말 그 정책의 수인지 다시 확인할 수 있다. (FR-11 ~ FR-16, M5-c)

- [ ] `analysis/PolicyRegistry.kt` — 레지스트리 읽기, ONNX SHA → (체크포인트 SHA, label, 파일)
- [ ] `serve --policies runs/policies` — `GET /api/policies`, `GET /api/policies/<onnx_sha>.onnx`
- [ ] `POST /api/live-games?p1=…&p2=…` — 참가자 주장 규칙 표 (§9.1), 레지스트리에 없으면 400
- [ ] `runs/live/manifest.jsonl` 에 `onnx` 필드 — `ingest runs/live --kind live` 가 그대로 읽는지 확인
- [ ] `ServeTest` — 주장 없음 = 기존 동작, 정책 주장 → 기준선과 같은 `participant` 행, 미등록 SHA → 400
- [ ] `test/verify-policy.mjs` — 리플레이 + manifest(또는 게임 id) → 정책 슬롯 입력 재추론 대조 (§9.2)
- [ ] 뷰어 설정 — 정책 옵션 · 목록 · 좌우 독립 (§10)
- [ ] 뷰어 라이브 — ORT 동적 import, ONNX SHA · 메타 대조, `await advance` 루프, 제출에 주장 쿼리
- [ ] 뷰어 시드 모드 "평가 경기 재현"
- [ ] headless Chrome — M5-b 브라우저 6게임 (시드별 양 진영 1게임) 바이트 일치
- [ ] headless Chrome — 추론 지연 p50 · p99 · 최대 · 첫 세션 생성 (§10.1). NFR-5 (p99 ≤ 5 ms) 판정
- [ ] 실경기 — 정책 vs FSM · 정책 vs 사람 · 정책 vs 정책 각 1게임 제출 → 적재 → 재생 ✓ → `verify-policy` 100%
- [ ] **확인**: M5-c 체크리스트 전부, 스크린샷 첨부

## P7. 회귀 · 문서 · 이관 (M5-e, M5-f)

> 휴지 지점. 재개할 때 맥락이 끊기지 않게 남긴다.

- [ ] 회귀: `./gradlew build` · `uv run pytest` · `npm test` 초록
- [ ] NFR-1: `ObsEncoder.kt` · `PikaEnv.kt` · `evaluate.py` 무수정, 골든 3종 파일 무변경 (`git diff` 로 확인)
- [ ] ROADMAP Phase 5 결과 표 (M5-a ~ f) · 체크포인트 ↔ ONNX SHA 쌍 3개
- [ ] ROADMAP "이후 Phase 가 반드시 알아야 하는 것" — JS/Kotlin 관측 차이와 원인 · 추론 지연 · ORT 1.30.0 / opset 17 / 레거시 exporter · argmax 여유 분포와 체크포인트마다 다시 재야 하는 이유
- [ ] ROADMAP **Phase 6 착수 전 체크리스트** — M6-c(학습 중 평가 리플레이 적재) 배선 없음, `evaluate_target(record_dir=…)` 한 줄이라는 사실, A+ 체크포인트 export · 레지스트리 절차, 의심 체크포인트를 라이브로 꽂는 절차
- [ ] `history/<완료일>-live-policy/` 로 `PRD.md` · `plan.md` · `tasks.md` + `README.md` 이관
- [ ] **확인**: 저장소 루트에 세 문서가 없고 history 에 있다

---

## 완료 조건 요약

| ID | 지표 | 목표치 | Phase |
|---|---|---|---|
| M5-a | JS 결정 관측 체인 = Kotlin | 13 / 13 케이스 | P1 · P2 |
| M5-b | 정책 vs FSM 재현 = Kotlin 평가 리플레이 바이트 | Node 2,400 / 2,400 · 브라우저 6 / 6 | P5 · P6 |
| M5-c | 정책 라이브 경기 적재 · 재생 · 사후 검증 | 3종 각 1게임+, `verify-policy` 100% | P6 |
| M5-d | 체크포인트 ↔ ONNX SHA-256 쌍, export 결정론 | 3쌍 기록, 재export 동일 SHA | P3 · P7 |
| M5-e | ROADMAP 인계 기록 | PRD §4 의 최소 항목 전부 | P7 |
| M5-f | 회귀 + 골든 불변 | 초록, 골든 3종 무변경 | P7 |
