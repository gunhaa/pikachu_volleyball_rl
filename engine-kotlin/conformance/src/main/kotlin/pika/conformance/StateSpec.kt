package pika.conformance

import pika.core.Ball
import pika.core.PikaPhysics
import pika.core.Player
import java.security.MessageDigest

/**
 * State Spec — 상태 직렬화와 해시. `tools/js-oracle/spec.mjs` 의 Kotlin 짝이다.
 *
 * 필드 순서의 단일 진실 공급원은 `proto/state_spec.proto` 다 (FR-5).
 * [assertMatchesProto] 가 아래 목록과 .proto 를 대조한다.
 *
 * ⚠️ 해시가 안 맞는다고 이 파일이나 .proto 를 고치면 안 된다. (plan.md §5.0)
 */
object StateSpec {

    private fun b(v: Boolean): Int = if (v) 1 else 0

    /** BallState — .proto 의 필드 번호 순서. */
    private val ballFields: List<Pair<String, (Ball) -> Int>> = listOf(
        "x" to { o: Ball -> o.x },
        "y" to { o: Ball -> o.y },
        "x_velocity" to { o: Ball -> o.xVelocity },
        "y_velocity" to { o: Ball -> o.yVelocity },
        "fine_rotation" to { o: Ball -> o.fineRotation },
        "rotation" to { o: Ball -> o.rotation },
        "punch_effect_radius" to { o: Ball -> o.punchEffectRadius },
        "punch_effect_x" to { o: Ball -> o.punchEffectX },
        "punch_effect_y" to { o: Ball -> o.punchEffectY },
        "is_power_hit" to { o: Ball -> b(o.isPowerHit) },
        "expected_landing_point_x" to { o: Ball -> o.expectedLandingPointX },
        "previous_x" to { o: Ball -> o.previousX },
        "previous_previous_x" to { o: Ball -> o.previousPreviousX },
        "previous_y" to { o: Ball -> o.previousY },
        "previous_previous_y" to { o: Ball -> o.previousPreviousY },
    )

    /** PlayerState */
    private val playerFields: List<Pair<String, (Player) -> Int>> = listOf(
        "x" to { o: Player -> o.x },
        "y" to { o: Player -> o.y },
        "y_velocity" to { o: Player -> o.yVelocity },
        "state" to { o: Player -> o.state },
        "frame_number" to { o: Player -> o.frameNumber },
        "diving_direction" to { o: Player -> o.divingDirection },
        "lying_down_duration_left" to { o: Player -> o.lyingDownDurationLeft },
        "is_collision_with_ball_happened" to { o: Player -> b(o.isCollisionWithBallHappened) },
        "normal_status_arm_swing_direction" to { o: Player -> o.normalStatusArmSwingDirection },
        "delay_before_next_frame" to { o: Player -> o.delayBeforeNextFrame },
        "computer_boldness" to { o: Player -> o.computerBoldness },
        "computer_where_to_stand_by" to { o: Player -> o.computerWhereToStandBy },
        "is_winner" to { o: Player -> b(o.isWinner) },
        "game_ended" to { o: Player -> b(o.gameEnded) },
    )

    private val ballSoundFields: List<Pair<String, (Ball) -> Int>> = listOf(
        "power_hit" to { o: Ball -> b(o.sound.powerHit) },
        "ball_touches_ground" to { o: Ball -> b(o.sound.ballTouchesGround) },
    )

    private val playerSoundFields: List<Pair<String, (Player) -> Int>> = listOf(
        "pipikachu" to { o: Player -> b(o.sound.pipikachu) },
        "pika" to { o: Player -> b(o.sound.pika) },
        "chu" to { o: Player -> b(o.sound.chu) },
    )

    /** FrameState 를 평탄화한 필드 이름 순서. */
    val baseFieldNames: List<String> =
        ballFields.map { "ball.${it.first}" } +
            playerFields.map { "player1.${it.first}" } +
            playerFields.map { "player2.${it.first}" } +
            "is_ball_touching_ground"

    /** SoundSuffix 를 평탄화한 필드 이름 순서 (엄격 모드에서만 붙는다). */
    val soundFieldNames: List<String> =
        ballSoundFields.map { "ball.${it.first}" } +
            playerSoundFields.map { "player1.${it.first}" } +
            playerSoundFields.map { "player2.${it.first}" }

    const val BASE_INT_COUNT = 44
    const val SOUND_INT_COUNT = 8

    fun intCount(strict: Boolean): Int = BASE_INT_COUNT + if (strict) SOUND_INT_COUNT else 0

    /** 필드 이름 목록 (엄격 모드 여부에 따라). */
    fun fieldNames(strict: Boolean): List<String> =
        if (strict) baseFieldNames + soundFieldNames else baseFieldNames

