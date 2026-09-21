package pika.conformance

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Node 오라클 자식 프로세스.
 *
 * 프로세스는 **한 번만** 띄우고 전체 시드를 한 프로세스가 처리한다.
 * 7,000회 spawn 하면 그 오버헤드가 물리 계산보다 커진다. (plan.md §5.2)
 *
 * ⚠️ 오라클이 죽은 것을 "불일치" 로 오인하면 안 된다.
 *    stderr 는 따로 모아두고, 스트림이 예상보다 일찍 끝나면 종료 코드와 stderr 를 함께 보고한다.
 */
class JsOracle private constructor(
    private val process: Process,
    val header: Header,
    private val reader: BufferedReader,
    private val stderrThread: Thread,
    private val stderrBuffer: StringBuilder,
) : Closeable {

    data class Header(
        val spec: String,
        val mode: String,
        val gen: String,
        val frames: Int,
        val strict: Boolean,
        val seedCount: Int,
        val intCount: Int,
    )

    /** 다음 줄. 스트림이 끝나면 null. */
    fun readLine(): String? = reader.readLine()

    /** 오라클이 비정상 종료했는지 확인하고, 그렇다면 stderr 와 함께 예외를 던진다. */
    fun assertHealthy(context: String) {
        val exit = if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else null
        if (exit != null && exit != 0) {
            error("JS 오라클이 종료 코드 $exit 로 죽었습니다 ($context)\n--- stderr ---\n${stderr()}")
        }
    }

    fun stderr(): String {
        stderrThread.join(2_000)
        return stderrBuffer.toString().trim()
    }

    override fun close() {
        runCatching { reader.close() }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        stderrThread.join(2_000)
    }

    companion object {
        /**
         * 업스트림 `package.json` 에 `"type": "module"` 이 없어 Node 가
         * `physics.js` 를 CommonJS 로 파싱해본 뒤 ESM 으로 재파싱하며 경고를 낸다.
         * 오라클의 신뢰성은 **업스트림을 수정하지 않는 것**에서 오므로 파일을 건드리지 않고
         * 이 경고만 끈다.
         */
        private const val DISABLE_WARNING = "--disable-warning=MODULE_TYPELESS_PACKAGE_JSON"

        private val HEADER_STR = Regex(""""(\w+)"\s*:\s*"([^"]*)"""")
        private val HEADER_NUM = Regex(""""(\w+)"\s*:\s*(-?\d+|true|false)""")

        fun start(
            seeds: String,
            frames: Int,
            gen: Generator,
            mode: String,
            strict: Boolean = false,
            node: String = "node",
        ): JsOracle {
            require(RepoPaths.upstreamIsPresent()) {
                "upstream/ 이 없습니다. scripts/fetch-upstream.sh 를 먼저 실행하세요."
            }

            val cmd = buildList {
                add(node)
                add(DISABLE_WARNING)
                add(RepoPaths.oracleScript.toString())
                addAll(listOf("--seeds", seeds))
                addAll(listOf("--frames", frames.toString()))
                addAll(listOf("--gen", gen.cliName))
                addAll(listOf("--mode", mode))
                if (strict) add("--strict")
            }

            val process = ProcessBuilder(cmd)
                .directory(RepoPaths.oracleDir.toFile())
                .start()

            val stderrBuffer = StringBuilder()
            val stderrThread = Thread({
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                }
            }, "js-oracle-stderr").apply { isDaemon = true; start() }

            // 해시 줄은 ASCII 다. 1MB 버퍼로 파이프에서 크게 읽어 온다.
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.US_ASCII), 1 shl 20)
            val headerLine = reader.readLine()
                ?: run {
                    process.waitFor(5, TimeUnit.SECONDS)
                    stderrThread.join(2_000)
                    error(
                        "JS 오라클이 헤더도 내보내지 못하고 종료했습니다 (exit=${process.exitValue()})\n" +
                            "--- stderr ---\n${stderrBuffer.toString().trim()}"
                    )
                }
            check(headerLine.startsWith("# ")) { "헤더가 '# ' 로 시작하지 않습니다: $headerLine" }

            val strs = HEADER_STR.findAll(headerLine).associate { it.groupValues[1] to it.groupValues[2] }
            val nums = HEADER_NUM.findAll(headerLine).associate { it.groupValues[1] to it.groupValues[2] }
            val header = Header(
                spec = strs.getValue("spec"),
                mode = strs.getValue("mode"),
                gen = strs.getValue("gen"),
                frames = nums.getValue("frames").toInt(),
                strict = nums.getValue("strict").toBoolean(),
                seedCount = nums.getValue("seedCount").toInt(),
                intCount = nums.getValue("intCount").toInt(),
            )

            // 오라클이 우리가 요청한 설정 그대로 돌고 있는지 확인한다.
            check(header.spec == "v1") { "State Spec 버전이 다릅니다: ${header.spec}" }
            check(header.mode == mode) { "mode 불일치: ${header.mode} != $mode" }
            check(header.gen == gen.cliName) { "gen 불일치: ${header.gen} != ${gen.cliName}" }
            check(header.frames == frames) { "frames 불일치: ${header.frames} != $frames" }
            check(header.strict == strict) { "strict 불일치: ${header.strict} != $strict" }
            check(header.intCount == StateSpec.intCount(strict)) {
                "intCount 불일치: ${header.intCount} != ${StateSpec.intCount(strict)}"
            }

            return JsOracle(process, header, reader, stderrThread, stderrBuffer)
        }
    }
}
