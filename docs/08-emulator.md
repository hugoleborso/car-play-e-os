# The head-unit emulator

A head unit, in Python, that a phone can connect to and be judged by.

It exists because the two questions this project cannot answer from a document
are *does the phone side implement the wire format correctly* and *how strict is
a real head unit about certificates*. The first needs a peer that was written
independently from the same specification; the second needs a peer whose
strictness is a command-line flag.

## Run it

```sh
pip install -r emulator/requirements.txt
emulator/run_emulator.py --profile mib2 --trust-policy lenient
python -m pytest emulator/tests -q
```

It listens on TCP 5288 and accepts one phone at a time. On the wireless
transport the head unit is the TCP **server** and the phone dials out — which is
the inverse of the TLS roles, where the head unit is the client and the phone is
the server holding the certificate under scrutiny. Getting those two the wrong
way round is the first thing to check when nothing connects.

The emulator does not do the Bluetooth RFCOMM bootstrap. Point the phone at the
address and port directly; from the first byte of TCP the stream is ordinary
AAP, so nothing downstream of the bootstrap is being skipped.

## Flags

| Flag | What it does |
| --- | --- |
| `--port` | TCP port to listen on. `0` asks the kernel for a free one, which is what the tests use. Default 5288. |
| `--host` | Address to bind. Default `0.0.0.0`. |
| `--trust-policy {lenient,strict,none}` | How hard the head unit looks at the phone's certificate. See below — this is the interesting one. |
| `--profile {mib2,generic}` | Which head unit to impersonate. `mib2` models a 2017 MIB2 as closely as the written evidence allows; `generic` advertises every channel the descriptor can express. |
| `--max-unacked` | The credit window advertised in every setup response, overriding the profile. `1` is strict send-one-wait-for-one, which is what open-source head units advertise. |
| `--seed` | Seed for the channel id allocator, so a failing run can be reproduced exactly. |
| `--channel-ids {scrambled,conventional}` | `scrambled` (default) hands out ids that are deliberately *not* the conventional ones. `conventional` reproduces the sanity-check table. |
| `--protocol-version` | The version offered in the version request, as `major.minor`. Implementations advertise 1.1 through 1.6. |
| `--log-level {debug,info,warning,error}` | Verbosity of everything that is not the message trace. The trace is always printed. |
| `--ping-interval` | Seconds between ping requests. Default 5. |
| `--idle-timeout` | Close the session after this many seconds with no inbound traffic. Off by default. |
| `--defer-auth-complete` | Do not send auth complete automatically. |
| `--no-auto-ack` | Never acknowledge media. |
| `--once` | Exit after the first phone disconnects. |

The trace goes to **stdout** and everything else to **stderr**, so
`run_emulator.py > trace.txt` gives a clean file to attach to a bug report while
the banner and the closing summary still appear on the terminal. The exit code
is 1 if the session ended in a violation.

### The two flags that exist to break the phone on purpose

`--defer-auth-complete` withholds the go signal. Auth complete is what gates the
phone into sending the service discovery request, and a phone that starts
discovery as soon as *its* TLS handshake finishes will appear to work against
any head unit that answers instantly — which is all of them, including this one
by default. A real unit that checks a certificate slowly will not. With this
flag the emulator holds the signal and the race becomes a named violation
instead of a mystery.

`--no-auto-ack` stops acknowledging media. A phone that ignores the credit
window keeps sending and is caught; a phone that honours it stalls after
`--max-unacked` messages, which is the correct behaviour and is visible as the
trace simply stopping.

## Reading the trace

One aligned line per message, in both directions:

```
   0.007 HU  -> phone 0/control          BULK                         8B  0x0004 auth-complete            outcome: 0
   0.008 HU  -> phone 0/control          BULK|ENCRYPTED             362B  0x0006 discovery-response       8 channels: 104/input 62/sensors 108/video 103/media-audio …
   0.051 phone -> HU  108/video          BULK|CONTROL|ENCRYPTED       6B  0x0007 channel-open-request     priority: 0 channel_id: 108
   0.052 HU  -> phone 108/video          BULK|ENCRYPTED              41B  0x8003 setup-response           outcome: SETUP_ACCEPTED max_unacked: 1 granted_format_index: 0
   0.055 phone -> HU  108/video          FIRST|ENCRYPTED          20031B  0x0000 media-with-timestamp     stamp=33000us 20021B [SPS,PPS,IDR]
```

The columns, left to right:

