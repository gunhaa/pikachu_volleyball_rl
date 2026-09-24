package pika.analysis

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pika.conformance.RepoPaths
import pika.env.replay.ReplayCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.TimeUnit

/** 뷰어 API (tasks.md P7, FR-15 · FR-18). MySQL 이 없으면 건너뛴다. */
class ServeTest {

    private lateinit var conn: Connection
    private lateinit var serve: Serve
    private lateinit var liveDir: Path
    private lateinit var policyDir: Path
    private lateinit var onnxSha: String
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun setUp() {
        conn = DbTestSupport.freshDb()
        liveDir = Files.createTempDirectory("pika-live-")
        policyDir = Files.createTempDirectory("pika-policies-")
        onnxSha = writeRegistry(policyDir, "test", CKPT)
        serve = Serve(DbTestSupport.config, 0, liveDir, PolicyRegistry.load(policyDir)).start()
    }

    @AfterEach
    fun tearDown() {
        serve.stop()
        conn.close()
    }

    private fun url(p: String) = URI.create("http://127.0.0.1:${serve.port}/api/$p")
    private fun get(p: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(url(p)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    private fun getJson(p: String): Any? = get(p).also { assertEquals(200, it.statusCode(), String(it.body())) }.let { Json.parse(String(it.body())) }
    /** 가짜 ONNX(내용은 상관없다 — 서버는 바이트를 해시만 한다) + 레지스트리 한 줄. ONNX SHA 를 돌려준다. */
    private fun writeRegistry(dir: Path, label: String, checkpointHex: String): String {
        val bytes = "fake-onnx:$label".toByteArray()
        Files.write(dir.resolve("$label.onnx"), bytes)
        val sha = Ingest.sha256Hex(bytes)
        val line = Json.write(
            linkedMapOf(
                "label" to label, "checkpoint_sha256" to checkpointHex, "onnx" to "runs/policies/$label.onnx",
                "onnx_sha256" to sha, "obs_dim" to 41, "obs_layout_hash" to "cd".repeat(32),
            ),
        )
        Files.writeString(dir.resolve("registry.jsonl"), line + "\n")
        return sha
    }

    private fun post(p: String, body: ByteArray): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(url(p)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    @DisplayName("읽기: 묶음 · 게임 목록(필터) · 게임 · 리플레이 바이트 · 통계")
    fun readEndpoints(@TempDir dir: Path) {
        val replays = DbTestSupport.writeEvalDir(dir, "api-eval", games = 3)
        Ingest.ingestDir(conn, dir)

        val sets = getJson("sets").arr().map { it.obj() }
        assertEquals(listOf("api-eval"), sets.map { it.str("name") })
        assertEquals(3L, sets[0]["games"])

        val list = getJson("games?set=api-eval&side=left").obj()
        assertEquals(3L, list["total"])
        assertEquals(0L, getJson("games?set=api-eval&side=right").obj()["total"], "정책은 왼쪽에만 있었다")
        assertEquals(3L, getJson("games?set=api-eval&winner=1").obj()["total"], "무작위 정책은 FSM 을 못 이긴다")

        val first = list["games"].arr()[0].obj()
        val id = (first["id"] as Number).toLong()
        val one = getJson("games/$id").obj()
        assertEquals(ReplayChain.compute(replays[0]).finalHex, one.str("chain"))
        assertArrayEquals(ReplayCodec.encode(replays[0]), get("games/$id/replay").body())

        val stats = getJson("stats/api-eval?bin=100").obj()
        assertEquals(replays.sumOf { it.rallyCount }.toLong(), stats["totals"].obj()["rallies"])
        val scored = stats["landing"].arr().sumOf { (it.obj()["count"] as Number).toLong() }
        assertEquals(replays.sumOf { r -> r.rallyOutcomes.count { it >= 0 } }.toLong(), scored)

        assertEquals(404, get("games/999999").statusCode())
        assertEquals(404, get("nope").statusCode())
    }

    @Test
    @DisplayName("라이브 제출: 검증 통과만 적재 (묶음 live, External = 사람), 변조 · 미완은 422 이고 아무것도 안 들어간다")
    fun liveSubmission(@TempDir dir: Path) {
        val replay = DbTestSupport.writeEvalDir(dir, "x", games = 1).single()
        val bytes = ReplayCodec.encode(replay)

        val ok = post("live-games", bytes)
        assertEquals(200, ok.statusCode(), ok.body())
        val res = Json.parse(ok.body()).obj()
        assertEquals(true, res["inserted"])
        val game = getJson("games/${(res["id"] as Number).toLong()}").obj()
        assertEquals("live", game.str("set"))
        assertEquals("human", game["p1"].obj().str("kind"))
        assertEquals("fsm", game["p2"].obj().str("kind"))

        // 같은 경기를 다시 내면 새 행이 생기지 않는다.
        assertEquals(false, Json.parse(post("live-games", bytes).body()).obj()["inserted"])

        // 원본은 파일로도 남는다 — DB 를 지워도 `ingest <liveDir> --kind live` 로 되살아난다.
        assertEquals(1, Files.readAllLines(liveDir.resolve("manifest.jsonl")).size, "중복 제출은 manifest 에 한 줄만")
        conn.createStatement().use { it.executeUpdate("DELETE FROM game") }
        val restored = Ingest.ingestDir(conn, liveDir, kind = "live")
        assertEquals(1, restored.inserted)
        assertEquals("human", getJson("games?set=live").obj()["games"].arr()[0].obj()["p1"].obj().str("kind"))

        // 결과(랠리 outcome)를 바꾼 바이트 — 재생이 거절한다.
        val tampered = bytes.copyOf()
        val k = 18 + 4 * replay.seeds.size + 4 // 첫 랠리의 outcome
        tampered[k] = (1 - tampered[k]).toByte()
        val bad = post("live-games", tampered)
        assertEquals(422, bad.statusCode(), bad.body())
        assertEquals(1, DbTestSupport.count(conn, "game"))

        assertEquals(422, post("live-games", byteArrayOf(1, 2, 3)).statusCode())
    }

    @Test
    @DisplayName("M4-k 자동화 부분: JS 러너가 기록한 라이브 경기 → 제출 → 목록 → 받은 바이트 = 보낸 바이트, 체인 = JS 라이브 체인")
    fun jsLiveGameRoundTrip(@TempDir dir: Path) {
        org.junit.jupiter.api.Assumptions.assumeTrue(RepoPaths.upstreamIsPresent(), "upstream/ 이 없습니다")
        val p = ProcessBuilder("node", "--disable-warning=MODULE_TYPELESS_PACKAGE_JSON", "test/export-live.mjs", dir.toString())
            .directory(RepoPaths.root.resolve("viewer-web").toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(2, TimeUnit.MINUTES) && p.exitValue() == 0) { out }
        val games = Json.parse(Files.readString(dir.resolve("chains.json"))).obj()["games"].arr().map { it.obj() }
        var submitted = 0
        for (g in games) {
            val bytes = Files.readAllBytes(dir.resolve(g.str("file")))
            val res = post("live-games", bytes)
            if (!ReplayCodec.decode(bytes).ended) {
                assertEquals(422, res.statusCode(), "잘린 라이브는 받지 않는다")
                continue
            }
            assertEquals(200, res.statusCode(), res.body())
            val body = Json.parse(res.body()).obj()
            assertEquals(g.str("final"), body.str("chain"), g.str("file"))
            assertArrayEquals(bytes, get("games/${(body["id"] as Number).toLong()}/replay").body())
            submitted++
        }
        assertTrue(submitted >= 5)
        assertEquals(submitted.toLong(), getJson("games?set=live").obj()["total"])
    }

    @Test
    @DisplayName("정책: 목록 · ONNX 바이트, 주장 없음 = 사람, policy 주장 → 기준선과 같은 participant 행, 미등록 · 형식 오류 → 400")
    fun policyClaims(@TempDir evalDir: Path, @TempDir liveSrc: Path) {
        val list = getJson("policies").arr().map { it.obj() }
        assertEquals(listOf("test"), list.map { it.str("label") })
        assertEquals(onnxSha, list[0].str("onnx"))
        assertEquals("sha256:$CKPT", list[0].str("checkpoint"))
        assertArrayEquals("fake-onnx:test".toByteArray(), get("policies/$onnxSha.onnx").body())
        assertEquals(404, get("policies/${"0".repeat(64)}.onnx").statusCode())
        assertEquals(404, get("policies/$onnxSha").statusCode())

        // 평가 기준선 — writeEvalDir 의 정책 참가자는 checkpoint = CKPT
        DbTestSupport.writeEvalDir(evalDir, "api-eval", games = 1, baseSeed = 1)
        Ingest.ingestDir(conn, evalDir)
        val baselineP1 = DbTestSupport.scalar(conn, "SELECT p1_id FROM game")

        val games = DbTestSupport.writeEvalDir(liveSrc, "x", games = 3, baseSeed = 2).map { ReplayCodec.encode(it) }

        // 미등록 · 형식 오류 주장은 400 이고 아무것도 들어가지 않는다
        assertEquals(400, post("live-games?p1=policy:${"0".repeat(64)}", games[0]).statusCode())
        assertEquals(400, post("live-games?p1=robot", games[0]).statusCode())
        assertEquals(1, DbTestSupport.count(conn, "game"))
        assertTrue(Files.notExists(liveDir.resolve("manifest.jsonl")))

        // 주장 없음 = 기존 동작 (사람)
        val human = Json.parse(post("live-games", games[0]).body()).obj()
        assertEquals("human", getJson("games/${(human["id"] as Number).toLong()}").obj()["p1"].obj().str("kind"))

        // policy 주장 → 체크포인트 SHA 참가자 = 기준선과 같은 행. FSM 슬롯(p2)의 주장은 무시한다.
        val res = post("live-games?p1=policy:$onnxSha&p2=policy:${"0".repeat(64)}", games[1])
        assertEquals(200, res.statusCode(), res.body())
        val id = (Json.parse(res.body()).obj()["id"] as Number).toLong()
        assertEquals(baselineP1, DbTestSupport.scalar(conn, "SELECT p1_id FROM game WHERE id = $id"))
        assertEquals("fsm", getJson("games/$id").obj()["p2"].obj().str("kind"))

        // manifest 에 체크포인트 · onnx 가 남고, DB 를 지우고 되살려도 같은 참가자다
        val line = Json.parse(Files.readAllLines(liveDir.resolve("manifest.jsonl"))[1]).obj()
        assertEquals("sha256:$CKPT", line["p1"].obj().str("checkpoint"))
        assertEquals("sha256:$onnxSha", line["p1"].obj().str("onnx"))
        assertEquals(null, line["p2"].obj()["onnx"])
        conn.createStatement().use { it.executeUpdate("DELETE FROM game WHERE set_id <> (SELECT id FROM match_set WHERE name = 'api-eval')") }
        assertEquals(2, Ingest.ingestDir(conn, liveDir, kind = "live").inserted)
        assertEquals(2L, DbTestSupport.scalar(conn, "SELECT COUNT(*) FROM game WHERE p1_id = $baselineP1"))
    }

    @Test
    @DisplayName("레지스트리: ONNX 파일 SHA 가 다르거나 label 이 두 줄이면 적재 실패")
    fun registryRejects(@TempDir dir: Path) {
        writeRegistry(dir, "p", CKPT)
        Files.write(dir.resolve("p.onnx"), "다른 가중치".toByteArray())
        assertTrue(runCatching { PolicyRegistry.load(dir) }.exceptionOrNull()?.message?.contains("SHA") == true)
        writeRegistry(dir, "p", CKPT)
        val line = Files.readString(dir.resolve("registry.jsonl"))
        Files.writeString(dir.resolve("registry.jsonl"), line + line)
        assertTrue(runCatching { PolicyRegistry.load(dir) }.exceptionOrNull()?.message?.contains("두 줄") == true)
    }

    private companion object {
        /** `DbTestSupport.writeEvalDir` 의 정책 참가자 체크포인트. */
        val CKPT = "ab".repeat(32)
    }
}
