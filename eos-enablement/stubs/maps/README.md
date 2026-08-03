# `com.google.android.apps.maps` — presence stub

## The check this satisfies

Android Auto will not complete setup unless a package named
`com.google.android.apps.maps` is installed. It is step 2 of 4 in
[/e/OS's installation instructions](https://doc.e.foundation/os/how-to/android-auto),
which go on to tell the user to grant it location permission "once the
permission is granted, Google Maps will work with Android Auto".

This package occupies that name. It contains no code, declares no components,
and requests no permissions — including no location permission, which is the
entire point of installing a stub rather than the real thing.

## Why a stub suffices

Verified: the /e/OS community has run Android Auto with a Maps stub since at
least December 2024, and the author of the April 2026 feature proposal reported
"no bugs with Google Maps — it doesn't show up at all"
([forum thread](https://community.e.foundation/t/feature-proposal-add-fake-dependency-for-android-auto-in-native-build-e-os/80862)).
"Doesn't show up at all" is the correct behaviour: Android Auto's launcher has
no Maps tile, and nothing else notices.

Assumed: that the check is presence, not a query for a navigation provider.
The evidence is the same as for the other two stubs — see
[`../README.md`](../README.md#why-a-package-with-nothing-in-it-is-enough).

## What is deliberately missing, and what breaks as a result

No `geo:` or `google.navigation:` intent filter, no map-URL handler, no
`CarAppService`, no `androidx.car.app` metadata.

Consequences:

- **There is no map on the car screen from this package.** Obviously.
- **Map links on the phone keep working.** If this stub declared the `geo:`
  filter, it would appear in the chooser for every address the user taps, and
  picking it would do nothing. /e/OS ships Magic Earth, and from 2026 Murena
  Maps; those keep the intents.
- **Navigation on the head unit must come from somewhere else,** and this is
  the part of the pragmatic path that disappoints people. Android Auto only
  shows apps it considers legitimately installed, and on a de-Googled phone
  most navigation apps do not appear. What actually works, and the two
  developer settings that change the answer, is in
  [docs/09-android-auto-on-eos.md](../../../docs/09-android-auto-on-eos.md).

## One naming collision worth knowing about

A forum user reported confusion because both the Maps stub and /e/OS's Magic
Earth present themselves as "Maps"
([thread](https://community.e.foundation/t/how-to-run-android-auto-without-google-apps/64578)).
This stub is labelled `Maps (Android Auto compatibility stub)` for that reason.
The label is cosmetic; nothing checks it.
