# ROADMAP

## 최종 목표

| # | 목표 | 트랙 | 지표 |
|---|---|---|---|
| 1 | RL binary 로 FSM(컴퓨터)을 이긴다 | Track A | vs FSM 승률 ≥ 90% |
| 2 | zero 학습으로 AI 끼리 대전시킨다 | Track B | 학습 중 FSM 미사용 |
| 3 | 그 결과로 다시 FSM 과 대전한다 | Track B 평가 | vs FSM 승률 ≥ 70% (held-out) |

- **Track A**: 랜덤 초기화 → FSM 상대로 학습.
- **Track B**: 랜덤 초기화 → 자기 자신/과거 체크포인트하고만 학습. **FSM 을 학습 중 한 번도 보지 않는다.**
- 두 트랙은 **환경 스텝 예산을 동일하게** 맞춘다. 맞추지 않으면 비교가 성립하지 않는다.

---

## 전체 아키텍처

```
┌──────────────────────────────────────────────────────────────┐
│                     trainer-python (PyTorch)                  │
│        PPO · Track A/B 러너 · 셀프플레이 리그 · ONNX          │
└───────────────┬──────────────────────────────┬───────────────┘
                │ gRPC (batched step)          │ 메트릭 / 체크포인트
                ▼                              ▼
┌───────────────────────────────┐   ┌──────────────────────────┐
│        engine-kotlin          │   │  MLflow (backend: MySQL) │
│  ┌─────────────────────────┐  │   │  체크포인트 → 볼륨/PVC    │
│  │ core     물리 엔진 포팅  │  │   └──────────────────────────┘
│  │ env      관측/행동/보상  │  │
│  │ server   gRPC 벡터 환경  │  │   ┌──────────────────────────┐
│  │ analysis 통계/리플레이   │  │──▶│  MySQL 8.4               │
│  └─────────────────────────┘  │   │  경기 지표 · 리플레이     │
└───────────────────────────────┘   └──────────────────────────┘
                                               │
                                               ▼
                                    ┌──────────────────────────┐
                                    │        viewer-web        │
                                    │ 시드+입력 → 결정론적 재생 │
                                    └──────────────────────────┘
```

**핵심 설계 두 가지**

- **물리 엔진을 Kotlin 으로 포팅한다.** `physics.js` 는 원본 기계어를 옮긴 코드라 전부 32비트 정수
  연산이므로 1:1 변환이 가능하고, JS 를 정답(oracle)으로 둔 차분 테스트로 동치성을 증명할 수 있다.
  JVM 벡터화로 처리량을 확보하고, 엔진이 시뮬레이션과 분석을 함께 담당한다.
- **결정론.** RNG 를 주입식 정수 PRNG 로 교체하면 `(시드, 입력 시퀀스)` 만으로 경기 전체가 재현된다.
  차분 테스트·리플레이 저장·평가 재현성이 모두 여기서 나온다.

---

## 구성품

| 구성품 | 기술 | 역할 |
|---|---|---|
| `engine-kotlin/core` | Kotlin/JVM 21 | `physics.js` 포팅. 순수 함수, 외부 의존성 0 |
| `engine-kotlin/env` | Kotlin | 관측/행동/보상/미러링, 라운드 관리, 벡터 환경 |
| `engine-kotlin/server` | Kotlin + gRPC | 배치 스텝 RPC, 헬스체크/메트릭 |
| `engine-kotlin/analysis` | Kotlin | 경기 통계, 리플레이 인코딩, MySQL 적재 |
| `engine-kotlin/conformance` | Kotlin + Node | JS 차분 테스트 하네스 |
| `trainer-python` | Python 3.11, PyTorch, Gymnasium | PPO, Track A/B 러너, 리그, ONNX export |
| `viewer-web` | PixiJS | 리플레이 재생 |
| `proto/` | protobuf | State Spec 및 gRPC 계약 (언어 간 단일 진실 공급원) |
| MySQL 8.4 | — | 경기 지표 · 리플레이 · MLflow backend store |
| `deploy/compose` · `deploy/k3s` | Docker / k3s | Phase 0~6 compose, Phase 7 k3s |

---

## 제약

- **업스트림 라이선스 미부여.** `LICENSE` 없음, `package.json` 은 `UNLICENSED`, GitHub API `license: None`.
  원작(1997) 저작권자는 SACHI SOFT / SAWAYAKAN Programmers, Satoshi Takenouchi.
  → `upstream/` 은 커밋하지 않고 고정 커밋 fetch 스크립트로 받는다. 원작 에셋은 커밋 금지.
  → 포팅본도 2차적 저작물이므로 라이선스 상황은 동일하다. 포팅의 근거는 성능과 아키텍처다.
