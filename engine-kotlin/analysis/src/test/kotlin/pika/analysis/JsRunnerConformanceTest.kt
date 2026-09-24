package pika.analysis

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pika.conformance.RepoPaths
import pika.env.replay.ReplayCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JS 경기 러너 ≡ Kotlin — Gradle 에서 Node 를 부른다. (tasks.md P2, M4-b · M4-e · M4-j)
 *
 * `JsOracleTest` 와 같은 방식: 업스트림이 없으면(CI — 라이선스 때문에 받지 않는다) 건너뛰고,
 * 업스트림은 있는데 **Node 가 없으면 실패**한다. 조용히 건너뛰면 JS 동치가 증명되지 않은 채
 * 초록불이 켜진다.
 */
class JsRunnerConformanceTest {

    private val viewer: Path = RepoPaths.root.resolve("viewer-web")

    @BeforeEach
    fun requireUpstream() {
        assumeTrue(RepoPaths.upstreamIsPresent(), "upstream/ 이 없습니다 (scripts/fetch-upstream.sh)")
    }

    private fun node(vararg args: String): String {
        val p = ProcessBuilder(listOf("node", "--disable-warning=MODULE_TYPELESS_PACKAGE_JSON") + args)
            .directory(viewer.toFile())
            .redirectErrorStream(true)
            .start() // node 가 없으면 IOException — 실패다
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(5, TimeUnit.MINUTES)) { "node 가 5분 안에 끝나지 않았습니다" }
        check(p.exitValue() == 0) { "node ${args.joinToString(" ")} 실패 (exit ${p.exitValue()}):\n$out" }
        return out
    }

    @Test
    @DisplayName("viewer-web 테스트 전부: 골든 체인 100% (M4-b) · 렌더링 격리 (M4-e) · 라이브 → 재생 (M4-j JS 쪽)")
    fun viewerWebTests() {
        val out = node("--test", "test/*.test.mjs")
        println(out.lines().filter { it.startsWith("ℹ") }.joinToString("\n"))
        assertTrue(out.contains("ℹ fail 0"), out)
    }

    @Test
    @DisplayName("M4-j: JS 라이브 기록 → Kotlin ReplayPlayer 재생 체인 = 라이브 중 JS 체인, 바이트 왕복 동일")
    fun liveRecordingsReplayInKotlin(@TempDir dir: Path) {
        node("test/export-live.mjs", dir.toString())
        val games = Json.parse(Files.readString(dir.resolve("chains.json"))).obj()["games"].arr().map { it.obj() }
        assertTrue(games.size >= 6)
        for (g in games) {
            val bytes = Files.readAllBytes(dir.resolve(g.str("file")))
            val replay = ReplayCodec.decode(bytes)
            // JS 기록기가 Kotlin 과 같은 바이트를 쓴다 — 다시 인코드해도 같다.
            assertArrayEquals(bytes, ReplayCodec.encode(replay), g.str("file"))
            val chain = ReplayChain.compute(replay)
            assertTrue(chain.play.ok, "${g.str("file")}: ${chain.play.mismatch}")
            assertEquals(g.int("frames"), replay.frameCount, g.str("file"))
            assertEquals(g.str("final"), chain.finalHex, g.str("file"))
        }
    }
}
