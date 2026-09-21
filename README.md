# pikachu-volleyball-rl

피카츄 발리볼을 강화학습으로 푸는 개인 학습용 프로젝트.

물리 엔진을 Kotlin 으로 포팅하고, JS 원본을 정답(oracle)으로 둔 차분 테스트로
동치성을 증명한 뒤, 그 위에서 PPO 로 정책을 학습한다.

목표는 세 가지다. 자세한 내용은 [`ROADMAP.md`](ROADMAP.md) 를 본다.

1. RL 정책으로 내장 컴퓨터 AI(FSM)를 이긴다
2. FSM 을 한 번도 보지 않고(zero) 셀프플레이만으로 학습한다
3. 그 결과를 다시 FSM 과 붙여 평가한다

---

## ⚠️ 출처와 저작권

이 저장소는 **원작 게임의 코드도 에셋도 포함하지 않는다.**

| 대상 | 권리자 |
|---|---|
| 원작 *Pikachu Volleyball* (1997) | SACHI SOFT / SAWAYAKAN Programmers, Satoshi Takenouchi |
| JavaScript 리버스 엔지니어링 구현 | Kyutae Lee ([gorisanson/pikachu-volleyball](https://github.com/gorisanson/pikachu-volleyball)) |

업스트림 저장소에는 `LICENSE` 파일이 없고 `package.json` 의 `license` 는 `UNLICENSED` 다.
즉 **사용 허가가 명시적으로 부여된 적이 없다.** 따라서

- 업스트림 코드는 저장소에 커밋하지 않는다. `scripts/fetch-upstream.sh` 로 고정 커밋을 받아 쓰고,
  `upstream/` 은 `.gitignore` 로 차단한다.
- 원작 스프라이트·효과음 등 게임 에셋은 어떤 경로로도 커밋하지 않는다.
- Kotlin 포팅본 역시 2차적 저작물이므로 같은 제약을 따른다.

이 프로젝트는 **비상업적 개인 학습·연구 목적**이며 배포·상업적 이용을 의도하지 않는다.
원작 권리자의 요청이 있으면 즉시 따른다.

---

## 셋업

### 요구 사항

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | **21** | Gradle toolchain 이 21 을 찾는다. 더 높은 JDK 로 Gradle 을 돌려도 된다 |
| Node.js | 20+ | JS 오라클 실행용 |
| [uv](https://docs.astral.sh/uv/) | 최신 | Python 3.11 환경 관리 |

### 1. 업스트림 받기

```bash
./scripts/fetch-upstream.sh
```

고정 커밋 `0d04dbaf165e4131e26f27f6e9def766f62260b3` 을 `upstream/` 에 체크아웃한다.
멱등하므로 여러 번 실행해도 안전하고, 받아온 내용은 `git status` 에 나타나지 않는다.

### 2. 빌드와 테스트

```bash
./gradlew build                 # Kotlin 빌드 + 단위 테스트
cd trainer-python && uv sync && uv run pytest
```

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

## 구조

```
proto/state_spec.proto      차분 테스트가 비교하는 상태의 단일 정의 (계약)
scripts/fetch-upstream.sh   업스트림 고정 커밋 fetch
tools/js-oracle/            Node 오라클 — physics.js 를 정답으로 돌린다
tools/targeted-cases.txt    표적 케이스 표 — JS·Kotlin 이 함께 읽는다
scripts/coverage.sh         physics.js 커버리지 측정
engine-kotlin/
  core/                     physics.js 포팅 (외부 의존성 0)
  conformance/              차분 테스트 하네스 + 실행기
    golden/chain-hashes.txt CI 골든 회귀용 체인 해시 (커밋 대상)
trainer-python/             PPO 트레이너 (현재는 골격)
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
