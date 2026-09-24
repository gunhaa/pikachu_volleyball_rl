package pika.analysis

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.env.replay.ReplayCodec
import java.nio.file.Files

/**
 * 골든 리플레이의 Kotlin 쪽 회귀. (tasks.md P2, M4-b 의 기준값)
 *
 * Node 없이 돈다 — CI 는 업스트림이 없어 JS 쪽을 못 돌리지만, **기준값 자체**가 흔들리지
 * 않는다는 것은 여기서 매번 본다. 기준값이 흔들리면 JS 대조는 의미를 잃는다.
 */
class GoldenReplayTest {

    private val chains = Json.parse(Files.readString(GoldenReplays.dir.resolve("chains.json"))).obj()
    private val games = chains["games"].arr().map { it.obj() }

    @Test
    @DisplayName("커밋된 골든: 재생 검증 통과, 체인 해시 = chains.json, 합계 ≥ 100,000 프레임")
    fun committedGoldenReplays() {
        var total = 0L
        for (g in games) {
            val replay = ReplayCodec.decode(Files.readAllBytes(GoldenReplays.dir.resolve(g.str("file"))))
            val chain = ReplayChain.compute(replay)
            assertTrue(chain.play.ok, "${g.str("file")}: ${chain.play.mismatch}")
            assertEquals(g.str("final"), chain.finalHex, g.str("file"))
            assertEquals(g["checkpoints"].arr(), chain.checkpoints, g.str("file"))
            assertEquals(g.int("frames"), replay.frameCount)
            total += replay.frameCount
        }
        assertTrue(total >= 100_000, "골든 합계 $total 프레임")
        assertEquals(total, (chains["totalFrames"] as Number).toLong())
    }

    @Test
    @DisplayName("골든은 결정론이다: 지금 코드로 다시 만든 바이트 = 커밋된 바이트")
    fun goldenIsDeterministic() {
        val regenerated = GoldenReplays.generate()
        assertEquals(games.map { it.str("file") }, regenerated.map { "${it.first.name}.pkr" })
        for ((case, replay) in regenerated) {
            val committed = Files.readAllBytes(GoldenReplays.dir.resolve("${case.name}.pkr"))
            assertArrayEquals(committed, ReplayCodec.encode(replay), case.name)
        }
    }

    @Test
    @DisplayName("골든 세트가 계획한 갈래를 전부 탄다 (§7.3)")
    fun goldenCoversCases() {
        val replays = games.associate { it.str("file") to ReplayCodec.decode(Files.readAllBytes(GoldenReplays.dir.resolve(it.str("file")))) }
        val all = replays.values
        assertTrue(all.any { it.seedMode.name == "GAME" } && all.any { it.seedMode.name == "RALLY" }, "두 시드 규약")
        assertTrue(all.any { !it.ended }, "미완 게임")
        assertTrue(all.any { it.fixedBoldness.p1 != it.fixedBoldness.p2 }, "진영별 boldness")
        assertTrue(all.any { it.slots.p1.isFsm && it.slots.p2.isFsm }, "FSM vs FSM")
        assertTrue(all.any { !it.slots.p1.isFsm && !it.slots.p2.isFsm }, "External vs External")
        assertTrue(all.any { it.slots.p1.isFsm != it.slots.p2.isFsm && it.firstServeIsPlayer2 }, "서브 오른쪽 + External")
        for (mode in listOf("GAME", "RALLY")) {
            assertTrue(
                all.any { r -> r.seedMode.name == mode && r.rallyOutcomes.count { it.toInt() == -1 } >= 10 },
                "$mode 규약의 truncation",
            )
        }
    }
}
