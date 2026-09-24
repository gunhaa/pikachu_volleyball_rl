package pika.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** 기준선 경로의 축소판 (tasks.md P6). 전체 800게임은 scripts/baseline-replays.sh 가 돈다. */
class BaselinesTest {

    @Test
    @DisplayName("baseline-fsm 축소판: DB 집계 = GameEvaluator.evaluate (양 진영 = 같은 게임의 두 시점)")
    fun fsmBaselineMatchesEvaluator(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            val expected = Baselines.writeFsm(dir, games = 6, baseSeed = 0)
            assertEquals(6, Ingest.ingestDir(conn, dir, kind = "baseline").inserted)
            val db = Baselines.report(conn, Baselines.FSM_SET)
            assertEquals(emptyList<String>(), Baselines.compare(db, expected))
            assertTrue((db["mean_replay_bytes"] as Double) <= 256.0, "M4-g FSM vs FSM ≤ 256 B")
            // 양쪽이 같은 게임이므로 p1 득점 = p2 실점.
            val left = db["as_left"].obj()
            val right = db["as_right"].obj()
            assertEquals(left["points_for"], right["points_against"])
            assertEquals(6L, (left["wins"] as Long) + (right["wins"] as Long))
        }
    }

    @Test
    @DisplayName("report: External 슬롯이 주체 — 정책의 진영별로 나뉘고, 미결은 unresolved 만 센다")
    fun reportUsesExternalSide(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            val replays = DbTestSupport.writeEvalDir(dir, "unit-eval", games = 4)
            Ingest.ingestDir(conn, dir)
            val db = Baselines.report(conn, "unit-eval")
            val left = db["as_left"].obj()
            assertEquals(4L, left["games"])
            assertEquals(replays.sumOf { it.finalScore[0] }.toLong(), left["points_for"])
            assertEquals(replays.sumOf { r -> r.rallyOutcomes.count { it.toInt() == 0 } }.toLong(), left["rally_wins"])
            assertEquals(0L, db["as_right"].obj()["games"], "정책은 왼쪽에만 있었다")
        }
    }
}
