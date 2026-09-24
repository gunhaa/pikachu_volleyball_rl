package pika.analysis

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.sql.Connection
import java.util.concurrent.Executors

/**
 * 뷰어용 HTTP API. (FR-15, plan.md §6.1 · §8)
 *
 * JDK `com.sun.net.httpserver` — 의존성을 더하지 않는다. **127.0.0.1 에만** 묶는다 (NFR-5, 인증 없음).
 *
 * | 경로 | |
 * |---|---|
 * | GET  /api/sets                          | 묶음 목록 |
 * | GET  /api/games?set&side&winner&unresolved&limit&offset | 게임 목록 (side = External 슬롯의 진영) |
 * | GET  /api/games/{id}                    | 게임 하나 + `chain_sha256` (뷰어가 JS 체인과 대조) |
 * | GET  /api/games/{id}/replay             | 리플레이 v1 바이트 |
 * | GET  /api/stats/{set}?bin=50            | 랠리 길이 · 착지 x · 파워히트 집계 |
 * | GET  /api/policies                      | ONNX 정책 목록 (레지스트리, Phase 5) |
 * | GET  /api/policies/{onnx sha}.onnx      | ONNX 바이트 |
 * | POST /api/live-games?p1=…&p2=…          | **유일한 쓰기.** 라이브 경기 바이트 → ingest 와 같은 검증 → 묶음 `live` |
 *
 * ⚠️ 라이브 경기는 DB 에만 두지 않는다. 검증을 통과한 바이트를 [liveDir] 에 `manifest.jsonl` 과 함께 남긴다 —
 *    DB 는 캐시라서(plan.md §6.3) 볼륨을 지우면 사라지고, 기준선과 달리 라이브는 다시 만들 수 없다.
 *    되살리기: `analysis ingest runs/live --kind live`.
 *
 * ⚠️ 라이브 제출은 브라우저를 신뢰하지 않는다. 받은 것은 바이트뿐이고, 참가자 종류(FSM / External)는
 *    슬롯 플래그에서 유도하며, 재생 검증을 통과해야만 적재한다. JS 규칙층이 틀려 있으면 여기서 드러난다.
 *    External 슬롯에 한해서만 "사람인가 어느 정책인가" 의 주장(`p1` · `p2` 쿼리)을 받는다 (plan.md §9.1) —
 *    주장은 믿고 받되 레지스트리에 없는 정책은 거절하고, 사실 여부는 `verify-policy` 가 사후에 본다.
 */
