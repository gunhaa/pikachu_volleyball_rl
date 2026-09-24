package pika.analysis

import pika.env.replay.Replay
import pika.env.replay.ReplayCodec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Statement

/**
 * 리플레이 → DB. **재생으로 검증한 뒤에만** 넣는다. (FR-9, plan.md §6.1)
 *
 * 1. 디렉터리의 `manifest.jsonl` 과 `*.pkr` 을 전부 읽고 **전부** 재생 검증한다 (Kotlin 체인도 여기서 뜬다).
 * 2. 하나라도 실패하면 아무것도 넣지 않는다.
 * 3. 통과하면 한 트랜잭션으로 넣는다. `replay_sha256` 이 이미 있으면 건너뛴다 (같은 디렉터리를
 *    두 번 적재해도 행 수가 불변).
 *
 * 라이브 경기 제출(P7)도 같은 [verify] → [insert] 를 탄다 — 브라우저를 신뢰하지 않는다.
 */
object Ingest {

    /** 참가자. identity 가 유일 키다 (스키마 주석 참고). */
    data class Participant(val kind: String, val checkpoint: String?, val label: String?) {
        init {
            require(kind in setOf("fsm", "external", "human")) { "참가자 종류: $kind" }
        }

        /** `sha256:` 접두어를 뗀 hex 64. */
        val checkpointHex: String? = checkpoint?.removePrefix("sha256:")

        val identity: String
            get() = when {
                checkpointHex != null -> "$kind:$checkpointHex"
                kind == "fsm" -> "fsm:"
                else -> "$kind:label:${label ?: ""}"
            }

        val isFsm: Boolean get() = kind == "fsm"

        companion object {
            val FSM = Participant("fsm", null, null)

            fun fromJson(m: Map<String, Any?>) = Participant(m.str("kind"), m["checkpoint"] as String?, m["label"] as String?)
        }
    }

    /** 검증을 통과한 게임 하나. */
    class Verified(
        val file: String,
        val bytes: ByteArray,
        val replay: Replay,
        val sha256: String,
        val chain: ReplayChain.Result,
        val stats: StatsCollector,
        val p1: Participant,
        val p2: Participant,
        val envIndex: Int?,
        val gameInEnv: Int?,
    )

    class IngestException(message: String) : RuntimeException(message)

    class Result(val inserted: Int, val skipped: Int, val setId: Long) {
        override fun toString() = "적재 $inserted, 중복 건너뜀 $skipped (set_id=$setId)"
    }

    /**
     * 바이트를 재생으로 검증한다.
     * @throws IngestException 디코드 실패 · 재생 결과가 기록과 다름 · 참가자와 슬롯 플래그가 다름
     */
    fun verify(
        file: String,
        bytes: ByteArray,
        p1: Participant,
        p2: Participant,
        envIndex: Int? = null,
        gameInEnv: Int? = null,
    ): Verified {
        val replay = try {
            ReplayCodec.decode(bytes)
        } catch (e: IllegalArgumentException) {
            throw IngestException("$file: 리플레이를 읽을 수 없습니다 — ${e.message}")
        }
        if (replay.slots.p1.isFsm != p1.isFsm || replay.slots.p2.isFsm != p2.isFsm) {
            throw IngestException("$file: 참가자(${p1.kind}, ${p2.kind}) 가 리플레이 슬롯 ${replay.slots} 와 다릅니다")
        }
        val stats = StatsCollector(replay)
        val chain = ReplayChain.compute(replay, stats::onFrame)
        if (!chain.play.ok) throw IngestException("$file: 재생 검증 실패 — ${chain.play.mismatch}")
        stats.finish()
        return Verified(file, bytes, replay, sha256Hex(bytes), chain, stats, p1, p2, envIndex, gameInEnv)
    }

