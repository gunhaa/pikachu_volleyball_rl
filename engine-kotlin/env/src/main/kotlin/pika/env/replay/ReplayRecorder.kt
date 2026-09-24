package pika.env.replay

import pika.core.PikaUserInput
import pika.env.ActionCodec

/**
 * 경기 한 판을 [Replay] 로 적는다. (FR-2, FR-5, plan.md §4.1)
 *
 * 호출 순서 (한 게임):
 * ```
 * beginGame(seed, config)
 *   { frame(inputs) × n ; endRally(outcome) ; beginRally(seed) /* RALLY 규약만 */ } ...
 * endGame()
 * ```
 * 끝난 리플레이는 [sink] 로 나간다. 반환값으로 돌려주지 않는 이유: 상한에서 잘린 게임은
 * `endGame()` 이 영영 안 불릴 수 있다 (엔진 안에서는 게임이 계속 돈다).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ 상한 [cap] 의 경계 — Python `evaluate.py` 와 같은 정의
 * ─────────────────────────────────────────────────────────────────────────────
 * `evaluate_policy` 는 `g_frames > MAX_GAME_FRAMES` 일 때 미결로 센다. 즉 **정확히 60,000
 * 프레임에 끝난 게임은 정상 게임**이다. 그래서 60,000 번째 프레임이 아니라 **60,001 번째
 * `frame()` 호출**에서 자른다. 잘린 리플레이는 앞의 [cap] 프레임을 담고 `ended = false` 다.
 * 자른 뒤에는 다음 [beginGame] 까지 모든 호출을 무시한다.
 *
 * ⚠️ 기록은 **`game.step(inputs)` 직전**에 한다. step 안에서 FSM 슬롯의 입력이 덮어써진다.
 *    지금은 External 슬롯만 적으므로 차이가 없지만, 순서가 뒤집혀 있으면 FSM 입력까지 적고
 *    싶어졌을 때 "다음 프레임에 쓰일 값" 이 들어간다.
 */
class ReplayRecorder(
    val seedMode: SeedMode,
    val cap: Int = DEFAULT_CAP,
    private val sink: (Replay) -> Unit,
) {
    init {
        require(cap > 0) { "cap 은 양수여야 합니다: $cap" }
    }

    private var config: RecordedConfig? = null
    private var externalSlots = IntArray(0)

    private var seeds = IntArray(16)
    private var seedCount = 0
    private var rallyFrames = IntArray(32)
    private var outcomes = ByteArray(32)
    private var rallyCount = 0
    private var currentRallyFrames = 0
    private val scores = IntArray(2)

    /** 슬롯별 입력 버퍼. 게임 사이에 재사용한다. */
    private var inputs: Array<ByteArray> = arrayOf(ByteArray(4096), ByteArray(4096))
    private var frameCount = 0

    /** 지금 게임 하나를 적고 있는가. 잘린 뒤 · [endGame] 뒤에는 false. */
    val recording: Boolean get() = config != null

    /** 새 게임. 적던 게임이 있으면 버린다 (환경 리셋 — 끝나지도 잘리지도 않은 게임). */
    fun beginGame(seed: Int, config: RecordedConfig) {
        this.config = config
        externalSlots = (0..1).filter { !config.slots[it].isFsm }.toIntArray()
        seedCount = 0
        rallyCount = 0
        currentRallyFrames = 0
        frameCount = 0
        scores.fill(0)
        pushSeed(seed)
    }

    /** RALLY 규약에서 두 번째 이후 랠리의 시드. GAME 규약에서 부르면 오류다. */
    fun beginRally(seed: Int) {
        if (!recording) return
        check(seedMode == SeedMode.RALLY) { "GAME 규약에서는 랠리 시드를 적지 않습니다" }
        check(seedCount == rallyCount) { "beginRally 는 endRally 뒤에 한 번만 부릅니다" }
        pushSeed(seed)
    }

    /** 물리 한 프레임의 입력. `game.step(inputs)` **직전**에 부른다. */
    fun frame(inputs: Array<PikaUserInput>) {
        if (!recording) return
        if (frameCount == cap) {
            finish(ended = false)
            return
        }
        if (frameCount == this.inputs[0].size) grow()
        for (k in externalSlots.indices) {
            val input = inputs[externalSlots[k]]
            this.inputs[k][frameCount] =
                ActionCodec.encode(input.xDirection, input.yDirection, input.powerHit == 1).toByte()
        }
        frameCount++
        currentRallyFrames++
    }

    /** @param outcome 0/1 득점자, [RallyOutcome.TRUNCATED] */
    fun endRally(outcome: Int) {
        if (!recording) return
        require(outcome == 0 || outcome == 1 || outcome == RallyOutcome.TRUNCATED) { "랠리 결과: $outcome" }
        pushRally(currentRallyFrames, outcome)
        if (outcome >= 0) scores[outcome]++
        currentRallyFrames = 0
    }

    fun endGame() {
        if (!recording) return
        finish(ended = true)
    }

    private fun finish(ended: Boolean) {
        val c = config!!
        if (!ended) pushRally(currentRallyFrames, RallyOutcome.UNFINISHED)
        if (seedMode == SeedMode.RALLY) {
            check(seedCount == rallyCount) { "RALLY 규약인데 시드 $seedCount 개 · 랠리 $rallyCount 개" }
        }
        val packed = ByteArray(frameCount * externalSlots.size)
        for (k in externalSlots.indices) System.arraycopy(inputs[k], 0, packed, k * frameCount, frameCount)
        config = null
        sink(
            Replay(
                slots = c.slots,
                firstServeIsPlayer2 = c.firstServeIsPlayer2,
                ended = ended,
                edgeTrigger = c.edgeTrigger,
                seedMode = seedMode,
                winningScore = c.winningScore,
                fixedBoldness = c.fixedBoldness,
                maxRallyFrames = c.maxRallyFrames,
                seeds = seeds.copyOf(if (seedMode == SeedMode.GAME) 1 else seedCount),
                rallyFrames = rallyFrames.copyOf(rallyCount),
                rallyOutcomes = outcomes.copyOf(rallyCount),
                finalScore = scores.copyOf(),
                frameCount = frameCount,
                inputs = packed,
            ),
        )
    }

    private fun pushSeed(seed: Int) {
        if (seedCount == seeds.size) seeds = seeds.copyOf(seeds.size * 2)
        seeds[seedCount++] = seed
    }

    private fun pushRally(frames: Int, outcome: Int) {
        if (rallyCount == rallyFrames.size) {
            rallyFrames = rallyFrames.copyOf(rallyCount * 2)
            outcomes = outcomes.copyOf(rallyCount * 2)
        }
        rallyFrames[rallyCount] = frames
        outcomes[rallyCount] = outcome.toByte()
        rallyCount++
    }

    private fun grow() {
        val size = minOf(inputs[0].size * 2, cap)
        inputs = Array(2) { inputs[it].copyOf(size) }
    }

    companion object {
        /** `evaluate.py` 의 `MAX_GAME_FRAMES` 와 같은 값 (FR-5). */
        const val DEFAULT_CAP: Int = 60_000
    }
}
