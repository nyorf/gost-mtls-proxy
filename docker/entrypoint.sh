#!/usr/bin/env bash
set -euo pipefail

run=/run/gost-proxy
prov_conf=/etc/ssl/gostprov.cnf
ca_bundle=/etc/gost-proxy/ca-bundle.pem
default_pfx=/certs/client.pfx

# escapes backslashes then quotes, in that order, so a literal backslash isn't re-escaped by the quote pass
json_escape() { local s=${1//\\/\\\\}; printf '%s' "${s//\"/\\\"}"; }
log() { printf '{"message":"%s","level":"INFO","logger":"entrypoint"}\n' "$(json_escape "$1")"; }
warn() { printf '{"message":"%s","level":"WARNING","logger":"entrypoint"}\n' "$(json_escape "$1")"; }
die() {
  printf 'entrypoint: %s\n' "$1" >&2
  exit 1
}
readable() { [[ -f "$1" && -r "$1" ]] || die "$2 is not a readable file: $1"; }

[[ -n "${TARGET_URL:-}" ]] || die "TARGET_URL is required, e.g. https://gost-openapi.tbank.ru"
[[ "$TARGET_URL" =~ ^https://([A-Za-z0-9._-]+)(:([0-9]{1,5}))?/?$ ]] ||
  die "TARGET_URL must be https://host[:port] with no path, got '$TARGET_URL'"
target_host="${BASH_REMATCH[1]}"
target_port="${BASH_REMATCH[3]:-443}"
upstream_port="${UPSTREAM_PORT:-8443}"
# 10# forces base 10: bash arithmetic would otherwise treat a leading-zero value like 0899 as (invalid) octal
[[ "$upstream_port" =~ ^[0-9]{1,5}$ ]] && ((10#$upstream_port >= 1 && 10#$upstream_port <= 65535)) ||
  die "UPSTREAM_PORT must be an integer between 1 and 65535, got '$upstream_port'"

mode=""
if [[ -n "${CERT_PATH:-}" ]]; then
  mode=pfx
elif [[ -z "${CERT_PEM_PATH:-}${KEY_PEM_PATH:-}" && -f "$default_pfx" ]]; then
  mode=pfx
  CERT_PATH=$default_pfx
fi
if [[ -n "${CERT_PEM_PATH:-}" || -n "${KEY_PEM_PATH:-}" ]]; then
  [[ -n "${CERT_PEM_PATH:-}" && -n "${KEY_PEM_PATH:-}" ]] ||
    die "pem mode needs both CERT_PEM_PATH and KEY_PEM_PATH"
  [[ -z "$mode" ]] ||
    die "set either CERT_PATH (pfx) or CERT_PEM_PATH+KEY_PEM_PATH (pem), not both"
  mode=pem
fi
[[ -n "$mode" ]] ||
  die "no client certificate: set CERT_PATH (pfx) or CERT_PEM_PATH+KEY_PEM_PATH (pem), or mount $default_pfx"

mkdir -p "$run"
chmod 700 "$run"

if [[ -n "${CA_BUNDLE_PATH:-}" ]]; then
  readable "$CA_BUNDLE_PATH" CA_BUNDLE_PATH
  ca_bundle=$CA_BUNDLE_PATH
fi
if [[ -n "${EXTRA_CA_PATH:-}" ]]; then
  readable "$EXTRA_CA_PATH" EXTRA_CA_PATH
  umask 077
  # write elsewhere first: CA_BUNDLE_PATH could itself be $run/ca-bundle.pem, and `>` would truncate it before cat reads it
  cat "$ca_bundle" "$EXTRA_CA_PATH" >"$run/ca-bundle.pem.tmp"
  mv "$run/ca-bundle.pem.tmp" "$run/ca-bundle.pem"
  ca_bundle=$run/ca-bundle.pem
fi
log "using CA bundle: $ca_bundle"

# the conversion is the only thing that may see the provider config: an engine and a provider
# for the same algorithms in one config make SSL_CTX_use_PrivateKey_file fail with "unknown key type"
convert_pfx() (
  set -euo pipefail
  export OPENSSL_CONF=$prov_conf
  umask 077
  if [[ -n "${CERT_PASSWORD:-}" ]]; then
    CERT_PASSWORD="$CERT_PASSWORD" openssl pkcs12 -in "$CERT_PATH" -nodes \
      -passin env:CERT_PASSWORD -out "$run/bundle.pem" ||
      die "could not read $CERT_PATH: wrong CERT_PASSWORD, or unsupported pkcs12"
  else
    openssl pkcs12 -in "$CERT_PATH" -nodes -passin pass: -out "$run/bundle.pem" ||
      die "could not read $CERT_PATH: it needs a password (CERT_PASSWORD), or is unsupported pkcs12"
  fi
  openssl pkey -in "$run/bundle.pem" -out "$run/key.pem" ||
    die "no private key in $CERT_PATH"
  chmod 600 "$run/key.pem"
  awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' "$run/bundle.pem" >"$run/allcerts.pem"
  [[ -s "$run/allcerts.pem" ]] || die "no certificate in $CERT_PATH"
  csplit -z -s -f "$run/c" -b '%d.pem' "$run/allcerts.pem" '/BEGIN CERTIFICATE/' '{*}'
  : >"$run/leaf.pem"
  : >"$run/chain.pem"
  for c in "$run"/c[0-9]*.pem; do
    if openssl x509 -in "$c" -noout -ext basicConstraints 2>/dev/null | grep -q 'CA:TRUE'; then
      cat "$c" >>"$run/chain.pem"
    else
      cat "$c" >>"$run/leaf.pem"
    fi
  done
  [[ -s "$run/leaf.pem" ]] || die "no leaf certificate in $CERT_PATH"
  # stunnel wants leaf first, intermediates after, in one file
  cat "$run/leaf.pem" "$run/chain.pem" >"$run/cert.pem"
  rm -f "$run"/c[0-9]*.pem "$run/allcerts.pem" "$run/bundle.pem" "$run/leaf.pem" "$run/chain.pem"
)

if [[ $mode == pfx ]]; then
  readable "$CERT_PATH" CERT_PATH
  if [[ -n "${CERT_PASSWORD:-}" && -n "${CERT_PASSWORD_FILE:-}" ]]; then
    die "CERT_PASSWORD and CERT_PASSWORD_FILE are mutually exclusive"
  fi
  if [[ -n "${CERT_PASSWORD_FILE:-}" ]]; then
    readable "$CERT_PASSWORD_FILE" CERT_PASSWORD_FILE
    CERT_PASSWORD="$(<"$CERT_PASSWORD_FILE")"
  fi
  log "converting pkcs12 client certificate"
  convert_pfx
  cert_file=$run/cert.pem
  key_file=$run/key.pem
else
  readable "$CERT_PEM_PATH" CERT_PEM_PATH
  readable "$KEY_PEM_PATH" KEY_PEM_PATH
  cert_file=$CERT_PEM_PATH
  key_file=$KEY_PEM_PATH
  if [[ "$(stat -c '%a' "$key_file")" =~ [4-7]$ ]]; then
    warn "client key is world-readable: $key_file"
  fi
fi

verify_lines="verifyChain = yes
checkHost = $target_host"
tls_verify="${TLS_VERIFY:-true}"
case "${tls_verify,,}" in
  true | 1 | yes | on) ;;
  false | 0 | no | off)
    verify_lines="verifyChain = no"
    warn "TLS verification disabled"
    ;;
  *) die "TLS_VERIFY must be a boolean, got '$tls_verify'" ;;
esac

umask 077
cat >"$run/stunnel.conf" <<EOF
foreground = quiet
output = /dev/stderr
syslog = no
pid =
debug = notice

[upstream]
client = yes
accept = 127.0.0.1:$upstream_port
connect = $target_host:$target_port
sni = $target_host
sslVersion = TLSv1.2
cert = $cert_file
key = $key_file
CAfile = $ca_bundle
$verify_lines
EOF

log "starting stunnel to $target_host:$target_port"
stunnel "$run/stunnel.conf" &
stunnel_pid=$!

listening=no
for _ in $(seq 1 50); do
  kill -0 "$stunnel_pid" 2>/dev/null || die "stunnel exited during startup, see its diagnostics above"
  if (exec 3<>"/dev/tcp/127.0.0.1/$upstream_port") 2>/dev/null; then
    listening=yes
    break
  fi
  sleep 0.1
done
[[ $listening == yes ]] || die "stunnel is not listening on 127.0.0.1:$upstream_port after 5s"

unset CERT_PASSWORD CERT_PASSWORD_FILE
export UPSTREAM_HOST=127.0.0.1
export UPSTREAM_PORT=$upstream_port

log "starting proxy"
exec /opt/jre/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/app/gost-mtls-proxy.jar
