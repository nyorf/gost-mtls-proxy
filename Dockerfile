# gostprov.so, provider mode, gost-engine master. only this build reads the CryptoPro
# proprietary pkcs12 PBE (1.2.840.113549.1.12.1.80). master's cmake floor is OpenSSL 3.4,
# so it is built on 26.04 (3.5.x headers) and used on noble only for the one-shot conversion.
FROM ubuntu:26.04 AS gostprov

ARG GOST_ENGINE_COMMIT=3dd0f0e4299489a537398cfa4d9daad260ac87a8

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential cmake git pkg-config libssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY patches/0001-cryptopro-keybag-empty-password.patch /tmp/

# without the patch an empty-password pfx dies with a bare "Error outputting keys and certificates"
RUN set -eux; \
    git clone https://github.com/gost-engine/engine.git /usr/src/gost-engine; \
    cd /usr/src/gost-engine; \
    git checkout "${GOST_ENGINE_COMMIT}"; \
    git submodule update --init --recursive; \
    patch -p1 < /tmp/0001-cryptopro-keybag-empty-password.patch; \
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS=-Wno-error; \
    cmake --build build --target gost_prov -j"$(nproc)"; \
    test -f build/bin/gostprov.so


# gost.so, engine mode, v3.0.3
FROM ubuntu:24.04 AS engine

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential cmake git pkg-config libssl-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    git clone --branch v3.0.3 --depth 1 --recurse-submodules \
        https://github.com/gost-engine/engine.git /usr/src/gost-engine; \
    test -f /usr/src/gost-engine/libprov/include/prov/err.h

# the engines dir is arch dependent, so it is resolved here and /out is copied later
RUN set -eux; \
    enginesdir="$(pkg-config --variable=enginesdir libcrypto)"; \
    cmake -S /usr/src/gost-engine -B /usr/src/gost-engine/build \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX=/usr \
        -DCMAKE_INSTALL_LIBDIR="lib/$(dpkg-architecture -qDEB_HOST_MULTIARCH)" \
        -DOPENSSL_ENGINES_DIR="$enginesdir"; \
    cmake --build /usr/src/gost-engine/build -j"$(nproc)"; \
    DESTDIR=/staging cmake --install /usr/src/gost-engine/build; \
    mkdir -p "/out$enginesdir"; \
    cp "/staging$enginesdir/gost.so" "/out$enginesdir/gost.so"


# assembly jar + a jlink runtime holding only the modules the jar actually reaches for
FROM ubuntu:24.04 AS app

ARG SBT_VERSION=1.12.15
ARG SBT_SHA256=d8c215f47b879002a030f930410d83e9d138015362411f62e294c145b9f8a931

# binutils is for jlink --strip-debug, which shells out to objcopy for the native libraries
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless binutils curl ca-certificates \
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
    /opt/sbt/bin/sbt -batch -Dsbt.color=false -Dsbt.supershell=false assembly; \
    mkdir -p /opt/app; \
    cp target/scala-*/gost-mtls-proxy.jar /opt/app/gost-mtls-proxy.jar

RUN set -eux; \
    mods="$(jdeps --multi-release 21 --ignore-missing-deps --print-module-deps -q /opt/app/gost-mtls-proxy.jar)"; \
    echo "jlink modules: $mods"; \
    jlink --add-modules "$mods" --no-header-files --no-man-pages --strip-debug --compress=zip-6 --output /opt/jre


FROM ubuntu:24.04

ARG CI_COMMIT=""
ARG CI_REF=""
ENV CI_COMMIT=${CI_COMMIT} CI_REF=${CI_REF}

# the final image cannot install packages: both apt source mechanisms are emptied on purpose
RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get -y upgrade; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        stunnel4 openssl ca-certificates curl; \
    rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/locale/* /var/cache/apt/* /var/lib/apt/lists/*; \
    find /var/log -type f -delete; \
    echo "" > /etc/apt/sources.list; \
    rm -rf /etc/apt/sources.list.d/*

COPY --from=engine /out/ /
COPY --from=gostprov /usr/src/gost-engine/build/bin/gostprov.so /usr/local/lib/ossl-modules/gostprov.so
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

USER gostproxy
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/healthz" || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