1. **Seconds since the session opened.** Relative, so two runs line up.
2. **Direction.** `HU  -> phone` and `phone -> HU `, padded to the same width so
   the eye can pick out one direction without reading.
3. **Channel**, as `id/service`. The id is whatever this run assigned; the
   service name comes from the head unit's own map. A channel the head unit
   never advertised reads `id/unannounced`, and is always a violation.
4. **Frame flags** of the opening frame. `BULK` is a complete single-frame
   message, `FIRST`/`MIDDLE`/`LAST` a fragment. `CONTROL` and `ENCRYPTED` are the
   two semantic bits.
5. **Bytes.** Outbound this is the whole frame or frames as written; inbound it
   is the reassembled *plaintext* message. The two are not comparable on an
   encrypted message and are not meant to be.
6. **Message id and name.** The name depends on the channel — `0x8001` is a start
   indication on a media channel, an input event on the input channel and a
   sensor start request on the sensor channel.
7. **Decoded contents**, one line.

Three payload shapes are not protobuf and get their own rendering:

- **Version exchange** — `offering 1.6`, `1.6, match`.
- **TLS handshake** — the records are named individually:
  `ServerHello 65B, Certificate 765B, ServerKeyExchange 300B, ServerHelloDone 4B`.
  A handshake that fails on real hardware fails somewhere specific, and this is
  what says where. After a `ChangeCipherSpec` the handshake body is ciphertext,
  so the trace says `Handshake(encrypted)` rather than inventing a name from a
  byte that is no longer a type byte.
- **Media** — the timestamp, the payload size, and for video the H.264 NAL unit
  types: `stamp=33000us 20021B [SPS,PPS,IDR]`. Two of the three ways video
  silently fails are visible here alone — no `SPS`/`PPS` in the first second,
  and no `IDR` after the focus indication. Audio shows the frame count implied
  by the negotiated format, which catches a phone sending the wrong sample rate.

The colours (cyan out, yellow in) appear only on a terminal. Redirected to a
file or a CI log the trace is plain, because half its value is being greppable.

### When something is wrong

A violation names the rule that was broken, and then the emulator tells the
phone why and stops:

```
ERROR openaap_hu.session: protocol violation [credit-window-overrun] channel 108
  has 2 media messages unacknowledged against the window of 1 this head unit
  advertised in its setup response
```

The rule names are stable, so they can be grepped and asserted on. The ones the
emulator enforces as fatal:

| Rule | What the phone did |
| --- | --- |
| `version-mismatch`, `version-status` | Answered a major it was not offered, a minor above it, or a status that is neither 0 nor `0xffff`. |
| `tls-handshake-failed` | Failed the handshake. The TLS alert is relayed before the session closes. |
| `ciphertext-before-handshake` | Set the ciphertext flag before the handshake settled. |
| `plaintext-after-auth-complete` | Sent plaintext after auth complete. Ping is the one carve-out. |
| `discovery-before-auth-complete` | Started service discovery without waiting for the go signal. |
| `control-flag` | Set frame bit 2 when it should not, or left it clear when it should not. |
| `unadvertised-channel` | Addressed, or tried to open, a channel id the discovery response never published. |
| `channel-not-open` | Sent on a channel before the channel open handshake. |
| `start-before-setup`, `start-before-video-focus` | Started a stream too early. Video waits for the focus indication; audio does not. |
| `media-before-start` | Sent media before its start indication. |
| `credit-window-overrun` | Had more media in flight than the advertised window. |
| `wrong-direction` | Sent a head-unit-only message, or media on the microphone channel. |
| `ping-stamp`, `ping-timeout` | Answered a ping with the wrong stamp, or not at all. |
| `framing`, `missing-message-id`, `malformed-body`, `undecryptable-frame` | Malformed at the link or payload layer. |

Anything the specification genuinely leaves open is a **warning** rather than a
violation, printed in the closing summary. Failing on an ambiguity would make
the emulator a worse oracle, not a better one. Warnings include: messages
`0x0009` and `0x000a`, which are unaccounted for in every public source and are
logged and ignored as the specification instructs; a format index outside what
the channel advertised, which is refused with `SETUP_REFUSED` rather than
treated as fatal; button codes bound that were never advertised, since nothing
in the specification assigns key codes at all; and a sensor subscribed that was
not offered.

### The credit window, and when it is released

Credit is released when the session's outbound buffer is **handed to the
transport**, not when the acknowledgement is built. That is the first moment the
phone could have seen it. Released any earlier, a phone that writes its whole
window in one go would look compliant — it would be measured against
acknowledgements it had not yet received. The video focus grant works the same
way for the same reason: a phone may not act on an indication it cannot yet have
read.

