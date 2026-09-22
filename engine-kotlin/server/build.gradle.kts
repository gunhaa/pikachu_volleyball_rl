plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

/**
 * gRPC 서버. protobuf·grpc 의존성은 **여기에만** 둔다 (NFR-1, NFR-2).
 *
 * grpc-kotlin 을 쓰지 않는 이유는 plan.md §7.2 — RPC 가 4개뿐이고 전부 요청-응답이라
 * 코루틴이 사줄 것이 없는데, 릴리스가 멈춘 코드 생성기가 하나 더 붙는다.
 */
dependencies {
    implementation(project(":engine-kotlin:env"))
    implementation(project(":engine-kotlin:core"))

    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.protobuf.java)
    // 생성된 stub 이 참조하는 javax.annotation.Generated (JDK 9+ 에는 없다).
    compileOnly(libs.tomcat.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// .proto 의 단일 위치는 저장소 루트의 proto/ 다 (Kotlin·Python·JS 가 같은 파일을 본다).
// state_spec.proto 는 계약 문서일 뿐이라 코드 생성 대상이 아니다 — env.proto 만 생성한다.
sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("proto"))
            // state_spec.proto · obs_spec.proto 는 **계약 문서**다. protobuf 인코딩을 쓰지
            // 않으므로 코드 생성 대상이 아니다 (각 언어가 파싱해 자기 목록과 대조할 뿐이다).
            setIncludes(listOf("env.proto"))
        }
    }
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    plugins {
        create("grpc") { artifact = libs.grpc.protoc.gen.java.get().toString() }
    }
    generateProtoTasks {
        all().forEach { it.plugins { create("grpc") } }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

/**
 * (b) gRPC 루프백 처리량. (NFR-3)
 *
 * (a) 와 같은 프로세스에서 재고 간극을 출력한다. CI 에는 넣지 않는다.
 */
tasks.register<JavaExec>("benchLoopback") {
    group = "verification"
    description = "gRPC 루프백(UDS·TCP) 처리량을 재고 in-process 와의 간극을 출력한다."
    mainClass.set("pika.server.LoopbackBench")
    classpath = sourceSets["main"].runtimeClasspath
}

/** 서버 실행. `--args="--uds /tmp/pika-env.sock"` 또는 `--args="--port 50051"`. */
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "엔진 gRPC 서버를 띄운다 (기본 UDS)."
    mainClass.set("pika.server.EnvServer")
    classpath = sourceSets["main"].runtimeClasspath
}
