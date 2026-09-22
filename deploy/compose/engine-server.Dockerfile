# 엔진 gRPC 서버. (FR-15)
#
# 빌드 컨텍스트는 **저장소 루트**다 (docker-compose.yml 의 context 참고).
# 2단계로 나눈 이유: JDK 는 빌드에만 필요하고, 실행에는 JRE 면 충분하다.
# 런타임 이미지에 소스와 Gradle 캐시를 남기지 않는다.

# ── 빌드 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# Gradle 래퍼와 버전 카탈로그를 먼저 복사한다. 이 층은 잘 안 바뀌므로 캐시가 산다.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./

# ⚠️ conformance 는 넣지 않는다. Node 와 upstream/ 에 묶여 있어 이미지에 들어갈 이유가 없다.
#    서버가 필요로 하는 것은 core · env · server 뿐이다.
COPY proto proto
COPY engine-kotlin/core engine-kotlin/core
COPY engine-kotlin/env engine-kotlin/env
COPY engine-kotlin/server engine-kotlin/server

# settings.gradle.kts 가 conformance 를 포함하므로, 이미지 안에서는 그 줄을 뺀다.
RUN sed -i '/conformance/d' settings.gradle.kts \
    && ./gradlew --no-daemon :engine-kotlin:server:installDist

# ── 실행 ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# 소켓을 둘 자리. compose 가 여기에 볼륨을 붙인다.
RUN mkdir -p /run/pika

COPY --from=build /src/engine-kotlin/server/build/install/server /opt/pika

# ⚠️ 서버는 디스크에 상태를 남기지 않는다 (plan.md §8.3). 남기는 유일한 파일은
#    UDS 소켓 노드이고 종료 시 지운다. 그래서 이 이미지에는 볼륨이 필요 없다 —
#    /run/pika 볼륨은 **다른 컨테이너와 소켓을 나누기 위한 것**이지 영속성이 아니다.
EXPOSE 50051
ENTRYPOINT ["/opt/pika/bin/server"]
CMD ["--uds", "/run/pika/engine.sock", "--port", "50051"]