    /** `manifest.jsonl` 의 모든 게임을 검증한다. 하나라도 실패하면 예외 (아무것도 넣기 전). */
    fun verifyDir(dir: Path): Pair<String, List<Verified>> {
        val manifest = dir.resolve("manifest.jsonl")
        if (!Files.exists(manifest)) throw IngestException("$manifest 가 없습니다")
        val lines = Files.readAllLines(manifest).filter { it.isNotBlank() }.map { Json.parse(it).obj() }
        if (lines.isEmpty()) throw IngestException("$manifest 가 비어 있습니다")
        val sets = lines.map { it.str("set") }.toSet()
        if (sets.size != 1) throw IngestException("한 디렉터리에 묶음이 여럿입니다: $sets")
        val verified = lines.map { m ->
            val file = m.str("file")
            verify(
                file, Files.readAllBytes(dir.resolve(file)),
                Participant.fromJson(m["p1"].obj()), Participant.fromJson(m["p2"].obj()),
                (m["envIndex"] as Number?)?.toInt(), (m["gameInEnv"] as Number?)?.toInt(),
            )
        }
        return sets.single() to verified
    }

    /** 검증 → 적재. `kind` 는 새 묶음을 만들 때만 쓰인다. */
    fun ingestDir(conn: Connection, dir: Path, kind: String = "eval", note: String? = null): Result {
        val (setName, games) = verifyDir(dir)
        return insert(conn, setName, kind, games, note)
    }

