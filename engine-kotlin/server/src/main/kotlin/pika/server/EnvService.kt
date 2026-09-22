package pika.server

import com.google.protobuf.ByteString
import com.google.protobuf.UnsafeByteOperations
import io.grpc.Status
import io.grpc.stub.StreamObserver
import pika.env.ActionCodec
import pika.env.EnvConfig
import pika.env.ObsSpec
import pika.env.RewardTerms
import pika.env.RewardWeights
import pika.env.Slot
import pika.env.Slots
import pika.env.VectorEnv
import pika.env.v1.ConfigureReply
import pika.env.v1.ConfigureRequest
import pika.env.v1.HealthReply
import pika.env.v1.HealthRequest
import pika.env.v1.PikaEnvGrpc
import pika.env.v1.ResetRequest
import pika.env.v1.SlotKind
import pika.env.v1.StepReply
import pika.env.v1.StepRequest

/**
 * gRPC 서비스 구현. (FR-11)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 이 클래스가 하는 일과 하지 않는 일
 * ─────────────────────────────────────────────────────────────────────────────
 * **하는 일**: 배열을 packed `bytes` 로 옮기는 것. 그게 전부다.
 * **하지 않는 일**: 환경 의미론에 대한 어떤 판단도 하지 않는다. 오토리셋도, 보상 계산도,
 * 시드 유도도 전부 [VectorEnv] 안에 있다. 그래야 gRPC 없이 (a) 를 잴 수 있고
 * (NFR-2), 서버를 거친 결과가 in-process 결과와 **바이트까지 같다**고 말할 수 있다.
 *
 * ⚠️ **디스크에 상태를 남기지 않는다** (plan.md §8.3). 재시작 후 같은 `Configure` 면
 *    같은 결과가 나와야 한다. 체크포인트·로그·캐시를 여기에 두면 그 성질이 깨진다.
 *
 * 스레드: [VectorEnv] 는 단일 스레드용이다. gRPC 가 동시 호출을 보낼 수 있으므로
 * 잠금으로 직렬화한다. 클라이언트가 순차 호출하면 경합이 없고, 경합이 생겨도
 * 결과의 결정론은 유지된다.
 */
class EnvService : PikaEnvGrpc.PikaEnvImplBase() {

    private val lock = Any()
    private var vec: VectorEnv? = null
    private var config: EnvConfig? = null

    private val startedAtNanos = System.nanoTime()
    private var stepNanos = 0L
    private var totalEnvSteps = 0L

    override fun configure(request: ConfigureRequest, responseObserver: StreamObserver<ConfigureReply>) {
        respond(responseObserver) {
            synchronized(lock) {
                val numEnvs = request.numEnvs
                if (numEnvs <= 0) throw invalidArgument("num_envs 는 양수여야 합니다: $numEnvs")

                val cfg = EnvConfig(
                    slots = Slots(request.p1.toSlot(default = Slot.External), request.p2.toSlot(default = Slot.Fsm)),
                    baseSeed = request.baseSeed,
                    winningScore = if (request.hasWinningScore()) request.winningScore else 15,
                    maxRallyFrames = if (request.hasMaxRallyFrames()) request.maxRallyFrames else 3_000,
                    obs = ObsSpec.Options(
                        includeExpectedLanding =
                        if (request.hasObsIncludeExpectedLanding()) request.obsIncludeExpectedLanding else true,
                        includeSideFlag =
                        if (request.hasObsIncludeSideFlag()) request.obsIncludeSideFlag else false,
                    ),
                    mirrorObservations =
                    if (request.hasMirrorObservations()) request.mirrorObservations else true,
                    edgeTriggerPowerHit =
                    if (request.hasEdgeTriggerPowerHit()) request.edgeTriggerPowerHit else true,
                    rewardWeights = if (request.hasRewardWeights()) {
                        val w = request.rewardWeights
                        RewardWeights(w.rallyWin, w.ballTouch, w.crossedNet, w.opponentMiss, w.timePenalty)
                    } else {
                        RewardWeights()
                    },
                )

                val v = VectorEnv(cfg, numEnvs)
                v.reset()
                vec = v
                config = cfg
                // 구성이 바뀌면 처리량 통계도 의미를 잃는다.
                stepNanos = 0
                totalEnvSteps = 0

                ConfigureReply.newBuilder()
                    .setNumEnvs(numEnvs)
                    .setSlotCount(cfg.slotCount)
                    .setObsDim(cfg.obsDim)
                    .setObsLayoutHash(cfg.layoutHash)
                    .addAllObsFieldNames(ObsSpec.fieldNames(cfg.obs))
                    .addAllRewardTermNames(RewardTerms.NAMES)
                    .setActionCount(ActionCodec.ACTION_COUNT)
                    .build()
            }
        }
    }

    override fun reset(request: ResetRequest, responseObserver: StreamObserver<StepReply>) {
        respond(responseObserver) {
            synchronized(lock) {
                val v = requireConfigured()
                if (request.hasBaseSeed()) v.reset(request.baseSeed) else v.reset()
                config = v.config
                buildReply(v)
            }
        }
    }

