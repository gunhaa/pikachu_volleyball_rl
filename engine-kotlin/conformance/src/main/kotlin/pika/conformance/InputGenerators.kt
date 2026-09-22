package pika.conformance

import pika.core.PikaPhysics
import pika.core.PikaUserInput
import pika.core.XorShift32
import kotlin.math.abs

/**
 * 입력 시퀀스 생성기 — `tools/js-oracle/inputs.mjs` 의 Kotlin 짝이다.
 *
 * ⚠️ 두 파일의 로직은 한 글자도 다르면 안 된다.
 *    입력이 갈라지면 물리가 맞아도 상태가 갈라진다.
 *
 * ⚠️ 나머지 연산은 전부 **부호 없는** uint32 기준이다.
 *    `Int % 100` 은 난수가 음수일 때 음수를 낸다. 반드시 [umod] 를 쓴다.
 */
enum class Generator(val cliName: String) {
    UNIFORM("uniform"),
    BIASED("biased"),
    FSM("fsm"),

    /**
     * (d) 표적 케이스. 다른 셋과 층위가 다르다 — 입력을 만드는 규칙이 아니라
     * **초기 상태를 심는 케이스 목록**이고, 입력은 각 케이스가 지정한 기본 생성기가 만든다.
     *
     * 이 값으로는 [fill] 도 [isComputerControlled] 도 호출되지 않는다.
     * `--gen targeted` 경로는 [Harness.runTargetedCase] 로 갈라져 케이스의 기본 생성기를 쓴다.
     */
    TARGETED("targeted"),
    ;

    /** 표적 케이스의 **기본** 생성기로 쓸 수 있는가. 표적이 표적을 가리킬 수는 없다. */
    val isBaseGenerator: Boolean get() = this != TARGETED

    /** FSM 모드에서는 엔진이 `letComputerDecideUserInput` 으로 입력을 덮어쓴다. */
    val isComputerControlled: Boolean
        get() = when (this) {
            FSM -> true
            UNIFORM, BIASED -> false
            TARGETED -> error("TARGETED 는 케이스마다 다르다. Harness.runTargetedCase 를 쓰세요.")
        }

    companion object {
        fun of(cliName: String): Generator =
            entries.firstOrNull { it.cliName == cliName }
                ?: error("알 수 없는 생성기: $cliName (가능: ${entries.joinToString(", ") { it.cliName }})")
    }
}

/** JS 의 `r % m` (r 은 uint32) 과 같은 결과. */
internal fun umod(r: Int, m: Int): Int = ((r.toLong() and 0xFFFF_FFFFL) % m).toInt()

private fun sgn(v: Int): Int = if (v > 0) 1 else if (v < 0) -1 else 0

internal object InputGenerators {

    fun fill(gen: Generator, rng: XorShift32, inputs: Array<PikaUserInput>, physics: PikaPhysics) {
        when (gen) {
            Generator.UNIFORM -> fillUniform(rng, inputs)
            Generator.BIASED -> fillBiased(rng, inputs, physics)
            Generator.FSM -> Unit // 입력 스트림에서 draw 하지 않는다
            Generator.TARGETED -> error("TARGETED 는 케이스의 기본 생성기로 대체된다")
        }
    }

    /** (a) 균일 무작위 — 플레이어당 3 draw. */
    private fun fillUniform(rng: XorShift32, inputs: Array<PikaUserInput>) {
        for (i in 0 until 2) {
            val u = inputs[i]
            u.xDirection = umod(rng.nextUInt(), 3) - 1
            u.yDirection = umod(rng.nextUInt(), 3) - 1
            u.powerHit = umod(rng.nextUInt(), 2)
        }
    }

    /** (b) 편향 무작위 — 공 쪽으로 가는 경향. 플레이어당 4 draw. */
    private fun fillBiased(rng: XorShift32, inputs: Array<PikaUserInput>, physics: PikaPhysics) {
        val ball = physics.ball
        for (i in 0 until 2) {
            val p = if (i == 0) physics.player1 else physics.player2
            val u = inputs[i]
            val r1 = rng.nextUInt()
            val r2 = rng.nextUInt()
            val r3 = rng.nextUInt()
            val r4 = rng.nextUInt()

            // 70% 확률로 공을 향해 이동
            u.xDirection = if (umod(r1, 100) < 70) sgn(ball.x - p.x) else umod(r2, 3) - 1

            // 25% 점프(위), 10% 아래
            val y = umod(r3, 100)
            u.yDirection = if (y < 25) -1 else if (y < 35) 1 else 0

            // 공이 가까우면 60%, 아니면 5%
            val near = abs(ball.x - p.x) <= 48 && abs(ball.y - p.y) <= 48
            u.powerHit = if (umod(r4, 100) < (if (near) 60 else 5)) 1 else 0
        }
    }
}
