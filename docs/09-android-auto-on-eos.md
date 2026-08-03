# Android Auto on /e/OS — the pragmatic path

**Field test protocol P6.** This is the one procedure in this project that is
expected to put a working map on a car screen. It uses Google's own Android
Auto app; nothing from the clean-room stack is involved. Run it before P3 if
you want a working car, run it after P0 if you want a controlled experiment,
and either way run it, because a success here means the head unit is definitely
capable and any later failure of ours is ours.

Written for someone with the phone in one hand and the cable in the other.

**Target.** Fairphone 6, /e/OS 3.3 or later. An /e/OS version number is not an
Android version — /e/OS ships parallel `-a13`, `-a14`, `-a15` and `-a16`
branches of the same release. On the FP6, /e/OS 3.x is Android 15 (API 35), and
4.1.1 (July 2026) moved it to Android 16 (API 36). Both work and the steps are
the same. Below /e/OS 3.3 on Android 15 this does not work at all and no amount
of sideloading fixes it — that is the regression /e/OS 3.3 repaired. Below
Android 13 it has never worked on any /e/OS build, and people lose days to that
because they read the /e/OS version and assumed.

**You need:** the phone, a short known-good USB **data** cable, the car, and a
computer with `adb`. Set the computer up per [P1](11-field-test-protocols.md).

---

## What you are actually assembling

Four things have to be true at once. Every failure below is one of them being
false, and the whole of the diagnosis is working out which.

1. **A package named `com.google.android.projection.gearhead` must be a system
   package.** Otherwise: error 22.
2. **The real Android Auto must be installed**, updating that system package.
   The placeholder /e/OS ships is a stub and cannot project anything.
3. **Three packages must exist**: `com.google.android.googlequicksearchbox`,
   `com.google.android.apps.maps`, `com.google.android.tts`. Either the real
   Google apps, or the stubs from
   [`eos-enablement/stubs/`](../eos-enablement/stubs/). They only have to
   exist.
4. **The car has to accept the connection** — cable, port, App-Connect licence.

/e/OS gives you (1) for free since 3.3. You supply (2) and (3). (4) is P0.

---

## P6.1 — Find out what the ROM already gave you

**Why first.** /e/OS's behaviour here changed three times between August 2025
and February 2026, the documentation has not caught up, and the forum contains
confident advice for all three states. Two minutes of `adb` beats guessing.

**Steps**

```sh
adb shell getprop ro.lineage.version        # e.g. 22.2-...  (A15) / 23.x (A16)
adb shell getprop ro.build.version.sdk      # 35 = Android 15, 36 = Android 16

adb shell pm list packages -f | grep -i gearhead
adb shell dumpsys package com.google.android.projection.gearhead \
    | grep -E 'versionName|codePath|firstInstallTime|flags=|pkgFlags|privateFlags'

for p in com.google.android.googlequicksearchbox \
         com.google.android.apps.maps com.google.android.tts; do
    echo -n "$p: "; adb shell pm path $p || echo ABSENT
done
```

**Record.** All of it. In particular the `codePath` line and the version name
for gearhead.

**What it means**

| `codePath` for gearhead | State |
| --- | --- |
| `/product/priv-app/...` or `/system/priv-app/...` | The ROM's placeholder, un-updated. This is a **stub**: it will not project. Go to P6.2. |
| `/data/app/...` with `pkgFlags` containing `SYSTEM` | The real app installed over the placeholder. This is the goal state. Skip to P6.3. |
| `/data/app/...` **without** `SYSTEM` | The real app installed with no placeholder underneath. This is the error-22 configuration. See "If your ROM has no placeholder" below. |
| nothing at all | No placeholder. /e/OS 3.2 or earlier on A15, or a build where the gate `PLATFORM_SDK_VERSION > 34` did not apply. |

A version name in the low double digits with a recent `firstInstallTime` is the
real app. The placeholder's version is whatever /e/OS froze; users describe it
as "just a stub", and it is around 129 KB.

