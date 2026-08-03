# State of the art

What exists, what works, what does not, and where the eleven-year community
effort around this protocol actually stopped.

## The shape of the field

Android Auto has been reverse-engineered continuously since 2015. The output is
substantial and almost entirely one-sided: **every significant open-source
project implements the head-unit side.** They exist so a Raspberry Pi can be a
car display for a normal Android phone.

| Project | Side | Licence |
| --- | --- | --- |
| `f1xpl/aasdk`, `f1xpl/openauto`, and the `opencardev` / `openDsh` forks | head unit | GPL-3.0 |
| `mikereidis/headunit`, `gartnera/headunit`, `borconi/headunit` | head unit | AGPL-3.0 |
| `opencardev/crankshaft` | head unit distribution | GPL-3.0 |
| `aa-proxy/aa-proxy-rs`, `nisargjhaveri/WirelessAndroidAutoDongle` | proxy, both sides | GPL-2.0 |
| `nisargjhaveri/AAWirelessGateway` | Android app, relays the phone side | — |
| `gamelaster/opengal_proxy` | capture proxy | GPL-3.0 |
| `uglyoldbob/android-auto` | head unit | LGPL-3.0 |
| **`tomasz-grobelny/AACS`** | **phone** | GPL-3.0 |

Two consequences. First, none of it can be copied into an Apache-2.0 project
aiming at LineageOS — GPL and AGPL consume Apache-2.0, never the reverse, and
GPL-2.0 is bidirectionally incompatible. Second, the phone side has been
attempted exactly once.

## The one phone-side implementation

`AACS` (2019–2021) runs on a single-board computer in USB gadget mode, presents
itself to a car as an Android Auto phone, terminates the TLS session as the
server, and streams video to the car display. It worked.

It worked because it embeds Google's phone-side certificate and private key,
extracted from Google's own software. That certificate expired in August 2022.
Its author's workaround was to set his car's clock back, and he explicitly
declined to source a newer one.

The issue tracker records the rest of the story: users on other cars hitting
`SSL_read failed` after authentication, never diagnosed, asking whether they
needed to regenerate keys for a new car. Nobody answered. Development stopped.

That is the entire prior art for the phone side.

## Why the wireless dongles are not a counterexample

AAWireless, the Motorola MA1, Carlinkit and the open dongle projects all work
without any Google credential, which looks like a contradiction until you see
the architecture: **they never terminate TLS.** They are a transparent relay —
head unit on one side, phone on the other, encrypted stream passed through
untouched. They convert wired to wireless. They do not participate in the
session.

The one mode that does terminate TLS, `aa-proxy-rs`'s MITM, requires exactly the
Google key material the project refuses to distribute.

## Why nobody tried the obvious experiment

Every attempt obtained a Google certificate first, because it was the shortest
path to a working session, and then had no reason to ask what would have
happened without one. So the question "does a production head unit actually
verify the phone's certificate?" has never been answered in public.

The evidence available points at "yes" — see
[the trust model](03-trust-model.md) for the full weighing — but it is one
report, on one car, from 2021, never reproduced or decomposed into which check
failed. The measurement is missing, and it is cheap to make.

## What actually works today on a de-Googled phone

Worth being clear about, because it is not nothing: **Google's Android Auto app
works on /e/OS right now**, with microG and no Play Services, including on the
Fairphone 6. It needs three stub packages to satisfy presence checks for the
Google app, Maps and text-to-speech, and it needs to appear preinstalled — the
"not preinstalled on this device" check is the real blocker, not any
attestation. Android 15 broke it and /e/OS 3.3 fixed it in December 2025 by shipping the
preinstall slot itself. The three dependency stubs are still not bundled, which
is the remaining gap and the one this project fills.

The blockers are packaging, not cryptography. No SafetyNet, no Play Integrity,
no DroidGuard involvement.

This is the pragmatic path, and this project delivers it alongside the clean-room
stack — see [Android Auto on /e/OS](09-android-auto-on-eos.md). There is an
unanswered /e/OS feature request from April 2026 asking for exactly this, which
is the gap that work fills.

## Protocols that are not the answer

**MirrorLink**, which the target car supports, requires the head unit to run
device attestation against a Car Connectivity Consortium root. The CCC stopped
certifying in July 2021 and terminated operations in September 2023, so no
entity issues those certificates any more. No open-source MirrorLink server has
ever worked; the closest attempt is titled, by its own author, "DOESN'T WORK".
Details in [alternatives considered](05-alternatives-considered.md).

**SmartDeviceLink** is Ford and Toyota family, not VW, and is a templated-HMI
protocol rather than a projection one — the head unit draws its own interface
from data the app sends. It would not put a phone's screen on a car display even
if VW supported it.

**CarPlay** authenticates through an Apple coprocessor that holds a private key
in silicon. That is why commercial adapters contain a physical chip. No
software-only implementation is possible, and no amount of protocol work
substitutes for the part.

## What was genuinely surprising

Three findings changed the plan.

**No privileged permission is needed.** AOSP's USB stack contains no
Android-Auto-specific path, so an ordinary APK receives the head unit's
accessory connection like any other accessory. And a `VirtualDisplay` created
with `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION` needs no permission at all,
because `OWN_CONTENT_ONLY` strips the permission-gated mirroring bit before the
framework's check runs. The projection pipeline is reachable from a plain
installable app.

**Privileged and platform-signed are different tiers.** The permissions that
would let us host third-party apps on the car screen are marked `signature`, not
`signature|privileged`, so a `privapp-permissions` allowlist does not grant
them. That requires being built into the ROM and signed with its keys — a much
larger ask than "ship it in `priv-app`".

**The head unit can be opened.** The target's projection stack runs on QNX and
exposes an unauthenticated root shell over Ethernet once developer mode is set
through the diagnostic interface. Its Android Auto binary can be copied off and
examined. That is a far better source of truth about certificate validation than
any amount of inference, and it is squarely within the interoperability
exemptions.

## Where this project sits

It builds the missing half — the phone side, written clean-room, from a
specification assembled out of open sources — and it treats the certificate
question as a measurement rather than an assumption. Either the wall is real, in
which case this produces the first published characterisation of it and a
working fallback for /e/OS users, or it is not, in which case the stack is
already there.
