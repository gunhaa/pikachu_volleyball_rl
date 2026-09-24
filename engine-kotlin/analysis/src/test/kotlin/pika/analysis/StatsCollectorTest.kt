package pika.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.env.FixedBoldness
import pika.env.Slots
import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import pika.env.replay.SeedMode
import java.nio.file.Files

/** 통계 정의 (tasks.md P5, plan.md §6.4). */
class StatsCollectorTest {

    /** 헤더만 쓰이는 가짜 리플레이 — 랠리 2개, 두 번째는 미완. */
    private val fake = Replay(
        slots = Slots.EXTERNAL_VS_EXTERNAL, firstServeIsPlayer2 = true, ended = false, edgeTrigger = false,
        seedMode = SeedMode.RALLY, winningScore = 15, fixedBoldness = FixedBoldness.RANDOM, maxRallyFrames = 0,
        seeds = intArrayOf(11, 22), rallyFrames = intArrayOf(8, 2), rallyOutcomes = byteArrayOf(0, -2),
        finalScore = intArrayOf(1, 0), frameCount = 10, inputs = ByteArray(20),
    )

    @Test
    @DisplayName("손으로 만든 시나리오: 파워히트 성공 1 · 실패 1 (상대 터치) · 미완 실패 1, 착지 x, 서브권")
    fun handScenario() {
        val c = StatsCollector(fake)
        //         touch1 touch2 powerHit punchX frame outcome
        c.observe(false, false, false, 0, 1, null)
        c.observe(true, false, true, 0, 2, null)   // p1 파워히트 (A)
        c.observe(true, false, true, 0, 3, null)   // 계속 닿아 있음 — 새 터치 아님
        c.observe(false, false, true, 0, 4, null)
        c.observe(false, true, false, 0, 5, null)  // p2 터치 → A 실패
        c.observe(false, false, false, 0, 6, null)
        c.observe(true, false, true, 0, 7, null)   // p1 파워히트 (B)
        c.observe(true, false, true, 300, 8, 0)    // p1 득점, 착지 x = 300 → B 성공
        // 랠리 1 — 충돌 플래그는 랠리 시작에서 내려가므로 첫 프레임의 true 는 새 터치다.
        c.observe(true, true, true, 0, 1, null)    // 둘 다 닿음 → player2 에게만 판정 (C)
        c.observe(false, false, true, 0, 2, null)
        c.finish()                                 // 미완 → C 실패

        assertEquals(2, c.rallies.size)
        val r0 = c.rallies[0]
        assertEquals(true, r0.serverP2, "첫 서브는 헤더를 따른다")
        assertEquals(0, r0.outcome)
        assertEquals(8, r0.frames)
        assertEquals(300, r0.landingX)
        assertEquals(11, r0.seed)
        assertEquals(listOf(2, 1), r0.touches.toList())
        assertEquals(listOf(2, 0), r0.powerHits.toList())

        val r1 = c.rallies[1]
        assertEquals(false, r1.serverP2, "p1 이 득점했으니 p1 서브")
        assertEquals(-2, r1.outcome)
        assertEquals(2, r1.frames)
        assertNull(r1.landingX, "득점 랠리만 착지 x 가 있다")
        assertEquals(listOf(1, 1), r1.touches.toList())
        assertEquals(listOf(0, 1), r1.powerHits.toList())

        assertEquals(
            listOf(Triple(0, 2, false), Triple(0, 7, true), Triple(1, 1, false)),
            c.powerHits.map { Triple(it.rallyIdx, it.frame, it.success) },
        )
        assertEquals(listOf(0, 0, 1), c.powerHits.map { it.hitter })
    }

    @Test
    @DisplayName("골든 전부: 랠리 수 · 프레임 합 · 결과가 리플레이와 같고, 파워히트 ≤ 터치")
    fun goldenConsistency() {
        var hits = 0
        var successes = 0
        for (file in Files.list(GoldenReplays.dir).filter { it.toString().endsWith(".pkr") }.sorted().toList()) {
            val replay = ReplayCodec.decode(Files.readAllBytes(file))
            val c = StatsCollector(replay)
            assertTrue(ReplayChain.compute(replay, c::onFrame).play.ok)
            c.finish()
            assertEquals(replay.rallyCount, c.rallies.size, "$file")
            assertEquals(replay.frameCount, c.rallies.sumOf { it.frames }, "$file")
            assertEquals(replay.rallyOutcomes.map { it.toInt() }, c.rallies.map { it.outcome }, "$file")
            for (r in c.rallies) {
                assertTrue(r.powerHits[0] <= r.touches[0] && r.powerHits[1] <= r.touches[1], "$file 랠리 ${r.idx}")
                if (r.outcome >= 0) {
                    val x = r.landingX!!
                    assertTrue(x in 0..432, "착지 x $x")
                    assertTrue(StatsCollector.landingBin(x) in 0 until StatsCollector.LANDING_BINS)
                    assertEquals(r.outcome == 1, x < 216, "득점 판정과 같은 필드 — 왼쪽에 떨어지면 p2 득점")
                }
            }
            hits += c.powerHits.size
            successes += c.powerHits.count { it.success }
        }
        assertTrue(hits > 0 && successes in 1 until hits, "파워히트 $hits, 성공 $successes")
    }
}
