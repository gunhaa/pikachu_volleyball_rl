package pika.env

import pika.env.replay.Replay
import pika.env.replay.ReplayRecorder
import pika.env.replay.SeedMode

/**
 * N개의 [PikaEnv] 를 배열로 들고 한 번에 스텝한다. (FR-9)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 버퍼를 재사용한다
 * ─────────────────────────────────────────────────────────────────────────────
 * [observations] · [rewards] · [rewardTerms] · [terminated] · [truncated] · [scores] 는
 * **한 번 할당되고 스텝마다 덮어쓰인다.** 호출자는 다음 [step] 전에 다 읽어야 한다.
 * 스텝마다 새로 할당하면 N=256 에서 초당 수백 MB 를 GC 에 태우게 된다 (plan.md §11).
 * 서버는 이 배열을 그대로 packed `bytes` 로 직렬화한다 — 중간 표현이 없다.
 *
 * ⚠️ 엔진을 병렬화하지 않는다. 엔진은 예산의 0.6% 만 쓴다 (plan.md §2.1).
 *    처리량이 모자라면 원인은 여기가 아니다. 병렬화는 오진이다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 진영 분할 (plan.md §4, FR-3)
 * ─────────────────────────────────────────────────────────────────────────────
 * [swappedEnvs] 개의 **뒤쪽** 환경은 `slots` 의 p1/p2 를 뒤바꿔 쓴다. 한 서버·한 배치
 * 안에서 정책이 양 진영을 동시에 본다. 이 게임은 좌우 대칭이 아니므로(PRD §2.4) 한
 * 진영에서만 학습하면 완료 조건 M3-b 가 운에 걸린다.
 *
 * ⚠️ **뒤쪽**인 이유: 시드는 `deriveSeed(baseSeed, envIndex, k)` 로 유도된다. 앞쪽을
 *    바꾸면 기존 시드 배치가 통째로 밀려 Phase 2 의 결정론 골든(M2-d)과 비교할 수 없게 된다.
 *    뒤에 붙이면 `swappedEnvs = 0` 이 기존 동작과 **바이트 단위로** 같다.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 리플레이 기록 (Phase 4 FR-2, plan.md §4.1)
 * ─────────────────────────────────────────────────────────────────────────────
 * [recordReplays] 면 환경마다 [ReplayRecorder] 를 달고, 끝난(또는 상한에서 잘린) 게임을
 * `(envIndex, gameInEnv, Replay)` 로 큐에 쌓는다. [drainReplays] 가 큐를 비운다.
 * 어느 게임을 집계에 넣을지는 호출자(Python `evaluate.py`)가 `(envIndex, gameInEnv)` 로 고른다.
 */
