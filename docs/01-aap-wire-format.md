# The Android Auto Protocol on the wire

A facts-only description of the protocol, written to be implemented from
directly. Everything here is an observable property of a wire format — byte
offsets, field widths, numeric constants, message ordering. None of it is copied
expression, and the naming throughout is ours.

## Why this document exists in this form

Every open-source implementation of this protocol is GPLv2, GPLv3 or AGPLv3.
This project is Apache-2.0, so none of that code can be copied, and identifier
names are the specific thing that must not be reused: three separate projects
chose three different sets of names for the same fields, which is itself proof
that the names are creative choices rather than dictated by the protocol. Field
numbers and byte offsets are dictated; names are not.

So this document is the handoff boundary. Implementations are written from it,
not from anyone's source. See `docs/07-provenance.md` for the full rules,
including the one document nobody on this project may read.

## Roles, and the two that are counterintuitive

| | Head unit | Phone (what we build) |
| --- | --- | --- |
| USB | host, drives the accessory-mode switch | device, becomes the accessory |
| Wireless TCP | **server**, listens on 5288 | **client**, connects out |
| TLS | **client** | **server** |
| Version exchange | sends request | sends response |
| TLS handshake | opens it | answers it |
| Auth complete | sends | receives — this is the go signal |
| Service discovery | answers | asks |
| Channel open | answers | asks |
| A/V setup | answers | asks, then streams media |
| Ping | sends request | sends response |

The two worth reading twice: on wireless the phone is the TCP *client* but the
TLS *server*, and the phone is the side that presents the certificate under
scrutiny. We are on the judged side of the handshake.

## Frames

```
 0               1               2               3
 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
+---------------+---------------+-------------------------------+
|    channel    |     flags     |        payload length         |
+---------------+---------------+-------------------------------+
|      total length — present only on FIRST-without-LAST         |
+---------------------------------------------------------------+
|                            payload                             |
+---------------------------------------------------------------+
```

Both length fields are big-endian and unsigned. Header is 4 bytes normally, 8
bytes on the opening frame of a fragmented message.

### Flags

| Bit | Value | Meaning |
| --- | --- | --- |
| 0 | `0x01` | FIRST fragment |
| 1 | `0x02` | LAST fragment |
| 2 | `0x04` | payload's message id comes from the control namespace |
| 3 | `0x08` | payload is TLS ciphertext |

Bits 4–7 are unused and always zero. The low two bits together give the
fragmentation state: `0x00` middle, `0x01` first, `0x02` last, `0x03` a complete
single-frame message.

Values seen constantly in practice: `0x03` plaintext single frame (version
exchange, TLS handshake), `0x0B` encrypted single frame (nearly everything),
`0x0F` encrypted single frame in the control namespace (channel open), and
`0x09` / `0x08` / `0x0A` for encrypted first / middle / last.

### The control bit is not "this is channel 0"

This is the rule most implementations get wrong. Bit 2 means *this message's id
comes from the control namespace even though it is addressed to a service
channel*. The sender sets it when all three hold:

- the channel is not 0, **and**
- the message id is ≥ 2, **and**
- the message id is < `0x8000`

Channel 0 never sets the bit. The `≥ 2` clause exists to carve out the two media
indication ids, which are `0x0000` and `0x0001` and are service messages despite
their low numbers.

### The two length fields measure different things

- `payload length` — bytes in **this frame**, after encryption.
- `total length` — bytes of the **whole message**, before encryption.

They are not comparable, and treating them as if they were is the single most
expensive mistake available here. A sender splits the *plaintext* into fragments
and encrypts each fragment independently. Consequently:

- every frame contains whole TLS records, so a receiver can decrypt frame by
  frame without buffering the message;
- a receiver must **decrypt first, reassemble second** — reassembling ciphertext
  and comparing its length to the announced total fails on every fragmented
  encrypted message, with a length mismatch that looks like a framing bug;
- the sum of the frame lengths of an encrypted message legitimately exceeds the
  announced total.

### Fragmentation

Maximum plaintext per frame is `0x4000` (16384). After encryption a frame body
runs slightly over that — around 16413 with typical TLS 1.2 overhead — so
receive buffers must allow for it. The 16-bit length field caps a frame body at
65535 regardless.

Some senders split at "≥ fragment size" rather than "> fragment size", so a
message that is an exact multiple ends with a **zero-length LAST frame**.
Accept it.

Channels interleave: a fragmented video message is routinely interrupted by ping
and sensor traffic, so reassembly state is per channel. Receivers must tolerate
this. Senders should not rely on it — some head units reject a frame arriving
from a different channel mid-message — so serialise a whole multi-frame message
per channel before switching.

