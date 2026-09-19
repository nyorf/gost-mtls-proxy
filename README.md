# gost-mtls-proxy

The Russian version is in [README-RU.md](README-RU.md).

A transparent proxy for an API that accepts only GOST TLS with a client certificate.

## What this proxy does

Some APIs accept only a TLS connection with the Russian GOST algorithms and a client certificate.
Standard HTTP clients cannot make a connection of that type.

`gost-mtls-proxy` is a container image with a complete GOST TLS stack.
Your client sends a plain HTTP request to the container.
The container sends the same request to the target API over GOST TLS 1.2 with your client certificate.
Then the container sends the response of the target API back to your client.

The proxy keeps the method, the path, the query, the headers and the body.
The query string reaches the target API with no change. A parameter can appear more than one time in the query string.
The proxy makes only these five changes to a request:

- The proxy replaces the `Host` header with the host of the target API.
- The proxy removes the `X-Proxy-Token` header.
- The proxy removes the hop-by-hop headers. These are `Connection`, `Keep-Alive`, `Proxy-Authenticate`,
  `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding` and `Upgrade`.
- The proxy replaces the `User-Agent` header with its own value, `GostMtlsProxy/v<version>`. Set
  `USER_AGENT_OVERRIDE` to `false` to send the `User-Agent` header of your client without a change.
- The proxy sets the `X-Request-Id` header to the request's own id: your value when you send a non-blank one,
  otherwise a new UUID4. Exactly one `X-Request-Id` header reaches the target API, whatever you sent.

A request to `http://localhost:8080/api/v2/company` becomes a request to
`https://gost-openapi.tbank.ru/api/v2/company`.

## How it works

```
                          gost-mtls-proxy container
                 +--------------------------------------------+
   plain HTTP    |  http4s app            stunnel client      |         GOST TLS 1.2
   request       |                                            |         client certificate
client --------->|  0.0.0.0:8080  ----->  127.0.0.1:8443      |-------> target API
       <---------|  (PORT)                (UPSTREAM_PORT)     |<------- (TARGET_URL)
   response      +--------------------------------------------+
```

The container holds two processes:

1. An http4s application in Scala 3 listens on `PORT`. The default port is 8080.
2. A local stunnel process listens on `127.0.0.1` at `UPSTREAM_PORT`. The default port is 8443.

The application sends each request to stunnel over the loopback interface.
stunnel makes the GOST TLS 1.2 connection to the target API.
stunnel also sends the client certificate.
The JVM does no TLS work at all.

### The GOST algorithms

The standard OpenSSL build has no GOST algorithms.
The image adds them with two separate builds of gost-engine:

- The Dockerfile builds gost-engine v3.0.3 from source in engine mode.
  stunnel uses this build for every TLS connection.
- The Dockerfile builds gost-engine master, commit `3dd0f0e4299489a537398cfa4d9daad260ac87a8`, in provider mode.
  Only this build reads a CryptoPro pfx file.

### The startup sequence

The entrypoint script does these steps at each start of the container:

1. The script checks `TARGET_URL`.
2. The script selects the certificate mode, pfx or pem.
3. In the pfx mode, the script converts the pfx file into a certificate file and a key file.
4. The script writes the stunnel configuration file.
5. The script starts stunnel.
6. The script waits until port `UPSTREAM_PORT` accepts a connection. The limit is 5 seconds.
7. The script removes `CERT_PASSWORD` and `CERT_PASSWORD_FILE` from the environment.
8. The script starts the Java application.

The script writes the unencrypted private key to `/run/gost-proxy/key.pem` only.
The mode of that file is 600.
The owner is the non-root user `gostproxy` with uid 10001.

### The image

The final stage of the image starts from `ubuntu:24.04`. The build hardens that stage:

- The build empties both apt source mechanisms. The final image cannot install a package at runtime.
- The build removes the documents, the manual pages and the locales.
- The build makes a trimmed JRE with jlink. The module set is `java.base`, `java.management` and `jdk.unsupported`.
- The container starts as the non-root user `gostproxy`.

## Necessary software

For a container from the published image:

- podman or docker. The image works with both.
- A client certificate for the target API, in pfx or PEM format.
- Network access to the target API.

For a build from source:

