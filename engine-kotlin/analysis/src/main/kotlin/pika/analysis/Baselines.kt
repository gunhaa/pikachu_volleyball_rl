package pika.analysis

import pika.env.GameEvaluator
import pika.env.Slots
import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import pika.env.replay.ReplayRecorder
import pika.env.replay.SeedMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * 기준선 생성과 DB 집계. (FR-10, M4-c · M4-d, plan.md §6.1)
 */
object Baselines {

    const val FSM_SET = "fsm-vs-fsm-baseline"

    /**
     * FSM vs FSM 을 `GameEvaluator.evaluate(null, null)` 과 **같은 시드 · 서브 배치**로 기록한다.
     *
     * 게임 i = `playGame(seed = baseSeed + i / 2, firstServeIsPlayer2 = i % 2 == 1)`. evaluate 의
     * 왼쪽 · 오른쪽은 FSM vs FSM 이라 **같은 게임을 두 시점에서** 센 것이므로 기록은 [games] 판이다.
     *
     * `<dir>/manifest.jsonl` + `*.pkr` + `expected.json` 을 쓴다. expected 는 기록과 **독립된 경로**
     * (`GameEvaluator.evaluate` 를 다시 돌린 결과)라서 DB 집계를 대조하는 기준이 된다.
     */
    fun writeFsm(dir: Path, games: Int = 800, baseSeed: Int = 0): Map<String, Any?> {
        Files.createDirectories(dir)
        val manifest = StringBuilder()
        for (i in 0 until games) {
            var replay: Replay? = null
            GameEvaluator.playGame(
                seed = baseSeed + i / 2, slots = Slots.FSM_VS_FSM, firstServeIsPlayer2 = i % 2 == 1,
                recorder = ReplayRecorder(SeedMode.GAME) { replay = it },
            )
            val file = "g%04d.pkr".format(i)
            Files.write(dir.resolve(file), ReplayCodec.encode(replay!!))
            manifest.append(
                Json.write(
                    linkedMapOf(
                        "file" to file, "set" to FSM_SET, "envIndex" to null, "gameInEnv" to i,
                        "p1" to mapOf("kind" to "fsm"), "p2" to mapOf("kind" to "fsm"),
                        "counted" to true, "unresolved" to false,
                    ),
                ),
            ).append('\n')
        }
        Files.writeString(dir.resolve("manifest.jsonl"), manifest)

        val r = GameEvaluator.evaluate(policy = null, opponent = null, games = games, baseSeed = baseSeed)
        fun side(w: GameEvaluator.WinRate) = linkedMapOf(
            "games" to w.games, "wins" to w.wins, "unresolved" to 0,
            "points_for" to w.pointsFor, "points_against" to w.pointsAgainst,
            "rallies" to w.rallies, "frames" to w.frames, "truncated_rallies" to w.truncatedRallies,
        )
        val expected = linkedMapOf("main" to linkedMapOf("as_left" to side(r.asLeft), "as_right" to side(r.asRight)))
        Files.writeString(dir.resolve("expected.json"), Json.write(expected) + "\n")
        return expected
    }

    /**
     * 묶음 하나를 DB 에서 다시 집계한다 — `evaluate.py` 의 `SideStats` 와 같은 이름 · 정의.
     *
     * 주체 = External 슬롯. 없으면(FSM vs FSM) 양쪽 모두 주체다 — `GameEvaluator.evaluate(null, null)`
     * 의 왼쪽 · 오른쪽과 같은 뜻이 된다. 미결(ended = false) 게임은 `unresolved` 만 센다.
     */
    fun report(conn: Connection, set: String): Map<String, Any?> {
        val sql = """
            SELECT g.id, g.ended, g.score_p1, g.score_p2, g.winner, g.frames, g.rallies, g.truncated,
                   p1.kind, p2.kind, LENGTH(g.replay),
                   (SELECT COUNT(*) FROM rally r WHERE r.game_id = g.id AND r.outcome = 0),
                   (SELECT COUNT(*) FROM rally r WHERE r.game_id = g.id AND r.outcome = 1)
            FROM game g JOIN match_set s ON s.id = g.set_id
            JOIN participant p1 ON p1.id = g.p1_id JOIN participant p2 ON p2.id = g.p2_id
            WHERE s.name = ?"""
        val acc = Array(2) { LinkedHashMap<String, Long>() }
        val keys = listOf("games", "wins", "unresolved", "points_for", "points_against", "rallies", "rally_wins", "frames", "truncated_rallies")
        for (a in acc) keys.forEach { a[it] = 0L }
        var n = 0
        var bytes = 0L
        conn.prepareStatement(sql).use { st ->
            st.setString(1, set)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    n++
                    bytes += rs.getLong(11)
                    val kinds = listOf(rs.getString(9), rs.getString(10))
                    val subjects = (0..1).filter { kinds[it] != "fsm" }.ifEmpty { listOf(0, 1) }
                    val score = intArrayOf(rs.getInt(3), rs.getInt(4))
                    val rallyWins = longArrayOf(rs.getLong(12), rs.getLong(13))
                    for (s in subjects) {
                        val a = acc[s]
                        if (!rs.getBoolean(2)) {
                            a.merge("unresolved", 1, Long::plus)
                            continue
                        }
                        a.merge("games", 1, Long::plus)
                        if (rs.getInt(5) == s) a.merge("wins", 1, Long::plus)
                        a.merge("points_for", score[s].toLong(), Long::plus)
                        a.merge("points_against", score[1 - s].toLong(), Long::plus)
                        a.merge("rallies", rs.getLong(7), Long::plus)
                        a.merge("rally_wins", rallyWins[s], Long::plus)
                        a.merge("frames", rs.getLong(6), Long::plus)
                        a.merge("truncated_rallies", rs.getLong(8), Long::plus)
                    }
                }
            }
        }
        return linkedMapOf(
            "set" to set, "games" to n, "mean_replay_bytes" to if (n == 0) 0.0 else bytes.toDouble() / n,
            "as_left" to acc[0], "as_right" to acc[1],
        )
    }

    /**
     * DB 집계를 리포트(JSON, `{"main": {"as_left": …, "as_right": …}}`)와 대조한다.
     * 리포트에 있는 키만 본다 (GameEvaluator 리포트에는 rally_wins 가 없다).
     * @return 불일치 목록 (비면 일치)
     */
    fun compare(db: Map<String, Any?>, expected: Map<String, Any?>): List<String> {
        val main = (expected["main"] ?: expected).obj()
        val out = mutableListOf<String>()
        for (side in listOf("as_left", "as_right")) {
            val want = main[side].obj()
            val got = db[side].obj()
            for ((k, v) in want) {
                val w = (v as? Number)?.toLong() ?: continue
                val g = (got[k] as Number?)?.toLong()
                if (g != w) out += "$side.$k: 리포트 $w ≠ DB $g"
            }
        }
        return out
    }
}