    /** 한 트랜잭션. 예외면 전부 되돌린다. */
    fun insert(conn: Connection, setName: String, kind: String, games: List<Verified>, note: String? = null): Result =
        Db.tx(conn) {
            val setId = ensureSet(conn, setName, kind, note)
            val participants = HashMap<String, Long>()
            fun pid(p: Participant) = participants.getOrPut(p.identity) { ensureParticipant(conn, p) }

            var inserted = 0
            var skipped = 0
            val exists = conn.prepareStatement("SELECT id FROM game WHERE replay_sha256 = ?")
            val ins = conn.prepareStatement(
                """INSERT INTO game (set_id, p1_id, p2_id, replay_sha256, replay, seed_mode, winning_score,
                   max_rally_frames, fixed_boldness_p1, fixed_boldness_p2, first_serve_p2, ended, score_p1, score_p2,
                   winner, frames, rallies, truncated, chain_sha256, env_index, game_in_env)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                Statement.RETURN_GENERATED_KEYS,
            )
            for (g in games) {
                exists.setString(1, g.sha256)
                if (exists.executeQuery().use { it.next() }) {
                    skipped++
                    continue
                }
                val r = g.replay
                var i = 1
                ins.setLong(i++, setId)
                ins.setLong(i++, pid(g.p1))
                ins.setLong(i++, pid(g.p2))
                ins.setString(i++, g.sha256)
                ins.setBytes(i++, g.bytes)
                ins.setInt(i++, r.seedMode.code)
                ins.setInt(i++, r.winningScore)
                ins.setInt(i++, r.maxRallyFrames)
                ins.setInt(i++, r.fixedBoldness.p1)
                ins.setInt(i++, r.fixedBoldness.p2)
                ins.setBoolean(i++, r.firstServeIsPlayer2)
                ins.setBoolean(i++, r.ended)
                ins.setInt(i++, r.finalScore[0])
                ins.setInt(i++, r.finalScore[1])
                ins.setObject(i++, r.winner)
                ins.setInt(i++, r.frameCount)
                ins.setInt(i++, r.rallyCount)
                ins.setInt(i++, r.rallyOutcomes.count { it.toInt() == -1 })
                ins.setString(i++, g.chain.finalHex)
                ins.setObject(i++, g.envIndex)
                ins.setObject(i++, g.gameInEnv)
                ins.executeUpdate()
                val gameId = ins.generatedKeys.use { it.next(); it.getLong(1) }
                insertStats(conn, gameId, g.stats)
                inserted++
            }
            Result(inserted, skipped, setId)
        }

    /** 랠리 · 파워히트 행. 적재와 `rebuild-stats` 가 같은 코드를 쓴다. */
    fun insertStats(conn: Connection, gameId: Long, stats: StatsCollector) {
        conn.prepareStatement(
            """INSERT INTO rally (game_id, idx, seed, server_p2, outcome, frames, landing_x,
               touches_p1, touches_p2, power_hits_p1, power_hits_p2) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ).use { st ->
            for (r in stats.rallies) {
                st.setLong(1, gameId)
                st.setInt(2, r.idx)
                st.setObject(3, r.seed)
                st.setBoolean(4, r.serverP2)
                st.setInt(5, r.outcome)
                st.setInt(6, r.frames)
                st.setObject(7, r.landingX)
                st.setInt(8, r.touches[0])
                st.setInt(9, r.touches[1])
                st.setInt(10, r.powerHits[0])
                st.setInt(11, r.powerHits[1])
                st.addBatch()
            }
            st.executeBatch()
        }
        if (stats.powerHits.isEmpty()) return
        conn.prepareStatement("INSERT INTO power_hit (game_id, rally_idx, frame, hitter, success) VALUES (?,?,?,?,?)").use { st ->
            for (p in stats.powerHits) {
                st.setLong(1, gameId)
                st.setInt(2, p.rallyIdx)
                st.setInt(3, p.frame)
                st.setInt(4, p.hitter)
                st.setBoolean(5, p.success)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /**
     * 리플레이 BLOB 에서 랠리 · 통계 테이블을 다시 파생한다 (FR-11). 통계 정의를 바꾼 뒤 쓴다.
     * 재생 체인이 적재 때의 `chain_sha256` 과 다르면 멈춘다 — 엔진이 바뀐 것이다.
     * @return 다시 계산한 게임 수
     */
    fun rebuildStats(conn: Connection, setName: String? = null): Int = Db.tx(conn) {
        val sql = "SELECT g.id, g.replay, g.chain_sha256 FROM game g JOIN match_set s ON s.id = g.set_id" +
            if (setName != null) " WHERE s.name = ?" else ""
        val games = conn.prepareStatement(sql).use { st ->
            if (setName != null) st.setString(1, setName)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getBytes(2), rs.getString(3))) }
            }
        }
        val delRally = conn.prepareStatement("DELETE FROM rally WHERE game_id = ?")
        val delHit = conn.prepareStatement("DELETE FROM power_hit WHERE game_id = ?")
        for ((id, bytes, chainHex) in games) {
            val replay = ReplayCodec.decode(bytes)
            val stats = StatsCollector(replay)
            val chain = ReplayChain.compute(replay, stats::onFrame)
            check(chain.play.ok && chain.finalHex == chainHex) {
                "game $id: 재생 체인이 적재 때와 다릅니다 (${chain.play.mismatch ?: "체인 불일치"}) — 엔진이 바뀌었습니까?"
            }
            stats.finish()
            delHit.setLong(1, id); delHit.executeUpdate()
            delRally.setLong(1, id); delRally.executeUpdate()
            insertStats(conn, id, stats)
        }
        games.size
    }

    private fun ensureSet(conn: Connection, name: String, kind: String, note: String?): Long {
        conn.prepareStatement("SELECT id FROM match_set WHERE name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { if (it.next()) return it.getLong(1) }
        }
        conn.prepareStatement("INSERT INTO match_set (name, kind, note) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS).use { st ->
            st.setString(1, name)
            st.setString(2, kind)
            st.setString(3, note)
            st.executeUpdate()
            st.generatedKeys.use { it.next(); return it.getLong(1) }
        }
    }

    private fun ensureParticipant(conn: Connection, p: Participant): Long {
        conn.prepareStatement("SELECT id FROM participant WHERE identity = ?").use { st ->
            st.setString(1, p.identity)
            st.executeQuery().use { if (it.next()) return it.getLong(1) }
        }
        conn.prepareStatement(
            "INSERT INTO participant (identity, kind, checkpoint_sha256, label) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { st ->
            st.setString(1, p.identity)
            st.setString(2, p.kind)
            st.setString(3, p.checkpointHex)
            st.setString(4, p.label)
            st.executeUpdate()
            st.generatedKeys.use { it.next(); return it.getLong(1) }
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
