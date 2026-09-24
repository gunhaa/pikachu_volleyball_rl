# pikachu-volleyball-rl

피카츄 발리볼 강화학습 프로젝트.

물리 엔진을 Kotlin 으로 포팅하고, JS 원본을 정답(oracle)으로 둔 차분 테스트로
동치성을 증명한 뒤, 그 위에서 PPO 로 정책을 학습한다.

목표는 세 가지다. 자세한 내용은 [`ROADMAP.md`](ROADMAP.md) 를 본다.

1. RL 정책으로 내장 컴퓨터 AI(FSM)를 이긴다
2. FSM 을 한 번도 보지 않고(zero) 셀프플레이만으로 학습한다
3. 그 결과를 다시 FSM 과 붙여 평가한다

---

## 업스트림

포팅의 원본은 [`gorisanson/pikachu-volleyball`](https://github.com/gorisanson/pikachu-volleyball) 이다.
고정 커밋 `0d04dbaf165e4131e26f27f6e9def766f62260b3` 을 `scripts/fetch-upstream.sh` 로 받아 쓴다.

**`upstream/` 과 게임 에셋은 저장소에 커밋하지 않는다.** `.gitignore` 가 차단한다.
차분 테스트의 JS 오라클이 이 디렉터리를 필요로 하므로, 새 환경에서는 fetch 스크립트를 먼저 실행한다.

---

## 셋업

### 요구 사항

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | **21** | Gradle toolchain 이 21 을 찾는다. 더 높은 JDK 로 Gradle 을 돌려도 된다 |
| Node.js | 20+ | JS 오라클 실행용 (차분 테스트에만 필요) |
| [uv](https://docs.astral.sh/uv/) | 최신 | Python 3.12 환경 관리 |
| Docker | 최신 | compose 로 엔진 서버를 띄울 때만 |

### 1. 업스트림 받기

```bash
./scripts/fetch-upstream.sh
```

고정 커밋 `0d04dbaf165e4131e26f27f6e9def766f62260b3` 을 `upstream/` 에 체크아웃한다.
멱등하므로 여러 번 실행해도 안전하고, 받아온 내용은 `git status` 에 나타나지 않는다.

### 2. 빌드와 테스트

```bash
./gradlew build                 # Kotlin 빌드 + 단위 테스트 (골든 회귀 포함)
cd trainer-python && uv sync && uv run pytest
```

Python 테스트는 **진짜 엔진 서버를 띄워서** 붙는다 (`tests/conftest.py`). 그래서 JDK 가 필요하다.
가짜 서버를 세우면 계약을 두 번 구현하게 되고, 그 둘이 맞는지는 아무도 확인하지 않는다.

### 3. 오라클 자체 검증

```bash
cd tools/js-oracle && node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON selfcheck.mjs
```

포팅을 비교하기 전에 "정답 쪽이 흔들리지 않는가" 부터 본다.

### 4. 차분 테스트

```bash
./gradlew conformance                                     # 전량 — 7,015 에피소드 / 4.2M 프레임 / 약 18초
./gradlew conformance --args="--gen fsm --seeds 1..100"   # 일부만
./gradlew conformance --args="--gen targeted"             # 표적 케이스만 (tools/targeted-cases.txt)
./gradlew conformance --args="--reset-probe"              # 엔진 없이 하네스만 검증
./gradlew conformance --args="--strict"                   # sound 필드까지 포함
```

현재 상태: **4,202,280 프레임 전수 일치, 불일치 0건** (엄격 모드 포함).

전수 검사는 Node 와 `upstream/` 이 필요하므로 `build` 에 포함하지 않는다.
CI 는 축소 샘플(615 에피소드)의 체인 해시 골든 회귀만 보고, 이쪽은 **Node 없이** 돈다.

```bash
./gradlew conformance --args="--write-golden"   # 골든 재생성 (JS 오라클에서 새로 받는다)
```

> ⚠️ 골든이 깨졌다고 재생성하면 안 된다. 그건 검증을 통과시키는 게 아니라 무력화하는 것이다.
> 먼저 전수 차분으로 원인을 찾는다.

### 5. 커버리지

```bash
cd tools/js-oracle && npm install     # 최초 1회 (c8)
./scripts/coverage.sh                 # 생성기당 300 시드 + 표적 케이스 전체
./scripts/coverage.sh 1..1000         # 시드 범위 지정
```

`physics.js` 의 분기 커버리지를 잰다. 현재 **문장·분기·함수·줄 전부 100%**.
HTML 리포트는 `coverage/index.html` 에 나온다.

---

## RL 환경 (Phase 2)

### 엔진 서버 띄우기

```bash
./gradlew :engine-kotlin:server:runServer                                  # UDS /tmp/pika-env.sock
./gradlew :engine-kotlin:server:runServer --args="--port 50051"            # TCP
./gradlew :engine-kotlin:server:runServer --args="--uds /tmp/s.sock --port 50051"  # 둘 다
```

컨테이너로:

```bash
docker compose -f deploy/compose/docker-compose.yml up -d --build
PIKA_ENV_TARGET=127.0.0.1:50051 uv run --directory trainer-python pytest
```

> ⚠️ 서버는 **단일 테넌트**다. `VectorEnv` 를 하나만 들고 있으므로 두 번째 클라이언트가
> `Configure` 하면 앞 구성은 사라진다. 그 사고가 조용히 일어나지 않도록 `Configure` 가
> 세션 번호를 주고 `Step`·`Reset` 이 그것을 들고 온다. 낡은 세션은 즉시 실패한다.

### 처리량 재기

```bash
./scripts/bench-env.sh                      # (a)(b)(c) 세 지점을 한 번에
./gradlew :engine-kotlin:env:benchEnv       # (a) env 단독만
./gradlew :engine-kotlin:server:benchLoopback  # (b) gRPC 루프백만
```

숫자 하나만 재면 미달일 때 어디를 고쳐야 할지 알 수 없다. 그래서 셋을 나눠 재고 **간극**으로 읽는다:
`(a)−(b)` = 직렬화 + RPC, `(b)−(c)` = Python 디코딩 + 정책 forward.

측정값은 [`ROADMAP.md`](ROADMAP.md) 의 Phase 2 결과에 있다.

### 관측 레이아웃 확인하기

레이아웃의 단일 정의는 [`proto/obs_spec.proto`](proto/obs_spec.proto) 다. Kotlin·Python 양쪽이
자기 목록을 이 파일과 대조하고, 서버의 `Health` 가 주는 레이아웃 해시를 클라이언트가 자기 해시와
맞춰 본다. 어긋난 서버에 붙으면 **관측이 조용히 뒤섞이는 대신 즉시 실패**한다.

```bash
# Python 쪽에서 본 레이아웃
uv run --directory trainer-python python -c "
from pika_trainer.obs_spec import ObsSpec
s = ObsSpec.load()
print(s.dim(), s.layout_hash()[:16])
print(s.field_names())
"
```

관측·보상이 바뀌면 `engine-kotlin/env/golden/env-chain-hashes.txt` 가 깨진다. 그것이 목적이다.

```bash
./gradlew :engine-kotlin:env:writeEnvGolden   # 골든 재생성
```

> ⚠️ 깨졌다고 습관처럼 재생성하지 않는다. 골든 줄에는 레이아웃 해시가 따로 실려 있어
> **레이아웃이 바뀐 것인지 값이 바뀐 것인지** 구별할 수 있다. 의도한 변경일 때만 갱신하고,
> **무엇을 왜 바꿨는지 커밋 메시지에 적는다.**

---

## 학습 (Phase 3)

랜덤 초기화에서 PPO 로 FSM(컴퓨터)을 이긴다. 결과는
[`ROADMAP.md`](ROADMAP.md) 의 Phase 3 과 `history/2026-09-23-track-a-ppo/` 에 있다.

```bash
scripts/train-track-a.sh                      # 본 학습 (5천만 step, 시드 0 — 약 6분)
SEED=1 scripts/train-track-a.sh               # 시드만 바꿔 재현
STEPS=5000000 scripts/train-track-a.sh        # 짧은 런 (배선 점검)
GAMMA=0.99 RUN_ID=ab-g099 scripts/train-track-a.sh   # A/B
```

> ⚠️ 러너는 **JVM 을 둘** 띄운다. 서버가 단일 테넌트라서 주기 평가가 학습 서버에
> `Configure` 를 부르면 그 자리에서 학습 세션이 죽는다. 이미 떠 있는 서버를 쓰려면
> `PIKA_ENV_TARGET` 과 `PIKA_EVAL_TARGET` 을 **둘 다** 준다.

산출물은 `runs/<run-id>/` 에 쌓인다 (`.gitignore` 대상):

```
config.json      하이퍼파라미터 · 시드 · git 해시
metrics.jsonl    반복당 한 줄 — 손실·KL·진영별 랠리 승률·항별 보상 기여·행동 분포
evals.jsonl      평가 한 번당 한 줄
ckpt-<steps>.pt  가중치 + 옵티마이저 + 재현 정보
```

체크포인트를 게임 단위로 채점한다. **평가는 체크포인트만 있으면 재현된다.**

```bash
scripts/eval-policy.sh runs/track-a-seed0/ckpt-final.pt
scripts/eval-policy.sh --random                              # 기준선 (0/400)
GAMES=400 BOLDNESS=1 scripts/eval-policy.sh runs/.../ckpt-final.pt   # boldness 진단 축
```

> ⚠️ 승률은 **언제나 양 진영에서** 재고 따로 보고한다. 진영은 교락변수다 — 벽 반사 조건
> 한 줄이 비대칭이라 내 진영의 폭이 왼쪽 196px · 오른쪽 216px 로 다르다. 한 진영에서만
> 재면 승률이 실력이 아니라 진영을 재게 된다.

## 리플레이 · 분석 · 뷰어 (Phase 4)

경기를 컴팩트한 리플레이(v1)로 기록하고, **재생으로 검증한 뒤** MySQL 에 적재하고, 브라우저에서
원본 그래픽으로 재생한다. 같은 경기 러너에 FSM · 사람을 꽂으면 라이브 대전이 된다.
결과는 [`ROADMAP.md`](ROADMAP.md) 의 Phase 4 와 `history/2026-09-24-replay-analysis/` 에 있다.

```bash
docker compose -f deploy/compose/docker-compose.yml up -d mysql   # 127.0.0.1:3306, DB 는 캐시다
scripts/baseline-replays.sh            # 기준선 두 벌 기록 · 적재 · 대조 (약 1분, 결정론)
FRESH=1 scripts/baseline-replays.sh    # 볼륨을 지우고 빈 DB 에서
```

평가하면서 리플레이를 남기고 적재하기:

```bash
cd trainer-python && uv run python -m pika_trainer.evaluate \
  --checkpoint ../runs/track-a-seed0/ckpt-final.pt --seed 0 \
  --record-replays ../runs/my-eval --set-name my-eval          # 센 게임만 + manifest.jsonl
cd .. && engine-kotlin/analysis/build/install/analysis/bin/analysis ingest runs/my-eval
```

`analysis` 명령 (`./gradlew :engine-kotlin:analysis:installDist` 후 `engine-kotlin/analysis/build/install/analysis/bin/analysis`):

| 명령 | 하는 일 |
|---|---|
| `ingest <dir> [--kind]` | `manifest.jsonl` + `*.pkr` → 전부 재생 검증 → 한 트랜잭션. 하나라도 틀리면 아무것도 안 넣는다 |
| `baseline-fsm` | FSM vs FSM 800게임을 `GameEvaluator` 규약으로 기록 · 적재 |
| `report --set <name> [--expect <json>]` | DB 에서 진영별 집계를 다시 계산 (평가 리포트와 대조) |
| `rebuild-stats [--set]` | 리플레이 BLOB 에서 랠리 · 파워히트 통계를 다시 파생 |
| `serve [--port 8081]` | 뷰어 API. 라이브 경기 원본은 `runs/live/` 에도 남는다 |
| `golden-replays` · `dump-states` | JS ≡ Kotlin 골든 생성 · 불일치 추적 |

뷰어 (로컬 전용 — 업스트림 에셋과 빌드 결과물은 커밋 · 배포하지 않는다):

```bash
engine-kotlin/analysis/build/install/analysis/bin/analysis serve &
cd viewer-web && npm install && npm run dev    # http://127.0.0.1:5173
npm test                                       # JS ≡ Kotlin 골든 · 렌더링 격리 · 라이브 동치 (Node)
```

- **경기 목록** — 묶음 · 진영 · 승패 · 미결 필터
- **재생** — 배속 · 시크(처음부터 다시 계산, 60,000 프레임 ≈ 50 ms) · 랠리 점프 · `#/play/<id>/<frame>`.
  로드할 때 JS 체인을 서버의 Kotlin 체인과 대조해 어긋나면 경고한다
- **대전** — 좌 · 우에 사람(키보드) / FSM. 왼쪽 `D G R V` + `Z`(파워히트) · `F`(↘), 오른쪽 방향키 + `Enter`.
  끝나면 서버가 Kotlin 으로 다시 재생해 검증한 뒤 적재한다
- **통계** — 랠리 길이 · 착지 x · 파워히트 성공률

> ⚠️ 뷰어가 업스트림 그리기 코드를 쓸 때 **그리기가 경기를 바꾸지 않게** 두 가지를 막는다
> (`viewer-web/src/view/guard.mjs`): 구름 · 파도가 부르는 전역 `rand()` 는 뷰 전용 RNG 로,
> `drawPlayersAndBall` 이 물리 객체의 `punchEffectRadius` 를 줄이는 부수 효과는 그림자 값으로.

---

## 구조

```
proto/
  state_spec.proto          차분 테스트가 비교하는 상태의 단일 정의 (계약)
  obs_spec.proto            관측 40차원 레이아웃의 단일 정의 (계약)
  env.proto                 gRPC 서비스 계약
scripts/
  fetch-upstream.sh         업스트림 고정 커밋 fetch
  coverage.sh               physics.js 커버리지 측정
  bench-env.sh              처리량 (a)(b)(c) 세 지점 측정
  bench-train.sh            학습 예산 측정 (정책 forward · 스레드 · 업데이트 · 종단)
  gen-python-proto.sh       env.proto → Python stub 생성
  baseline-replays.sh       기준선 리플레이 두 벌 기록 · 적재 · 대조 (Phase 4)
  train-track-a.sh          Track A 학습 · A/B · 재개
  eval-policy.sh            체크포인트를 게임 단위로 평가
tools/js-oracle/            Node 오라클 — physics.js 를 정답으로 돌린다
tools/targeted-cases.txt    표적 케이스 표 — JS·Kotlin 이 함께 읽는다
engine-kotlin/
  core/                     physics.js 포팅 + XorShift32 (외부 의존성 0)
  env/                      경기 규칙 · 관측 · 행동 · 보상 · 벡터 환경 · 벤치
    …/replay/               리플레이 v1 — 형식 · 기록기 · 재생기 (외부 의존성 0)
    golden/env-chain-hashes.txt  관측·보상 골든 (커밋 대상)
    golden/replay/          JS ≡ Kotlin 골든 리플레이 + chains.json (커밋 대상)
  server/                   gRPC 서버 (packed bytes 직렬화)
  conformance/              차분 테스트 하네스 + 실행기
    golden/chain-hashes.txt CI 골든 회귀용 체인 해시 (커밋 대상)
  analysis/                 적재 · 통계 · 기준선 · 뷰어 API (MySQL · HTTP 는 여기에만)
trainer-python/
  src/pika_trainer/
    obs_spec.py             obs_spec.proto 파서 (레이아웃 대조)
    env_client.py           Gymnasium VectorEnv
    bench_client.py         (c) 종단 처리량 벤치
    net.py                  정책·가치망 (환경을 모른다)
    rollout.py              롤아웃 버퍼 · autoreset 마스킹 · GAE (환경을 모른다)
    ppo.py                  PPO 손실과 업데이트 (**상대를 모른다** — Track B 가 재사용한다)
    schedules.py            LR · 엔트로피 · 셰이핑 스케줄
    metrics.py              JSONL 로거 · 런 디렉터리 규약
    track_a.py              Track A 러너 (알고리즘이 아니라 배선과 기록)
    evaluate.py             게임 단위 평가 — 양 진영 · 미결 규칙 · boldness 축 · 리플레이 기록
    replay.py               리플레이 헤더 파서 · 센 게임만 쓰는 ReplaySink
    server_process.py       평가 전용 서버 기동
    pb/                     env.proto 에서 생성된 stub (커밋 대상)
viewer-web/
  src/runner/               GameRunner (경기 규칙층) · 코덱 · 기록기 · 시드 · SHA-256 체인 — Node 와 브라우저 공용
  src/sources/              입력원: 리플레이 · FSM · 키보드 · 스크립트
  src/view/                 Pixi 화면 · 재생기 · 라이브 · 목록 · 통계
  test/                     JS ≡ Kotlin (M4-b) · 렌더링 격리 (M4-e) · 라이브 동치 (M4-j)
deploy/compose/             엔진 서버 이미지와 compose (+ mysql:8.4.11)
deploy/mysql/init/          분석 DB 스키마
```

문서는 [`CLAUDE.md`](CLAUDE.md) 의 문서 맵을 따른다.
진행 중인 작업의 요구사항·계획·태스크는 `PRD.md` · `plan.md` · `tasks.md` 에 있고,
완료된 작업은 `history/` 로 이관한다.

---

## 왜 Kotlin 으로 포팅하는가

`physics.js` 는 1997년 기계어를 옮긴 코드라 **모든 연산이 32비트 정수**다.
나눗셈 7곳은 전부 `| 0` 으로 절삭되고, `Math` 는 `abs` 만 쓰며, 소수점 리터럴은 0건이다.

Kotlin 의 `Int` 나눗셈은 JS 와 똑같이 0 방향으로 절삭되므로 1:1 변환이 가능하다.
Python 이었다면 `//` 가 floor division 이라 `-5 // 2 == -3` 으로 갈라졌을 것이다
(그 차이를 고정해 둔 것이 `trainer-python/src/pika_trainer/intsem.py` 다).

부동소수점 반올림 차이가 원천적으로 없으므로, 같은 입력에 같은 숫자가 나오고
같은 숫자면 같은 해시가 나온다. 이것이 상태 해시 100% 일치를 목표로 걸 수 있는 근거였고,
실제로 4,202,280 프레임에서 불일치 0건으로 확인되었다.
