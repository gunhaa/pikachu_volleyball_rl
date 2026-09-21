package pika.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Node 오라클 배관을 본다. (tasks.md P5)
 *
 * 오라클이 죽거나 다른 설정으로 도는 것을 "불일치" 로 오인하면,
 * 포팅이 멀쩡한데 엉뚱한 곳을 파게 된다. 그래서 배관부터 따로 검증한다.
 *
 * 업스트림이 없으면(CI) 건너뛴다 — NFR-2.
 */
class JsOracleTest {

    @BeforeEach
    fun requireUpstream() {
        assumeTrue(RepoPaths.upstreamIsPresent(), "upstream/ 이 없습니다 (scripts/fetch-upstream.sh)")
    }

    @Test
    @DisplayName("헤더가 요청한 설정을 그대로 반영한다")
    fun headerReflectsRequest() {
        JsOracle.start("1..3", frames = 7, gen = Generator.BIASED, mode = "frame-hash").use { o ->
            assertEquals("v1", o.header.spec)
            assertEquals("frame-hash", o.header.mode)
            assertEquals("biased", o.header.gen)
            assertEquals(7, o.header.frames)
            assertEquals(3, o.header.seedCount)
            assertEquals(44, o.header.intCount)
            assertEquals(false, o.header.strict)
        }
    }

    @Test
    @DisplayName("엄격 모드는 sound 8필드가 더 붙는다")
    fun strictModeAddsSoundFields() {
        JsOracle.start("1", frames = 3, gen = Generator.UNIFORM, mode = "frame-hash", strict = true).use { o ->
            assertEquals(52, o.header.intCount)
            assertEquals(true, o.header.strict)
        }
    }

    @Test
    @DisplayName("같은 시드를 두 번 돌리면 같은 해시가 나온다 (오라클 결정론)")
    fun oracleIsDeterministic() {
        fun hashes(): List<String> =
            JsOracle.start("5", frames = 40, gen = Generator.FSM, mode = "frame-hash").use { o ->
                assertEquals("E 5", o.readLine())
                List(40) { o.readLine()!! }
            }
        val a = hashes()
        val b = hashes()
        assertEquals(a, b)
        assertEquals(40, a.size)
        assertTrue(a.all { it.length == 16 })
    }

    @Test
    @DisplayName("시드가 다르면 결과가 다르다")
    fun differentSeedsDiffer() {
        fun firstHash(seed: Int): String =
            JsOracle.start("$seed", frames = 5, gen = Generator.UNIFORM, mode = "frame-hash").use { o ->
                o.readLine()
                o.readLine()!!
            }
        assertNotEquals(firstHash(1), firstHash(2))
    }

    @Test
    @DisplayName("오라클이 인자 오류로 죽으면 stderr 와 함께 알려준다 — 불일치로 오인하지 않는다")
    fun badArgumentsReportStderr() {
        val e = assertThrows<IllegalStateException> {
            JsOracle.start("not-a-seed", frames = 5, gen = Generator.UNIFORM, mode = "frame-hash")
        }
        assertTrue(e.message!!.contains("js-oracle"), "stderr 내용이 실려야 한다: ${e.message}")
        assertTrue(e.message!!.contains("잘못된 시드 표기"), "원인이 실려야 한다: ${e.message}")
    }

    @Test
    @DisplayName("node 실행 파일이 없으면 즉시 실패한다")
    fun missingNodeFailsFast() {
        assertThrows<java.io.IOException> {
            JsOracle.start("1", frames = 5, gen = Generator.UNIFORM, mode = "frame-hash", node = "node-does-not-exist")
        }
    }
}
