# 2026-09-25 — live-policy (ROADMAP Phase 5)

학습한 체크포인트를 ONNX 로 내보내 브라우저 경기 러너의 **정책 입력원(`PolicySource`)** 으로 꽂는 작업.
이것으로 사람 · FSM · 리플레이 · 정책 네 입력원이 임의 조합으로 대전하고, 정책이 낀 라이브 경기도 검증 · 적재 · 재생된다.

| 문서 | 답하는 질문 |
|---|---|
| [`PRD.md`](PRD.md) | 무엇을 만족해야 완료인가 |
| [`plan.md`](plan.md) | 어떻게 할 것인가 (판단과 근거, 측정값) |
| [`tasks.md`](tasks.md) | 무엇부터 할 것인가 (P1~P7 체크리스트 · 결과) |

이 작업의 본질은 브라우저 추론이 아니라 **"브라우저의 정책이 평가받은 그 정책과 같은 수를 둔다" 는 증명**이었다.
관측이 1비트 달라도, 결정 시점이 1프레임 어긋나도, 추론이 동률 근처에서 뒤집혀도 정책은 조용히 다른 게임을 하고,
그 사실은 승률 하락으로만 드러난다. 그래서 관측을 먼저 비트 단위로 고정하고(M5-a), 그다음 평가 경기 2,400개를
브라우저와 같은 코드로 다시 치러 리플레이 바이트를 비교했다(M5-b). 규칙을 일부러 망가뜨려(부호 반전 · 미러 ·
진영 플래그 · 첫 서브 · 랠리 번호 · 엣지 트리거) 테스트가 각각 원인 쪽을 가리키는지도 확인했다.

## 결과

| ID | 지표 | 목표 | 결과 |
|---|---|---|---|
| M5-a | JS 결정 관측 체인 = Kotlin | 13 케이스 | ✅ 13 / 13 |
| M5-b | 정책 vs FSM 재현 = Kotlin 평가 리플레이 바이트 | Node 2,400 · 브라우저 6 | ✅ 2,400 / 2,400 (수치 동률 0) · headless Chrome 6 / 6 |
| M5-c | 라이브 적재 · 재생 · 사후 검증 | 3종 각 1게임+, 100% | ✅ 정책 vs FSM · 사람 vs 정책 · 정책 vs 정책, `verify-policy` 100% |
| M5-d | 체크포인트 ↔ ONNX SHA-256, 결정론 | 3쌍 · 재export 동일 | ✅ ROADMAP Phase 5 표 |
| M5-e | 인계 기록 | PRD §4 항목 | ✅ ROADMAP "이후 Phase 가 반드시 알아야 하는 것" · "Phase 6 착수 전 체크리스트" |
| M5-f | 회귀 + 골든 불변 | 초록 | ✅ Kotlin 146 · pytest 148 · `npm test` 66, 골든 3종 diff 0 |

브라우저 추론 지연: 평균 38 µs · p99 0.2 ms · 최대 4.8 ms (NFR-5 p99 ≤ 5 ms), 첫 세션 생성 162 ms.

## 계획과 달라진 것

1. **M5-a 는 관측 전용 골든 13 케이스** — 기존 env 골든은 보상과 함께 해시돼 있고, Track A 의 오른쪽 평가 구성
   (미러 + 진영 플래그)을 덮지 않았다. 검토 단계에서 합의 (PRD §4).
2. **ORT JS 에 모델 메타데이터 API 가 없다** — `model.mjs` 가 ModelProto 필드 14 를 직접 읽는다 (60줄).
3. **불일치 판정에 `header` 단계를 앞에 더했다** — 첫 서브 · 슬롯 · edgeTrigger 오류는 헤더 플래그에서 바로 드러난다.
   원본 바이트가 엣지 변환 후라서 `bug:edge` 를 따로 가른다.
4. **라이브 manifest 에 `checkpoint` · `onnx`** — Phase 4 manifest 는 kind · label 만 적어, DB 를 지우고 되살리면
   정책 참가자가 기준선과 다른 행이 됐을 것이다. 함께 고쳤다.
5. **뷰어 라이브 루프는 펌프 하나** — 틱 콜백은 동기라 밀린 프레임 수를 쌓고 펌프 하나가 `await` 로 소화한다.
   추론 await 중에 다음 틱이 와도 두 번째 펌프가 겹치지 않는다.
6. **Phase 4 인계 기록의 "M6-c 는 한 줄" 은 틀렸다** — 학습 루프는 `evaluate_target` 이 아니라 `evaluate_policy` 를
   직접 부른다. ROADMAP Phase 6 착수 전 체크리스트에 정확히 적었다.

## 재현

```bash
scripts/export-policies.sh                                   # 레지스트리 · ONNX SHA 가 ROADMAP 표와 같아야 한다
node viewer-web/test/policy-parity.mjs runs/baselines/track-a-seed0 --base-seed 0   # 전수 M5-b (runs/ 필요)
cd viewer-web && npm test                                    # M5-a · 축소 M5-b · verify (upstream/ 필요)
```

브라우저 확인 스크립트(`m5b-browser.mjs` · `m5c-live.mjs`)와 스크린샷은 `runs/viewer-check/` 에 있다 (커밋하지 않음).