- **`physics.js` 는 전부 32비트 정수 연산.** 나눗셈 7곳 전부 `| 0` 절삭, `Math` 는 `abs` 뿐,
  소수점 리터럴 0건. 포팅 시 `Long` 이 아닌 `Int` 를 써야 `| 0` 의 래핑 동작까지 일치한다.
- **게임 FPS 25, 승점 15.** 에피소드는 랠리 단위, 게임 단위는 평가에만 사용.

---

## Phase

| Phase | 목표 | 산출 구성품 | 완료 조건 |
|---|---|---|---|
| **0** | 저장소 기반과 빌드 골격 | `.gitignore`, `README.md`, `scripts/`, Gradle/uv 골격, CI | `gradlew build` · `pytest` 통과 |
| **1** | 물리 엔진 동치성 확보 | `core`, `conformance`, `proto`(State Spec) | 상태 해시 100% 일치, 분기 커버리지 ≥ 95% |
| **2** | RL 환경과 학습 파이프라인 연결 | `env`, `server`, `env_client.py`, compose | ≥ 50,000 step/s, Gymnasium 규약 준수 |
| **3** | Track A — FSM 이기기 | `ppo.py`, `track_a.py`, 평가 스크립트 | vs FSM 승률 ≥ 90% (시드 ≥ 3) |
| **4** | Track B — zero 셀프플레이 | `track_b.py`, `league.py` | vs FSM 승률 ≥ 70% (held-out) |
| **5** | 두 트랙 비교 | 비교 리포트 | A vs B 대결 + 학습 곡선 분석 완료 |
| **6** | 분석과 리플레이 | `analysis`, MySQL 스키마, `viewer-web` | 임의 경기를 브라우저에서 재현 |
| **7** | 수평 확장 | `deploy/k3s`, (필요 시) ONNX rollout | replica 증가 시 처리량 선형 증가 |

### Phase 0 — 저장소 기반

저장소 규칙(라이선스 대응 포함)과 Kotlin/Python 빌드 골격을 세운다.
업스트림은 고정 커밋으로 받아오되 커밋하지 않는다.

### Phase 1 — 물리 엔진 포팅 + 차분 테스트

`physics.js` 를 Kotlin 으로 포팅하고, JS 를 정답으로 둔 차분 테스트로 동치성을 증명한다.
**이 Phase 가 실패하면 이후 모든 학습 결과가 "원본과 다른 게임"의 결과가 된다.**
상태 해시로 전수 비교하고, 불일치 시 체인 해시 이분 탐색으로 최초 불일치 프레임을 특정한다.

### Phase 2 — RL 환경 + gRPC

관측/행동/보상/미러링과 라운드 관리를 붙여 RL 환경을 만들고, 배치 스텝 gRPC 로 Python 과 연결한다.
`powerHit` 은 엣지 트리거이므로 환경 래퍼에서 변환한다.

### Phase 3 — Track A

랜덤 초기화에서 PPO 로 FSM 을 상대한다. boldness 커리큘럼 0 → 4.
셰이핑 보상은 초반에만 켜고 어닐링한다.

### Phase 4 — Track B

**다시 랜덤 초기화에서 시작.** 상대 풀은 과거 체크포인트 70% / 현재 정책 30%, FSM 0%.
학습 루프에서 FSM 접근을 코드 레벨로 차단한다.
랜덤 정책끼리는 공을 못 쳐 보상 신호가 없으므로 셰이핑 보상이 필수이고 어닐링을 훨씬 늦춘다.

### Phase 5 — 두 트랙 비교

Track A vs Track B 직접 대결, 동일 스텝 예산 학습 곡선, 플레이 스타일 차이를 정리한다.

### Phase 6 — 분석 + 리플레이

랠리 길이 분포, 착지 지점 히트맵, 파워히트 성공률을 MySQL 에 적재하고 뷰어로 재생한다.
리플레이는 `(seed, inputSeq[2][T], metadata)` 로 경기당 수 KB.

### Phase 7 — k3s

compose 구성을 k3s 매니페스트로 옮기고 엔진을 수평 확장한다.
스텝 RPC 가 병목이면 ONNX rollout 방식으로 전환한다.
