# gost.so (engine mode) and gostprov.so (provider mode), both from gost-engine v3.0.5. 3.0.5 is the first release that
# checks a GOST R 34.10-2012 public key lies in the prime-order subgroup (GHSA-c92j-m65q-j326), and its cmake floor is
# OpenSSL 3.4, so this stage and the final one are 26.04 (OpenSSL 3.5). the provider is the build that reads the
# CryptoPro proprietary pkcs12 PBE (1.2.840.113549.1.12.1.80)
FROM ubuntu:26.04 AS gost

ARG GOST_ENGINE_COMMIT=09fdd535fae018815651f34af6462fc280c3002e

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential cmake git pkg-config libssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY patches/0001-cryptopro-keybag-empty-password.patch /tmp/

# without the patch an empty-password pfx dies with a bare "Error outputting keys and certificates"
WORKDIR /usr/src/gost-engine

# cmake refuses to configure without OPENSSL_ROOT_DIR and OPENSSL_ENGINES_DIR. the engines dir is arch dependent, so it
# is resolved here and /out is copied later. GOST_BUILD_ENGINE=ON fails the build instead of skipping gost.so when
# libcrypto has no engine api
RUN set -eux; \
    git clone https://github.com/gost-engine/engine.git .; \
    git checkout "${GOST_ENGINE_COMMIT}"; \
    git submodule update --init --recursive; \
    patch -p1 < /tmp/0001-cryptopro-keybag-empty-password.patch; \
    enginesdir="$(pkg-config --variable=enginesdir libcrypto)"; \
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
        -DOPENSSL_ROOT_DIR=/usr -DOPENSSL_ENGINES_DIR="$enginesdir" -DGOST_BUILD_ENGINE=ON; \
    cmake --build build --target gost_engine gost_prov -j"$(nproc)"; \
    mkdir -p "/out$enginesdir" /out/usr/local/lib/ossl-modules; \
    cp build/bin/gost.so "/out$enginesdir/gost.so"; \
    cp build/bin/gostprov.so /out/usr/local/lib/ossl-modules/gostprov.so


# assembly jar + a jlink runtime holding only the modules the jar actually reaches for
FROM ubuntu:26.04 AS app

ARG SBT_VERSION=2.0.11
ARG SBT_SHA256=d8409a244e97c3839f27c4f435f49421196c126a450d30f4cf53522d39e3c379

# binutils is for jlink --strip-debug, which shells out to objcopy for the native libraries
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-25-jdk-headless binutils curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -fsSL -o /tmp/sbt.tgz \
        "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"; \
    echo "${SBT_SHA256}  /tmp/sbt.tgz" > /tmp/sbt.sha256; \
    sha256sum -c /tmp/sbt.sha256; \
    tar -xzf /tmp/sbt.tgz -C /opt; \
    rm -f /tmp/sbt.tgz /tmp/sbt.sha256

WORKDIR /build
COPY build.sbt ./
COPY project/ project/
COPY src/ src/

RUN set -eux; \
    /opt/sbt/bin/sbt --server -batch -Dsbt.color=false -Dsbt.supershell=false assembly; \
    mkdir -p /opt/app; \
    cp target/out/jvm/scala-*/gost-mtls-proxy/gost-mtls-proxy.jar /opt/app/gost-mtls-proxy.jar

RUN set -eux; \
    mods="$(jdeps --multi-release 25 --ignore-missing-deps --print-module-deps -q /opt/app/gost-mtls-proxy.jar)"; \
    echo "jlink modules: $mods"; \
    jlink --add-modules "$mods" --no-header-files --no-man-pages --strip-debug --compress=zip-6 --output /opt/jre


FROM ubuntu:26.04

LABEL org.opencontainers.image.title="gost-mtls-proxy" \
      org.opencontainers.image.description="mTLS-terminating proxy for Russian GOST TLS endpoints (stunnel + gost-engine, fronted by an http4s Scala app)" \
      org.opencontainers.image.licenses="Apache-2.0"

ARG CI_COMMIT=""
ARG CI_REF=""
ENV CI_COMMIT=${CI_COMMIT} CI_REF=${CI_REF}

# the final image cannot install packages: both apt source mechanisms are emptied on purpose
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        stunnel4 openssl ca-certificates curl; \
    find /usr/share/doc -mindepth 1 ! -name copyright ! -type d -delete; \
    find /usr/share/doc -mindepth 1 -type d -empty -delete; \
    rm -rf /usr/share/man/* /usr/share/locale/* /var/cache/apt/* /var/lib/apt/lists/*; \
    find /var/log -type f -delete; \
    echo "" > /etc/apt/sources.list; \
    rm -rf /etc/apt/sources.list.d/*

COPY --from=gost /out/ /
COPY docker/gost.cnf /etc/ssl/gost.cnf
COPY docker/gostprov.cnf /etc/ssl/gostprov.cnf

ENV OPENSSL_CONF=/etc/ssl/gost.cnf

RUN set -eux; \
    openssl engine -t gost; \
    OPENSSL_CONF=/etc/ssl/gostprov.cnf openssl list -providers

COPY certs/ /tmp/certs/
RUN set -eux; \
    mkdir -p /etc/gost-proxy; \
    cat /tmp/certs/*.pem > /etc/gost-proxy/ca-bundle.pem; \
    for f in /tmp/certs/*.pem; do cp "$f" "/usr/local/share/ca-certificates/$(basename "$f" .pem).crt"; done; \
    update-ca-certificates; \
    rm -rf /tmp/certs

COPY --from=app /opt/app/gost-mtls-proxy.jar /opt/app/gost-mtls-proxy.jar
COPY --from=app /opt/jre/ /opt/jre/
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY LICENSE THIRD-PARTY-NOTICES.md /usr/share/doc/gost-mtls-proxy/

RUN set -eux; \
    chmod 0755 /usr/local/bin/entrypoint.sh; \
    groupadd --gid 10001 gostproxy; \
    useradd --uid 10001 --gid 10001 --home-dir /nonexistent --no-create-home \
        --shell /usr/sbin/nologin gostproxy; \
    install -d -o gostproxy -g gostproxy -m 0700 /run/gost-proxy

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=UTC \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    SSL_CERT_DIR=/etc/ssl/certs \
    PATH=/opt/jre/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/bin/sh", "-c", "curl -fsS http://127.0.0.1:${PORT:-8080}/healthz || exit 1"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
