package pika.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 비교 하네스 자체를 검증한다. (tasks.md P5)
 *
 * 비교기는 **불일치를 정확히 짚어낼 때만** 쓸모가 있다.
 * 불일치를 놓치는 비교기는 초록불을 주면서 아무것도 증명하지 않는다.
 *
 * ⚠️ 지금은 `PhysicsEngine` 이 no-op 이라 프레임 0 부터 갈라지는 것이 **정상**이다 (P6 미완).
 *    P6 이 끝나면 불일치가 사라지고, 그때는 [ConformanceTest] 가 이 역할을 넘겨받는다.
 *    그래서 이 테스트는 "일치하거나, 불일치라면 반드시 특정된다" 를 주장한다.
 */
class LockstepSelfTest {

    @BeforeEach
    fun requireUpstream() {
        assumeTrue(RepoPaths.upstreamIsPresent(), "upstream/ 이 없습니다 (scripts/fetch-upstream.sh)")
    }

    @Test
    @DisplayName("불일치가 있다면 시드·프레임·필드까지 특정된다")
    fun mismatchIsLocalized() {
        val result = Lockstep(frames = 10, gen = Generator.UNIFORM).run("1..3")

        if (result.allMatched) {
            // P6 완료 후의 정상 경로.
            assertEquals(3, result.episodes)
            assertEquals(30L, result.framesCompared)
            return
        }

        val m = result.mismatch!!
        assertTrue(m.seed in 1..3, "시드가 범위를 벗어났다: ${m.seed}")
        assertTrue(m.frame in 0..9, "프레임이 범위를 벗어났다: ${m.frame}")
        assertTrue(m.expected != m.actual, "해시가 같은데 불일치로 보고되었다")

        // 드릴다운이 실제로 필드를 짚어내는가 — 이것이 없으면 디버깅이 불가능하다.
        val d = Drilldowns.of(m)
        assertTrue(d.diffs.isNotEmpty(), "해시는 다른데 필드 차이가 나오지 않았다:\n${d.report()}")
        assertTrue(d.input.size == 2, "입력이 두 플레이어분 나와야 한다")
        assertTrue(
            d.minimalRepro().contains("seed=${m.seed}"),
            "최소 재현 케이스에 시드가 들어가야 한다:\n${d.minimalRepro()}",
        )

        println(d.report())
        println("최소 재현 케이스:\n${d.minimalRepro()}")
    }

    @Test
    @DisplayName("비교 대상 프레임 수가 정확하다")
    fun comparesEveryFrame() {
        // 리셋 프로브는 엔진과 무관하므로 항상 전부 일치한다. 프레임 수 계산을 여기서 확인한다.
        val result = Lockstep(frames = 12, gen = Generator.UNIFORM, probeResets = true).run("1..5")
        assertTrue(result.allMatched, "리셋 프로브가 갈라졌다: ${result.mismatch}")
        assertEquals(5, result.episodes)
        assertEquals(60L, result.framesCompared)
    }

    @Test
    @DisplayName("시드 표기 파싱")
    fun seedSpecParsing() {
        assertEquals(listOf(42), Lockstep.parseSeeds("42"))
        assertEquals(listOf(1, 2, 3), Lockstep.parseSeeds("1..3"))
        assertEquals(listOf(1, 2, 7, 9), Lockstep.parseSeeds("1..2,7,9"))
    }
}
