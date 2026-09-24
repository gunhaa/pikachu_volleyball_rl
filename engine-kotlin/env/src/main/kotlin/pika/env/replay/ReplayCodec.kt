package pika.env.replay

import pika.env.FixedBoldness
import pika.env.Slot
import pika.env.Slots
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 리플레이 형식 v1 ↔ 바이트. (FR-1, plan.md §3.1)
 *
 * ```
 * little-endian
 * magic      "PKRP"
 * u8         version = 1
 * u8         flags      bit0 p1External · bit1 p2External · bit2 firstServeIsPlayer2
 *                       bit3 ended · bit4 edgeTrigger (정보용)
 * u8         seedMode   0 = GAME · 1 = RALLY
 * u8         winningScore
 * i8 i8      fixedBoldness p1, p2   (-1 = 추첨)
 * i32        maxRallyFrames         (0 = 없음)
 * u32        rallyCount
 * i32 × (seedMode == RALLY ? rallyCount : 1)      seeds
 * { i32 frames, i8 outcome } × rallyCount
 * u8 u8      finalScore p1, p2
 * u32        frameCount
 * u8 × frameCount × externalCount                  슬롯별로 연속 배치 (p1 먼저)
 * ```
 *
 * ⚠️ JS 쪽 `viewer-web/src/runner/codec.mjs` 가 **같은 바이트**를 읽고 쓴다. 여기를 바꾸면 버전을 올린다.
 */
object ReplayCodec {

    val MAGIC: ByteArray = "PKRP".toByteArray(Charsets.US_ASCII)
    const val VERSION: Int = 1

    private const val FLAG_P1_EXTERNAL = 1
    private const val FLAG_P2_EXTERNAL = 2
    private const val FLAG_FIRST_SERVE_P2 = 4
    private const val FLAG_ENDED = 8
    private const val FLAG_EDGE_TRIGGER = 16
    private const val KNOWN_FLAGS = 31

    private const val HEADER_BYTES = 4 + 1 + 1 + 1 + 1 + 2 + 4 + 4

    fun encode(r: Replay): ByteArray {
        val size = HEADER_BYTES + 4 * r.seeds.size + 5 * r.rallyCount + 2 + 4 + r.inputs.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        var flags = 0
        if (!r.slots.p1.isFsm) flags = flags or FLAG_P1_EXTERNAL
        if (!r.slots.p2.isFsm) flags = flags or FLAG_P2_EXTERNAL
        if (r.firstServeIsPlayer2) flags = flags or FLAG_FIRST_SERVE_P2
        if (r.ended) flags = flags or FLAG_ENDED
        if (r.edgeTrigger) flags = flags or FLAG_EDGE_TRIGGER
        buf.put(flags.toByte())
        buf.put(r.seedMode.code.toByte())
        buf.put(r.winningScore.toByte())
        buf.put(r.fixedBoldness.p1.toByte())
        buf.put(r.fixedBoldness.p2.toByte())
        buf.putInt(r.maxRallyFrames)
        buf.putInt(r.rallyCount)
        for (s in r.seeds) buf.putInt(s)
        for (k in 0 until r.rallyCount) {
            buf.putInt(r.rallyFrames[k])
            buf.put(r.rallyOutcomes[k])
        }
        buf.put(r.finalScore[0].toByte())
        buf.put(r.finalScore[1].toByte())
        buf.putInt(r.frameCount)
        buf.put(r.inputs)
        check(!buf.hasRemaining()) { "인코드 크기 계산이 틀렸습니다" }
        return buf.array()
    }

    /**
     * @throws IllegalArgumentException magic · 버전이 다르거나, 바이트가 모자라거나 남거나, 불변식이 깨졌을 때.
     *   알 수 없는 버전을 "대충 읽는" 경로는 없다 — 틀리게 읽은 리플레이도 그럴듯한 경기를 만든다.
     */
    fun decode(bytes: ByteArray): Replay {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val magic = ByteArray(4).also { buf.get(it) }
            require(magic.contentEquals(MAGIC)) { "리플레이가 아닙니다 (magic 불일치)" }
            val version = buf.get().toInt() and 0xFF
            require(version == VERSION) { "알 수 없는 리플레이 버전: $version (지원: $VERSION)" }
            val flags = buf.get().toInt() and 0xFF
            require(flags and KNOWN_FLAGS.inv() == 0) { "알 수 없는 플래그 비트: $flags" }
            val seedMode = SeedMode.of(buf.get().toInt() and 0xFF)
            val winningScore = buf.get().toInt() and 0xFF
            val boldness = FixedBoldness(buf.get().toInt(), buf.get().toInt())
            val maxRallyFrames = buf.getInt()
            val rallyCount = buf.getInt()
            // 음수(= u32 상위 비트) 이거나 남은 바이트로 담을 수 없으면 할당 전에 거절한다.
            require(rallyCount in 0..buf.remaining() / 5) { "rallyCount 가 잘못됐습니다: $rallyCount" }
            val seeds = IntArray(if (seedMode == SeedMode.GAME) 1 else rallyCount) { buf.getInt() }
            val rallyFrames = IntArray(rallyCount)
            val outcomes = ByteArray(rallyCount)
            for (k in 0 until rallyCount) {
                rallyFrames[k] = buf.getInt()
                outcomes[k] = buf.get()
            }
            val finalScore = intArrayOf(buf.get().toInt() and 0xFF, buf.get().toInt() and 0xFF)
            val frameCount = buf.getInt()
            val slots = Slots(
                if (flags and FLAG_P1_EXTERNAL != 0) Slot.External else Slot.Fsm,
                if (flags and FLAG_P2_EXTERNAL != 0) Slot.External else Slot.Fsm,
            )
            val externalCount = (if (slots.p1.isFsm) 0 else 1) + (if (slots.p2.isFsm) 0 else 1)
            val inputBytes = frameCount.toLong() * externalCount
            require(frameCount >= 0 && inputBytes == buf.remaining().toLong()) {
                "입력 바이트 수가 맞지 않습니다: 남은 ${buf.remaining()}, 기대 $inputBytes"
            }
            val inputs = ByteArray(inputBytes.toInt()).also { buf.get(it) }
            return Replay(
                slots = slots,
                firstServeIsPlayer2 = flags and FLAG_FIRST_SERVE_P2 != 0,
                ended = flags and FLAG_ENDED != 0,
                edgeTrigger = flags and FLAG_EDGE_TRIGGER != 0,
                seedMode = seedMode,
                winningScore = winningScore,
                fixedBoldness = boldness,
                maxRallyFrames = maxRallyFrames,
                seeds = seeds,
                rallyFrames = rallyFrames,
                rallyOutcomes = outcomes,
                finalScore = finalScore,
                frameCount = frameCount,
                inputs = inputs,
            )
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("리플레이 바이트가 모자랍니다 (${bytes.size} B)", e)
        }
    }
}