### Payload

After decryption, every message payload starts with a big-endian `uint16`
message id, then the message body. The id appears only in the first fragment,
because it is part of the reassembled payload rather than of each frame.

Bodies are serialised protobuf, except the version exchange, the TLS handshake
and the media indications, which are raw bytes.

## Channels

Only channel 0 is fixed. **Every other channel id is chosen by the head unit**
and announced in the service discovery response, where each entry carries a
channel id plus exactly one populated service descriptor identifying what it is.
An implementation must build a runtime map from the response. Hardcoding the
conventional ids will work against most open-source head units and then fail
against a real car.

The conventional assignment, useful only as a sanity check:

| Id | Service |
| --- | --- |
| 0 | control |
| 1 | input (touch, buttons) |
| 2 | sensors |
| 3 | video |
| 4 | media audio |
| 5 | speech audio |
| 6 | system audio |
| 7 | microphone input |
| 8 | Bluetooth |
| 9 | phone status |
| 10 | notifications |
| 11 | navigation |

The service descriptor is identified by its **field number** inside the channel
entry, which is the part that is actually dictated:

| Field | Service |
| --- | --- |
| 1 | channel id |
| 2 | sensor source |
| 3 | media sink (audio or video out) |
| 4 | input source |
| 5 | media source (microphone) |
| 6 | Bluetooth |
| 8 | navigation status |
| 10 | phone status |
| 12 | vendor extension |
| 13 | generic notification |

## Control messages

| Id | Message | Direction |
| --- | --- | --- |
| `0x0001` | version request | head unit → phone |
| `0x0002` | version response | phone → head unit |
| `0x0003` | TLS handshake | both |
| `0x0004` | auth complete | head unit → phone |
| `0x0005` | service discovery request | phone → head unit |
| `0x0006` | service discovery response | head unit → phone |
| `0x0007` | channel open request | phone → head unit |
| `0x0008` | channel open response | head unit → phone |
| `0x000b` | ping request | head unit → phone |
| `0x000c` | ping response | phone → head unit |
| `0x000d` | navigation focus request | phone → head unit |
| `0x000e` | navigation focus response | head unit → phone |
| `0x000f` | shutdown request | both |
| `0x0010` | shutdown response | both |
| `0x0011` | voice session request | phone → head unit |
| `0x0012` | audio focus request | phone → head unit |
| `0x0013` | audio focus response | head unit → phone |

`0x0009` and `0x000a` are unaccounted for in every public source. Log and
ignore them.

### A/V channel messages

| Id | Message |
| --- | --- |
| `0x0000` | media with timestamp |
| `0x0001` | media without timestamp |
| `0x8000` | setup request |
| `0x8001` | start indication |
| `0x8002` | stop indication |
| `0x8003` | setup response |
| `0x8004` | media acknowledgement |
| `0x8005` | microphone open request |
| `0x8006` | microphone open response |
| `0x8007` | video focus request |
| `0x8008` | video focus indication |

### Other channels

Input: `0x8001` input event (head unit → phone), `0x8002` binding request,
`0x8003` binding response.

Sensors: `0x8001` start request, `0x8002` start response, `0x8003` event
indication (head unit → phone).

Bluetooth: `0x8001` pairing request, `0x8002` pairing response, `0x8003` auth
data.

## Handshake

```
head unit → phone   plaintext   0x0001  version request
phone → head unit   plaintext   0x0002  version response
head unit → phone   plaintext   0x0003  TLS handshake  (ClientHello)
phone → head unit   plaintext   0x0003  TLS handshake  (ServerHello …)
                                        … repeat until the handshake settles
head unit → phone   plaintext   0x0004  auth complete
phone → head unit   encrypted   0x0005  service discovery request
head unit → phone   encrypted   0x0006  service discovery response
  per channel:
phone → head unit   encrypted   0x0007  channel open request
head unit → phone   encrypted   0x0008  channel open response
  video channel:
phone → head unit   encrypted   0x8000  setup request
head unit → phone   encrypted   0x8003  setup response
head unit → phone   encrypted   0x8008  video focus indication
phone → head unit   encrypted   0x8001  start indication
phone → head unit   encrypted   0x0000  media, repeating
head unit → phone   encrypted   0x8004  media acknowledgement
```

Everything up to and including auth complete is **plaintext**. Everything after
is **encrypted**. Auth complete is what gates the phone into sending the service
discovery request — it is the head unit declaring the TLS session acceptable.

