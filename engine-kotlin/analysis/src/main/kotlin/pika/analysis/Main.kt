package pika.analysis

import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * `analysis` 하위 명령. (plan.md §6.1)
 *
 * ```
 * ./gradlew :engine-kotlin:analysis:run --args="golden-replays [--out <dir>]"
 * ```
 */
object Main {
    private val USAGE = """
        사용법: analysis <명령> [옵션]
          golden-replays [--out <dir>]   골든 리플레이 + Kotlin 체인 해시 (M4-b 기준)
          dump-states <pkr> --from N --count M [--out <file>]
                                         프레임별 State Spec 44필드 (JS 불일치 추적용)
          ingest <dir> [--kind eval|baseline|selfplay] [--note <text>]
                                         manifest.jsonl + *.pkr → 재생 검증 → DB (전부 아니면 전무)
          rebuild-stats [--set <name>]   리플레이 BLOB 에서 rally · power_hit 를 다시 파생
          baseline-fsm [--games 800] [--base-seed 0] [--out <dir>] [--no-ingest]
                                         FSM vs FSM 을 GameEvaluator 규약으로 기록 → 적재 (M4-c)
          report --set <name> [--expect <json>]
                                         DB 집계 (SideStats 모양). --expect 와 다르면 exit 1 (M4-c · M4-d)
          replay-hashes                  DB 의 replay_sha256 집합 digest (재생성 결정론 확인)
          serve [--port 8081] [--live-dir runs/live]
                                         뷰어용 HTTP API (127.0.0.1 전용). 라이브 원본은 live-dir 에도 남긴다

        DB 옵션: --db-url <jdbc> --db-user <u> --db-password <p>  (환경 변수 PIKA_DB_URL 등, 기본 compose 값)
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println(USAGE)
            exitProcess(2)
        }
        val opts = Options(args.drop(1))
        when (args[0]) {
            "golden-replays" -> println(GoldenReplays.write(opts.path("--out") ?: GoldenReplays.dir))
            "dump-states" -> dumpStates(opts)
            "ingest" -> Db.open(Db.Config.from(opts)).use { conn ->
                val dir = Paths.get(opts.positional.single())
                val result = try {
                    Ingest.ingestDir(conn, dir, opts.str("--kind") ?: "eval", opts.str("--note"))
                } catch (e: Ingest.IngestException) {
                    System.err.println("적재 실패 — 아무것도 넣지 않았습니다: ${e.message}")
                    exitProcess(1)
                }
                println("$dir: $result")
            }
            "baseline-fsm" -> {
                val dir = opts.path("--out") ?: Paths.get("runs/baselines/fsm-vs-fsm")
                val expected = Baselines.writeFsm(dir, opts.int("--games", 800), opts.int("--base-seed", 0))
                println("FSM vs FSM 기록 → $dir\n  GameEvaluator: ${Json.write(expected)}")
                if ("--no-ingest" !in opts.flags) {
                    Db.open(Db.Config.from(opts)).use { conn ->
                        println("  ${Ingest.ingestDir(conn, dir, kind = "baseline")}")
                    }
                }
            }
            "report" -> Db.open(Db.Config.from(opts)).use { conn ->
                val set = opts.str("--set") ?: error("--set 이 필요합니다")
                val db = Baselines.report(conn, set)
                println(Json.write(db))
                opts.path("--expect")?.let { path ->
                    val diff = Baselines.compare(db, Json.parse(java.nio.file.Files.readString(path)).obj())
                    if (diff.isNotEmpty()) {
                        System.err.println("DB 집계 ≠ 리포트 ($path):\n  " + diff.joinToString("\n  "))
                        exitProcess(1)
                    }
                    println("일치: $path")
                }
            }
            "serve" -> {
                val config = Db.Config.from(opts)
                Db.open(config).close() // 스키마 버전을 먼저 본다
                val liveDir = opts.path("--live-dir") ?: Paths.get("runs/live")
                val s = Serve(config, opts.int("--port", 8081), liveDir).start()
                println("serve: http://127.0.0.1:${s.port}/api/ · 라이브 원본 → ${liveDir.toAbsolutePath()} (Ctrl+C 로 종료)")
            }
            "replay-hashes" -> Db.open(Db.Config.from(opts)).use { conn ->
                val hashes = conn.createStatement().use { st ->
                    st.executeQuery("SELECT replay_sha256 FROM game ORDER BY replay_sha256").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                println("${hashes.size} 게임, 집합 digest ${Ingest.sha256Hex(hashes.joinToString("\n").toByteArray())}")
            }
            "rebuild-stats" -> Db.open(Db.Config.from(opts)).use { conn ->
                println("통계 재계산: ${Ingest.rebuildStats(conn, opts.str("--set"))} 게임")
            }
            else -> {
                System.err.println("알 수 없는 명령: ${args[0]}\n$USAGE")
                exitProcess(2)
            }
        }
    }

    /** `test/diff-states.mjs` 가 읽는 형식: `프레임 v0 v1 … v43` 한 줄씩. */
    private fun dumpStates(opts: Options) {
        val file = Paths.get(opts.positional.single())
        val from = opts.int("--from", 0)
        val count = opts.int("--count", 1000)
        val replay = pika.env.replay.ReplayCodec.decode(java.nio.file.Files.readAllBytes(file))
        val ints = IntArray(pika.conformance.StateSpec.intCount(strict = false))
        val sb = StringBuilder()
        pika.env.replay.ReplayPlayer(replay).play { game, frame, scorer ->
            if (frame in from until from + count) {
                pika.conformance.StateSpec.writeInts(game.physics, scorer != null, strict = false, out = ints)
                sb.append(frame).append(' ').append(ints.joinToString(" ")).append('\n')
            }
        }
        val out = opts.path("--out")
        if (out == null) print(sb) else java.nio.file.Files.writeString(out, sb)
    }

    /** `--key value` 와 위치 인자. */
    class Options(args: List<String>) {
        val named = mutableMapOf<String, String>()
        val flags = mutableSetOf<String>()
        val positional = mutableListOf<String>()

        init {
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--")) {
                    if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        named[a] = args[i + 1]
                        i += 2
                    } else {
                        flags += a
                        i++
                    }
                } else {
                    positional += a
                    i++
                }
            }
        }

        fun path(key: String) = named[key]?.let { Paths.get(it) }
        fun int(key: String, default: Int) = named[key]?.toInt() ?: default
        fun str(key: String) = named[key]
    }
}