    override fun step(request: StepRequest, responseObserver: StreamObserver<StepReply>) {
        respond(responseObserver) {
            synchronized(lock) {
                val v = requireConfigured()
                val expected = v.numEnvs * v.slotCount
                val actions = request.actions
                if (actions.size() != expected) {
                    throw invalidArgument("actions 길이가 ${actions.size()} 입니다. ${expected} 이어야 합니다")
                }
                val bytes = actions.toByteArray()
                for (b in bytes) {
                    val a = b.toInt() and 0xFF
                    if (a >= ActionCodec.ACTION_COUNT) {
                        throw invalidArgument("행동은 0..${ActionCodec.ACTION_COUNT - 1} 이어야 합니다: $a")
                    }
                }

                val t0 = System.nanoTime()
                v.step(bytes)
                stepNanos += System.nanoTime() - t0
                totalEnvSteps += v.numEnvs.toLong()

                buildReply(v)
            }
        }
    }

    override fun health(request: HealthRequest, responseObserver: StreamObserver<HealthReply>) {
        respond(responseObserver) {
            synchronized(lock) {
                val v = vec
                val cfg = config
                HealthReply.newBuilder()
                    .setVersion(VERSION)
                    .setConfigured(v != null)
                    .setNumEnvs(v?.numEnvs ?: 0)
                    .setSlotCount(v?.slotCount ?: 0)
                    .setObsDim(cfg?.obsDim ?: 0)
                    .setObsLayoutHash(cfg?.layoutHash ?: "")
                    .setTotalEnvSteps(totalEnvSteps)
                    .setUptimeSeconds((System.nanoTime() - startedAtNanos) / 1e9)
                    // ⚠️ 순간값이 아니라 **스텝에 쓴 시간** 기준의 누적 처리량이다.
                    //    대기 시간이 섞이면 서버가 느린지 클라이언트가 느린지 구별할 수 없다.
                    .setEnvStepsPerSec(if (stepNanos > 0) totalEnvSteps * 1e9 / stepNanos else 0.0)
                    .addAllRewardTermNames(RewardTerms.NAMES)
                    .build()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 직렬화
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [VectorEnv] 의 재사용 버퍼를 응답으로 옮긴다.
     *
     * ⚠️ 매번 새 `ByteArray` 를 만든다. 버퍼를 그대로 wrap 하면 다음 스텝이 응답의 내용을
     *    덮어쓴다 — gRPC 가 아직 보내는 중일 수도 있다. 복사는 N=256 에서 스텝당 약 82KB,
     *    초당 195회 호출이면 16MB/s 다 (plan.md §2.1). 예산 안이다.
     */
    private fun buildReply(v: VectorEnv): StepReply = StepReply.newBuilder()
        .setObservations(floatsToBytes(v.observations))
        .setRewards(floatsToBytes(v.rewards))
        .setTerminated(UnsafeByteOperations.unsafeWrap(v.terminated.copyOf()))
        .setTruncated(UnsafeByteOperations.unsafeWrap(v.truncated.copyOf()))
        .setRewardTerms(floatsToBytes(v.rewardTerms))
        .setScores(intsToBytes(v.scores))
        .build()

    private fun floatsToBytes(values: FloatArray): ByteString {
        val out = ByteArray(values.size * 4)
        var j = 0
        for (value in values) {
            val bits = value.toRawBits()
            out[j++] = (bits and 0xFF).toByte()
            out[j++] = ((bits ushr 8) and 0xFF).toByte()
            out[j++] = ((bits ushr 16) and 0xFF).toByte()
            out[j++] = ((bits ushr 24) and 0xFF).toByte()
        }
        return UnsafeByteOperations.unsafeWrap(out)
    }

    private fun intsToBytes(values: IntArray): ByteString {
        val out = ByteArray(values.size * 4)
        var j = 0
        for (value in values) {
            out[j++] = (value and 0xFF).toByte()
            out[j++] = ((value ushr 8) and 0xFF).toByte()
            out[j++] = ((value ushr 16) and 0xFF).toByte()
            out[j++] = ((value ushr 24) and 0xFF).toByte()
        }
        return UnsafeByteOperations.unsafeWrap(out)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 자잘한 것
    // ─────────────────────────────────────────────────────────────────────────

    private fun requireConfigured(): VectorEnv =
        vec ?: throw Status.FAILED_PRECONDITION
            .withDescription("Configure 를 먼저 호출하세요")
            .asRuntimeException()

    private fun invalidArgument(message: String) =
        Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException()

    /** 예외를 gRPC 상태로 옮긴다. 서버가 조용히 멈추는 것보다 클라이언트가 실패하는 것이 낫다. */
    private inline fun <T> respond(observer: StreamObserver<T>, block: () -> T) {
        try {
            observer.onNext(block())
            observer.onCompleted()
        } catch (e: io.grpc.StatusRuntimeException) {
            observer.onError(e)
        } catch (e: Exception) {
            observer.onError(
                Status.INTERNAL.withDescription("${e::class.simpleName}: ${e.message}").withCause(e)
                    .asRuntimeException(),
            )
        }
    }

    private fun SlotKind.toSlot(default: Slot): Slot = when (this) {
        SlotKind.SLOT_KIND_EXTERNAL -> Slot.External
        SlotKind.SLOT_KIND_FSM -> Slot.Fsm
        else -> default
    }

    companion object {
        /** 계약 버전. 관측 레이아웃이나 바이트 레이아웃을 바꾸면 올린다. */
        const val VERSION = "pika-env/1"
    }
}