## What the trust policies model

This is the open question of the project. `docs/03-trust-model.md` sets out the
evidence; the three policies are the three hypotheses, made runnable.

**`lenient`** — the head unit requests the phone's certificate and accepts
whatever arrives. If real hardware behaves this way, a clean-room phone side
works with a self-signed certificate and the project's central problem
evaporates. This is what every open-source head unit does, which is exactly why
passing against one proves nothing.

**`strict`** — the head unit pins a certificate authority and the phone's
certificate must chain to it. The emulator generates that authority at start-up,
so no phone can possibly chain to it and the handshake always fails. That is
deliberate: it reproduces the pessimistic case, in which a clean-room phone side
is impossible without vendor key material. What it is good for is checking that
the phone *fails correctly* — that it reports a certificate rejection rather
than hanging, and that the on-device probe matrix records what we think it
records.

**`none`** — the head unit never asks for a client certificate. The happiest
possible outcome, and cheap to test.

None of these is evidence about real hardware. They are three shapes the phone
side has to survive, because until somebody runs the probe matrix against a car
we do not know which one a 2017 MIB2 implements. The certificates involved are
generated at run time and thrown away; no key material in this repository
originates anywhere but its own test PKI.

## Channel ids are assigned at run time, on purpose

Only channel 0 is fixed. Every other id is chosen by the head unit and published
in the discovery response, so a phone must build its map from that response.

The default allocator therefore hands out ids that are deliberately *not* the
conventional ones — 39, 62, 85, 103, 104, 108, 121, 125 in one run rather than
1 through 8. A phone that hardcoded the conventional table works against most
open-source head units and then fails in a real car, which is the worst possible
failure schedule: it passes every test you own. Here it fails on the first
message, loudly, with the channel printed.

`--seed` makes a run reproducible. `--channel-ids conventional` reproduces the
sanity-check table, which is useful only for comparing behaviour against another
head unit.

The two profiles differ in channel *set* as well as in ids: `mib2` offers no
phone-status, notification or navigation channels, `generic` offers all of them.
A phone that assumes a channel exists fails against `mib2` and not against
`generic`.

## Layout

```
emulator/openaap_hu/framing.py    frame codec, per-channel reassembly
emulator/openaap_hu/tls.py        TLS client half over memory BIOs, trust policies
emulator/openaap_hu/pki.py        run-time certificate generation
emulator/openaap_hu/wire.py       message ids, the control-flag rule, non-protobuf codecs
emulator/openaap_hu/profile.py    head unit self-description, channel id allocation
emulator/openaap_hu/session.py    the session state machine and every rule it enforces
emulator/openaap_hu/transport.py  the byte pipe and the pump loop
emulator/run_emulator.py          the CLI
```

`session.py` never touches a socket: it consumes bytes through `feed` and
produces them through `drain_outbound`. That is what lets the tests drive a full
session with no I/O at all, and what would let a USB bulk pair replace TCP
without the session noticing — a `Link` with `read`, `write` and `close`, which
is everything `transport.py` asks of one.

## Field numbers and names

`descriptors.proto` is the one file whose field numbers are dictated by the wire
format: a receiver identifies what a channel *is* by which sub-descriptor field
number is populated, so 1, 2, 3, 4, 5, 6, 8, 10, 12 and 13 are the protocol, and
7, 9 and 11 are absent on purpose. Everywhere else the numbering is this
implementation's own choice, assigned in the order the fields are described in
prose in `docs/01-aap-wire-format.md`.

This matters for what the emulator can and cannot prove. Where the numbers are
dictated, a disagreement with the phone side is a real interoperability bug.
Where they are ours, the two implementations agree because both were written
from the same prose, and agreement proves only that the prose was read the same
way — not that either matches a car. The same caveat covers every `outcome`
field: the specification never says what a successful outcome looks like, so the
emulator sends 0 and a phone must not key behaviour off the value.

## What the emulator does not do

- No Bluetooth RFCOMM bootstrap and no USB accessory-mode switch. Both are
  described in the specification and neither is implemented; connect over TCP.
- It consumes media without decoding it. Nothing renders; the acknowledgement is
  the only response.
- It does not check H.264 conformance beyond naming NAL unit types, or PCM
  beyond checking the buffer is a whole number of frames.
- Resolution and frame rate travel as enum indices, and indices of 4 and above
  are known to exist on modern head units and are not publicly documented, so
  the emulator cannot offer them.