class VectorEnv(
    config: EnvConfig,
    val numEnvs: Int,
    val swappedEnvs: Int = 0,
    val recordReplays: Boolean = false,
) {

    var config: EnvConfig = config
        private set

    val slotCount: Int = config.slotCount
    val obsDim: Int = config.obsDim

    /**
     * 환경 [i] 의 구성. 뒤쪽 [swappedEnvs] 개만 진영을 뒤집는다.
     *
     * 스왑은 **슬롯 구성만** 바꾼다. 시드·보상·관측 플래그는 그대로이므로
     * `layoutHash` 도 같다 (관측 레이아웃은 슬롯 구성과 무관하다).
     */
    private fun configFor(i: Int): EnvConfig =
        if (i >= numEnvs - swappedEnvs) config.copy(slots = Slots(config.slots.p2, config.slots.p1))
        else config

    /** 기록된 게임. 환경 [envIndex] 의 [gameInEnv] 번째 게임 (리셋 이후 0부터). */
    class RecordedGame(val envIndex: Int, val gameInEnv: Int, val replay: Replay)

    private val recorded = ArrayDeque<RecordedGame>()

    private fun makeEnv(i: Int): PikaEnv {
        if (!recordReplays) return PikaEnv(configFor(i), i)
        lateinit var env: PikaEnv
        // sink 는 게임이 끝나거나 잘린 그 스텝 안에서 불린다 — 아직 다음 게임을 시작하기 전이라
        // gameCounter - 1 이 이 게임의 번호다.
        val recorder = ReplayRecorder(SeedMode.RALLY) { recorded.addLast(RecordedGame(i, env.gameCounter - 1, it)) }
        env = PikaEnv(configFor(i), i, recorder)
        return env
    }

    private var envs: Array<PikaEnv> = Array(numEnvs) { makeEnv(it) }

    /** `numEnvs × slotCount × obsDim` (float32). */
    val observations: FloatArray = FloatArray(numEnvs * slotCount * obsDim)

    /** `numEnvs × slotCount` — 가중 합계. */
    val rewards: FloatArray = FloatArray(numEnvs * slotCount)

    /** `numEnvs × slotCount × RewardTerms.COUNT` — 항별 값 (info 용, FR-6). */
    val rewardTerms: FloatArray = FloatArray(numEnvs * slotCount * RewardTerms.COUNT)

    /** `numEnvs` — 0/1. */
    val terminated: ByteArray = ByteArray(numEnvs)
    val truncated: ByteArray = ByteArray(numEnvs)

    /** `numEnvs × 2` — `[player1, player2]` 진영 순서다 (슬롯 시점이 아니다). */
    val scores: IntArray = IntArray(numEnvs * 2)

    /** 누적 env-step 수. `Health` 가 처리량을 보고하는 데 쓴다. */
    var totalSteps: Long = 0L
        private set

    init {
        require(numEnvs > 0) { "numEnvs 는 양수여야 합니다" }
        require(swappedEnvs in 0..numEnvs) {
            "swappedEnvs 는 0..$numEnvs 여야 합니다: $swappedEnvs"
        }
        // 스왑이 슬롯 수를 보존한다는 것이 packed 바이트 레이아웃의 전제다.
        // `(External, Fsm)` 도 `(Fsm, External)` 도 slotCount == 1 이다. 구조로 못 박는다 —
        // 여기가 깨지면 클라이언트의 reshape 가 조용히 어긋난다.
        check(configFor(0).slotCount == configFor(numEnvs - 1).slotCount) {
            "진영을 뒤집었더니 슬롯 수가 달라졌습니다: " +
                "${configFor(0).slotCount} vs ${configFor(numEnvs - 1).slotCount}"
        }
        writeScores()
    }

    /** 환경 [i] 가 뒤집힌 진영인가. 진영별 메트릭을 나누는 기준이다 (FR-9). */
    fun isSwapped(i: Int): Boolean = i >= numEnvs - swappedEnvs

    /**
     * 전부 처음 상태로. [baseSeed] 를 주면 그 시드로 갈아탄다.
     *
     * ⚠️ 환경 i 의 수열은 `(baseSeed, i, 랠리번호)` 만의 함수다. **[numEnvs] 는 들어가지 않는다.**
     *    벡터 크기를 바꿔도 환경 0 이 같은 것을 보게 하려는 것이다 (M2-d).
     */
    fun reset(baseSeed: Int = config.baseSeed) {
        if (baseSeed != config.baseSeed) config = config.copy(baseSeed = baseSeed)
        envs = Array(numEnvs) { makeEnv(it) }
        recorded.clear()
        for (i in 0 until numEnvs) {
            envs[i].reset(observations, i * slotCount * obsDim)
            terminated[i] = 0
            truncated[i] = 0
        }
        java.util.Arrays.fill(rewards, 0f)
        java.util.Arrays.fill(rewardTerms, 0f)
        totalSteps = 0
        writeScores()
    }

    /**
     * 배치 한 스텝.
     *
     * @param actions `numEnvs × slotCount` 바이트. 환경 i 슬롯 k 의 행동은 `actions[i * slotCount + k]`
     */
    fun step(actions: ByteArray) {
        require(actions.size >= numEnvs * slotCount) {
            "행동 배열이 작습니다: ${actions.size} < ${numEnvs * slotCount}"
        }
        val termsPerEnv = slotCount * RewardTerms.COUNT
        for (i in 0 until numEnvs) {
            val flags = envs[i].step(
                actions, i * slotCount,
                observations, i * slotCount * obsDim,
                rewards, i * slotCount,
                rewardTerms, i * termsPerEnv,
            )
            terminated[i] = (flags and 1).toByte()
            truncated[i] = ((flags shr 1) and 1).toByte()
        }
        writeScores()
        totalSteps += numEnvs.toLong()
    }

    private fun writeScores() {
        for (i in 0 until numEnvs) {
            val s = envs[i].scores
            scores[i * 2] = s[0]
            scores[i * 2 + 1] = s[1]
        }
    }

    /** 쌓인 기록을 전부 꺼내고 큐를 비운다. 기록이 꺼져 있으면 항상 빈 목록이다. */
    fun drainReplays(): List<RecordedGame> {
        val out = recorded.toList()
        recorded.clear()
        return out
    }

    /** 진단용. 환경 하나를 들여다본다. */
    fun envAt(index: Int): PikaEnv = envs[index]
}
