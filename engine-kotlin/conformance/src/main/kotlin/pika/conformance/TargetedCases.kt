package pika.conformance

import pika.core.Ball
import pika.core.PikaPhysics
import pika.core.Player

/**
 * 표적 케이스 — 입력 생성기 (d). (plan.md §6.4 / tasks.md P7)
 *
 * 케이스 표는 `tools/targeted-cases.txt` 한 곳에만 있고 JS 오라클(`targeted.mjs`) 과
 * **같은 파일을 읽는다.** `InputGenerators` 는 로직이라 양쪽에 복제하고 하네스로 대조하지만,
 * 이건 데이터라 복제하면 대조할 방법이 없다.
 */
data class TargetedCase(
    val name: String,
    /** 입력을 만들 기본 생성기. 표적 케이스는 "초기 상태" 만 다르다. */
    val gen: Generator,
    val seed: Int,
    val frames: Int,
    /** `(State Spec 필드명, 값)` 목록. 순서대로 적용된다. */
    val setup: List<Pair<String, Int>>,
)

object TargetedCases {

    val all: List<TargetedCase> by lazy { parse(RepoPaths.targetedCases.toFile().readText()) }

    val totalFrames: Long get() = all.sumOf { it.frames.toLong() }

    /**
     * 케이스 번호(1-based) 로 찾는다.
     *
     * 번호를 쓰는 이유는 하네스의 "시드" 자리에 그대로 들어가기 때문이다.
     * `E <n>` 마커와 `--seeds 1..15` 표기를 손대지 않고 재사용한다.
     */
    fun at(index: Int): TargetedCase {
        require(index in 1..all.size) { "케이스 번호는 1..${all.size} 이어야 합니다 (받은 값: $index)" }
        return all[index - 1]
    }

    /** 전체 케이스 번호 표기 (`1..15`). */
    fun allSeedSpec(): String = "1..${all.size}"

    // ─────────────────────────────────────────────────────────────────────────
    // 파싱 — 문법은 `tools/targeted-cases.txt` 상단 주석 참고
    // ─────────────────────────────────────────────────────────────────────────

    private val COMMENT = Regex("""#.*$""")
    private val INT = Regex("""^-?\d+$""")
    private val UINT = Regex("""^\d+$""")

    internal fun parse(text: String): List<TargetedCase> {
        val cases = mutableListOf<TargetedCase>()
        val setups = mutableListOf<MutableList<Pair<String, Int>>>()

        for ((i, raw) in text.split("\n").withIndex()) {
            val line = COMMENT.replace(raw, "").trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("""\s+"""))
            val lineNo = i + 1

            when (parts[0]) {
                "case" -> {
                    require(parts.size == 5) { "${lineNo}행: case 는 '<이름> <생성기> <시드> <프레임수>' 4개 인자" }
                    val (_, name, gen, seed) = parts
                    val frames = parts[4]
                    require(UINT.matches(seed) && UINT.matches(frames)) { "${lineNo}행: 시드·프레임수는 양의 정수" }
                    require(Generator.of(gen).isBaseGenerator) { "${lineNo}행: 표적 케이스의 기본 생성기로 $gen 을 쓸 수 없습니다" }
                    setups += mutableListOf<Pair<String, Int>>()
                    cases += TargetedCase(name, Generator.of(gen), seed.toInt(), frames.toInt(), setups.last())
                }
                "set" -> {
                    require(cases.isNotEmpty()) { "${lineNo}행: set 앞에 case 가 없습니다" }
                    require(parts.size == 3) { "${lineNo}행: set 은 '<필드> <정수>' 2개 인자" }
                    require(INT.matches(parts[2])) { "${lineNo}행: 값은 정수여야 합니다" }
                    setups.last() += parts[1] to parts[2].toInt()
                }
                else -> error("${lineNo}행: 알 수 없는 지시어 ${parts[0]}")
            }
        }