- podman or docker with network access. The build downloads gost-engine and sbt.
- A JDK 21 and sbt 1.12.15 for the tests on your machine. The image build does not use them.

## Quick start

**NOTE: the requests in this document use T-Bank's GOST API as the example target API. See the [API reference](https://developer.tbank.ru/docs/api/get-api-v-2-company).**

### A pfx file with no password

1. Copy your pfx file into the current directory with the name `client.pfx`.
2. Start the container.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

The entrypoint script finds `/certs/client.pfx` without `CERT_PATH`.

### A pfx file with a password

**CAUTION: USE ONLY ASCII CHARACTERS IN THE PFX PASSWORD. GOST-ENGINE CANNOT READ A PASSWORD WITH OTHER
CHARACTERS. THE CONTAINER STOPS WITH AN ERROR.**

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PATH=/certs/client.pfx \
  -e CERT_PASSWORD=your-pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PATH=/certs/client.pfx \
  -e CERT_PASSWORD=your-pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### A pfx file with a password file

A password on the command line is visible in the process list.
A password file keeps the value out of the process list.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PASSWORD_FILE=/run/secrets/pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/pfx-password.txt:/run/secrets/pfx-password:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PASSWORD_FILE=/run/secrets/pfx-password \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/pfx-password.txt:/run/secrets/pfx-password:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### A certificate and a key in PEM format

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PEM_PATH=/certs/client.pem \
  -e KEY_PEM_PATH=/certs/client-key.pem \
  -v "$PWD/certs:/certs:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CERT_PEM_PATH=/certs/client.pem \
  -e KEY_PEM_PATH=/certs/client-key.pem \
  -v "$PWD/certs:/certs:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### A container with a shared secret

Set `PROXY_TOKEN` to a long random value.
Every caller must then send that value in the `X-Proxy-Token` header.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e PROXY_TOKEN=change-me-to-a-long-random-value \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e PROXY_TOKEN=change-me-to-a-long-random-value \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

### A request through the proxy

Send your API token in the `Authorization` header.
The proxy sends that header to the target API without a change.

```bash
curl -s http://localhost:8080/api/v2/company \
  -H 'Authorization: Bearer t.XXXXXXXXXXXXXXXXXXXXXX'
```

With `PROXY_TOKEN` one more header is necessary:

```bash
curl -s http://localhost:8080/api/v2/company \
  -H 'X-Proxy-Token: change-me-to-a-long-random-value' \
  -H 'Authorization: Bearer t.XXXXXXXXXXXXXXXXXXXXXX'
```

## Configuration

