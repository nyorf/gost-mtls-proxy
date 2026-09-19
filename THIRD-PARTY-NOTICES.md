# Third-Party Notices

`gost-mtls-proxy` itself is licensed under the Apache License, Version 2.0
(see `LICENSE`). The distributed container **image**, however, also bundles
third-party components under their own licenses. This file lists them,
identifies which of them carry a copyleft obligation, and points at where to
obtain their source.

This file identifies each package below from the image produced by this
repository's `Dockerfile`. Each package's license comes from its own
`/usr/share/doc/<pkg>/copyright` file, which the Dockerfile keeps inside
the shipped image.

## 1. stunnel (copyleft — read this one)

- **License:** GPL-2.0-or-later, with stunnel's own exception permitting
  linking against OpenSSL. Verified against the upstream license file:
  https://raw.githubusercontent.com/mtrojnar/stunnel/master/COPYING.md
  ("as a special exception, the copyright holder of stunnel gives you
  permission to combine stunnel with free software programs or libraries
  that are released under the GNU LGPL and with code included in the
  standard release of OpenSSL").
- **Copyright:** (C) 1998-2026 Michal Trojnara, plus the Debian/Ubuntu
  packaging contributors listed in the package's own copyright file.
- **Source for the exact binary shipped:** the Ubuntu "noble" source
  package, e.g. `https://packages.ubuntu.com/noble/stunnel4` or
  `https://launchpad.net/ubuntu/+source/stunnel4`, and upstream at
  https://www.stunnel.org/downloads.html. The exact version shipped is
  recorded in the image's own `/usr/share/doc/stunnel4/copyright` file.
- The image preserves this package's Debian copyright file at
  `/usr/share/doc/stunnel4/copyright`. That file points to
  `/usr/share/common-licenses/GPL-2` for the full GPL-2 text. The image
  ships that file too.

## 2. gost-engine

Two separate builds of https://github.com/gost-engine/engine are compiled
into the image, both under **Apache License 2.0**:

- **`gost.so`** (OpenSSL engine mode) — built from tag `v3.0.3`, unmodified.
  Source: `https://github.com/gost-engine/engine/tree/v3.0.3`.
- **`gostprov.so`** (OpenSSL 3 provider mode) — built from master commit
  `3dd0f0e4299489a537398cfa4d9daad260ac87a8`, with this repository's own
  patch `patches/0001-cryptopro-keybag-empty-password.patch` applied (fixes
  handling of empty-password PKCS#12 files using the CryptoPro proprietary
  PBE OID). The patch is a derivative of Apache-2.0-licensed code and is
  distributed under the same Apache License 2.0; the patch file itself
  ships in this repository's `patches/` directory alongside its own source.
  Upstream source: `https://github.com/gost-engine/engine/tree/3dd0f0e4299489a537398cfa4d9daad260ac87a8`.
- **Copyright:** the gost-engine project contributors. See
  `https://github.com/gost-engine/engine/blob/master/LICENSE`.

## 3. OpenSSL, ca-certificates, curl (packages the Dockerfile installs explicitly)

The Dockerfile installs these packages from a rolling `ubuntu:24.04` base.
An exact version pinned here goes stale within weeks. This file lists the
package name, license and source, not a version:

| Package | License | Source |
|---|---|---|
| `openssl` (CLI, linked against `libssl3t64`) | Apache-2.0 | https://www.openssl.org |
| `ca-certificates` | GPL-2.0-or-later (packaging/scripts) + MPL-2.0 (bundled Mozilla `certdata.txt` CA bundle) | Debian/Ubuntu `ca-certificates` source package; CA data from Mozilla NSS |
| `curl` | the "curl license" (MIT/ISC-style permissive; a small number of vendored files use BSD-3-Clause/ISC/OLDAP-2.8/FSFULLR) | https://curl.se |

Each package's license comes from its own `/usr/share/doc/<pkg>/copyright`
file (OpenSSL's copyright is a symlink to `libssl3t64`'s, per Debian's
packaging convention). The Dockerfile keeps all three copyright files,
with the full license text, inside the shipped image, at
`/usr/share/doc/openssl/copyright`, `/usr/share/doc/ca-certificates/copyright`,
and `/usr/share/doc/curl/copyright`. Read the copy in your own image. It
names the exact version that image shipped.

### The rest of the Ubuntu 24.04 base

The final stage is `ubuntu:24.04` with a security `apt-get upgrade`, which
pulls in the standard minimal userland (`bash`, `coreutils`, `dpkg`,
`libc6`, `perl`, `systemd` libraries, `util-linux`, and more). These are
stock Ubuntu packages under their own upstream
licenses (predominantly GPL-2/GPL-3/LGPL/BSD/MIT, varying by package) that
this project neither modifies nor selects individually. Rather than
transcribing all of them into this file (and risking it going stale across
base-image rebuilds), the Dockerfile now keeps every package's own
`/usr/share/doc/<pkg>/copyright` file inside the shipped image — that is
the authoritative, version-matched source for each of those licenses. Run
`podman run --rm --entrypoint /bin/sh <image> -c 'ls /usr/share/doc'` against
any built image to enumerate them for that exact build.

## 4. The Java runtime

The final image's JVM at `/opt/jre` is a custom runtime produced by `jlink`
(from the `openjdk-21-jdk-headless` package on Ubuntu 24.04),
trimmed to only the modules `gost-mtls-proxy.jar` actually uses. OpenJDK is
licensed under the **GNU General Public License, version 2, with the
Classpath Exception** (the Classpath Exception is what allows an
application jar to link against it without becoming GPL itself). Because
`jlink` produces a bespoke runtime image rather than installing the Debian
package into the final stage, this image does not carry the Debian
`openjdk-21-jre-headless` copyright file itself; the authoritative license
text is upstream at `https://github.com/openjdk/jdk21u/blob/master/LICENSE`
(also mirrored in `/usr/share/doc/openjdk-21-jre-headless/copyright` in the
Ubuntu 24.04 package that the runtime was `jlink`-ed from).

## 5. Scala / JVM library dependencies compiled into `gost-mtls-proxy.jar`

The application is an `sbt-assembly` fat jar. The following is the full,
actual compile classpath as resolved by sbt for this build (`sbt "show
Compile/dependencyClasspathAsJars"`), not just the directly-declared
dependencies in `build.sbt` — i.e. every one of these is compiled into the
jar. License for each was read from the artifact's own published Maven POM
(`<licenses>`) or, where the POM did not carry one, from the license file
bundled inside the jar itself.

**Apache License 2.0:**
`org.scala-lang:scala3-library_3:3.8.4`,
`org.scala-lang:scala-library:3.8.4` (the Scala standard library — Scala 3.8
depends on a shared `scala-library` artifact alongside `scala3-library`),
`org.http4s:http4s-core_3:0.23.36`, `http4s-server_3`, `http4s-client_3`,
`http4s-ember-core_3`, `http4s-ember-server_3`, `http4s-ember-client_3`,
`http4s-crypto_3:0.2.5`,
`io.circe:circe-core_3:0.14.16`, `circe-parser_3`, `circe-jawn_3`,
`circe-numbers_3`,
`org.typelevel:cats-effect_3:3.7.0`, `cats-effect-std_3`,
`cats-effect-kernel_3`,
`org.typelevel:log4cats-core_3:2.8.0`, `log4cats-slf4j_3`,
`org.typelevel:cats-mtl_3:1.6.0`,
`org.typelevel:case-insensitive_3:1.5.0`,
`org.typelevel:literally_3:1.2.0`,
`com.comcast:ip4s-core_3:3.8.0`,
`com.twitter:hpack:1.0.2` (verified against
`https://raw.githubusercontent.com/twitter/hpack/master/LICENSE`, since its
Maven POM carries no `<licenses>` block).

**MIT License:**
`org.slf4j:slf4j-api:2.0.18` (verified from the `LICENSE.txt` bundled in the
jar's `META-INF`, since its POM carries no `<licenses>` block either — the
text is the standard MIT wording),
`org.typelevel:cats-core_3:2.13.0`, `cats-kernel_3`, `cats-parse_3:1.1.0`,
`cats-collections-core_3:0.9.10`, `algebra_3:2.13.0`, `keypool_3:0.4.11`,
`vault_3:3.7.0`, `jawn-parser_3:1.7.0`, `idna4s-core_3:0.1.0`,
`co.fs2:fs2-core_3:3.13.0`, `fs2-io_3` (fs2's own POM declares MIT, **not**
Apache-2.0 — verified, do not assume otherwise from its association with the
Typelevel/cats-effect stack),
`org.log4s:log4s_3:1.10.0`.

**BSD 3-Clause License:**
`org.scodec:scodec-bits_3:1.2.5`.

None of the above require anything beyond retaining their license text and
copyright notices, which this file does by identifying each artifact,
version, and license; the upstream project pages (`github.com/<org>/<repo>`
for each) carry the full license text and are the authoritative source
should a longer form be needed.

## 6. Root CA certificates under `certs/`

The `.pem` files under `certs/` are public government and CryptoPro
Certification Authority root/intermediate certificates for the Russian
GOST PKI, redistributed here as **data** (X.509 certificate material), not
as copyrightable source code — they are not licensed software and carry no
software license of their own.
