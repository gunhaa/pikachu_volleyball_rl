package pika.analysis

import org.junit.jupiter.api.Assumptions.assumeTrue
import pika.conformance.RepoPaths
import pika.core.XorShift32
import pika.env.ActionCodec
import pika.env.EnvConfig
import pika.env.PikaEnv
import pika.env.RewardTerms
import pika.env.Slots
import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import pika.env.replay.ReplayRecorder
import pika.env.replay.SeedMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * DB 테스트 공용. `pika_test` 에 스키마를 새로 깐다 (`deploy/mysql/init/000_databases.sql`).
 *
 * MySQL 이 없으면 **건너뛴다** — CI 에는 DB 가 없다. 로컬에서는
 * `docker compose -f deploy/compose/docker-compose.yml up -d mysql` 후 돌린다.
 */
object DbTestSupport {
    val config = Db.Config.from(url = System.getenv("PIKA_TEST_DB_URL") ?: "jdbc:mysql://127.0.0.1:3306/pika_test")

    fun freshDb(): Connection {
        val conn = try {
            Db.connect(config)
        } catch (e: java.sql.SQLException) {
            assumeTrue(false, "MySQL 에 붙을 수 없습니다 (${e.message}) — docker compose up -d mysql")
            throw e
        }
        Db.dropAll(conn)
        Db.applySchema(conn, RepoPaths.root.resolve("deploy/mysql/init/001_schema.sql"))
        Db.checkSchema(conn)
        return conn
    }

    fun count(conn: Connection, table: String): Int =
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }

    /** PikaEnv(External 무작위 vs FSM) 로 [games] 게임을 기록해 evaluate.py 와 같은 모양의 디렉터리를 만든다. */
    fun writeEvalDir(dir: Path, set: String, games: Int, baseSeed: Int = 1): List<Replay> {
        Files.createDirectories(dir)
        val out = mutableListOf<Replay>()
        val env = PikaEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = baseSeed), 0, ReplayRecorder(SeedMode.RALLY) { out += it })
        val obs = FloatArray(env.obsDim)
        val r = FloatArray(1)
        val t = FloatArray(RewardTerms.COUNT)
        val a = ByteArray(1)
        val rng = XorShift32(baseSeed)
        env.reset(obs, 0)
        while (out.size < games) {
            a[0] = (rng.nextRand() % ActionCodec.ACTION_COUNT).toByte()
            env.step(a, 0, obs, 0, r, 0, t, 0)
        }
        val manifest = StringBuilder()
        out.forEachIndexed { i, replay ->
            val file = "e000-g%04d.pkr".format(i)
            Files.write(dir.resolve(file), ReplayCodec.encode(replay))
            manifest.append(
                Json.write(
                    linkedMapOf(
                        "file" to file, "set" to set, "envIndex" to 0, "gameInEnv" to i,
                        "p1" to mapOf("kind" to "external", "checkpoint" to "sha256:" + "ab".repeat(32), "label" to "test"),
                        "p2" to mapOf("kind" to "fsm"), "counted" to true, "unresolved" to !replay.ended,
                    ),
                ),
            ).append('\n')
        }
        Files.writeString(dir.resolve("manifest.jsonl"), manifest)
        return out
    }
}
