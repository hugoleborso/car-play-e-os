# Android integration: what a plain APK can do, and where the ROM starts

Target device: Fairphone 6 running /e/OS 3.x, which sits on a LineageOS 22.2
base, which is Android 15 (API 35). Plan against API 35 with API 36 on the
horizon.

This document records which parts of the phone side are reachable from an
ordinary installable APK and which require being built into the ROM. It is the
document that decides the architecture, so every claim below cites the AOSP
source that enforces it.

## Summary

| Capability | Mechanism | Gate | Plain APK? |
| --- | --- | --- | --- |
| Render our own UI to an off-screen display | `DisplayManager.createVirtualDisplay` with `PUBLIC \| OWN_CONTENT_ONLY \| PRESENTATION` | none | yes |
| H.264 encode that display | `MediaCodec` + `createInputSurface()` | none | yes |
| Receive the head unit's USB connection | `USB_ACCESSORY_ATTACHED` intent filter | user grant at attach | yes |
| Stay alive for a whole drive | foreground service, type `specialUse` | `normal` permission | yes |
| Host *third-party* activities on the display | `VIRTUAL_DISPLAY_FLAG_TRUSTED` | `ADD_TRUSTED_DISPLAY`, `signature\|role` | no |
| Mirror the phone's real screen | `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` | `CAPTURE_VIDEO_OUTPUT` (`signature`) or a MediaProjection token | consent dialog per session |
| Force USB into NCM / enable tethering | `UsbManager.setCurrentFunctions`, tethering APIs | `MANAGE_USB`, `TETHER_PRIVILEGED`, both `signature\|privileged` | no, needs priv-app |

The important line is the first one, and it is better news than expected.

## Rendering and encoding need no permissions at all

`DisplayManagerService.createVirtualDisplayInternal` gates three flags:
`AUTO_MIRROR` needs `CAPTURE_VIDEO_OUTPUT` or a MediaProjection token, `SECURE`
needs `CAPTURE_SECURE_VIDEO_OUTPUT`, and `TRUSTED` needs `ADD_TRUSTED_DISPLAY`.

Before those checks run, the service normalises the flags:

```java
if ((flags & VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
    flags |= VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
    ...
}
if ((flags & VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
    flags &= ~VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
}
```

`PUBLIC` implicitly requests `AUTO_MIRROR`, which is permission-gated — but
`OWN_CONTENT_ONLY` strips that bit back off *before* the permission check. So
the combination `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION` passes every gate
while holding no permission at all.

That is the whole video pipeline: create such a display, attach
`MediaCodec.createInputSurface()` to it, put our own `Presentation` on it, and
encode. No permissions, no consent dialog, no ROM modification.

## Why we render our own UI instead of hosting real apps

The obvious design — put Google Maps or OsmAnd on the virtual display and stream
it — does not work. `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`
refuses it:

```java
if (!display.isTrusted()) {
    // Limit launching on untrusted displays because their contents can be read
    // from Surface by apps that created them.
    if ((aInfo.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) {
        return false;
    }
    ...
}
...
if (display.getOwnerUid() == callingUid) { return true; }
```

An activity launches on an untrusted display only if it declares
`android:allowEmbedded="true"`. Essentially nothing real does — not Maps, not
OsmAnd, not Organic Maps, not Spotify. Marking the display `PUBLIC` does not
help; the untrusted branch runs first. Our *own* UI is always allowed, because
`display.getOwnerUid() == callingUid`.

The cross-check is `scrcpy --new-display`, which *can* host arbitrary apps on a
virtual display. It runs as the `shell` UID, and `packages/Shell` declares
`ADD_TRUSTED_DISPLAY`, `ADD_ALWAYS_UNLOCKED_DISPLAY`, `INTERNAL_SYSTEM_WINDOW`,
`CAPTURE_VIDEO_OUTPUT` and `ACTIVITY_EMBEDDING`. That is simultaneously proof
the mechanism works and proof it is out of reach for an installable app.

**Decision: we ship our own car UI, drawn into our own Surface.** Content comes
from data sources rather than from other apps' pixels — `MediaSession` for
playback and metadata, a navigation integration for turn-by-turn, our own
renderer for everything else. This is the same shape as Android Auto's own app
model, and it is the only tier that does not require owning the operating
system.

Hosting real activities stays on the roadmap as a later escalation, not as v1.

## Privileged is not the same as platform-signed

A distinction that changes what "just ship it in the ROM" costs.

`privapp-permissions` allowlist XML unlocks permissions marked
`signature|privileged`. It does **not** unlock permissions marked plain
`signature`. Checked across android10 through android16 and `main`:

