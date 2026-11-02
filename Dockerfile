FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS base_image

ARG version=21.0.12.8.1

RUN echo "@edge https://dl-cdn.alpinelinux.org/alpine/edge/main" >> /etc/apk/repositories
RUN apk update

# Please note that the THIRD-PARTY-LICENSE could be out of date if the base image has been updated recently.
# The Corretto team will update this file but you may see a few days' delay.
#
# Slim:
#   JLink is used (retaining all modules) to create a slimmer version of the JDK excluding man-pages, header files and debugging symbols - saving ~113MB.
RUN wget -O /THIRD-PARTY-LICENSES-20200824.tar.gz https://corretto.aws/downloads/resources/licenses/alpine/THIRD-PARTY-LICENSES-20200824.tar.gz && \
    echo "82f3e50e71b2aee21321b2b33de372feed5befad6ef2196ddec92311bc09becb  /THIRD-PARTY-LICENSES-20200824.tar.gz" | sha256sum -c - && \
    tar x -ovzf THIRD-PARTY-LICENSES-20200824.tar.gz && \
    rm -rf THIRD-PARTY-LICENSES-20200824.tar.gz && \
    wget -O /etc/apk/keys/amazoncorretto.rsa.pub https://apk.corretto.aws/amazoncorretto.rsa.pub && \
    SHA_SUM="6cfdf08be09f32ca298e2d5bd4a359ee2b275765c09b56d514624bf831eafb91" && \
    echo "${SHA_SUM}  /etc/apk/keys/amazoncorretto.rsa.pub" | sha256sum -c - && \
    echo "https://apk.corretto.aws" >> /etc/apk/repositories && \
    apk add --no-cache amazon-corretto-21=$version-r0 binutils && \
    /usr/lib/jvm/default-jvm/bin/jlink --add-modules "$(java --list-modules | sed -e 's/@[0-9].*$/,/' | tr -d \\n)" --no-man-pages --no-header-files --strip-debug --output /opt/corretto-slim && \
    apk del binutils amazon-corretto-21 && \
    mkdir -p /usr/lib/jvm/ && \
    mv /opt/corretto-slim /usr/lib/jvm/java-21-amazon-corretto && \
    ln -sfn /usr/lib/jvm/java-21-amazon-corretto /usr/lib/jvm/default-jvm

ENV LANG=C.UTF-8
ENV JAVA_HOME=/usr/lib/jvm/default-jvm
ENV PATH=$PATH:/usr/lib/jvm/default-jvm/bin

FROM base_image AS build_image
RUN apk update && apk add --no-cache --upgrade maven
COPY pom.xml /matrix/pom.xml
COPY parent /matrix/parent
COPY operator /matrix/operator
RUN cd matrix && mvn clean package

# ---------------------------------------------------------------------------------------------------------------------
# GraalVM native-image variant (optional): docker buildx build --target native_deploy_image -t matrix-operator --load .
#
# Fully static (musl) executables are only supported by GraalVM on x64, so the native executable is linked against
# glibc and needs a glibc based runtime image instead of the Alpine one used for the jar. The native-image build
# needs a lot of RAM (give the Docker VM at least 12GB).
# ---------------------------------------------------------------------------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:25 AS native_build_image

ARG MAVEN_VERSION=3.9.11
ARG MAVEN_SHA512=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978

RUN curl -fsSL -o /tmp/maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" && \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - && \
    mkdir -p /opt/maven && \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 && \
    rm -f /tmp/maven.tar.gz
ENV PATH=/opt/maven/bin:$PATH

COPY pom.xml /matrix/pom.xml
COPY parent /matrix/parent
COPY operator /matrix/operator
RUN cd /matrix && mvn -B -Pnative -DskipTests clean package

FROM node:24-bookworm-slim AS native_deploy_image
ENV LANG=C.UTF-8
ENV PATH=$PATH:/app/bin
# The node image ships a "node" user with uid 1000, which would clash with the uid docker-entry.sh creates.
RUN userdel -r node && \
    apt-get update && \
    apt-get install -y --no-install-recommends sudo zlib1g ca-certificates && \
    rm -rf /var/lib/apt/lists/*
COPY scripts/docker-entry.sh /usr/local/bin
COPY --from=native_build_image /matrix/operator/target/operator /app/bin/operator
RUN printf '#!/bin/sh\nexec /app/bin/operator operator "$@"\n' > /app/bin/run && \
    chmod +x /app/bin/run /usr/local/bin/docker-entry.sh
ENTRYPOINT ["/usr/local/bin/docker-entry.sh"]

# ---------------------------------------------------------------------------------------------------------------------
# Default (last) stage: JVM jar
# ---------------------------------------------------------------------------------------------------------------------
FROM build_image AS deploy_image
ENV PATH=$PATH:/app/bin
RUN apk add --no-cache sudo npm
COPY scripts/docker-entry.sh /usr/local/bin
COPY --from=build_image /matrix/operator/target/operator-0.0.1-SNAPSHOT.jar /app/bin/operator-0.0.1-SNAPSHOT.jar
RUN <<EOF echo -e "#!/bin/sh\n" > /app/bin/run \
"/usr/bin/java --enable-native-access=ALL-UNNAMED -jar /app/bin/operator-0.0.1-SNAPSHOT.jar operator \${@}\n" >> /app/bin/run
EOF
RUN chmod +x /app/bin/run /usr/local/bin/docker-entry.sh
ENTRYPOINT ["/usr/local/bin/docker-entry.sh"]
