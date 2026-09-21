package pika.conformance

import kotlin.system.exitProcess

/**
 * 차분 테스트 실행기.
 *
 * 전수 검사는 시간이 걸리므로 `test` 태스크가 아니라 별도 진입점으로 둔다.
 * `./gradlew build` 는 축소된 단위 테스트만 돌고 초록을 유지한다. (NFR-2)
 *
 *   ./gradlew conformance                                  # plan.md §6.3 전량
 *   ./gradlew conformance --args="--gen uniform --seeds 1..100"
 *   ./gradlew conformance --args="--strict"
 *   ./gradlew conformance --args="--gen targeted"      # 표적 케이스만
 */
object Main {

    /** plan.md §6.3 의 배분. 커버리지가 부족하면 주저 없이 늘린다. */
    private val FULL_PLAN: List<Batch> by lazy {
        listOf(
            Batch(Generator.UNIFORM, "1..3000", 600),
            Batch(Generator.BIASED, "1..2000", 600),
            Batch(Generator.FSM, "1..2000", 600),
            targetedBatch(),
        )
    }

    /** (d) 표적 케이스. 시드가 케이스 번호이고 프레임 수는 케이스마다 다르다. */
    private fun targetedBatch(seeds: String? = null) =
        Batch(Generator.TARGETED, seeds ?: TargetedCases.allSeedSpec(), 0)

    data class Batch(val gen: Generator, val seeds: String, val frames: Int) {
        val frameCount: Long
            get() = if (gen == Generator.TARGETED) {
                Lockstep.parseSeeds(seeds).sumOf { TargetedCases.at(it).frames.toLong() }
            } else {
                Lockstep.parseSeeds(seeds).size.toLong() * frames
            }
    }

    @JvmStatic
    fun main(argv: Array<String>) {
        var gen: Generator? = null
        var seeds: String? = null
        var frames = 600
        var strict = false
        var probeResets = false

        var i = 0
        while (i < argv.size) {
            when (val a = argv[i]) {
                "--strict" -> strict = true
                // 엔진을 돌리지 않고 라운드 리셋만 대조한다. 포팅 전에도 초록이 나와야 한다.
                "--reset-probe" -> probeResets = true
                "--gen" -> gen = Generator.of(argv[++i])
                "--seeds" -> seeds = argv[++i]
                "--frames" -> frames = argv[++i].toInt()
                else -> {
                    System.err.println("알 수 없는 인자: $a")
                    exitProcess(2)
                }
            }
            i++
        }

        if (!RepoPaths.upstreamIsPresent()) {
            System.err.println("upstream/ 이 없습니다. scripts/fetch-upstream.sh 를 먼저 실행하세요.")
            exitProcess(2)
        }

        StateSpec.assertMatchesProto()
        TargetedCases.assertFieldsMatchSpec()

        val batches = when {
            gen == Generator.TARGETED -> listOf(targetedBatch(seeds))
            gen != null -> listOf(Batch(gen, seeds ?: "1..100", frames))
            // --seeds 만 준 경우: 표적 케이스는 시드 축이 다르므로 빼고 돈다.
            seeds != null -> Generator.entries.filter { it.isBaseGenerator }.map { Batch(it, seeds, frames) }
            else -> FULL_PLAN
        }

        val planned = batches.sumOf { it.frameCount }
        println("차분 테스트  배치 ${batches.size}개 · 총 ${"%,d".format(planned)} 프레임 · strict=$strict" + if (probeResets) " · 리셋 프로브" else "")
        println("─".repeat(72))

        var comparedTotal = 0L
        val startedAll = System.nanoTime()

        for (batch in batches) {
            val label = if (batch.gen == Generator.TARGETED) {
                "${batch.gen.cliName} cases=${batch.seeds}"
            } else {
                "${batch.gen.cliName} seeds=${batch.seeds} T=${batch.frames}"
            }
            print("  $label ... ")
            System.out.flush()

            val started = System.nanoTime()
            val result = Lockstep(batch.frames, batch.gen, strict, probeResets).run(batch.seeds)
            val elapsed = (System.nanoTime() - started) / 1e9
            comparedTotal += result.framesCompared

            if (!result.allMatched) {
                val m = result.mismatch!!
                println("불일치")
                println()
                println(Drilldowns.of(m).report())
                println()
                println("최소 재현 케이스 — conformance 테스트에 그대로 붙여 넣는다:")
                println(Drilldowns.of(m).minimalRepro())
                println()
                println("M1-a 는 타협하지 않는다. 불일치 1건이라도 남으면 완료가 아니다.")
                exitProcess(1)
            }

            val rate = result.framesCompared / elapsed
            println(
                "일치  ${"%,d".format(result.framesCompared)} 프레임 / " +
                    "%.1fs (%,.0f frame/s)".format(elapsed, rate)
            )
        }

        val total = (System.nanoTime() - startedAll) / 1e9
        println("─".repeat(72))
        println("전부 일치  ${"%,d".format(comparedTotal)} 프레임 / %.1fs".format(total))
    }
}
