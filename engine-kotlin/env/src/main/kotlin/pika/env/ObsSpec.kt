package pika.env

import java.security.MessageDigest

/**
 * 관측 레이아웃의 Kotlin 쪽 목록. 단일 진실 공급원은 `proto/obs_spec.proto` 다. (NFR-5)
 *
 * [assertMatchesProto] 가 아래 목록과 .proto 를 대조한다. Python 쪽도 같은 대조를 한다.
 * 세 번째 소비자(장래의 JS) 를 위해 정규화 상수는 .proto 주석에 숫자로 박혀 있다.
 *
 * ⚠️ 여기를 고치면 관측이 바뀐다 = P4 의 골든 체인 해시가 깨진다. 그것이 정상이다.
 *    의도한 변경이면 골든을 갱신하고 **무엇을 왜 바꿨는지 커밋 메시지에 적는다.**
 */
object ObsSpec {

    /** 관측 레이아웃을 바꾸는 플래그들. 이것이 곧 레이아웃의 신원이다. */
    data class Options(
        /** 공의 예상 착지점을 넣는가. plan.md §5.1 — 기본 on. */
        val includeExpectedLanding: Boolean = true,
        /** 진영 플래그를 넣는가. plan.md §5.3 — 기본 off. */
        val includeSideFlag: Boolean = false,
    )

    /** `PlayerObs` 의 필드 이름. 15개. */
    val playerFieldNames: List<String> = listOf(
        "x",
        "y",
        "y_velocity",
        "state_is_normal",
        "state_is_jumping",
        "state_is_jumping_and_power_hitting",
        "state_is_diving",
        "state_is_lying_down_after_diving",
        "state_is_win",
        "state_is_lost",
        "frame_number",
        "diving_direction",
        "lying_down_duration_left",
        "is_collision_with_ball_happened",
        "delay_before_next_frame",
    )

    /** `BallObs` 의 필드 이름. 기본 6개. */
    fun ballFieldNames(opts: Options): List<String> = buildList {
        add("x")
        add("y")
        add("x_velocity")
        add("y_velocity")
        add("is_power_hit")
        if (opts.includeExpectedLanding) add("expected_landing_point_x")
    }

    /** `MatchObs` 의 필드 이름. 기본 4개. */
    fun matchFieldNames(opts: Options): List<String> = buildList {
        add("my_score")
        add("opponent_score")
        add("score_diff")
        add("i_am_serving")
        if (opts.includeSideFlag) add("side_flag")
    }

    /** 평탄화된 필드 이름 순서. 이 순서가 곧 float32 배열의 순서다. */
    fun fieldNames(opts: Options = Options()): List<String> =
        playerFieldNames.map { "me.$it" } +
            playerFieldNames.map { "opponent.$it" } +
            ballFieldNames(opts).map { "ball.$it" } +
            matchFieldNames(opts).map { "match.$it" }

    /** 관측 차원. 기본 구성에서 40. */
    fun dim(opts: Options = Options()): Int =
        2 * playerFieldNames.size + ballFieldNames(opts).size + matchFieldNames(opts).size

    /**
     * 레이아웃 해시 — SHA-256("\n" 으로 이은 활성 필드 이름 목록) 을 hex 64자로.
     *
     * 서버의 `Health` 가 이 값을 돌려주고 클라이언트가 자기 값과 대조한다.
     * 어긋난 서버에 붙으면 관측이 **조용히 뒤섞이는 대신** 즉시 실패한다.
     */
    fun layoutHash(opts: Options = Options()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(fieldNames(opts).joinToString("\n").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // .proto 대조 (state_spec.proto 를 다루는 방식과 같다)
    // ─────────────────────────────────────────────────────────────────────────

    private val MESSAGE_RE = Regex("""message\s+(\w+)\s*\{((?:[^{}]|\n)*)\}""")
    private val FIELD_RE = Regex("""(\w+)\s+(\w+)\s*=\s*(\d+)\s*;""")
    private val OPTIONAL_RE = Regex("""//\s*@optional:(\w+)""")

    private data class ProtoField(val type: String, val name: String, val number: Int, val optionalFlag: String?)

    /**
     * .proto 를 파싱한다. 주석을 통째로 버리지 않는다 — `@optional:` 태그가 주석에 있다.
     * 그래서 줄 단위로 읽으면서 직전 주석 줄들에 붙은 태그를 다음 필드에 붙인다.
     */
    private fun parseProto(text: String): Map<String, List<ProtoField>> {
        val out = mutableMapOf<String, List<ProtoField>>()
        for (m in MESSAGE_RE.findAll(text)) {
            val fields = mutableListOf<ProtoField>()
            var pendingFlag: String? = null
            for (raw in m.groupValues[2].split("\n")) {
                val line = raw.trim()
                OPTIONAL_RE.find(line)?.let { pendingFlag = it.groupValues[1] }
                if (line.startsWith("//")) continue
                val f = FIELD_RE.find(line.substringBefore("//")) ?: continue
                fields += ProtoField(f.groupValues[1], f.groupValues[2], f.groupValues[3].toInt(), pendingFlag)
                pendingFlag = null
            }
            out[m.groupValues[1]] = fields.sortedBy { it.number }
        }
        return out
    }

    private fun flatten(messages: Map<String, List<ProtoField>>, root: String, opts: Options): List<String> {
        val enabled = mapOf(
            "obs_include_expected_landing" to opts.includeExpectedLanding,
            "obs_include_side_flag" to opts.includeSideFlag,
        )
        val out = mutableListOf<String>()
        fun walk(msgName: String, prefix: String) {
            val fields = messages[msgName] ?: error(".proto 에 message $msgName 이 없습니다")
            for (f in fields) {
                if (f.optionalFlag != null) {
                    val on = enabled[f.optionalFlag]
                        ?: error("알 수 없는 @optional 플래그: ${f.optionalFlag} (${f.name})")
                    if (!on) continue
                }
                val path = if (prefix.isEmpty()) f.name else "$prefix.${f.name}"
                when {
                    messages.containsKey(f.type) -> walk(f.type, path)
                    f.type == "float" -> out += path
                    else -> error("예상치 못한 타입 ${f.type} ($path). Obs Spec 은 전부 float 다.")
                }
            }
        }
        walk(root, "")
        return out
    }

    /**
     * 이 객체의 필드 목록이 `proto/obs_spec.proto` 와 일치하는지 확인한다.
     * 플래그 조합마다 따로 본다 — 조건부 필드가 빠지는 위치까지 계약이기 때문이다.
     */
    fun assertMatchesProto(protoText: String = EnvPaths.obsSpecProto.toFile().readText()) {
        val messages = parseProto(protoText)
        for (includeLanding in listOf(true, false)) {
            for (includeSide in listOf(false, true)) {
                val opts = Options(includeLanding, includeSide)
                val expected = flatten(messages, "Observation", opts)
                val actual = fieldNames(opts)
                check(expected == actual) {
                    buildString {
                        appendLine("Obs Spec 불일치 (includeExpectedLanding=$includeLanding, includeSideFlag=$includeSide)")
                        appendLine("  .proto     : ${expected.joinToString(", ")}")
                        appendLine("  ObsSpec.kt : ${actual.joinToString(", ")}")
                    }
                }
                check(expected.size == dim(opts)) { "dim() 이 실제 필드 수와 다릅니다" }
            }
        }
    }
}