```xml
<permission android:name="android.permission.CAPTURE_VIDEO_OUTPUT"
    android:protectionLevel="signature" />
<permission android:name="android.permission.ADD_TRUSTED_DISPLAY"
    android:protectionLevel="signature|role" />
<permission android:name="android.permission.MANAGE_USB"
    android:protectionLevel="signature|privileged" />
<permission android:name="android.permission.TETHER_PRIVILEGED"
    android:protectionLevel="signature|privileged" />
```

So there are three tiers, not two:

1. **Plain APK** — the virtual display, the encoder, USB accessory mode, the
   foreground service. Everything v1 needs.
2. **Privileged app** (`privileged: true` + allowlist XML on the same
   partition) — `MANAGE_USB`, `TETHER_PRIVILEGED`. Needed only for USB-network
   transports.
3. **Platform-signed** (`certificate: "platform"`, built into the ROM and signed
   with e Foundation's release keys) — `CAPTURE_VIDEO_OUTPUT`,
   `ADD_TRUSTED_DISPLAY`. Needed only to host third-party activities.

Tier 3 means every user needs a custom ROM build. Staying in tier 1 is worth a
lot of design effort.

## MediaProjection is a trap for this use case

`MediaProjection` would grant the same capability without platform signing, but
its terms are wrong for a car:

- Consent is required **per session**, where a session is a single
  `createVirtualDisplay()` call, and a token may be used only once. That is a
  dialog every time the user gets in the car.
- Android 14+ throws `SecurityException` on token reuse.
- Android 15 QPR1+ **stops projection when the screen locks**, plus a persistent
  stop chip. A phone in a car dock will lock.

Since `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION` needs no token at all, we avoid
`MediaProjection` entirely and inherit none of this.

## Lifecycle constraints on Android 15

- **Do not auto-start at boot.** Android 15 forbids a `BOOT_COMPLETED` receiver
  from starting a `mediaProjection`, `dataSync`, `camera`, `mediaPlayback`,
  `phoneCall` or `microphone` foreground service. Start on the USB attach
  broadcast instead, which is a user-visible hardware event and the natural
  trigger anyway.
- **Use foreground service type `specialUse`.** `connectedDevice` describes what
  this is, but from API 34 each type is gated behind a permission set, and the
  set for `connectedDevice` is Bluetooth, Wi-Fi, NFC, infrared and UWB. There is
  no USB entry, because accessory access is granted per device at runtime rather
  than declared in a manifest — so a cable-only projection service cannot satisfy
  it, and starting it anyway throws `SecurityException` the moment the user plugs
  into the car. `dataSync` passes the permission check but is capped at 6 hours
  per 24 and calls `onTimeout()`, after which failing to stop promptly throws,
  which ends a long drive badly. `specialUse` exists for exactly the cases the
  enumerated types do not cover. When the wireless transport lands the app will
  hold `CHANGE_WIFI_STATE` to join the head unit's access point, and
  `connectedDevice` becomes both accurate and available.
- Apps targeting API 35 must be the top app or run a foreground service to
  request audio focus — satisfied by the projection service.
- Keep the pipeline inside the foreground service rather than in `JobScheduler`;
  API 36 tightens job quotas around foreground services.
- Ask for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and treat the USB connection
  as the session anchor.

## Packaging, if and when we go into the ROM

LineageOS's own `LineageParts` is the template:

```
android_app {
    name: "LineageParts",
    platform_apis: true,
    certificate: "platform",
    system_ext_specific: true,
    privileged: true,
    required: ["privapp_whitelist_org.lineageos.lineageparts"],
}
```

Notes that matter: modern Lineage uses Soong `Android.bp` with
`privileged: true`, not the legacy `LOCAL_PRIVILEGED_MODULE`; the allowlist must
land on the same partition as the app; packages live at `packages/apps/<Name>`.

Contribution goes through **Gerrit at review.lineageos.org**, not GitHub pull
requests — `repo upload`, patchsets on a single commit, merge on +2. /e/OS is a
LineageOS fork on gitlab.e.foundation using ordinary merge requests, and e
Foundation asks contributors to go upstream to LineageOS first. So the
sequencing is Lineage first, /e/OS by rebase; /e/OS-only means maintaining a
fork forever.

LineageOS has never shipped a projection app — it lost Miracast when Google
removed Wi-Fi Display from AOSP around Android 9. A self-contained,
permission-free app is plausibly acceptable upstream; one demanding
`ADD_TRUSTED_DISPLAY` would face real resistance.
