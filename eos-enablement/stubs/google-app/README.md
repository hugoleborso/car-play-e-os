# `com.google.android.googlequicksearchbox` — presence stub

## The check this satisfies

Android Auto will not complete setup unless a package named
`com.google.android.googlequicksearchbox` is installed. That is the Google app
— the search bar, the assistant, the hotword — known internally as *Velvet*.
It is listed as a hard prerequisite in
[/e/OS's own installation instructions](https://doc.e.foundation/os/how-to/android-auto):
"Install the Google app (available in App Lounge)", step 1 of 4.

This package occupies that name. It contains no code, declares no components,
and requests no permissions.

## Why a stub suffices

Verified: this exact stub, with an empty `<application>` element and nothing
else, has been the working answer on de-Googled ROMs since 2020 —
[`SolidHal/Gapp-Package-Spoof`](https://github.com/SolidHal/Gapp-Package-Spoof)
(MIT), redistributed via `SolidEva/android-auto-stub` and `rik-shaw/aa-stubs`,
and recommended in the /e/OS forum. Ours is written from scratch and is
functionally the same manifest, because there is only one way to write it.

Assumed: that Android Auto's test is presence rather than resolution of a
specific component. See [`../README.md`](../README.md#why-a-package-with-nothing-in-it-is-enough)
for the evidence and the limits of it.

## What is deliberately missing, and what breaks as a result

No activity, service, provider or receiver. In particular there is no assistant
service and no `android.intent.action.ASSIST` handler.

The visible consequence: **the assistant does not work in the car.** The
steering-wheel voice button, "Hey Google", read-out-and-reply for incoming
messages, and voice destination entry all depend on the real Google app, and
none of them can be stubbed — they are network services, not a package name.
Android Auto's own microphone button will either be absent or produce nothing.

That is not a defect of this stub. There is no de-Googled implementation of
Google Assistant to point the slot at, and pointing it at a component that
returns nothing would be worse: a dead handler that the system prefers over
whatever the user actually chose. Declaring nothing leaves the assistant slot
empty, which is the honest state of the device.