One inconsistency to absorb: implementations disagree on whether ping is sent
plaintext or encrypted, and both occur in the field. Accept either; send the
response encrypted.

### Version exchange

Raw bytes, not protobuf.

- Request: `uint16` major, `uint16` minor. Both big-endian.
- Response: `uint16` major, `uint16` minor, `uint16` status. Status 0 means
  the versions match; `0xFFFF` means they do not.

Implementations advertise 1.1 through 1.6. Echoing the head unit's minor,
capped at what we support, is safer than announcing a fixed number.

### TLS

- **TLS 1.2 only.** Offering 1.3 breaks head units.
- Mutual: the head unit requests the phone's certificate, and the phone requests
  the head unit's.
- The phone must support both DHE and ECDHE with RSA. A server that cannot do
  DHE-RSA fails the handshake in a way that looks like a certificate problem.
- Session resumption disabled.
- Certificates in the wild are RSA-2048, **X.509 version 1** — no extensions, no
  SAN, no key usage. Modern TLS stacks reject v1 client certificates outright,
  and an ancient head unit parser may equally reject a v3 one.
- No hostname verification; there is no meaningful hostname on a USB cable.
- Records are carried as the body of message `0x0003` and driven through memory
  buffers rather than a socket.

Which certificate the phone may present, and whether a real head unit checks it,
is the subject of `docs/03-trust-model.md`. It is the open question of the whole
project.

## Media

**Video** is H.264 in Annex-B byte-stream form, Baseline profile — raw NAL units
with start codes, no container, no RTP, no length prefixing. Parameter sets must
be in band and should be repeated periodically, because the head unit may begin
decoding at any point.

**Audio** is raw PCM: signed 16-bit little-endian, interleaved, at the rate and
channel count negotiated for that channel. No codec, no header. The standard
configurations:

| Channel | Rate | Depth | Channels |
| --- | --- | --- | --- |
| media audio | 48000 | 16 | 2 |
| speech audio | 16000 | 16 | 1 |
| system audio | 16000 | 16 | 1 |
| microphone | 16000 | 16 | 1 |

A media message with id `0x0000` carries an 8-byte big-endian timestamp in
**microseconds** before the media bytes; id `0x0001` carries the media bytes
alone. The timestamp is a monotonic media clock, not wall time.

Resolutions are enum indices, not pixel counts: 1 is 800×480, 2 is 1280×720, 3
is 1920×1080. Frame rate: 1 is 30 fps, 2 is 60 fps. Values of 4 and above exist
on modern head units and are not publicly documented.

### Flow control

The setup response carries a maximum-unacknowledged count, and the head unit
sends an acknowledgement after consuming media. Open-source head units advertise
1, meaning strict send-one-wait-for-one. Implement it as a real credit window
per channel — not honouring it makes head units drop frames or stall the
channel.

The microphone channel reverses direction: the head unit sends media and the
phone acknowledges.

### Video focus

The video channel has an extra step. After the setup response, wait for the head
unit's video focus indication before sending the start indication. A video focus
request may be sent to prompt it. Audio channels have no such step and may start
immediately after their setup response.

## Wireless bootstrap

Not applicable to a 2017 MIB2, which is wired-only, but documented for
completeness and because it is the cheapest bench transport.

Bluetooth RFCOMM, service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`. The head
unit must also expose a headset profile or phones will not treat it as a head
unit. Framing on that socket differs from AAP: `uint16` big-endian payload
length, `uint16` big-endian message id, then protobuf.

| Id | Message |
| --- | --- |
| 1 | start request — carries the head unit's IP and port |
| 2 | info request |
| 3 | info response — SSID, key, BSSID, security mode, access point type |
| 4 | version request |
| 5 | version response |
| 6 | connect status |
| 7 | start response |
| 8 | ping request |
| 9 | ping response |
| 11 | setup info |

The phone joins the head unit's access point with the delivered credentials,
then opens TCP to the address from the start request — port 5288 by convention.
From there the byte stream is ordinary AAP.

## USB accessory mode

The head unit is the USB host and switches the phone into accessory mode with
three vendor control requests: 51 to read the protocol version, 52 six times to
send manufacturer, model, description, version, URI and serial, and 53 to start.
The phone then re-enumerates with a vendor-specific interface carrying exactly
two bulk endpoints, and AAP frames are read and written as raw bulk transfers
with no additional framing.

Head units identify themselves as manufacturer `Android` with model either
`Android Auto` or `Android Open Automotive Protocol`. Match on manufacturer and
model only — the version string varies between vendors, and matching it would
break on hardware we have not seen.

A single read or write is capped at 16384 bytes by the kernel driver.