| Variable | Necessary | Default | Description |
|---|---|---|---|
| `TARGET_URL` | Yes | none | The address of the target API. The form is `https://host[:port]` with no path, no query and no fragment. |
| `CERT_PATH` | No | `/certs/client.pfx` when that file is present | The path of the pfx file in the container. |
| `CERT_PASSWORD` | No | none | The password of the pfx file. |
| `CERT_PASSWORD_FILE` | No | none | The path of a file that holds the password of the pfx file. |
| `CERT_PEM_PATH` | No | none | The path of the client certificate in PEM format. |
| `KEY_PEM_PATH` | No | none | The path of the private key in PEM format. |
| `PORT` | No | `8080` | The port for the plain HTTP requests of your client. |
| `UPSTREAM_PORT` | No | `8443` | The loopback port of the local stunnel process. |
| `TLS_VERIFY` | No | `true` | The value `false` stops the check of the certificate chain of the target API. `TLS_VERIFY` accepts the same values as `LOG_BODIES` and `USER_AGENT_OVERRIDE`. An invalid value stops the container at startup. |
| `CA_BUNDLE_PATH` | No | the built-in CA bundle | The path of a CA bundle file in the container. This value replaces the built-in CA bundle. |
| `EXTRA_CA_PATH` | No | none | The path of a file with extra CA certificates in the container. The proxy adds this file to the active CA bundle. |
| `PROXY_TOKEN` | No | none | A shared secret. Each caller must send this value in the `X-Proxy-Token` header. An empty value stops the container at startup. |
| `USER_AGENT_OVERRIDE` | No | `true` | The value `false` sends the `User-Agent` header of your client to the target API without a change. |
| `LOG_BODIES` | No | `true` | The value `false` keeps the JSON bodies out of the log lines. |
| `LOG_BODY_MAX_BYTES` | No | `10485760` | The threshold, in bytes, that decides whether the proxy buffers a body to log it. This value never limits the proxied request itself. See [Logs](#logs) for the details. |
| `LOG_LEVEL` | No | `INFO` | `DEBUG`, `INFO`, `WARNING` or `ERROR`, case-insensitive. `WARN` is also accepted as an alias for `WARNING`. An invalid value stops the container at startup. See [Logs](#logs) for the levels and what `DEBUG` adds. |
| `SERVICE_NAME` | No | `gost-mtls-proxy` | The value of the `system` field and the `api` field in each log line. |
| `ENV` | No | `dev` | The value of the `env` field in each log line. The proxy makes this value lowercase. |

The Dockerfile also accepts two build arguments, `CI_COMMIT` and `CI_REF`.
The build stores both values in the image as environment variables.
Both values appear in the `ci` object of each log line.

`PORT` and `UPSTREAM_PORT` accept a number between 0 and 65535.
`TLS_VERIFY`, `LOG_BODIES` and `USER_AGENT_OVERRIDE` accept `true`, `1`, `yes`, `on`, `false`, `0`, `no` and `off`.
`LOG_LEVEL` accepts `DEBUG`, `INFO`, `WARNING` and `ERROR`, plus the alias `WARN` for `WARNING`.
`LOG_BODY_MAX_BYTES` accepts a non-negative number of bytes.
A bad value stops the container with a message on stderr.

**NOTE: the entrypoint script always sets `UPSTREAM_HOST` to `127.0.0.1`. A different value has no effect.**

## Client certificate modes

The proxy reads the client certificate in one of two modes.
The two modes are mutually exclusive.
A container with `CERT_PATH` and `CERT_PEM_PATH` together stops with an error.

### The pfx mode

The proxy reads a PKCS#12 file:

- Set `CERT_PATH` to the path of the pfx file in the container.
- As an alternative, mount your pfx file at `/certs/client.pfx`. The script then finds it without `CERT_PATH`.
- For a pfx file with a password, set `CERT_PASSWORD` or `CERT_PASSWORD_FILE`.
- `CERT_PASSWORD` and `CERT_PASSWORD_FILE` are mutually exclusive. Both together stop the container.

At each start the script converts the pfx file:

1. The script reads the pfx file with the gostprov provider.
2. The script writes the private key to `/run/gost-proxy/key.pem` with mode 600.
3. The script separates the leaf certificate from the CA certificates.
4. The script writes the leaf certificate and the CA certificates to `/run/gost-proxy/cert.pem`.
5. The script gives both files to stunnel.

### The CryptoPro pfx files

CryptoPro tools write a pfx file with a proprietary password-based encryption.
The OID is `1.2.840.113549.1.12.1.80`.
A standard OpenSSL build cannot read a pfx file of that type.
BouncyCastle cannot read it either.

The image therefore holds a second build of gost-engine in provider mode.
The patch `patches/0001-cryptopro-keybag-empty-password.patch` adds support for an empty password.
Without that patch a pfx file with an empty password stops the conversion.

The conversion is the only step with the provider configuration `/etc/ssl/gostprov.cnf`.
An engine and a provider for the same algorithms in one configuration break the TLS connection of stunnel.

### The pem mode

The proxy reads two separate files:

- Set `CERT_PEM_PATH` to the path of the client certificate.
- Set `KEY_PEM_PATH` to the path of the private key.
- Both variables are necessary. One variable alone stops the container.

The script writes a warning to the log when the key file is world-readable.
The script gives both files to stunnel without a change.

## Trust store

The repository holds 10 CA certificates in `certs/`:

| File | Description |
|---|---|
| `cryptopro-gost-root-ca.pem` | CryptoPro GOST Root CA of 2022. |
| `cryptopro-tls-ca.pem` | CryptoPro TLS CA. |
| `cryptopro-gost-root-ca-2025.pem` | CryptoPro GOST Root CA of 2025. |
| `cryptopro-gost-root-ca-2025-cross-signed.pem` | CryptoPro GOST Root CA of 2025, cross-signed by the CryptoPro GOST Root CA of 2022. |
| `cryptopro-gost-tls-ca.pem` | CryptoPro GOST TLS CA, issued by the CryptoPro GOST Root CA of 2025. |
| `russian-trusted-root-ca.pem` | Russian Trusted Root CA, RSA, from Минцифры России. |
| `russian-trusted-sub-ca.pem` | Russian Trusted Sub CA of 2022, RSA. |
| `russian-trusted-sub-ca-2024.pem` | Russian Trusted Sub CA of 2024, RSA. |
| `russian-trusted-gost-root-ca.pem` | Russian Trusted Root CA, GOST. |
| `russian-trusted-gost-sub-ca.pem` | Russian Trusted Sub CA, GOST. |

The build does two things with these files:

1. The build copies each file into the system trust store of the image.
2. The build joins all files into `/etc/gost-proxy/ca-bundle.pem`. stunnel reads that bundle.

### How to refresh a CA certificate

1. Replace the PEM file in `certs/`.
2. Build the image again.

There is no runtime refresh. A new certificate makes a new image necessary.

### How to use your own CA certificates

Set `CA_BUNDLE_PATH` to the path of your own CA bundle file. This value replaces the built-in CA bundle.
Set `EXTRA_CA_PATH` to the path of a file with extra CA certificates. The proxy adds this file to the active CA bundle.
Both variables take effect at each start of the container. No rebuild of the image is necessary.

```bash
# podman
podman run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CA_BUNDLE_PATH=/etc/gost-proxy/custom-ca-bundle.pem \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/custom-ca-bundle.pem:/etc/gost-proxy/custom-ca-bundle.pem:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest

# docker
docker run --rm -p 8080:8080 \
  -e TARGET_URL=https://gost-openapi.tbank.ru \
  -e CA_BUNDLE_PATH=/etc/gost-proxy/custom-ca-bundle.pem \
  -v "$PWD/client.pfx:/certs/client.pfx:ro" \
  -v "$PWD/custom-ca-bundle.pem:/etc/gost-proxy/custom-ca-bundle.pem:ro" \
  ghcr.io/nyorf/gost-mtls-proxy:latest
```

## Health check

The proxy answers `GET /healthz` with JSON:

- The proxy reads `/proc/net/tcp` and `/proc/net/tcp6`. It looks for a socket in the `LISTEN`
  state on `UPSTREAM_PORT`.
- This is a read of the kernel socket table. The check sends nothing to the target API.
- The proxy answers 200 with the body `{"status":"ok"}` when either table shows a listening socket.
- The proxy answers 503 with the body `{"status":"upstream unreachable"}` when neither table shows
  one.
- The proxy falls back to a TCP connection when neither file can be read. This can happen on a
  non-Linux host or in a restricted environment.
- The fallback connects to the local stunnel port. The timeout is 1 second. The proxy also writes
  one `WARNING` line.
- `/healthz` writes no other log lines.
- `PROXY_TOKEN` does not apply to `/healthz`.

The image also holds a `HEALTHCHECK` instruction for this path.
The interval is 30 seconds, the timeout is 5 seconds and the start period is 10 seconds.
The retry count is 3.

## Logs

Every line of the proxy is single-line JSON on stdout.
stunnel writes its own diagnostics to stderr.

Each line holds these fields:

| Field | Description |
|---|---|
| `message` | The text of the event. |
| `level` | `DEBUG`, `INFO`, `WARNING` or `ERROR`. A line below the configured `LOG_LEVEL` never reaches stdout. |
| `@timestamp` | The time with microseconds and the UTC offset. |
| `logger` | `app.http`, `app.outbound` or `app.operations` for the lines of the proxy. The entrypoint script uses `entrypoint`. The slf4j bridge passes an http4s or cats-effect record through with its own class name. One example is `org.http4s.ember.server.EmberServerBuilder`. |
| `system` | The value of `SERVICE_NAME`. |
| `env` | The value of `ENV`, in lowercase. |
| `inst` | The hostname of the container. |
| `ci` | An object with `deployed_at`. The fields `commit` and `ref` are present only when the build sets `CI_COMMIT` and `CI_REF`. |
| `request_id` | The caller's `X-Request-Id` header, or a new UUID4 when the caller sent none or a blank one. The same value is forwarded to the target API and echoed on the response. |
| `trace-id` | The value of `x-b3-traceid`, or the trace part of `traceparent`, or a new value. |
| `span-id` | A new 16-character value for each request. |
| `method` | The HTTP method of the request. |
| `route` | The path of the request. |

One request gives six lines in this order:

1. `Received http request`
2. `Method proxy was called for <path> with body '<type>'`
3. `Sending http request`
4. `Got http response with code=<code>`
5. `Method proxy returned response`
6. `api operation executed`

The last line carries the fields `api`, `path`, `uri`, `ip`, `start_time`, `duration_ms`, `result` and `details`.
The `details` object always holds `http_code`.
It also holds `username`, `procedure_run_id`, `client_id` and `user_agent` from the request headers
`X-Username`, `X-Procedure-Run-Id`, `X-Client-Id` and `User-Agent`.
The `user_agent` field always holds the `User-Agent` header of the caller. `USER_AGENT_OVERRIDE` has no effect on this field.

### Log levels

`LOG_LEVEL` sets the lowest level that reaches stdout. The order from lowest to highest is `DEBUG`, `INFO`,
`WARNING` and `ERROR`.

- `LOG_LEVEL=DEBUG` suppresses nothing.
- `LOG_LEVEL=INFO` suppresses `DEBUG` lines.
- `LOG_LEVEL=WARNING` suppresses `DEBUG` and `INFO` lines.
- `LOG_LEVEL=ERROR` suppresses `DEBUG`, `INFO` and `WARNING` lines. Only `ERROR` lines reach stdout.

The `level` field of an emitted line holds `DEBUG`, `INFO`, `WARNING` or `ERROR`.
A library record from the slf4j bridge obeys the same `LOG_LEVEL` threshold as the lines of the proxy itself.

### LOG_LEVEL=DEBUG

**WARNING: `LOG_LEVEL=DEBUG` WRITES THE FULL BODY OF EVERY REQUEST AND EVERY RESPONSE INTO THE LOG.
DO NOT USE `LOG_LEVEL=DEBUG` IN PRODUCTION.**

`LOG_BODY_MAX_BYTES` still bounds this behavior. A lower value limits how much body content one log
line can hold at `LOG_LEVEL=DEBUG`.

`LOG_LEVEL=DEBUG` makes three differences, on top of every line at every lower level:

- Line 1 (`Received http request`) and line 5 (`Method proxy returned response`) each gain a `debug` object.
  Its fields are `http_version`, `query_params`, `outbound_uri` (the full target URL, query string included),
  `remote_addr`, `request_content_type`, `request_content_length`, `response_content_type` and
  `response_content_length`. A field is absent, not null, when the proxy has nothing to report for it — for
  example `response_content_*` on line 1, before the target API has answered.
- A buffered body renders in full: the 10000-character clip and the 256 KiB summary described below do not apply.
  A `text` or a `multipart` body also renders as text, instead of the empty string it gets at every other level.
  The headers and the JSON keys below still become `***`. A body above `LOG_BODY_MAX_BYTES` is still `unread`
  and is never buffered, at every level.
- `GET /healthz` stays silent at every other level, apart from the fallback `WARNING` line. It also emits `Received http request` and `api operation executed` at DEBUG.

`LOG_BODIES=false` still wins over `LOG_LEVEL=DEBUG`: an explicit body opt-out is respected at every level.

### What the proxy keeps out of the logs

- The values of the headers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` and
  `X-Proxy-Token` become `***`, at every `LOG_LEVEL`.
- The JSON keys `token`, `raw_token`, `rawtoken`, `password`, `secret`, `apikey` and `api_key` become `***`,
  at every `LOG_LEVEL`.
- The proxy writes only JSON, `text` and `multipart` bodies. A body of another type never reaches the log.
  `text` and `multipart` bodies render only at `LOG_LEVEL=DEBUG`; below that they are the empty string.
- A body with a Content-Encoding other than `identity` becomes `binary`. The proxy never reads its content as JSON.
- `LOG_BODY_MAX_BYTES` sets the threshold, in bytes, that decides whether the proxy buffers a body to log it.
  The proxy buffers a body within the threshold. The proxy also detects its type from the buffered bytes.
- Below `LOG_LEVEL=DEBUG`, a JSON body above 256 KiB becomes a short summary, and a longer text gets a clip after
  10000 characters. At `LOG_LEVEL=DEBUG` a buffered JSON body always renders in full.
- A body above the `LOG_BODY_MAX_BYTES` threshold streams through unbuffered. A body sent with
  `Transfer-Encoding: chunked` and no `Content-Length` also streams through unbuffered. The log shows
  `unread` for both, at every `LOG_LEVEL`.
- `LOG_BODY_MAX_BYTES` only decides what the proxy buffers to build a log line. It never caps, truncates
  or rejects the proxied request. The proxy forwards the full request body to the target API, whatever
  the threshold. The proxy also returns the full response body to your client, whatever the threshold.
- The value `0` buffers no body at all. Every body then streams through unbuffered, with zero-copy passthrough.
  Its type in the log is `unread`.
- `LOG_BODIES=false` replaces every body text with `logging is disabled by flag`, at every `LOG_LEVEL`.
- The startup line reports `"proxy_token":true` only. The value of `PROXY_TOKEN` never reaches the log.

### An example line

```json
{
    "@timestamp": "2026-08-07T09:14:22.203118+00:00",
    "ci": {
        "commit": "9f2c1ab3d4e5f60718293a4b5c6d7e8f90a1b2c3",
        "deployed_at": "2026-08-07T09:11:07.442015+00:00",
        "ref": "release/v1.1.0"
    },
    "env": "prod",
    "headers": [
        "Host: localhost:8080",
        "User-Agent: curl/8.5.0",
        "Accept: */*",
        "Authorization: ***"
    ],
    "inst": "8f1c0d2e4a7b",
    "level": "INFO",
    "logger": "app.http",
    "message": "Received http request",
    "method": "GET",
    "path": "/api/v2/company",
    "request_id": "6b1f4a2c-9d3e-4f57-8a01-2c3d4e5f6071",
    "route": "/api/v2/company",
    "span-id": "00f067aa0ba902b7",
    "system": "gost-mtls-proxy",
    "trace-id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "uri": "http://localhost:8080/api/v2/company"
}
```

## Security

**WARNING: DO NOT MAKE THE PROXY PORT AVAILABLE ON AN UNTRUSTED NETWORK. THE CONTAINER HOLDS A CLIENT
CERTIFICATE THAT IDENTIFIES YOU. EVERY CALLER THAT REACHES THE PORT CAN USE THAT IDENTITY.**

**WARNING: SET `TLS_VERIFY=false` ONLY FOR A DEBUG SESSION. THE PROXY THEN ACCEPTS ANY CERTIFICATE FROM
THE TARGET API. AN ATTACKER CAN THEN READ AND CHANGE YOUR TRAFFIC.**

The container writes a loud `WARNING` line when `TLS_VERIFY` is `false`.

`PROXY_TOKEN` gives a second layer of protection.
`PROXY_TOKEN` is not a replacement for network control.
A caller without the correct `X-Proxy-Token` header gets status 401 and the body `{"error":"unauthorized"}`.
The proxy compares the two values with a constant-time function.

These properties of the image lower the risk:

- The unencrypted private key exists only in `/run/gost-proxy`, with mode 600 and the owner `gostproxy`.
- The entrypoint script removes `CERT_PASSWORD` and `CERT_PASSWORD_FILE` before the start of the application.
- The container starts as the non-root user `gostproxy` with uid 10001.
- The final image cannot install a package at runtime.
- The proxy removes the `X-Proxy-Token` header from every request to the target API.

## Problems and solutions

| Symptom | Cause | Action |
|---|---|---|
| The target API answers 400 with `No required SSL certificate was sent`. | The target API did not get the client certificate, or did not accept it. | Check `CERT_PATH`. Make sure that the pfx file holds the leaf certificate and the private key. |
| The target API answers 401 with a JSON body. | The mutual TLS connection is correct. The `Authorization` token is absent or wrong. | Send a valid token in the `Authorization` header. |
| The target API answers 404 on every path. | The `Host` header did not reach the target API. | Send your requests through this proxy. The proxy always sets the `Host` header. |
| The target API answers 200. | The request is correct and complete. | No action is necessary. |
| The container stops with `could not read ...: wrong CERT_PASSWORD, or unsupported pkcs12`. | The password is wrong, or the password holds non-ASCII characters. | Set the correct password. Use only ASCII characters. |
| The container stops with `it needs a password (CERT_PASSWORD)`. | The pfx file has a password. The configuration has none. | Set `CERT_PASSWORD` or `CERT_PASSWORD_FILE`. |
| The container stops with `TARGET_URL must be https://host[:port] with no path`. | `TARGET_URL` holds a path, a query, a fragment or the wrong scheme. | Set `TARGET_URL` to `https://host` or `https://host:port`. |
| The container stops with `no client certificate`. | There is no `CERT_PATH`, no PEM pair and no file at `/certs/client.pfx`. | Mount your client certificate. Set the correct variables. |
| The container stops with `stunnel exited during startup`. | stunnel cannot read the certificate, the key or the CA bundle. | Read the stunnel diagnostics on stderr. |
| The container stops with `stunnel is not listening on 127.0.0.1:8443 after 5s`. | Another process in the container holds that port. | Set `UPSTREAM_PORT` to a free port. |
| The proxy answers 502 with `{"error":"upstream unreachable"}`. | The proxy cannot connect to the local stunnel port. | Read the stunnel diagnostics on stderr. |
| The proxy answers 504 with `{"error":"upstream timeout"}`. | The target API did not answer in time. | Send the request again. Check the network route to the target API. |
| The proxy answers 401 with `{"error":"unauthorized"}`. | `PROXY_TOKEN` has a value. The request has no `X-Proxy-Token` header, or a wrong one. | Send the correct value in the `X-Proxy-Token` header. |
| `/healthz` answers 503 with `{"status":"upstream unreachable"}`. | stunnel does not accept connections on the loopback port. | Read the stunnel diagnostics on stderr. |

The 400, the 401 and the 404 answers separate three different faults.
A 400 answer means a fault in the client certificate.
A 401 answer means a correct mutual TLS connection and a fault in the API token.
A 404 answer on every path means a lost `Host` header.

**NOTE: a 404 answer on every path is not possible through this proxy. The proxy always sets the `Host`
header of the target API.**

### A known limit

A pfx password with non-ASCII characters does not work.
This is a limit of gost-engine, not of this image.
The error text is `could not read ...: wrong CERT_PASSWORD, or unsupported pkcs12`.

## Build from source

**CAUTION: BUILD THE IMAGE WITH `podman build --format docker`. THE DEFAULT OCI FORMAT REMOVES THE
`HEALTHCHECK` INSTRUCTION AND WRITES ONLY A WARNING.**

```bash
# podman
podman build --format docker -t gost-mtls-proxy .

# docker
docker build -t gost-mtls-proxy .
```

Network access is necessary for the build.
The build clones gost-engine from GitHub and downloads sbt.
The build also checks the SHA-256 sum of the sbt archive.

The build has four stages:

1. `gostprov` builds `gostprov.so` from gost-engine master on `ubuntu:26.04`. The cmake floor of master is
   OpenSSL 3.4, and `ubuntu:26.04` has the necessary headers.
2. `engine` builds `gost.so` from gost-engine v3.0.3 on `ubuntu:24.04`.
3. `app` builds the assembly jar with sbt. Then jlink makes the trimmed JRE.
4. The final stage joins all parts on `ubuntu:24.04`.

### Tests on your machine

```bash
sbt scalafmtCheckAll scalafmtSbtCheck test
```

The build uses Scala 3.8.4 and sbt 1.12.15.
The tests use no certificate. The tests make no connection to the target API.

### Container registries

- `ghcr.io/nyorf/gost-mtls-proxy`
- `docker.io/nyorf/gost-mtls-proxy`

## Licensing

This project is licensed under Apache-2.0. See the `LICENSE` file for the full text.

The container image bundles third-party software. See `THIRD-PARTY-NOTICES.md` for the full list.

stunnel is licensed under GPL-2.0-or-later. Its license text ships inside the image under `/usr/share/doc`.

The CA certificates under `certs/` are public certificates.
