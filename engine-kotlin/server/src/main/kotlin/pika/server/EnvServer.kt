package pika.server

import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerDomainSocketChannel
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel
import java.io.File
import java.net.InetSocketAddress
import java.net.UnixDomainSocketAddress
import java.util.concurrent.TimeUnit

/**
 * 엔진 gRPC 서버. (FR-11, plan.md §7.3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 전송: Unix domain socket 우선, TCP 도 지원
 * ─────────────────────────────────────────────────────────────────────────────
 * UDS 는 TCP 루프백보다 지연이 낮고, Phase 9 에서 주소만 바꾸면 TCP 로 간다.
 *
 * **NIO 도메인 소켓**을 쓴다 (`NioServerDomainSocketChannel`). netty 의 epoll 도메인
 * 소켓이 리눅스에서 조금 더 빠르지만 **리눅스 전용**이라, 개발 기계(macOS)에서 UDS 를
 * 아예 못 쓰게 된다. "개발에서는 TCP, 배포에서는 UDS" 는 측정값을 비교 불가능하게 만든다.
 * NIO 도메인 소켓은 JDK 16+ 의 표준이라 두 곳에서 같은 코드가 돈다.
 * 필요하면 P8 의 측정 결과를 보고 리눅스에서만 epoll 로 바꾼다 — 짐작으로 정하지 않는다.
 *
 * ⚠️ 서버는 디스크에 상태를 남기지 않는다. 남기는 유일한 파일은 UDS 소켓 노드이고,
 *    종료 시 지운다.
 */
class EnvServer private constructor(
    private val server: Server,
    private val socketFile: File?,
    /** 우리가 만든 이벤트 루프 그룹. 만든 쪽이 닫는다 — 아래 [stop] 참고. */
    private val ownedGroups: List<EventLoopGroup>,
) {

    fun start(): EnvServer {
        server.start()
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        return this
    }

    fun awaitTermination() = server.awaitTermination()

    fun stop() {
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) server.shutdownNow()
        // ⚠️ channelType 을 지정하면 gRPC 는 이벤트 루프 그룹도 **우리가 주기를 요구**하고,
        //    준 것은 정리해 주지 않는다. 닫지 않으면 스레드가 남는다. 데몬으로 만들어 두었지만
        //    (벤치가 결과를 다 내고도 JVM 이 안 끝나는 것을 한 번 겪었다) 명시적으로도 닫는다.
        for (group in ownedGroups) group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        socketFile?.delete()
    }

    companion object {
        /** UDS. [path] 에 소켓 노드를 만든다. 이미 있으면 지우고 다시 만든다. */
        fun overUnixSocket(path: String, service: EnvService = EnvService()): EnvServer {
            val file = File(path)
            file.parentFile?.mkdirs()
            // 남아 있는 소켓 노드는 "주소가 이미 사용 중" 으로 보인다. 죽은 프로세스의 흔적이다.
            if (file.exists()) file.delete()

            // gRPC 는 "boss · worker · channelType 셋 다 주거나 셋 다 주지 말라" 고 요구한다.
            // 도메인 소켓을 쓰려면 channelType 이 필요하므로 그룹도 우리가 만들고, 그래서
            // 우리가 닫아야 한다. 스레드는 데몬으로 만든다.
            val boss = daemonGroup("pika-boss", 1)
            val worker = daemonGroup("pika-worker", 0)
            val builder = NettyServerBuilder
                .forAddress(UnixDomainSocketAddress.of(path))
                .channelType(NioServerDomainSocketChannel::class.java)
                .bossEventLoopGroup(boss)
                .workerEventLoopGroup(worker)
            return EnvServer(builder.tune(service).build(), file, listOf(boss, worker))
        }

        /**
         * TCP. 컨테이너 밖에서 붙거나 UDS 가 막힌 환경용.
         *
         * @param host 기본은 모든 인터페이스다 — 컨테이너 안에서 127.0.0.1 에 열면
         *   포트를 매핑해도 밖에서 붙을 수 없다. 이 서버는 인증이 없으므로
         *   신뢰할 수 있는 네트워크(로컬·compose·파드 안)에만 둔다.
         */
        fun overTcp(port: Int, service: EnvService = EnvService(), host: String = "0.0.0.0"): EnvServer {
            val boss = daemonGroup("pika-boss", 1)
            val worker = daemonGroup("pika-worker", 0)
            val builder = NettyServerBuilder
                .forAddress(InetSocketAddress(host, port))
                .channelType(NioServerSocketChannel::class.java)
                .bossEventLoopGroup(boss)
                .workerEventLoopGroup(worker)
            return EnvServer(builder.tune(service).build(), null, listOf(boss, worker))
        }

        /** @param threads 0 이면 netty 기본값 (코어 수 × 2). */
        private fun daemonGroup(name: String, threads: Int): NioEventLoopGroup =
            NioEventLoopGroup(threads, DefaultThreadFactory(name, true))

        private fun NettyServerBuilder.tune(service: EnvService): NettyServerBuilder = this
            .addService(service)
            // N=256 의 Step 응답이 약 82KB 다. 기본 상한(4MB) 안이지만, N 을 크게 키우는
            // 실험에서 조용히 막히지 않도록 넉넉히 올려 둔다.
            .maxInboundMessageSize(64 * 1024 * 1024)
            .flowControlWindow(8 * 1024 * 1024)

        @JvmStatic
        fun main(args: Array<String>) {
            var uds: String? = null
            var port: Int? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--uds" -> uds = args[++i]
                    "--port" -> port = args[++i].toInt()
                    "--help", "-h" -> {
                        println("사용법: EnvServer [--uds <경로>] [--port <포트>]")
                        println("  둘 다 주면 두 전송을 동시에 연다 (compose 는 그렇게 쓴다).")
                        println("  아무것도 안 주면 UDS $DEFAULT_UDS_PATH.")
                        return
                    }
                    else -> error("알 수 없는 인자: ${args[i]} (--help)")
                }
                i++
            }

            // ⚠️ 두 전송이 **같은** EnvService 를 공유한다. 서버는 단일 테넌트이므로
            //    두 클라이언트가 동시에 붙으면 나중에 Configure 한 쪽이 이긴다.
            //    앞 클라이언트는 조용히 망가지는 대신 세션 번호 덕에 즉시 실패한다.
            //    (컨테이너 안에서는 UDS 로, 호스트에서는 TCP 로 붙는 구성을 위해 필요하다.
            //     macOS 에서는 바인드 마운트 UDS 가 동작하지 않아 TCP 가 유일한 길이다.)
            val service = EnvService()
            val servers = buildList {
                if (port != null) {
                    add(EnvServer.overTcp(port, service))
                    println("TCP 0.0.0.0:$port 에서 대기합니다")
                }
                if (uds != null || port == null) {
                    val path = uds ?: DEFAULT_UDS_PATH
                    add(EnvServer.overUnixSocket(path, service))
                    println("UDS $path 에서 대기합니다")
                }
            }
            println("계약 버전 ${EnvService.VERSION}. Configure 를 기다립니다.")
            servers.forEach { it.start() }
            servers.first().awaitTermination()
        }

        /** compose 에서 볼륨으로 공유하는 경로와 같게 둔다. */
        const val DEFAULT_UDS_PATH = "/tmp/pika-env.sock"
    }
}