class Serve(
    private val db: Db.Config,
    port: Int,
    private val liveDir: java.nio.file.Path,
    private val policies: PolicyRegistry = PolicyRegistry.EMPTY,
) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val port: Int get() = server.address.port

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/api/") { ex -> handle(ex) }
    }

    fun start(): Serve {
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private class HttpError(val status: Int, message: String) : RuntimeException(message)

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path.removePrefix("/api/").trimEnd('/').split('/')
            val query = parseQuery(ex.requestURI.rawQuery)
            // 정책은 DB 없이 — 레지스트리만 본다.
            if (ex.requestMethod == "GET" && path == listOf("policies")) return json(ex, policyList())
            if (ex.requestMethod == "GET" && path.size == 2 && path[0] == "policies") return bytes(ex, policyBytes(path[1]))
            Db.connect(db).use { conn ->
                when {
                    ex.requestMethod == "GET" && path == listOf("sets") -> json(ex, sets(conn))
                    ex.requestMethod == "GET" && path == listOf("games") -> json(ex, games(conn, query))
                    ex.requestMethod == "GET" && path.size == 2 && path[0] == "games" -> json(ex, game(conn, id(path[1])))
                    ex.requestMethod == "GET" && path.size == 3 && path[0] == "games" && path[2] == "replay" ->
                        bytes(ex, replay(conn, id(path[1])))
                    ex.requestMethod == "GET" && path.size == 2 && path[0] == "stats" ->
                        json(ex, stats(conn, path[1], query["bin"]?.toIntOrNull() ?: 50))
                    ex.requestMethod == "POST" && path == listOf("live-games") -> json(ex, submitLive(conn, ex, query))
                    else -> throw HttpError(404, "없는 경로: ${ex.requestMethod} ${ex.requestURI.path}")
                }
            }
        } catch (e: HttpError) {
            json(ex, mapOf("error" to e.message), e.status)
        } catch (e: Ingest.IngestException) {
            json(ex, mapOf("error" to e.message), 422)
        } catch (e: Exception) {
            System.err.println("serve: ${e::class.simpleName}: ${e.message}")
            json(ex, mapOf("error" to "${e::class.simpleName}: ${e.message}"), 500)
        } finally {
            ex.close()
        }
    }

    // ── 읽기 ───────────────────────────────────────────────────────────────

    private fun sets(conn: Connection): List<Map<String, Any?>> = rows(
        conn,
        """SELECT s.name, s.kind, s.created_at, COUNT(g.id), COALESCE(SUM(NOT g.ended), 0)
           FROM match_set s LEFT JOIN game g ON g.set_id = s.id GROUP BY s.id, s.name, s.kind, s.created_at ORDER BY s.id""",
    ) { rs -> linkedMapOf("name" to rs.getString(1), "kind" to rs.getString(2), "createdAt" to rs.getString(3), "games" to rs.getLong(4), "unresolved" to rs.getLong(5)) }

    private val gameColumns = """g.id, s.name, p1.kind, p1.label, p2.kind, p2.label, g.score_p1, g.score_p2, g.winner, g.ended,
        g.frames, g.rallies, g.truncated, g.env_index, g.game_in_env, g.chain_sha256, g.seed_mode, g.first_serve_p2,
        g.fixed_boldness_p1, g.fixed_boldness_p2, g.max_rally_frames, LENGTH(g.replay)"""
    private val gameFrom = """FROM game g JOIN match_set s ON s.id = g.set_id
        JOIN participant p1 ON p1.id = g.p1_id JOIN participant p2 ON p2.id = g.p2_id"""

    private fun gameRow(rs: java.sql.ResultSet): Map<String, Any?> = linkedMapOf(
        "id" to rs.getLong(1), "set" to rs.getString(2),
        "p1" to mapOf("kind" to rs.getString(3), "label" to rs.getString(4)),
        "p2" to mapOf("kind" to rs.getString(5), "label" to rs.getString(6)),
        "score" to listOf(rs.getInt(7), rs.getInt(8)), "winner" to rs.getObject(9)?.let { (it as Number).toInt() },
        "ended" to rs.getBoolean(10), "frames" to rs.getInt(11), "rallies" to rs.getInt(12), "truncated" to rs.getInt(13),
        "envIndex" to rs.getObject(14), "gameInEnv" to rs.getObject(15), "chain" to rs.getString(16),
        "seedMode" to rs.getInt(17), "firstServeP2" to rs.getBoolean(18),
        "fixedBoldness" to listOf(rs.getInt(19), rs.getInt(20)), "maxRallyFrames" to rs.getInt(21), "bytes" to rs.getLong(22),
    )

    private fun games(conn: Connection, q: Map<String, String>): Map<String, Any?> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        q["set"]?.let { where += "s.name = ?"; args += it }
        when (q["side"]) {
            "left" -> where += "p1.kind <> 'fsm'"
            "right" -> where += "p2.kind <> 'fsm'"
            null, "", "any" -> {}
            else -> throw HttpError(400, "side 는 left | right: ${q["side"]}")
        }
        q["winner"]?.takeIf { it.isNotEmpty() && it != "any" }?.let { where += "g.winner = ?"; args += it.toInt() }
        when (q["unresolved"]) {
            "true" -> where += "NOT g.ended"
            "false" -> where += "g.ended"
            else -> {}
        }
        val cond = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val offset = (q["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val total = rows(conn, "SELECT COUNT(*) $gameFrom $cond", args) { it.getLong(1) }.single()
        val items = rows(conn, "SELECT $gameColumns $gameFrom $cond ORDER BY g.id LIMIT $limit OFFSET $offset", args, ::gameRow)
        return linkedMapOf("total" to total, "limit" to limit, "offset" to offset, "games" to items)
    }

    private fun game(conn: Connection, id: Long): Map<String, Any?> =
        rows(conn, "SELECT $gameColumns $gameFrom WHERE g.id = ?", listOf(id), ::gameRow).singleOrNull()
            ?: throw HttpError(404, "게임 $id 이 없습니다")

    private fun replay(conn: Connection, id: Long): ByteArray =
        rows(conn, "SELECT replay FROM game WHERE id = ?", listOf(id)) { it.getBytes(1) }.singleOrNull()
            ?: throw HttpError(404, "게임 $id 이 없습니다")

    /**
     * 통계 (FR-11). 뷰어가 그대로 그린다 — 여기서 비율을 계산하지 않고 **개수**를 준다.
     * `kind` 열은 그 진영의 참가자 종류 (fsm / external / human) 라서 정책 대 FSM 으로 묶을 수 있다.
     */
    private fun stats(conn: Connection, set: String, bin: Int): Map<String, Any?> {
        require(bin in 1..10_000) { "bin: $bin" }
        val base = "FROM rally r JOIN game g ON g.id = r.game_id JOIN match_set s ON s.id = g.set_id " +
            "JOIN participant p1 ON p1.id = g.p1_id JOIN participant p2 ON p2.id = g.p2_id WHERE s.name = ?"
        val rallyLength = rows(
            conn,
            "SELECT FLOOR(r.frames / $bin), CASE WHEN r.outcome >= 0 THEN 'scored' WHEN r.outcome = -1 THEN 'truncated' ELSE 'unfinished' END, COUNT(*) " +
                "$base GROUP BY 1, 2 ORDER BY 1, 2",
            listOf(set),
        ) { linkedMapOf("bucket" to it.getLong(1) * bin, "outcome" to it.getString(2), "count" to it.getLong(3)) }
        val landing = rows(
            conn,
            "SELECT LEAST(FLOOR(r.landing_x / ${StatsCollector.LANDING_BIN}), ${StatsCollector.LANDING_BINS - 1}), r.outcome, " +
                "CASE r.outcome WHEN 0 THEN p1.kind ELSE p2.kind END, r.server_p2, COUNT(*) " +
                "$base AND r.outcome >= 0 GROUP BY 1, 2, 3, 4 ORDER BY 1",
            listOf(set),
        ) { linkedMapOf("bin" to it.getInt(1), "scorer" to it.getInt(2), "scorerKind" to it.getString(3), "serverP2" to it.getBoolean(4), "count" to it.getLong(5)) }
        val powerHits = rows(
            conn,
            "SELECT h.hitter, CASE h.hitter WHEN 0 THEN p1.kind ELSE p2.kind END, COUNT(*), SUM(h.success) " +
                "FROM power_hit h JOIN game g ON g.id = h.game_id JOIN match_set s ON s.id = g.set_id " +
                "JOIN participant p1 ON p1.id = g.p1_id JOIN participant p2 ON p2.id = g.p2_id WHERE s.name = ? " +
                "GROUP BY 1, 2 ORDER BY 1, 2",
            listOf(set),
        ) { linkedMapOf("hitter" to it.getInt(1), "hitterKind" to it.getString(2), "total" to it.getLong(3), "success" to it.getLong(4)) }
        val totals = rows(conn, "SELECT COUNT(*), COALESCE(SUM(r.frames), 0), COALESCE(SUM(r.touches_p1 + r.touches_p2), 0) $base", listOf(set)) {
            linkedMapOf("rallies" to it.getLong(1), "frames" to it.getLong(2), "touches" to it.getLong(3))
        }.single()
        return linkedMapOf(
            "set" to set, "bin" to bin, "landingBin" to StatsCollector.LANDING_BIN, "landingBins" to StatsCollector.LANDING_BINS,
            "totals" to totals, "rallyLength" to rallyLength, "landing" to landing, "powerHits" to powerHits,
        )
    }

    // ── 정책 (Phase 5 FR-11) ───────────────────────────────────────────────

    private fun policyList(): List<Map<String, Any?>> = policies.entries.map {
        linkedMapOf(
            "label" to it.label, "onnx" to it.onnxSha256, "checkpoint" to "sha256:${it.checkpointSha256}",
            "obsDim" to it.obsDim, "obsLayoutHash" to it.obsLayoutHash,
        )
    }

    private fun policyBytes(name: String): ByteArray {
        val e = name.takeIf { it.endsWith(".onnx") }?.let { policies.byOnnxSha(it.removeSuffix(".onnx")) }
            ?: throw HttpError(404, "등록되지 않은 정책: $name")
        return java.nio.file.Files.readAllBytes(e.file)
    }

    // ── 쓰기: 라이브 경기 제출 ─────────────────────────────────────────────

    /** 한 슬롯의 참가자 + (정책이면) 주장한 ONNX SHA. */
    private class Claim(val participant: Ingest.Participant, val onnx: String?)

    /**
     * External 슬롯의 참가자 주장 (plan.md §9.1). FSM 슬롯은 주장을 무시한다 — 슬롯 플래그가 진실이다.
     *   없음 · `human`            → 사람 (기존과 같다)
     *   `policy:<onnx sha256>`    → 레지스트리의 **체크포인트** SHA 참가자 (FR-12). 없으면 400
     */
    private fun claimed(external: Boolean, claim: String?): Claim = when {
        !external -> Claim(Ingest.Participant.FSM, null)
        claim == null || claim == "" || claim == "human" -> Claim(HUMAN, null)
        claim.startsWith("policy:") -> {
            val sha = claim.removePrefix("policy:")
            val e = policies.byOnnxSha(sha) ?: throw HttpError(400, "레지스트리에 없는 정책입니다: $claim")
            Claim(e.participant, sha)
        }
        else -> throw HttpError(400, "참가자 주장은 human | policy:<onnx sha256>: $claim")
    }

    private fun submitLive(conn: Connection, ex: HttpExchange, query: Map<String, String>): Map<String, Any?> {
        val body = ex.requestBody.readNBytes(MAX_LIVE_BYTES + 1)
        if (body.size > MAX_LIVE_BYTES) throw HttpError(413, "리플레이가 너무 큽니다 (> $MAX_LIVE_BYTES B)")
        // 참가자 종류는 바이트에서 유도한다 — 요청이 무엇을 주장하든 슬롯 플래그가 진실이다.
        val flags = if (body.size > 5) body[5].toInt() else 0
        val c1 = claimed(flags and 1 != 0, query["p1"])
        val c2 = claimed(flags and 2 != 0, query["p2"])
        val verified = Ingest.verify("live", body, c1.participant, c2.participant)
        if (!verified.replay.ended) throw HttpError(422, "끝나지 않은 라이브 경기는 받지 않습니다")
        keepLiveFile(verified, c1.onnx, c2.onnx)
        val result = Ingest.insert(conn, LIVE_SET, "live", listOf(verified), note = "브라우저 라이브 대전 (P7)")
        val id = rows(conn, "SELECT id FROM game WHERE replay_sha256 = ?", listOf(verified.sha256)) { it.getLong(1) }.single()
        return linkedMapOf("id" to id, "inserted" to (result.inserted == 1), "chain" to verified.chain.finalHex, "score" to verified.replay.finalScore.toList())
    }

    /**
     * 라이브 원본을 파일로 — 이미 있으면(같은 경기) 건너뛴다. manifest 는 ingest 가 읽는 모양 그대로에
     * 정책 슬롯이면 `onnx`(ONNX SHA) 를 더한다 — ingest 는 모르는 필드를 무시하고, `verify-policy` 가 읽는다.
     */
    @Synchronized
    private fun keepLiveFile(v: Ingest.Verified, onnx1: String?, onnx2: String?) {
        java.nio.file.Files.createDirectories(liveDir)
        val file = "${v.sha256}.pkr"
        val path = liveDir.resolve(file)
        if (java.nio.file.Files.exists(path)) return
        java.nio.file.Files.write(path, v.bytes)
        fun who(p: Ingest.Participant, onnx: String?) = linkedMapOf<String, Any?>("kind" to p.kind, "label" to p.label).apply {
            // ⚠️ 체크포인트를 적지 않으면 되살릴 때 identity 가 `external:label:…` 로 바뀌어 기준선과 다른 행이 된다.
            p.checkpoint?.let { put("checkpoint", it) }
            onnx?.let { put("onnx", "sha256:$it") }
        }
        val line = Json.write(
            linkedMapOf(
                "file" to file, "set" to LIVE_SET, "envIndex" to null, "gameInEnv" to null,
                "p1" to who(v.p1, onnx1), "p2" to who(v.p2, onnx2), "counted" to true, "unresolved" to false,
            ),
        )
        java.nio.file.Files.writeString(
            liveDir.resolve("manifest.jsonl"), line + "\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND,
        )
    }

    // ── 자잘한 것 ─────────────────────────────────────────────────────────

    private fun <T> rows(conn: Connection, sql: String, args: List<Any> = emptyList(), map: (java.sql.ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun id(s: String): Long = s.toLongOrNull() ?: throw HttpError(400, "게임 id: $s")

    private fun parseQuery(raw: String?): Map<String, String> =
        raw?.split('&')?.filter { it.isNotEmpty() }?.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        } ?: emptyMap()

    private fun json(ex: HttpExchange, body: Any?, status: Int = 200) {
        val bytes = Json.write(body).toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun bytes(ex: HttpExchange, body: ByteArray) {
        ex.responseHeaders.set("Content-Type", "application/octet-stream")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    companion object {
        const val LIVE_SET = "live"

        private val HUMAN = Ingest.Participant("human", null, "keyboard")

        /** 60,000 프레임 × 2 슬롯 + 헤더 · 랠리 표보다 넉넉히. */
        const val MAX_LIVE_BYTES = 1 shl 20
    }
}
