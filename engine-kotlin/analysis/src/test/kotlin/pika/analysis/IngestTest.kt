package pika.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pika.env.replay.ReplayCodec
import pika.env.replay.ReplayPlayer
import java.nio.file.Files
import java.nio.file.Path

/** 적재 — 검증한 뒤에만, 전부 아니면 전무. (tasks.md P4, FR-9) */
class IngestTest {

    @Test
    @DisplayName("적재: 게임 행 · 참가자 · 체인이 리플레이와 같다")
    fun ingestsVerifiedGames(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            val replays = DbTestSupport.writeEvalDir(dir, "unit-eval", games = 5)
            val result = Ingest.ingestDir(conn, dir)
            assertEquals(5, result.inserted)
            assertEquals(5, DbTestSupport.count(conn, "game"))
            assertEquals(2, DbTestSupport.count(conn, "participant"), "정책 하나 + FSM 하나")
            conn.createStatement().use { st ->
                st.executeQuery("SELECT score_p1, score_p2, frames, rallies, chain_sha256, replay FROM game ORDER BY game_in_env").use { rs ->
                    for (r in replays) {
                        assertTrue(rs.next())
                        assertEquals(r.finalScore[0], rs.getInt(1))
                        assertEquals(r.finalScore[1], rs.getInt(2))
                        assertEquals(r.frameCount, rs.getInt(3))
                        assertEquals(r.rallyCount, rs.getInt(4))
                        assertEquals(ReplayChain.compute(r).finalHex, rs.getString(5))
                        assertTrue(ReplayCodec.encode(r).contentEquals(rs.getBytes(6)))
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("같은 디렉터리를 두 번 적재해도 행 수 불변 (replay_sha256 중복은 건너뜀)")
    fun ingestIsIdempotent(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            DbTestSupport.writeEvalDir(dir, "unit-eval", games = 4)
            assertEquals(4, Ingest.ingestDir(conn, dir).inserted)
            val again = Ingest.ingestDir(conn, dir)
            assertEquals(0, again.inserted)
            assertEquals(4, again.skipped)
            assertEquals(4, DbTestSupport.count(conn, "game"))
            assertEquals(1, DbTestSupport.count(conn, "match_set"))
            assertEquals(2, DbTestSupport.count(conn, "participant"))
        }
    }

    @Test
    @DisplayName("변조된 리플레이(입력 1바이트)는 적재되지 않고 전체가 롤백된다")
    fun tamperedReplayRollsBackEverything(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            val replays = DbTestSupport.writeEvalDir(dir, "unit-eval", games = 4)
            // 마지막 게임의 입력 한 바이트를 바꾼다. 재생 결과가 실제로 달라지는 자리를 고른다 —
            // 무의미한 자리(움직일 수 없는 프레임)를 바꾸면 "다른 경기" 가 아니라 "같은 경기" 다.
            val victim = dir.resolve("e000-g0003.pkr")
            val bytes = Files.readAllBytes(victim)
            val inputStart = bytes.size - replays[3].frameCount
            val pos = (0 until replays[3].frameCount).map { inputStart + it }.first { p ->
                val t = bytes.copyOf()
                t[p] = ((t[p] + 7) % 18).toByte()
                !ReplayPlayer(ReplayCodec.decode(t)).play().ok
            }
            bytes[pos] = ((bytes[pos] + 7) % 18).toByte()
            Files.write(victim, bytes)

            val e = assertThrows<Ingest.IngestException> { Ingest.ingestDir(conn, dir) }
            assertTrue(e.message!!.contains("e000-g0003.pkr"), e.message)
            assertEquals(0, DbTestSupport.count(conn, "game"), "앞의 정상 게임 3개도 들어가면 안 된다")
            assertEquals(0, DbTestSupport.count(conn, "match_set"))
        }
    }

    @Test
    @DisplayName("트랜잭션 도중 실패하면 앞서 넣은 행도 되돌린다")
    fun failureDuringInsertRollsBack(@TempDir dir: Path) {
        DbTestSupport.freshDb().use { conn ->
            DbTestSupport.writeEvalDir(dir, "unit-eval", games = 3)
            val (set, games) = Ingest.verifyDir(dir)
            // 없는 kind 로 묶음을 만들면 INSERT 가 실패한다 — 그 전 단계까지의 쓰기가 남지 않아야 한다.
            assertThrows<java.sql.SQLException> { Ingest.insert(conn, set, "no-such-kind", games) }
            assertEquals(0, DbTestSupport.count(conn, "game"))
            assertEquals(0, DbTestSupport.count(conn, "match_set"))
            assertEquals(0, DbTestSupport.count(conn, "participant"))
        }
    }

    @Test
    @DisplayName("스키마 버전이 다르면 연결을 거절한다")
    fun schemaVersionMismatch() {
        DbTestSupport.freshDb().use { conn ->
            conn.createStatement().use { it.executeUpdate("UPDATE schema_version SET version = 999") }
            val e = assertThrows<IllegalStateException> { Db.checkSchema(conn) }
            assertTrue(e.message!!.contains("999"))
            assertFalse(e.message!!.isBlank())
        }
    }
}