        check(cases.isNotEmpty()) { "표적 케이스가 하나도 없습니다" }
        val dup = cases.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(dup.isEmpty()) { "중복된 케이스 이름: $dup" }
        return cases
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 상태 심기
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `set <필드> <값>` 하나를 적용한다.
     *
     * 필드명은 State Spec (`proto/state_spec.proto`) 의 이름이고 bool 은 0/1 이다.
     * 알 수 없는 이름은 **조용히 무시하지 않고 즉시 실패**시킨다 —
     * 오타 하나가 "표적 케이스인데 아무것도 겨냥하지 않는" 상태를 만든다.
     */
    fun applySetting(physics: PikaPhysics, field: String, value: Int) {
        val dot = field.indexOf('.')
        require(dot >= 0) { "필드명은 '<객체>.<필드>' 형식이어야 합니다: $field" }
        val name = field.substring(dot + 1)
        when (val obj = field.substring(0, dot)) {
            "ball" -> setBall(physics.ball, name, value, field)
            "player1" -> setPlayer(physics.player1, name, value, field)
            "player2" -> setPlayer(physics.player2, name, value, field)
            else -> error("알 수 없는 객체: $obj ($field)")
        }
    }

    /** 케이스의 초기 상태를 심는 콜백. [Harness.runEpisode] 의 `onCreate` 로 넘긴다. */
    fun setupFor(case: TargetedCase): (PikaPhysics) -> Unit = { physics ->
        for ((field, value) in case.setup) applySetting(physics, field, value)
    }

    private fun b(v: Int): Boolean = v != 0

    private fun setBall(ball: Ball, name: String, v: Int, field: String) {
        when (name) {
            "x" -> ball.x = v
            "y" -> ball.y = v
            "x_velocity" -> ball.xVelocity = v
            "y_velocity" -> ball.yVelocity = v
            "fine_rotation" -> ball.fineRotation = v
            "rotation" -> ball.rotation = v
            "punch_effect_radius" -> ball.punchEffectRadius = v
            "punch_effect_x" -> ball.punchEffectX = v
            "punch_effect_y" -> ball.punchEffectY = v
            "is_power_hit" -> ball.isPowerHit = b(v)
            "expected_landing_point_x" -> ball.expectedLandingPointX = v
            "previous_x" -> ball.previousX = v
            "previous_previous_x" -> ball.previousPreviousX = v
            "previous_y" -> ball.previousY = v
            "previous_previous_y" -> ball.previousPreviousY = v
            else -> error("알 수 없는 필드: $field")
        }
    }

    private fun setPlayer(player: Player, name: String, v: Int, field: String) {
        when (name) {
            "x" -> player.x = v
            "y" -> player.y = v
            "y_velocity" -> player.yVelocity = v
            "state" -> player.state = v
            "frame_number" -> player.frameNumber = v
            "diving_direction" -> player.divingDirection = v
            "lying_down_duration_left" -> player.lyingDownDurationLeft = v
            "is_collision_with_ball_happened" -> player.isCollisionWithBallHappened = b(v)
            "normal_status_arm_swing_direction" -> player.normalStatusArmSwingDirection = v
            "delay_before_next_frame" -> player.delayBeforeNextFrame = v
            "computer_boldness" -> player.computerBoldness = v
            "computer_where_to_stand_by" -> player.computerWhereToStandBy = v
            "is_winner" -> player.isWinner = b(v)
            "game_ended" -> player.gameEnded = b(v)
            else -> error("알 수 없는 필드: $field")
        }
    }

    /**
     * 세터 표가 State Spec 과 어긋나지 않는지 확인한다. (`targeted.mjs` 의 짝)
     *
     * 모든 base 필드를 실제로 한 번씩 써 보고, 케이스가 쓰는 필드명이 전부 spec 에 있는지 본다.
     * @return 검사한 필드 수
     */
    fun assertFieldsMatchSpec(): Int {
        val specNames = StateSpec.baseFieldNames.filter { it != "is_ball_touching_ground" }
        val scratch = PikaPhysics(false, false) { 0 }
        for (name in specNames) applySetting(scratch, name, 0) // 없으면 여기서 터진다

        val known = specNames.toSet()
        for (case in all) {
            for ((field, _) in case.setup) {
                check(field in known) { "케이스 ${case.name}: State Spec 에 없는 필드 $field" }
            }
        }
        return specNames.size
    }
}
