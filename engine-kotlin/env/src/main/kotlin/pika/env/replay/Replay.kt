package pika.env.replay

import pika.env.FixedBoldness
import pika.env.Slots

/**
 * 시드 규약. (FR-1, plan.md §2.1)
 *
 * 경기를 만드는 경로가 둘이고 RNG 를 거는 방식이 다르다. 하나로 통일하면 M2-e(799/800) 나
 * Track A 평가 중 하나가 재현되지 않는다.
 */
enum class SeedMode(val code: Int) {
    /** `GameEvaluator.playGame` — `XorShift32(seed)` 하나로 게임 전체. */
    GAME(0),

    /**
     * `PikaEnv` — 게임 시작 **및 매 랠리** `XorShift32(seeds[k])`.
     *
     * `rallyCounter` 가 게임을 넘어 증가하므로 시드를 다시 유도할 수 없다. 그래서 시드 자체를 저장한다.
     */
    RALLY(1),
    ;

    companion object {
        fun of(code: Int): SeedMode =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("알 수 없는 seedMode: $code")
    }
}

/** 랠리 결과 코드. 0/1 은 득점자다. */
object RallyOutcome {
    /** `maxRallyFrames` 에서 득점 없이 버려진 랠리. */
    const val TRUNCATED = -1

    /** 기록 상한에서 잘려 끝나지 않은 랠리. `ended = false` 인 게임의 마지막 랠리에만 온다. */
    const val UNFINISHED = -2
}

/**
 * 기록 시작 시점에 고정되는 경기 설정. [ReplayRecorder.beginGame] 이 받는다.
 *
 * @param edgeTrigger 정보용. 입력은 **엣지 변환 후** 값으로 저장하므로 재생은 이 값을 읽지 않는다 (FR-4).
 */
data class RecordedConfig(
    val slots: Slots,
    val winningScore: Int,
    val firstServeIsPlayer2: Boolean,
    val fixedBoldness: FixedBoldness,
    val maxRallyFrames: Int,
    val edgeTrigger: Boolean = false,
)

/**
 * 리플레이 형식 v1 의 메모리 표현. (FR-1, plan.md §3.1)
 *
 * 재생에 필요한 최소 + **검증용 결과**(랠리별 프레임 수 · 결과, 최종 점수). 결과가 없으면
 * 틀린 재생도 "어떤 경기" 를 그럴듯하게 만들어 낸다 — 결과가 재생기에게 스스로 틀렸는지 알려 준다.
 *
 * 누가 뛰었는지(체크포인트), 어느 평가에서 나왔는지는 여기 없다 (plan.md §3.2, manifest 의 일).
 * 같은 경기 바이트가 맥락과 무관하게 같은 해시를 가져야 중복 적재를 막을 수 있다.
 *
 * @param seeds GAME 이면 1개, RALLY 면 랠리마다 1개
 * @param inputs External 슬롯 입력. **슬롯 순서로 연속 배치** (p1 먼저) — `inputs[order * frameCount + frame]`.
 *   값은 `ActionCodec.encode(x, y, powerHit == 1)`, 엣지 변환 **후** 의 엔진 입력이다 (FR-4).
 */
class Replay(
    val slots: Slots,
    val firstServeIsPlayer2: Boolean,
    val ended: Boolean,
    val edgeTrigger: Boolean,
    val seedMode: SeedMode,
    val winningScore: Int,
    val fixedBoldness: FixedBoldness,
    val maxRallyFrames: Int,
    val seeds: IntArray,
    val rallyFrames: IntArray,
    val rallyOutcomes: ByteArray,
    val finalScore: IntArray,
    val frameCount: Int,
    val inputs: ByteArray,
) {
    /** 입력을 기록하는 슬롯 (0 = player1, 1 = player2). [inputs] 의 배치 순서다. */
    val externalSlots: IntArray = (0..1).filter { !slots[it].isFsm }.toIntArray()

    val rallyCount: Int get() = rallyFrames.size

    init {
        require(rallyFrames.size == rallyOutcomes.size) { "랠리 프레임 수와 결과 수가 다릅니다" }
        require(rallyCount >= 1) { "랠리가 하나도 없습니다" }
        require(seeds.size == if (seedMode == SeedMode.GAME) 1 else rallyCount) {
            "시드 수 ${seeds.size} 가 규약 $seedMode · 랠리 $rallyCount 와 맞지 않습니다"
        }
        require(winningScore in 1..255) { "winningScore 는 1..255: $winningScore" }
        require(maxRallyFrames >= 0) { "maxRallyFrames 는 0 이상: $maxRallyFrames" }
        require(finalScore.size == 2 && finalScore.all { it in 0..255 }) { "finalScore 가 잘못됐습니다" }
        require(frameCount >= 0 && inputs.size.toLong() == frameCount.toLong() * externalSlots.size) {
            "입력 ${inputs.size} 바이트가 ${frameCount} 프레임 × ${externalSlots.size} 슬롯과 맞지 않습니다"
        }
        require(rallyFrames.all { it >= 0 } && rallyFrames.sumOf { it.toLong() } == frameCount.toLong()) {
            "랠리 프레임 합이 frameCount($frameCount) 와 다릅니다"
        }
        for (k in 0 until rallyCount) {
            val o = rallyOutcomes[k].toInt()
            val last = k == rallyCount - 1
            require(o in RallyOutcome.UNFINISHED..1) { "랠리 $k 의 결과 코드가 잘못됐습니다: $o" }
            require((o == RallyOutcome.UNFINISHED) == (last && !ended)) {
                "미완(-2) 은 ended = false 인 게임의 마지막 랠리에만 옵니다 (랠리 $k, ended=$ended)"
            }
        }
        require(inputs.all { (it.toInt() and 0xFF) < 18 }) { "입력 바이트는 0..17 이어야 합니다" }
    }

    /** External 슬롯 [order] (0 = 첫 External 슬롯) 의 [frame] 번째 입력. */
    fun input(order: Int, frame: Int): Int = inputs[order * frameCount + frame].toInt() and 0xFF

    /** 이긴 쪽(0/1). 끝나지 않았으면 null. */
    val winner: Int?
        get() = when {
            !ended -> null
            finalScore[0] >= winningScore -> 0
            else -> 1
        }

    override fun equals(other: Any?): Boolean =
        other is Replay && ReplayCodec.encode(this).contentEquals(ReplayCodec.encode(other))

    override fun hashCode(): Int = ReplayCodec.encode(this).contentHashCode()

    override fun toString(): String =
        "Replay($seedMode, slots=$slots, ${finalScore[0]}:${finalScore[1]}, 랠리 $rallyCount, " +
            "프레임 $frameCount, ended=$ended)"
}
