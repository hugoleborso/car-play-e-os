# /e/OS enablement — making Google's Android Auto work, properly

The rest of this repository builds a phone-side Android Auto implementation
from scratch, on the bet that a head unit might accept a certificate we
generated. This directory does the opposite and much duller thing: it makes
**Google's own Android Auto app** work reliably on a de-Googled phone, and
packages that work so /e/OS can ship it.

Both are worth having. The clean-room stack answers a question nobody has
answered; this answers a question everybody keeps asking on the /e/OS forum.
This one is far more likely to put a map on a car screen this year.

## What is here

```
stubs/           three Android packages that exist and do nothing, so that
                 Android Auto's presence checks pass without Google Maps,
                 the Google app or Google's speech services
packaging/       Soong modules and a product fragment to ship them in a ROM
packaging/gearhead-slot/
                 everything needed to reserve a preinstalled slot for Android
                 Auto itself -- except the one file that cannot come from us
tools/           a 60-line shell script that builds a stub into an APK using
                 nothing but the Android SDK build-tools
```

and two documents:

- [docs/09-android-auto-on-eos.md](../docs/09-android-auto-on-eos.md) — the
  runnable procedure, every error code, and what will not work
- [docs/10-upstreaming.md](../docs/10-upstreaming.md) — what to propose to
  /e/OS, in what order, and what would be rejected

## No APKs, ever

Nothing in this repository is, contains, or downloads an APK, a Google-signed
binary, or a Google certificate. The three stubs are 40 lines of XML each and
build in about two seconds. Android Auto itself is installed by the user from a
store, on their own device, as it always was.

That constraint is not squeamishness. Every existing solution in this space —
`rik-shaw/aa-stubs`, `sn-00-x/aa4mg`, the SourceForge drop the /e/OS feature
proposal links to — is a bag of prebuilt APKs with, in two of those three
cases, no licence file at all. That is a bad thing to ask an OS vendor to ship
and a worse thing to ask a user to sideload. Forty lines of readable XML is the
whole contribution here, and it is the part that was missing.

## The state of play, as of August 2026

Facts, with sources, because this changes fast and half of what is written
about it online is out of date.

**Android Auto works on /e/OS today**, with microG and no Play Services. /e/OS
has supported it officially since v2.0 (May 2024) and
[documents the procedure](https://doc.e.foundation/os/how-to/android-auto) —
which still tells the user to install the real Google app, real Google Maps and
real Google speech services, and adds: *"We are actively exploring solutions to
eliminate these dependencies."*

**"Communication error 22" is solved in the ROM, for A15 and newer.** /e/OS 3.3
(December 2025) began shipping an `AndroidAutoStub` module, gated on
`PLATFORM_SDK_VERSION > 34`, in `e/os/android_prebuilts_prebuiltapks_lfs`
(commit `63cf352f`). Users confirmed the fix on 3.3 in
[e/backlog#8843](https://gitlab.e.foundation/e/backlog/-/issues/8843). The
Android 15 regression that broke it was a missing placeholder, not a protocol
change.

**The three dependency stubs are still not shipped.** Checked on /e/OS 3.5 in
March 2026 by the reporter of
[e/backlog#9118](https://gitlab.e.foundation/e/backlog/-/issues/9118): "I only
have `com.google.android.projection.gearhead` installed… I do not find stubs
for `com.google.android.apps.maps`, `com.google.android.tts`,
`com.google.android.googlequicksearchbox`."

**The gap has been formally requested twice and is still open.** #9118 (January
2026, labelled `microG::Android Auto`, three upvotes, no assignee) and the
community feature proposal of
[2 April 2026](https://community.e.foundation/t/feature-proposal-add-fake-dependency-for-android-auto-in-native-build-e-os/80862),
whose author posted "Hello! No reaction at all?" a fortnight later. Both remain
open as of August 2026. That is the gap this directory fills.

**Version numbers have moved on since this work was scoped.** /e/OS is at 4.1.1
(20 July 2026) and the Fairphone 6 is now on Android 16. Everything here
applies to /e/OS 3.3 and later on Android 15 or 16; where a step differs
between the two, doc 09 says so.

## Honest scope

This does not make Android Auto free software, does not remove Google from the
session, and does not fix the biggest practical complaint — that most
third-party apps never appear on the car screen, because Android Auto checks
where an app was installed from. It removes three Google applications from the
phone and it makes a preinstall slot exist. That is all, and it is what people
have been asking for.
