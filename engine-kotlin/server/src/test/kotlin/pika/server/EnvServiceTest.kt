package pika.server

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import pika.env.ActionCodec
import pika.env.EnvConfig
import pika.env.EnvGolden
import pika.env.ObsSpec
import pika.env.RewardTerms
import pika.env.Slots
import pika.env.VectorEnv
import pika.env.v1.ConfigureRequest
import pika.env.v1.HealthRequest
import pika.env.v1.PikaEnvGrpc
import pika.env.v1.ResetRequest
import pika.env.v1.SlotKind
import pika.env.v1.StepRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * 서버가 **의미론을 더하지 않는다**는 것을 본다. (tasks.md P6)
 *
 * 핵심은 [serverMatchesInProcess] 다 — 같은 구성·같은 행동이면 서버를 거친 결과가
 * in-process [VectorEnv] 와 **바이트까지 같아야** 한다. 이것이 참이어야 (a) 와 (c) 의
 * 뺄셈이 "RPC 비용" 을 뜻하게 된다. 서버가 조금이라도 다르게 굴면 그 뺄셈은 무의미하다.
 *
 * 전송은 실제 UDS 를 쓴다. in-process 전송으로 시험하면 정작 배포에서 쓰는 경로를
 * 한 번도 안 밟게 된다.
 */
class EnvServiceTest {

    private lateinit var server: EnvServer
    private lateinit var connection: EnvConnection
    private lateinit var stub: PikaEnvGrpc.PikaEnvBlockingStub
    private lateinit var socketPath: String

    @BeforeEach
    fun setUp() {
        // UDS 경로에는 길이 제한(약 104바이트)이 있다. 짧은 임시 경로를 쓴다.
        socketPath = Files.createTempFile("pika-", ".sock").also { Files.delete(it) }.toString()
        server = EnvServer.overUnixSocket(socketPath).start()
        connection = EnvClient.overUnixSocket(socketPath)
        stub = connection.blocking
    }

    @AfterEach
    fun tearDown() {
        connection.close()
        server.stop()
    }

    private fun configure(
        numEnvs: Int = 4,
        baseSeed: Int = 11,
        p1: SlotKind = SlotKind.SLOT_KIND_EXTERNAL,
        p2: SlotKind = SlotKind.SLOT_KIND_FSM,
    ) = stub.configure(
        ConfigureRequest.newBuilder()
            .setNumEnvs(numEnvs).setBaseSeed(baseSeed).setP1(p1).setP2(p2).build(),
    )

