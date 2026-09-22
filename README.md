# pikachu-volleyball-rl

피카츄 발리볼을 강화학습으로 푸는 프로젝트.

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
  gen-python-proto.sh       env.proto → Python stub 생성
tools/js-oracle/            Node 오라클 — physics.js 를 정답으로 돌린다
tools/targeted-cases.txt    표적 케이스 표 — JS·Kotlin 이 함께 읽는다
engine-kotlin/
  core/                     physics.js 포팅 + XorShift32 (외부 의존성 0)
  env/                      경기 규칙 · 관측 · 행동 · 보상 · 벡터 환경 · 벤치
    golden/env-chain-hashes.txt  관측·보상 골든 (커밋 대상)
  server/                   gRPC 서버 (packed bytes 직렬화)
  conformance/              차분 테스트 하네스 + 실행기
    golden/chain-hashes.txt CI 골든 회귀용 체인 해시 (커밋 대상)
trainer-python/
  src/pika_trainer/
    obs_spec.py             obs_spec.proto 파서 (레이아웃 대조)
    env_client.py           Gymnasium VectorEnv
    bench_client.py         (c) 종단 처리량 벤치
    pb/                     env.proto 에서 생성된 stub (커밋 대상)
deploy/compose/             엔진 서버 이미지와 compose
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
