package pika.env

import pika.core.PikaUserInput
import pika.core.Rand
import pika.core.XorShift32

/**
 * (a) `env` 단독 처리량. JVM in-process, gRPC 없음. (M2-b, NFR-3, plan.md §11)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 왜 gRPC 보다 **먼저** 재는가
 * ─────────────────────────────────────────────────────────────────────────────
 * 다 만든 뒤에 "느리다" 를 마주하면 어디가 느린지 알 수 없다. 여기서 바닥 숫자를 확정해
 * 두면 종단 숫자(M2-a)와의 **간극이 곧 RPC 계층의 비용**이 된다. 뺄셈이 가능한 상태를
 * 먼저 만드는 것이 이 벤치의 목적이다 (plan.md §1).
 *
 * 같은 이유로 **엔진만 돌린 수치도 함께 잰다.** 같은 기계에서 재야 "관측 인코딩이
 * 몇 배 비싼가" 를 말할 수 있다. plan.md §2.1 의 8.1M step/s 는 다른 실행의 값이다.
 */
object EnvBench {

    /** plan.md §2.1 의 예산. 전제가 바뀌면 여기서 다시 계산한다. */
    const val TARGET_ENV_STEPS_PER_SEC = 50_000.0

    data class Result(val label: String, val envSteps: Long, val nanos: Long) {
        val stepsPerSec: Double get() = envSteps * 1e9 / nanos
    }

    /**
     * 엔진만. 관측 인코딩도 보상도 없다 — 같은 기계에서의 참조점이다.
     *
     * 입력은 미리 만든 고정 배열을 쓴다. 입력 생성 비용이 섞이면 참조점이 오염된다.
     */
    fun engineOnly(frames: Int, warmup: Int = frames / 4): Result {
        fun loop(n: Int): Long {
            val rng = XorShift32(1)
            var game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM)
            val inputs = arrayOf(PikaUserInput(), PikaUserInput())
            var done = 0
            while (done < n) {
                if (game.gameEnded) {
                    game = PikaGame(Rand { rng.nextRand() }, Slots.FSM_VS_FSM)
                } else {
                    if (game.step(inputs) != null) game.startNextRally()
                }
                done++
            }
            return done.toLong()
        }
        loop(warmup)
        val t0 = System.nanoTime()
        val steps = loop(frames)
        return Result("engine-only (FSM vs FSM)", steps, System.nanoTime() - t0)
    }

    /** `env` 전체 — 물리 + 관측 인코딩 + 보상 + 오토리셋. */
    fun envOnly(config: EnvConfig, numEnvs: Int, frames: Int, warmup: Int = frames / 4): Result {
        fun loop(vec: VectorEnv, batches: Int): Double {
            val actions = EnvGolden.ActionSequence(1, numEnvs, vec.slotCount)
            var sink = 0.0
            repeat(batches) {
                vec.step(actions.next())
                // JIT 이 인코딩을 죽은 코드로 지우지 못하게 결과를 읽는다.
                sink += vec.observations[0] + vec.rewards[0]
            }
            return sink
        }

        val vec = VectorEnv(config, numEnvs)
        vec.reset()
        val warmupBatches = maxOf(1, warmup / numEnvs)
        blackhole += loop(vec, warmupBatches)

        vec.reset()
        val batches = maxOf(1, frames / numEnvs)
        val t0 = System.nanoTime()
        blackhole += loop(vec, batches)
        val nanos = System.nanoTime() - t0
        return Result("env N=$numEnvs", batches.toLong() * numEnvs, nanos)
    }

    /** JIT 제거 방지용. 어디에도 쓰이지 않지만 컴파일러는 그것을 모른다. */
    @JvmStatic
    var blackhole: Double = 0.0

    @JvmStatic
    fun main(args: Array<String>) {
        val frames = args.getOrNull(0)?.toInt() ?: 2_000_000
        val sizes = listOf(1, 4, 16, 64, 256, 1024)

        println("── (a) env 단독 처리량 — JVM in-process, 단일 스레드 (M2-b) ──")
        println("   JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        println("   프레임: $frames (워밍업 별도)")
        println()

        val engine = engineOnly(frames)
        println("참조점  %-24s %,12.0f step/s".format(engine.label, engine.stepsPerSec))
        println()
        println("%-18s %14s %10s %12s %12s".format("구성", "step/s", "엔진대비", "배치시간", "예산대비"))
        println("-".repeat(70))

        for (track in listOf("A" to Slots.EXTERNAL_VS_FSM, "B" to Slots.EXTERNAL_VS_EXTERNAL)) {
            val (name, slots) = track
            for (n in sizes) {
                val r = envOnly(EnvConfig(slots = slots), n, frames)
                val batchMs = n * 1000.0 / r.stepsPerSec
                // N=256 에서 50,000 env-step/s 의 배치 예산은 5.12 ms 다 (plan.md §2.1).
                val budgetMs = n / TARGET_ENV_STEPS_PER_SEC * 1000.0
                println(
                    "Track %-2s N=%-11d %,14.0f %9.1f배 %10.3fms %10.2f%%".format(
                        name, n, r.stepsPerSec, engine.stepsPerSec / r.stepsPerSec, batchMs,
                        batchMs / budgetMs * 100,
                    ),
                )
            }
        }
        println("-".repeat(70))
        println("배치시간 = N개 환경을 한 번 스텝하는 데 걸린 시간.")
        println("예산대비 = 그 시간이 '50,000 env-step/s' 예산에서 차지하는 비율.")
        println("           100% 를 넘으면 env 만으로 이미 목표를 못 맞춘다는 뜻이다.")
        println()
        println("M2-b 기준: 단일 스레드 ≥ 1,000,000 env-step/s")
        println("⚠️ 엔진 대비 10배 이상 느리면 범인은 관측 인코딩이다 (plan.md §11).")
        println("   엔진을 병렬화하는 것은 오진이다 — 엔진은 예산의 1% 도 쓰지 않는다.")
    }
}
