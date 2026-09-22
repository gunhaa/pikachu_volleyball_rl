package pika.server

import com.google.protobuf.ByteString
import pika.env.EnvBench
import pika.env.EnvConfig
import pika.env.EnvGolden
import pika.env.Slots
import pika.env.VectorEnv
import pika.env.v1.ConfigureRequest
import pika.env.v1.SlotKind
import pika.env.v1.StepRequest
import java.nio.file.Files

/**
 * (b) gRPC 루프백 처리량 — **Kotlin 클라이언트, 더미 행동**. (NFR-3, plan.md §11)
 *
 * (a) 와 같은 프로세스에서 in-process 수치도 함께 재고 **간극**을 출력한다.
 * 그 간극이 곧 직렬화 + RPC 왕복의 비용이다. Python 은 끼우지 않는다 — 끼우면
 * 그 비용과 Python 디코딩 비용이 섞여서 (c) 와 구별할 수 없게 된다.
 *
 * 숫자 하나만 재면 미달일 때 어디를 고쳐야 할지 알 수 없다. 그래서 세 지점을 나눈다.
 */
object LoopbackBench {

    data class Point(val label: String, val envStepsPerSec: Double, val batchMillis: Double)

    private fun benchTransport(
        name: String,
        numEnvs: Int,
        steps: Int,
        slots: Slots,
        makeServer: () -> EnvServer,
        connect: () -> EnvConnection,
    ): Point {
        val server = makeServer().start()
        val connection = connect()
        try {
            val stub = connection.blocking
            val reply = stub.configure(
                ConfigureRequest.newBuilder()
                    .setNumEnvs(numEnvs)
                    .setBaseSeed(1)
                    .setP1(SlotKind.SLOT_KIND_EXTERNAL)
                    .setP2(if (slots.p2.isFsm) SlotKind.SLOT_KIND_FSM else SlotKind.SLOT_KIND_EXTERNAL)
                    .build(),
            )

            // 더미 행동. 정책 비용을 섞지 않는다 — 여기서 재는 것은 전송이다.
            val actions = EnvGolden.ActionSequence(1, numEnvs, reply.slotCount)
            val request = { StepRequest.newBuilder().setActions(ByteString.copyFrom(actions.next())).build() }

            repeat(maxOf(20, steps / 10)) { stub.step(request()) }

            val t0 = System.nanoTime()
            repeat(steps) { stub.step(request()) }
            val nanos = System.nanoTime() - t0

            val envSteps = steps.toLong() * numEnvs
            return Point(name, envSteps * 1e9 / nanos, nanos / 1e6 / steps)
        } finally {
            connection.close()
            server.stop()
        }
    }

    /** 같은 프로세스에서 (a) 를 다시 잰다. 다른 실행의 숫자와 빼면 의미가 없다. */
    private fun inProcess(numEnvs: Int, steps: Int, slots: Slots): Point {
        val vec = VectorEnv(EnvConfig(slots = slots, baseSeed = 1), numEnvs)
        vec.reset()
        val actions = EnvGolden.ActionSequence(1, numEnvs, vec.slotCount)
        repeat(maxOf(20, steps / 10)) { vec.step(actions.next()) }

        val t0 = System.nanoTime()
        repeat(steps) { vec.step(actions.next()) }
        val nanos = System.nanoTime() - t0
        return Point("in-process", steps.toLong() * numEnvs * 1e9 / nanos, nanos / 1e6 / steps)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val steps = args.getOrNull(0)?.toInt() ?: 2000
        val sizes = listOf(16, 64, 256, 1024)

        println("── (a) vs (b) — in-process 와 gRPC 루프백 (NFR-3) ──")
        println("   스텝: $steps (워밍업 별도), 더미 행동, Track A")
        println()
        println("%-8s %-12s %14s %12s %12s".format("N", "지점", "env-step/s", "배치시간", "예산대비"))
        println("-".repeat(64))

        for (n in sizes) {
            val budgetMs = n / EnvBench.TARGET_ENV_STEPS_PER_SEC * 1000.0
            val a = inProcess(n, steps, Slots.EXTERNAL_VS_FSM)

            val socketPath = Files.createTempFile("pika-bench-", ".sock").also { Files.delete(it) }.toString()
            val uds = benchTransport(
                "UDS", n, steps, Slots.EXTERNAL_VS_FSM,
                { EnvServer.overUnixSocket(socketPath) },
                { EnvClient.overUnixSocket(socketPath) },
            )
            val port = 50000 + (n % 1000)
            val tcp = benchTransport(
                "TCP", n, steps, Slots.EXTERNAL_VS_FSM,
                { EnvServer.overTcp(port) },
                { EnvClient.overTcp(port) },
            )

            for (p in listOf(a, uds, tcp)) {
                println(
                    "%-8d %-12s %,14.0f %10.3fms %10.2f%%".format(
                        n, p.label, p.envStepsPerSec, p.batchMillis, p.batchMillis / budgetMs * 100,
                    ),
                )
            }
            val rpcCostMs = uds.batchMillis - a.batchMillis
            println(
                "         └ (a)−(b) 간극: %.3f ms/배치 = 직렬화 + RPC 왕복 (예산의 %.1f%%)".format(
                    rpcCostMs, rpcCostMs / budgetMs * 100,
                ),
            )
            println()
        }
        println("-".repeat(64))
        println("예산: 50,000 env-step/s 기준 배치 하나에 허용되는 시간 (N=256 이면 5.12 ms).")
        println("(c) Python 종단은 scripts/bench-env.sh 가 잰다. (b)−(c) 가 Python 쪽 비용이다.")
    }
}
