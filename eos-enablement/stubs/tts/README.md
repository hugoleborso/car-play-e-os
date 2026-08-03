# `com.google.android.tts` — presence stub

## The check this satisfies

Android Auto will not complete setup unless a package named
`com.google.android.tts` is installed — "Speech Recognition & Synthesis",
formerly "Speech Services by Google". It is step 3 of 4 in
[/e/OS's installation instructions](https://doc.e.foundation/os/how-to/android-auto).

This package occupies that name. It contains no code, declares no components,
and requests no permissions.

## Why a stub suffices

Verified: `SolidHal/SpeechServices-Package-Spoof` (MIT) is a manifest naming
`com.google.android.tts` around an empty `<application>` element, nothing more
([source](https://github.com/SolidHal/SpeechServices-Package-Spoof/blob/master/app/src/main/AndroidManifest.xml)),
and it is what the /e/OS community has been installing. The April 2026 feature
proposal reports "no issues with TTS either; it just says the service isn't
available"
([forum thread](https://community.e.foundation/t/feature-proposal-add-fake-dependency-for-android-auto-in-native-build-e-os/80862)).

Assumed: presence rather than component resolution, same evidence as the other
two — see [`../README.md`](../README.md#why-a-package-with-nothing-in-it-is-enough).

## What is deliberately missing — read this one before changing anything

No `<service>` with an `android.intent.action.TTS_SERVICE` intent filter, and
no `android.speech.RecognitionService`.

This is the stub where a well-meaning addition does real damage. Android
discovers text-to-speech engines by scanning for that first intent filter. A
stub that declared one would:

- appear in `Settings → Accessibility → Text-to-speech output` as a selectable
  engine, sitting next to the device's real one;
- be picked by any app that asks for the Google engine by package name;
- and then produce silence — breaking TalkBack, turn-by-turn voice prompts and
  message read-out **across the whole device**, not only in the car.

The same argument applies to speech recognition: /e/OS ships an offline
voice-to-text engine, and a stub `RecognitionService` would shadow it.

So the correct behaviour of this package is that the TTS subsystem never sees
it. The package exists for Android Auto's check; the device's real engine
(eSpeak, or /e/OS's offline engine) keeps the job; and inside Android Auto,
anything that would have used Google's cloud voices reports the service as
unavailable. That last part is a real functional loss and it is not fixable by
packaging — it is a hosted service.
