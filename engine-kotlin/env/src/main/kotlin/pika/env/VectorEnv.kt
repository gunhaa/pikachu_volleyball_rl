package pika.env

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
 */
class VectorEnv(config: EnvConfig, val numEnvs: Int) {

    var config: EnvConfig = config
        private set

    val slotCount: Int = config.slotCount
    val obsDim: Int = config.obsDim

    private var envs: Array<PikaEnv> = Array(numEnvs) { PikaEnv(config, it) }

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
        writeScores()
    }

    /**
     * 전부 처음 상태로. [baseSeed] 를 주면 그 시드로 갈아탄다.
     *
     * ⚠️ 환경 i 의 수열은 `(baseSeed, i, 랠리번호)` 만의 함수다. **[numEnvs] 는 들어가지 않는다.**
     *    벡터 크기를 바꿔도 환경 0 이 같은 것을 보게 하려는 것이다 (M2-d).
     */
    fun reset(baseSeed: Int = config.baseSeed) {
        if (baseSeed != config.baseSeed) config = config.copy(baseSeed = baseSeed)
        envs = Array(numEnvs) { PikaEnv(config, it) }
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

    /** 진단용. 환경 하나를 들여다본다. */
    fun envAt(index: Int): PikaEnv = envs[index]
}