    // ─────────────────────────────────────────────────────────────────────────
    // 직렬화
    // ─────────────────────────────────────────────────────────────────────────

    /** 프레임 상태를 [out] 에 채운다. 순서는 [baseFieldNames] 와 같다. */
    fun writeInts(
        physics: PikaPhysics,
        isBallTouchingGround: Boolean,
        strict: Boolean,
        out: IntArray,
    ) {
        require(out.size == intCount(strict)) { "out 크기가 ${intCount(strict)} 이어야 합니다" }
        var i = 0
        for ((_, get) in ballFields) out[i++] = get(physics.ball)
        for ((_, get) in playerFields) out[i++] = get(physics.player1)
        for ((_, get) in playerFields) out[i++] = get(physics.player2)
        out[i++] = b(isBallTouchingGround)
        if (strict) {
            for ((_, get) in ballSoundFields) out[i++] = get(physics.ball)
            for ((_, get) in playerSoundFields) out[i++] = get(physics.player1)
            for ((_, get) in playerSoundFields) out[i++] = get(physics.player2)
        }
        check(i == out.size)
    }

    /** Int 배열 → little-endian 4바이트 이어붙이기. */
    fun packInts(ints: IntArray, out: ByteArray) {
        require(out.size == ints.size * 4)
        var j = 0
        for (v in ints) {
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v ushr 8) and 0xFF).toByte()
            out[j++] = ((v ushr 16) and 0xFF).toByte()
            out[j++] = ((v ushr 24) and 0xFF).toByte()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 해시
    // ─────────────────────────────────────────────────────────────────────────

    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray, count: Int = bytes.size): String {
        val sb = StringBuilder(count * 2)
        for (i in 0 until count) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 프레임별 해시 — SHA-256 앞 8바이트를 hex 16자로. (plan.md §6.3) */
    fun frameHash(digest: MessageDigest, bytes: ByteArray): String {
        digest.reset()
        return toHex(digest.digest(bytes), 8)
    }

    /** 체인 해시 한 단계 — h_n = SHA256(h_{n-1} ‖ state_n). 절단하지 않는다. */
    fun chainStep(digest: MessageDigest, prev32: ByteArray, bytes: ByteArray): ByteArray {
        digest.reset()
        digest.update(prev32)
        digest.update(bytes)
        return digest.digest()
    }

    fun chainSeed(): ByteArray = ByteArray(32)

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    // ─────────────────────────────────────────────────────────────────────────
    // .proto 대조
    // ─────────────────────────────────────────────────────────────────────────

    private val MESSAGE_RE = Regex("""message\s+(\w+)\s*\{([^}]*)\}""")
    private val FIELD_RE = Regex("""(\w+)\s+(\w+)\s*=\s*(\d+)\s*;""")
    private val COMMENT_RE = Regex("""//[^\n]*""")

    private data class ProtoField(val type: String, val name: String, val number: Int)

    private fun parseProto(text: String): Map<String, List<ProtoField>> {
        val stripped = COMMENT_RE.replace(text, "")
        return MESSAGE_RE.findAll(stripped).associate { m ->
            val fields = FIELD_RE.findAll(m.groupValues[2])
                .map { ProtoField(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }
                .sortedBy { it.number }
                .toList()
            m.groupValues[1] to fields
        }
    }

    private fun flatten(messages: Map<String, List<ProtoField>>, root: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(msgName: String, prefix: String) {
            val fields = messages[msgName] ?: error(".proto 에 message $msgName 이 없습니다")
            for (f in fields) {
                val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                when {
                    messages.containsKey(f.type) -> walk(f.type, path)
                    f.type == "int32" -> out += path
                    else -> error("예상치 못한 타입 ${f.type} ($path). State Spec 은 전부 int32 다.")
                }
            }
        }
        walk(root, "")
        return out
    }

    /**
     * 이 객체의 필드 목록이 `proto/state_spec.proto` 와 일치하는지 확인한다.
     * @throws IllegalStateException 불일치 시
     */
    fun assertMatchesProto(protoText: String = RepoPaths.stateSpecProto.toFile().readText()) {
        val messages = parseProto(protoText)
        fun check(root: String, actual: List<String>) {
            val expected = flatten(messages, root)
            check(expected == actual) {
                buildString {
                    appendLine("State Spec 불일치 ($root)")
                    appendLine("  .proto     : ${expected.joinToString(", ")}")
                    appendLine("  StateSpec.kt: ${actual.joinToString(", ")}")
                }
            }
        }
        check("FrameState", baseFieldNames)
        check("SoundSuffix", soundFieldNames)
        check(baseFieldNames.size == BASE_INT_COUNT) { "BASE_INT_COUNT 가 실제와 다릅니다" }
        check(soundFieldNames.size == SOUND_INT_COUNT) { "SOUND_INT_COUNT 가 실제와 다릅니다" }
    }
}
