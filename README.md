# openaap

A clean-room, phone-side implementation of the Android Auto Protocol for
de-Googled Android — so that a phone running /e/OS or LineageOS can project onto
a car head unit without Google Play Services.

Every open-source implementation of this protocol implements the **head unit**
side. This one implements the **phone** side, which is the half nobody has built
without borrowing Google's key material.

## Get the app

**[Download the latest APK](../../releases/latest)** — then open it on the phone, or
`adb install -r openaap-*.apk`.

Android 10 or later. No Play Services, no Google account, no root, no custom ROM.
It is signed with Android's standard debug key, which is deliberate for a
sideloaded diagnostic tool: no secret lives in this repository and the build is
reproducible from the tagged source.

The app starts in **status mode**. Plug the phone into a car's data USB port and it
breaks the session in a different way on each connection, recording how far each
attempt got and the exact bytes the head unit answered with. The screen shows this
live, which matters because the cable is in the car and `adb` cannot see the phone
while it is happening. The full procedure is in
[field test protocols](docs/11-field-test-protocols.md).

The certificate matrix that used to be the default is still there, one switch
away. It has been run: nine identities, one refusal code, every time. What that
code *means* is now the open question, and presenting a tenth certificate cannot
answer it — see [the trust model](docs/03-trust-model.md).

It does **not** project video or audio yet. It measures.

## Status

The protocol stack is implemented and tested. Whether it can connect to a real
car is an open question with a known experiment attached — see
[the trust model](docs/03-trust-model.md). Nothing here has yet touched physical
hardware.

| Piece | State |
| --- | --- |
| Link framing, fragmentation, reassembly | done, cross-validated against an independent implementation |
| TLS tunneled through control messages | done |
| Session state machine: version, handshake, discovery, channels | done |
| Protobuf schemas | done |
| USB accessory transport | done, untested on hardware |
| TCP transport (wireless, emulator, desktop head unit) | done |
| Head-unit emulator | in progress |
| Video and audio pipeline | in progress |
| Car UI | not started |
| Certificate probe matrix | done, runnable on-device, report pulled over adb |

## Why this is hard, in one paragraph

The session is protected by mutual TLS in which the phone is the **server**, and
every certificate either end has ever presented chains to one self-signed root
whose private key is Google's. There is no intermediate, no enrolment endpoint,
and no way to obtain a leaf. The only prior phone-side implementation solved
this by embedding Google's extracted certificate, which expired in 2022. This
project refuses that route, which means the central question is empirical: what
does a real head unit actually check? Nobody has published an answer in eleven
years of community work. The stack here exists partly to find out.

## Layout

```
protocol/    link framing, message ids, protobuf schemas  (pure JVM)
crypto/      TLS engine, pluggable credentials, probe matrix  (pure JVM)
transport/   transport interface, TCP, loopback  (pure JVM)
core/        message layer and session state machine  (pure JVM)
services/    per-channel service implementations  (pure JVM)
harness/     headless phone for end-to-end tests  (pure JVM)
android/     USB transport, projection pipeline, app shell
emulator/    head-unit emulator, in Python
testdata/    golden wire vectors shared by both implementations
docs/        specification and design record
```

The split is deliberate: **the entire protocol lives in pure-JVM modules** and is
buildable and testable with nothing but a JDK. No Android SDK, no device, no
emulator. The Android modules are thin adapters over it. CI enforces this by
building the JVM modules on a machine with no SDK installed.

## Build

```sh
./gradlew build                       # JVM modules; no Android SDK needed
./gradlew :android:app:assembleDebug  # the APK; needs an SDK
python -m pytest emulator/tests -q    # the head-unit emulator
```

The Android modules join the build only when an SDK is present, so the first
command works everywhere.

## Two implementations on purpose

The head-unit emulator is a second, independent implementation of the same wire
format, written in another language from the same written specification, sharing
no code with the phone side. A test peer that shares its codec with the
implementation under test proves very little — the two agree because they make
the same mistakes. Written twice, they disagree whenever one has misread the
spec, which is a failure that would otherwise surface as a head unit silently
refusing to talk, in a car, with no logs.

`testdata/frame-vectors.json` holds byte sequences derived by hand from the
written frame layout rather than captured from either implementation. Both test
suites assert against it.

This has already paid for itself twice, catching a wrong length-field comparison
and a decoder check that was invalid on encrypted frames.

## Provenance

Every existing implementation of this protocol is GPL or AGPL. This project is
Apache-2.0 and aims to be acceptable upstream, so nothing is copied from them —
not code, not `.proto` files, and specifically **not identifier names**, which
are creative choices rather than protocol requirements. All naming here is ours.
No certificate or private key in this repository originates anywhere but its own
generated test PKI.

[docs/07-provenance.md](docs/07-provenance.md) sets out the rules in full,
including which document nobody working on this may read, and why the usual
clean-room two-team split does not make that one safe.

## Reading order

1. [State of the art](docs/00-state-of-the-art.md) — what exists, what fails, why
2. [The wire format](docs/01-aap-wire-format.md) — the specification both implementations are written from
3. [The trust model](docs/03-trust-model.md) — the open question and the experiment
4. [Android integration](docs/04-android-integration.md) — what a plain APK can do, and where the ROM starts
5. [Alternatives considered](docs/05-alternatives-considered.md) — MirrorLink and SmartDeviceLink, and why both are closed
6. [Provenance](docs/07-provenance.md) — where information may come from
7. [Field test protocols](docs/11-field-test-protocols.md) — how to test this against a real car with nothing but a cable and a laptop

## Licence

Apache-2.0.
