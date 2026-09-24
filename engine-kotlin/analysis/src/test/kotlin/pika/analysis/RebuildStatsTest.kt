package pika.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection

/** 통계 적재 · 재계산 (tasks.md P5). MySQL 이 없으면 건너뛴다. */
class RebuildStatsTest {

    private fun dump(conn: Connection, sql: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).joinToString("|") { rs.getString(it) ?: "null" }) }
        }
    }

    private fun rallies(conn: Connection) = dump(conn, "SELECT * FROM rally ORDER BY game_id, idx")
    private fun hits(conn: Connection) = dump(conn, "SELECT * FROM power_hit ORDER BY game_id, rally_idx, frame, hitter")

    @Test
    @DisplayName("통계의 랠리 수 · 프레임 합 = 게임 행의 값, rebuild-stats 전후 통계 테이블 동일")
    fun rebuildIsIdentity(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            DbTestSupport.writeEvalDir(dir, "unit-eval", games = 6)
            Ingest.ingestDir(conn, dir)
            val agg = dump(
                conn,
                """SELECT g.id, g.rallies, g.frames, COUNT(r.idx), SUM(r.frames) FROM game g
                   JOIN rally r ON r.game_id = g.id GROUP BY g.id, g.rallies, g.frames""",
            )
            assertEquals(6, agg.size)
            for (row in agg) {
                val (_, rallies, frames, n, sum) = row.split("|")
                assertEquals(rallies, n, row)
                assertEquals(frames, sum, row)
            }
            val beforeR = rallies(conn)
            val beforeH = hits(conn)
            assertTrue(beforeH.isNotEmpty(), "무작위 정책도 파워히트를 친다")

            assertEquals(6, Ingest.rebuildStats(conn, "unit-eval"))
            assertEquals(beforeR, rallies(conn))
            assertEquals(beforeH, hits(conn))

            // 통계를 지워도 리플레이만 있으면 되살아난다 — 리플레이가 단일 진실 공급원이다.
            conn.createStatement().use { it.executeUpdate("DELETE FROM rally"); it.executeUpdate("DELETE FROM power_hit") }
            assertEquals(6, Ingest.rebuildStats(conn))
            assertEquals(beforeR, rallies(conn))
            assertEquals(beforeH, hits(conn))
        }
    }
}
