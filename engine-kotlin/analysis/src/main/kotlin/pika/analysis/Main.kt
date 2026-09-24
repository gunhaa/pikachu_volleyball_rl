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
