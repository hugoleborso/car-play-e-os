# Alternatives considered: MirrorLink and SmartDeviceLink

Android Auto is not the only way to get a phone's UI onto a car screen. Two
alternatives were investigated as fallbacks, in case the Android Auto trust
model (see `03-trust-model.md`) turns out to be impassable. Both were closed
out. This document records why, so the question does not get reopened on a hunch.

## MirrorLink: closed, and closed permanently

The 2017 Polo's MIB2 supports MirrorLink, which made it the obvious fallback:
an actual published standard (ETSI TS 103 544, 24 parts, a CCC specification
published through ETSI as a PAS) rather than a reverse-engineered one. VNC over
IP-over-USB, UPnP for discovery, RTP or Bluetooth for audio. Nothing exotic.

It fails on attestation, and then it fails again on institutional collapse.

### The head unit is required to attest the phone

TS 103 544-13 is normative and unambiguous:

> The MirrorLink Client **shall** execute the Device Attestation Protocol as
> specified in [4].

> The MirrorLink Client **shall immediately terminate** any later established
> connection, if the attested component's URL is not identical to the
> established connection.

(Note the terminology inversion: in MirrorLink the **phone** is the "Server" and
the **head unit** is the "Client".)

The only latitude the spec grants is `may defer` — the head unit may postpone
attestation to keep session setup fast. There is no `may skip`, and no
reduced-functionality mode for an unattested phone. The URL-binding rule further
means attestation is cryptographically tied to the actual VNC and UPnP
endpoints, so attesting one component and serving another does not work.

The mechanism (TS 103 544-4) is a nonce challenge over TCP: the head unit sends
`attestationRequest` with a nonce, the phone returns `attestationResponse` with
a signature over it plus a certificate chain rooted in the **CCC DAP root**.
Root is RSA-4096/SHA-512, 20-year validity, status checked over OCSP. Device
certificates are issued only by the CCC PKI, only to CCC members, only through
certified test labs. The private key lives on the certified phone and is not
designed to be extractable.

Independent confirmation from security research: Mazloom, Rezaeirad, Hunter and
McCoy, *A Security Analysis of an In-Vehicle Infotainment and App Platform*,
USENIX WOOT '16, describe DAP as "the main security mechanism included in the
MirrorLink specification … designed to prevent unauthorized hardware from
accessing the IVI system". Worth noting what their attack actually was: they did
**not** defeat DAP. They found MirrorLink latently present and re-enablable on a
head unit that shipped with it disabled, then exploited a heap overflow in the
head unit's parser. That is an exploit, not an interoperability path, and it is
not something this project would ship.

There is a second, separate certificate system on top: application certificates
(TS 103 544-14/-16) decide which apps may appear on the car screen at all, in
"park mode only" or "drive mode" tiers. It is why the folklore workaround exists
— people use a legitimately certified app as a canvas and draw a floating window
over it. That trick presupposes a certified phone that already passed DAP.

### The certificate authority no longer exists

The Car Connectivity Consortium **stopped certifying new MirrorLink devices and
applications on 31 July 2021** and **terminated MirrorLink operations entirely
on 30 September 2023**. There is no longer any entity issuing the certificates
DAP requires. "Get certified" is not expensive; it is impossible.

The strongest empirical evidence that head units really do enforce this is what
happened when the infrastructure went away. Samsung ended MirrorLink support on
1 June 2020 and decommissioned its certificate-download server; every Samsung
phone on Android 9+ now fails MirrorLink with a certificate download error. If
head units ignored attestation, killing a certificate server would have been
harmless.

### Nobody has ever built one

An exhaustive survey of open-source MirrorLink work found no working phone-side
implementation:

| Project | What it is | Licence | State |
| --- | --- | --- | --- |
| `CarConnectivityConsortium/MirrorLink_Android_CommonAPI` | Official CCC AIDL interface files | Apache-2.0 | Interfaces only, no implementation |
| `CarConnectivityConsortium/RockScout` | Official reference *client app* | Apache-2.0 | Useless without a certified server |
| `rsyrnicki/mirror-link-pi` | Raspberry Pi server attempt | GPL-3.0 | 9 commits, "no results yet"; open questions are literally "Is it possible without being part of the CCC?" and "How to handle the necessary certificates" |
| `tooming/mondeo-mirrorlink` | SSDP/UPnP announcer for Ford SYNC 2 | none | Best analysis found; phase 1 only, no VNC |
| `sileht/mirrorlink-nexus5-apps` | Ported LG G4 stock MirrorLink APKs, re-signed | none | Author's own title: "That was an investigation and that DOESN'T WORK" |

Re-signing breaks the device-bound key. That last row is the closest anyone has
come, and it is a documented failure.

### The one cheap experiment left

Residual uncertainty is small but not zero, and one report (for Ford SYNC 2, not
VW, and explicitly flagged unverified by its own author) speculates the head unit
may not enforce signature checking at runtime. Falsifying this is cheap:

1. Enable USB tethering on the phone.
2. Broadcast `NOTIFY ssdp:alive` for `urn:schemas-upnp-org:device:TmServerDevice:1`
   to `239.255.255.250:1900`.
3. Serve an **unsigned** device-description XML over HTTP at the advertised
   `LOCATION`.
4. Watch the logs.

Three outcomes: no HTTP request at all means the USB personality is wrong (MIB2
expects CDC/NCM; Android's tethering default is usually RNDIS); an HTTP fetch
followed by silence means signature or DAP enforcement, which is the expected
result; a MirrorLink icon appearing means the door is ajar and worth pushing on.

This is scheduled as a half-day experiment when the car is available, purely
because it is definitive. It is not a plan.

### A prerequisite to check on the actual car

The head unit's part number decides what is even possible:

- `3Q0 035 874` (no suffix) — Discover Media that does MirrorLink **only** and
  is **not** App-Connect capable. If this is the unit, Android Auto is not
  available at all without swapping hardware, and the whole project premise
  changes.
- `3Q0 035 874 A / B / C` — App-Connect capable: CarPlay, Android Auto and
  MirrorLink.

Also worth knowing: App-Connect activation on VW is not pure VCDS/OBDeleven long
coding. It generally needs a dealer-side component-protection and feature
enablement step, and sometimes a control unit change.

Check `Menu → Setup → System information`, or the part-number label on the unit,
before further work.

## SmartDeviceLink: not applicable to VW

Closed out quickly, for two independent reasons.

**VW does not support it.** SDL's OEM list is Ford and Lincoln globally, plus
Toyota, Lexus, Suzuki and Daihatsu in Japan and the US. Consortium members
include Mazda, Subaru and PSA. Volkswagen appears nowhere — not as a member, not
as a shipping OEM. This is not an accident of timing: VW was a driving force
behind MirrorLink inside the CCC, and SDL was the Ford/Toyota answer to the same
problem. VW was on the other side of that split.

**It would not deliver the goal even if VW did support it.** SDL is a templated
HMI-command protocol, not a projection protocol. The head unit renders its own
native UI from data the app sends. There is no "your UI on the car screen"; there
is "your data in the car's UI". That is a different product.

## Where that leaves things

Both fallbacks are gone. Android Auto's trust model is not one option among
several — it is the only path to the stated goal, which is why the project puts
so much weight on measuring exactly where its wall is rather than assuming.

Also worth stating plainly: the CarPlay framing in the original request is not
reachable by any software-only route. Wired and wireless CarPlay both
authenticate through an Apple MFi coprocessor that holds a private key in
silicon. This is why commercial adapters contain a physical MFi chip. No amount
of protocol work substitutes for that part.