    private fun ByteString.asFloats(): FloatArray {
        val buf = asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size() / 4) { buf.getFloat(it * 4) }
    }

    private fun ByteString.asInts(): IntArray {
        val buf = asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(size() / 4) { buf.getInt(it * 4) }
    }

    private fun assertFloatsEqual(expected: FloatArray, actual: FloatArray, message: String) {
        assertArrayEquals(expected, actual, 0f, message)
    }

    @Test
    @DisplayName("Configure 는 관측 레이아웃의 신원을 함께 돌려준다")
    fun configureReportsLayout() {
        val reply = configure()
        val expected = EnvConfig(slots = Slots.EXTERNAL_VS_FSM)
        assertEquals(4, reply.numEnvs)
        assertEquals(1, reply.slotCount, "Track A 의 외부 슬롯은 하나다")
        assertEquals(expected.obsDim, reply.obsDim)
        assertEquals(expected.layoutHash, reply.obsLayoutHash)
        assertEquals(ObsSpec.fieldNames(), reply.obsFieldNamesList)
        assertEquals(RewardTerms.NAMES, reply.rewardTermNamesList)
        assertEquals(ActionCodec.ACTION_COUNT, reply.actionCount)

        val trackB = configure(p2 = SlotKind.SLOT_KIND_EXTERNAL)
        assertEquals(2, trackB.slotCount, "Track B 의 외부 슬롯은 둘이다")
    }

    @Test
    @DisplayName("Health: Configure 전에는 configured=false, 후에는 레이아웃 해시를 준다")
    fun healthReportsState() {
        val before = stub.health(HealthRequest.getDefaultInstance())
        assertFalse(before.configured)
        assertEquals(EnvService.VERSION, before.version)
        assertEquals(0, before.totalEnvSteps)

        configure(numEnvs = 2)
        val actions = ByteString.copyFrom(ByteArray(2))
        repeat(10) { stub.step(StepRequest.newBuilder().setActions(actions).build()) }

        val after = stub.health(HealthRequest.getDefaultInstance())
        assertTrue(after.configured)
        assertEquals(EnvConfig().layoutHash, after.obsLayoutHash, "클라이언트는 이 값으로 서버를 검증한다")
        assertEquals(20, after.totalEnvSteps, "2환경 × 10스텝")
        assertTrue(after.envStepsPerSec > 0.0)
    }

    @Test
    @DisplayName("서버를 거친 결과가 in-process VectorEnv 와 바이트까지 같다")
    fun serverMatchesInProcess() {
        for (slots in listOf(Slots.EXTERNAL_VS_FSM, Slots.EXTERNAL_VS_EXTERNAL)) {
            val numEnvs = 4
            val baseSeed = 77
            val config = EnvConfig(slots = slots, baseSeed = baseSeed)

            val expected = VectorEnv(config, numEnvs)
            expected.reset()

            configure(
                numEnvs = numEnvs,
                baseSeed = baseSeed,
                p2 = if (slots.p2.isFsm) SlotKind.SLOT_KIND_FSM else SlotKind.SLOT_KIND_EXTERNAL,
            )

            val actions = EnvGolden.ActionSequence(1, numEnvs, expected.slotCount)
            repeat(300) {
                val batch = actions.next().copyOf()
                expected.step(batch)
                val reply = stub.step(StepRequest.newBuilder().setActions(ByteString.copyFrom(batch)).build())

                assertFloatsEqual(expected.observations, reply.observations.asFloats(), "관측 ($slots)")
                assertFloatsEqual(expected.rewards, reply.rewards.asFloats(), "보상 ($slots)")
                assertFloatsEqual(expected.rewardTerms, reply.rewardTerms.asFloats(), "보상 항 ($slots)")
                assertArrayEquals(expected.terminated, reply.terminated.toByteArray(), "terminated ($slots)")
                assertArrayEquals(expected.truncated, reply.truncated.toByteArray(), "truncated ($slots)")
                assertArrayEquals(expected.scores, reply.scores.asInts(), "점수 ($slots)")
            }
        }
    }

    @Test
    @DisplayName("Reset(base_seed) 은 그 시드의 처음 상태를 준다")
    fun resetWithSeed() {
        configure(numEnvs = 2, baseSeed = 1)
        val actions = ByteString.copyFrom(ByteArray(2))
        repeat(50) { stub.step(StepRequest.newBuilder().setActions(actions).build()) }

        val reply = stub.reset(ResetRequest.newBuilder().setBaseSeed(9).build())

        val expected = VectorEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 9), 2)
        expected.reset()
        assertFloatsEqual(expected.observations, reply.observations.asFloats(), "리셋 관측")
        assertFloatsEqual(FloatArray(2), reply.rewards.asFloats(), "리셋 응답의 보상은 0 이다")
        assertArrayEquals(ByteArray(2), reply.terminated.toByteArray())
    }

    @Test
    @DisplayName("Configure 없이 Step 하면 FAILED_PRECONDITION")
    fun stepBeforeConfigure() {
        val e = assertThrows(StatusRuntimeException::class.java) {
            stub.step(StepRequest.newBuilder().setActions(ByteString.copyFrom(ByteArray(1))).build())
        }
        assertEquals(Status.Code.FAILED_PRECONDITION, e.status.code)
    }

    @Test
    @DisplayName("행동 배열의 길이나 값이 틀리면 INVALID_ARGUMENT — 조용히 뭉개지 않는다")
    fun badActionsAreRejected() {
        configure(numEnvs = 2)

        val wrongSize = assertThrows(StatusRuntimeException::class.java) {
            stub.step(StepRequest.newBuilder().setActions(ByteString.copyFrom(ByteArray(3))).build())
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, wrongSize.status.code)

        val outOfRange = assertThrows(StatusRuntimeException::class.java) {
            stub.step(StepRequest.newBuilder().setActions(ByteString.copyFrom(byteArrayOf(0, 18))).build())
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, outOfRange.status.code)
    }

    @Test
    @DisplayName("응답 버퍼는 스텝 사이에 덮어쓰이지 않는다")
    fun repliesAreIndependent() {
        configure(numEnvs = 2)
        val actions = ByteString.copyFrom(byteArrayOf(3, 3))
        val first = stub.step(StepRequest.newBuilder().setActions(actions).build())
        val firstObs = first.observations.asFloats()

        repeat(20) { stub.step(StepRequest.newBuilder().setActions(actions).build()) }

        assertFloatsEqual(firstObs, first.observations.asFloats(), "예전 응답의 내용이 바뀌었습니다")
    }

    @Test
    @DisplayName("스텝 응답의 크기가 계약과 같다")
    fun replySizesMatchContract() {
        val reply = configure(numEnvs = 8, p2 = SlotKind.SLOT_KIND_EXTERNAL)
        val n = reply.numEnvs
        val slots = reply.slotCount
        val step = stub.step(
            StepRequest.newBuilder().setActions(ByteString.copyFrom(ByteArray(n * slots))).build(),
        )
        assertEquals(n * slots * reply.obsDim * 4, step.observations.size())
        assertEquals(n * slots * 4, step.rewards.size())
        assertEquals(n, step.terminated.size())
        assertEquals(n, step.truncated.size())
        assertEquals(n * slots * RewardTerms.COUNT * 4, step.rewardTerms.size())
        assertEquals(n * 2 * 4, step.scores.size())
    }

    @Test
    @DisplayName("두 번째 Configure 는 첫 세션을 무효화한다 — 단일 테넌트가 조용히 깨지지 않는다")
    fun secondConfigureInvalidatesFirstSession() {
        val first = configure(numEnvs = 4)
        val second = configure(numEnvs = 8)
        assertTrue(second.sessionId > first.sessionId, "Configure 마다 세션 번호가 올라간다")

        // 낡은 세션으로 스텝하면 실패한다. 이것이 없으면 첫 클라이언트는 아무 에러 없이
        // **모양이 다른 응답**을 받고, 한참 뒤 reshape 에서야 터진다.
        val stale = assertThrows(StatusRuntimeException::class.java) {
            stub.step(
                StepRequest.newBuilder()
                    .setActions(ByteString.copyFrom(ByteArray(4)))
                    .setSessionId(first.sessionId)
                    .build(),
            )
        }
        assertEquals(Status.Code.FAILED_PRECONDITION, stale.status.code)

        // 새 세션은 정상이다.
        val ok = stub.step(
            StepRequest.newBuilder()
                .setActions(ByteString.copyFrom(ByteArray(8)))
                .setSessionId(second.sessionId)
                .build(),
        )
        assertEquals(8, ok.terminated.size())
    }

    @Test
    @DisplayName("관측 바이트는 float32 little-endian 이다 (np.frombuffer 와 같은 해석)")
    fun observationsAreLittleEndianFloat32() {
        val reply = configure(numEnvs = 1)
        val step = stub.step(StepRequest.newBuilder().setActions(ByteString.copyFrom(ByteArray(1))).build())
        val buf = ByteBuffer.wrap(step.observations.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)

        val expected = VectorEnv(EnvConfig(slots = Slots.EXTERNAL_VS_FSM, baseSeed = 11), 1)
        expected.reset()
        expected.step(ByteArray(1))
        for (i in 0 until reply.obsDim) {
            assertEquals(expected.observations[i], buf.getFloat(i * 4), 0f, "관측 $i")
        }
    }
}
