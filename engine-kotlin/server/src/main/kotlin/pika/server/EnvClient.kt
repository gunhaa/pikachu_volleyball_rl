package pika.server

import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioDomainSocketChannel
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory
import pika.env.v1.PikaEnvGrpc
import java.net.InetSocketAddress
import java.net.UnixDomainSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Kotlin 쪽 클라이언트. 루프백 벤치(b)와 서버 테스트가 쓴다.
 *
 * 학습기는 Python 이므로 이것이 주 클라이언트는 아니다. 그럼에도 있는 이유는 (b) 지점을
 * 재기 위해서다 — (a) `env` 단독과 (c) Python 종단 사이에서 **직렬화 + RPC 비용만**
 * 떼어 보려면 Python 을 끼우지 않은 왕복이 필요하다 (NFR-3).
 *
 * ⚠️ 채널과 이벤트 루프를 **함께** 들고 있는 이유: gRPC 는 `channelType` 을 지정하면
 *    이벤트 루프 그룹도 요구하고, 받은 그룹은 정리해 주지 않는다. 채널만 닫고 그룹을
 *    놔두면 스레드가 남는다 (이 함정을 한 번 밟았다 — 벤치가 결과를 다 내고도 JVM 이
 *    끝나지 않았다). 그래서 채널을 닫을 수 있는 자리에서 그룹도 같이 닫는다.
 */
class EnvConnection internal constructor(
    val channel: ManagedChannel,
    private val group: EventLoopGroup,
) : AutoCloseable {

    val blocking: PikaEnvGrpc.PikaEnvBlockingStub = PikaEnvGrpc.newBlockingStub(channel)

    override fun close() {
        channel.shutdownNow()
        channel.awaitTermination(5, TimeUnit.SECONDS)
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
    }
}

object EnvClient {

    /** ⚠️ 연결은 한 번만 만든다. 스텝마다 만들면 예산이 그 자리에서 날아간다 (plan.md §7.4). */
    fun overUnixSocket(path: String): EnvConnection {
        val group = daemonGroup()
        val channel = NettyChannelBuilder
            .forAddress(UnixDomainSocketAddress.of(path))
            .channelType(NioDomainSocketChannel::class.java)
            .eventLoopGroup(group)
            .tune()
            .build()
        return EnvConnection(channel, group)
    }

    fun overTcp(port: Int, host: String = "127.0.0.1"): EnvConnection {
        val group = daemonGroup()
        val channel = NettyChannelBuilder
            .forAddress(InetSocketAddress(host, port))
            .channelType(NioSocketChannel::class.java)
            .eventLoopGroup(group)
            .tune()
            .build()
        return EnvConnection(channel, group)
    }

    private fun daemonGroup(): NioEventLoopGroup =
        NioEventLoopGroup(1, DefaultThreadFactory("pika-client", true))

    private fun NettyChannelBuilder.tune(): NettyChannelBuilder = this
        .usePlaintext()
        .maxInboundMessageSize(64 * 1024 * 1024)
        .flowControlWindow(8 * 1024 * 1024)
}