**While you are here, resolve the signing question.** There is a documented
inconsistency in /e/OS's own tree about how the placeholder is signed — see
[`eos-enablement/packaging/gearhead-slot/README.md`](../eos-enablement/packaging/gearhead-slot/README.md#an-unresolved-discrepancy-in-eoss-own-tree).
One command settles it:

```sh
adb shell dumpsys package com.google.android.projection.gearhead | grep -A3 signatures
```

Compare that before and after P6.2. If the signature changes, the ROM allows a
signature-mismatched update to a system package, which is not stock AOSP
behaviour and is worth reporting. If P6.2 fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, that is the same finding from the other
direction, and it is a bug in /e/OS worth filing against
[e/backlog#8843](https://gitlab.e.foundation/e/backlog/-/issues/8843).

---

## P6.2 — Install the real Android Auto over the placeholder

**Steps**

1. Try App Lounge first: search *Android Auto*. On several /e/OS builds it does
   not appear, or appears and does not install.
2. If that fails, Aurora Store, installed from App Lounge.
3. If that fails, APKMirror plus the APKMirror Installer, which is the route
   /e/OS users report working most recently
   ([forum, 24 July 2026](https://community.e.foundation/t/cannot-manage-to-install-android-auto/82309)).
4. Reboot. Not optional — /e/OS's own instructions say so, and package-manager
   state for an updated system app is read at scan time.

**Then re-run the `dumpsys` from P6.1.** You want `codePath=/data/app/...`
*and* `SYSTEM` in the flags. If you have the first without the second, the
placeholder was not underneath and you are heading for error 22.

**Record.** Which of the three routes worked, and the resulting version name.
This is genuinely useful to the /e/OS tickets; nobody has written it down for
the FP6.

**If your ROM has no placeholder** — /e/OS 3.2 or earlier, LineageOS proper,
LineageOS for microG — then you need root, and the answer is
`sn-00-x/aa4mg` as a Magisk module, or pushing the APK into `/system/priv-app`
by hand as in the
[XDA GSI guide](https://xdaforums.com/t/gsi-fix-communication-error-22-on-android-auto.4456645/).
Both are out of scope here. Upgrading to /e/OS 3.3+ is easier than either.

---

## P6.3 — The three dependency packages

You are choosing between Google's real apps and stubs. The real apps are what
/e/OS's documentation says; the stubs are what this project supplies. They
behave identically as far as Android Auto's checks are concerned. The
difference is what else lands on your phone.

### Option A — stubs (recommended)

Build them; there is no APK to download, by design.

```sh
cd eos-enablement
export ANDROID_SDK_ROOT=~/Android/Sdk
for s in stubs/google-app stubs/maps stubs/tts; do ./tools/build-stub.sh $s; done

adb install --force-queryable build/google-app.apk
adb install --force-queryable build/maps.apk
adb install --force-queryable build/tts.apk
```

`--force-queryable` is belt and braces. `android:forceQueryable` in a manifest
is honoured by the framework **only for packages on a system partition**; the
install flag is the one path that works for a sideloaded package. Explanation
and the AOSP citation are in
[`eos-enablement/stubs/README.md`](../eos-enablement/stubs/README.md#package-visibility-and-the-one-attribute-that-is-not-decoration).

If any install is refused with `INSTALL_FAILED_ALREADY_EXISTS` or a signature
error, the real Google app is already there. Remove it first:

```sh
adb shell pm uninstall --user 0 com.google.android.apps.maps
```

(`--user 0` also works on preinstalled packages; it hides rather than deletes,
which is what you want.)

### Option B — the real Google apps

App Lounge, three of them, per
[/e/OS's instructions](https://doc.e.foundation/os/how-to/android-auto). Google
Maps additionally needs `Settings → Apps → Google Maps → Permissions →
Location → Allow while using the app`, or it does nothing on the car screen.

Choose this only if you specifically want Google Maps on the head unit. Note
that it cannot be undone cleanly later if you subsequently build the stubs into
a ROM image — see the trade in
[`eos-enablement/stubs/README.md`](../eos-enablement/stubs/README.md#the-trade-nobody-mentions).

### Either way

Reboot, then confirm all three resolve:

```sh
for p in com.google.android.googlequicksearchbox \
         com.google.android.apps.maps com.google.android.tts; do
    echo -n "$p: "; adb shell pm path $p || echo ABSENT
done
```

---

## P6.4 — Settings on the phone

Three of these are non-obvious and each one produces a distinct failure.

1. **Notification access.** Android Auto asks for it on first connection and
   will not proceed without it. `Settings → Notifications → App notifications →
   Android Auto`. If the toggle is greyed out, the app is not fully installed —
   go back to P6.2. A greyed-out toggle after a successful install is a known
   /e/OS complaint and is a symptom, not the disease.
2. **Advanced Privacy.** /e/OS's tracker blocker and IP hiding sit in the path
   of Android Auto's calls home. `Settings → Advanced Privacy → App trackers &
   ads`, find Android Auto, turn tracker control **off** for it. If it does not
   appear in the list, it is not yet visible to Advanced Privacy — another
   symptom of an incomplete install. Turn off IP address hiding and location
   spoofing for the first attempt; you can put them back one at a time
   afterwards and find out which one, if any, actually matters. Nobody has
   published that, and it would be a useful thing to know.
3. **Unknown sources, inside Android Auto.** Open Android Auto on the phone,
   scroll to the bottom, tap **Version** ten times, then the three-dot menu →
   **Developer settings** → **Unknown sources** → on. This is what allows
   sideloaded apps to appear on the car screen. It is necessary and, as P6.6
   explains, usually not sufficient.
4. **Bluetooth on.** Android Auto brings up a Bluetooth link alongside the USB
   session. Users who turn Bluetooth off report the car refusing to connect.

---

## P6.5 — Connect to the car

**Steps**

1. Engine on, head unit fully booted.
2. Set the phone's USB mode to **charging only**, not file transfer. Some MIB2
   units get confused by a phone that enumerates as storage first.
3. Plug into the car's data USB port — on a Polo, the one in the centre
   console, not a rear charge-only socket.
4. Wait. First connection is slow and involves two dialogs on the phone.
5. If nothing happens, open Android Auto on the phone and connect from there.
   Several /e/OS users report the first association only working manually.

**Record.** Verbatim: anything the car displays, including the error number,
and anything the phone displays. Photograph the car screen. The error text is
the single most diagnostic thing in this whole procedure and it is easy to
mistype from memory.

Immediately afterwards, back at the computer:

```sh
adb logcat -d > p6-attempt.txt
adb shell dumpsys package com.google.android.projection.gearhead > p6-package.txt
```

Do this before rebooting anything.

---

## P6.6 — Error codes, and what they actually mean

Android Auto reports failures as *Communication error N*. Be careful with this
table: only one row is solid. The rest is what the community reports, and the
community is guessing from correlation. The **Confidence** column says which is
which, and a low-confidence row is a hypothesis to test, not a diagnosis.

| Code | On-screen text | What it means | Confidence |
| --- | --- | --- | --- |
| **22** | "Android Auto was not pre-installed on this device" | Exactly what it says. The gearhead package is not a system package. Nothing else produces this. | **High** — the message is unambiguous, the mechanism is understood, and the fix is reproducible. See below. |
| 8 | — | Time or time-zone mismatch between phone and head unit. Set the phone to automatic network time; on a MIB2 set the car's clock from GPS. | Medium — consistently reported, mechanism plausible, never confirmed against a log. |
| 11, 12, 16 | — | Usually the cable, or the head unit refusing mid-session. Change the cable before changing anything else. | Low — these are the codes people report when something transient goes wrong, which makes them nearly uninformative. |
| 14 | — | Reported on /e/OS in [May 2026](https://community.e.foundation/t/android-auto-communication-error-14/82150) with no resolution. | None. |
| 17 | — | Reported as microphone-permission related. | Low. |
| — | "No applications are compatible with Android Auto" | The gearhead package is present but is the ROM's stub, not the real app. This is what /e/OS 3.7.3 users saw. Go to P6.2. | **High** — the reporter's own follow-up confirms it. |
| — | "Android Open Automotive Protocol — no installed apps for this USB accessory" | The phone entered accessory mode and nothing claimed the connection: Android Auto is absent, disabled, or invisible to the intent. Reported on /e/OS 4.0 after an A15 upgrade. | **High** — this is an Android framework message, not an Android Auto one. |
| — | nothing at all on either screen | The head unit never tried. Wrong port, wrong cable, or no App-Connect licence. Go back to [P0](11-field-test-protocols.md). | High. |

**Error 22 in detail**, because it is the one this project has something to say
about. Android Auto checks that it is a system package. The
package manager grants that flag to a user-installed APK only when a package of
the same name already exists on a system partition —
`ScanPackageUtils.scanPackageOnlyLI` re-scans the update with `SCAN_AS_SYSTEM`
inherited from the system package it replaces. So the fix is a preinstalled
placeholder, which is what /e/OS 3.3 added. No attestation, no SafetyNet, no
Play Integrity is involved anywhere in this. Full mechanism and the AOSP
citation:
[`eos-enablement/packaging/gearhead-slot/README.md`](../eos-enablement/packaging/gearhead-slot/README.md).

**If you get an error code not in this table, it is new information.** Send it
with `p6-attempt.txt`. The public record on these codes is thin and mostly
wrong.

---

## P6.7 — Working out which piece is missing

In order. Each step is one command and rules out one thing.

```
Car shows nothing at all
    -> P0. Cable, port, App-Connect licence. Not a software problem.

Car shows error 22
    -> adb shell dumpsys package com.google.android.projection.gearhead | grep pkgFlags
       no SYSTEM  -> the placeholder is missing or was not underneath.
                     Are you on /e/OS 3.3+? Did the store install succeed?
       SYSTEM     -> you should not be seeing 22. Capture logcat and say so;
                     that would be a new failure mode.

Phone says "no installed apps for this USB accessory"
    -> adb shell pm list packages -d | grep gearhead   (is it disabled?)
    -> adb shell pm path com.google.android.projection.gearhead
       absent -> P6.2.

Car says "no applications are compatible"
    -> you are running the ROM stub. P6.2, then reboot.

Android Auto starts, but immediately complains about a Google app
    -> the three package checks. P6.3, then reboot. Presence is enough;
       do not grant the stubs anything.

Android Auto starts and the car screen is nearly empty
    -> expected. See P6.8. This is not a fault.

Everything worked, then stopped after an /e/OS update
    -> a system update can restore the placeholder over your installed
       Android Auto. Re-run P6.1 and P6.2.
```

---

## P6.8 — What will not work, and why

Do not skip this section. Most of the disappointment in the /e/OS forum
threads comes from expecting a stock-Android experience.

**The assistant.** No voice control, no "Hey Google", no dictated replies to
messages, no spoken destination entry. The steering-wheel voice button does
nothing useful. Assistant is a hosted service reached through the real Google
app; a stub cannot provide it and neither can microG. If you chose Option B and
installed the real Google app, you get an assistant that mostly works and a
Google account requirement.

**Google Maps**, if you used the stub. Obviously. Which raises:

**Most third-party apps do not appear on the car screen**, and this is the
single biggest practical limitation of the whole path. Android Auto restricts
which apps it will host based on where they were installed from — apps
installed by the Play Store are trusted, apps sideloaded or installed by Aurora
or F-Droid frequently are not. Turning on *Unknown sources* in Android Auto's
developer settings helps and is not a complete answer; `sn-00-x/aa4mg`'s README
has a section titled ["Android Auto still won't show some apps"](https://github.com/sn-00-x/aa4mg#android-auto-still-wont-show-some-apps)
whose answer is an Xposed module. /e/OS users report the same thing
repeatedly: navigation apps that show up in Google's Desktop Head Unit vanish
when connected to a real car.

What people report actually working: Magic Earth, sometimes, after uninstalling
the /e/OS build and reinstalling it from a store; Spotify, after the *Unknown
sources* toggle; VLC. What people report not working: Organic Maps, OsmAnd,
Qobuz, several others. This is the area where a genuine contribution is still
available and none of the packaging in this repository addresses it.

**Text-to-speech inside the car**, if you used the TTS stub. Navigation voice
prompts and message read-out will report the service as unavailable. The
phone's own TTS is untouched, deliberately —
[`eos-enablement/stubs/tts/README.md`](../eos-enablement/stubs/tts/README.md)
explains why the stub declares no engine and why declaring one would be much
worse.

**Wireless Android Auto**, on most setups. It needs the head unit to support
it, and /e/OS users on FP6 report the phone being told it does not support a
wireless connection. Use the cable.

**Video and browser apps** that work under Android 13 do not under 14+. This is
Google tightening Android Auto, not /e/OS.

---

## P6.9 — Undoing it

```sh
adb uninstall com.google.android.apps.maps
adb uninstall com.google.android.tts
adb uninstall com.google.android.googlequicksearchbox
adb uninstall com.google.android.projection.gearhead    # reverts to the ROM stub
```

The last one removes your update and leaves /e/OS's placeholder, which is the
factory state. The ROM placeholder itself cannot be removed without a new
build, only disabled:

```sh
adb shell pm disable-user --user 0 com.google.android.projection.gearhead
```

Nothing in this procedure writes to the car.

---

## What to send back

- The output of P6.1, before and after P6.2
- Which install route worked in P6.2, and the resulting version name
- Whether the gearhead signature changed across P6.2
- Every error code and the verbatim on-screen text, photographed
- `p6-attempt.txt`
- Which apps appeared on the car screen and which did not, with where each was
  installed from

The first, third and last of those do not exist in public for any device, let
alone the Fairphone 6. They are worth more than the rest combined, and they are
what [docs/10-upstreaming.md](10-upstreaming.md) needs in order to be an
argument rather than an opinion.
