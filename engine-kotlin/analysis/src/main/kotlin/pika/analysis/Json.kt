package pika.analysis

/**
 * 아주 작은 JSON 파서 · 작성기. manifest.jsonl · chains.json · HTTP API 가 쓴다.
 *
 * 의존성을 들이지 않는 이유: 여기서 다루는 JSON 은 우리가 쓰는 몇 가지 모양뿐이고,
 * 라이브러리 하나가 `analysis` 의 클래스패스 전부를 끌고 온다. 숫자는 Long 또는 Double 로 푼다.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "JSON 뒤에 남은 글자가 있습니다 (위치 ${p.i})" }
        return v
    }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> {
                require(v.isFinite()) { "JSON 은 NaN/Infinity 를 못 담습니다" }
                sb.append(v.toString())
            }
            is Float -> write(v.toDouble(), sb)
            is String -> quote(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k as String, sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(e, sb)
                }
                sb.append(']')
            }
            is IntArray -> write(v.toList(), sb)
            else -> throw IllegalArgumentException("JSON 으로 못 쓰는 값: ${v::class}")
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON 이 갑자기 끝났습니다" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON 위치 $i: '$c'")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "JSON 위치 $i: $word 기대" }
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "JSON 위치 $i: ':' 기대" }
                i++
                out[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("JSON 위치 ${i - 1}: ',' 또는 '}' 기대")
                }
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("JSON 위치 ${i - 1}: ',' 또는 ']' 기대")
                }
            }
        }

        fun str(): String {
            require(s[i] == '"') { "JSON 위치 $i: 문자열 기대" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("JSON 위치 $i: 알 수 없는 이스케이프 \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

/** JSON 객체 접근 도우미. */
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as List<Any?>

fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()
fun Map<String, Any?>.str(key: String): String = this[key] as String
fun Map<String, Any?>.bool(key: String): Boolean = this[key] as Boolean
