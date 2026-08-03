# Field test protocols

Everything here needs only: the Fairphone 6, a USB cable, the car, and a
computer with `adb`. No diagnostic tool, no OBD interface, no soldering, no
second device except where noted.

Run them in order. Each one either produces data or rules something out, and
the later ones are hard to interpret without the earlier ones. Record the
outcome of each even when it is boring — "the head unit said nothing" is a
result, and it means something quite different from "the head unit rejected us".

---

## P0 — Does this car do Android Auto at all?

**Why first.** Everything downstream assumes the head unit has App-Connect
enabled. On VW that is a licensed feature written to the unit at a dealer, not
something enabled by coding, and plenty of cars have the hardware without the
licence. If this fails, no amount of software fixes it.

**You need:** any phone with Google's Android Auto installed and working —
borrowed for two minutes is enough.

**Steps**

1. Start the engine, let the head unit boot fully.
2. Plug the phone into the car's data USB port. On a Polo this is the one in the
   centre console, not a charge-only port in the rear.
3. Watch the screen for an Android Auto prompt or an App-Connect menu.

**Record**

- Whether an Android Auto or App-Connect entry appears at all
- Whether it lists CarPlay and MirrorLink alongside it
- The head unit part number from `Menu → Setup → System information`

**What it means**

| Outcome | Meaning |
| --- | --- |
| Android Auto starts | App-Connect is licensed and the port works. Continue. |
| App-Connect menu appears but Android Auto is greyed out | The feature exists but is not licensed. Needs a dealer. |
| Nothing happens | Wrong port, wrong cable, or no App-Connect. Try another cable first — see the note below. |

**The cable matters more than it should.** MIB2 units are notoriously fussy
about USB cables and about re-enumeration timing with modern Android. A cheap
charge-oriented cable will fail in ways that look like protocol problems. Use a
short, known-good data cable, and if a test fails inexplicably, change the cable
before changing anything else.

---

## P1 — Set up the computer

**You need:** a computer, and `adb` from Android platform-tools. Nothing else is
being installed on the phone that cannot be uninstalled.

**Steps**

1. Install platform-tools (`brew install android-platform-tools`,
   `apt install adb`, or the zip from Google).
2. On the Fairphone: `Settings → About phone`, tap the build number seven times
   to enable developer options, then `Settings → System → Developer options →
   USB debugging`.
3. Connect the phone to the computer and accept the debugging prompt.
4. `adb devices` should list it as `device`, not `unauthorized`.

**Note on the cable conflict.** The phone can only be plugged into one thing at
a time. During car tests it is plugged into the car, so `adb` cannot see it. The
protocols below are written around this: the phone records to a file in the car,
and you pull the file afterwards on the computer. Do not plan on watching a live
log while driving.

If you want live logs anyway, `adb tcpip 5555` then `adb connect <phone-ip>:5555`
puts adb on Wi-Fi. It works, but the phone and the computer must share a
network, which in a car park usually means tethering.

---

## P2 — What does the head unit say it is?

**Why.** When a head unit switches a phone into accessory mode it sends six
identifying strings. Our app matches on two of them. Nobody has published what a
MIB2 sends, and if it differs from what we expect, our app will not even be
offered the connection — a failure that looks exactly like a protocol failure
and is not one.

This is the cheapest useful data point in the whole exercise.

**Steps**

1. Get the APK from the [latest release](../../releases/latest) and install it,
   either by opening it on the phone or with `adb install -r openaap-*.apk`.
2. Clear the log: `adb logcat -c`
3. Unplug from the computer, plug into the car, wait ten seconds, unplug.
4. Back at the computer: `adb logcat -d -s openaap.attach openaap.service > attach.txt`

**Record.** The whole of `attach.txt`. The interesting line is either
`head unit attached: <manufacturer>/<model>` or
`ignoring accessory manufacturer='…' model='…' version='…'`.

**What it means**

| Outcome | Meaning |
| --- | --- |
| `head unit attached` | Our filter matches. Continue to P3. |
| `ignoring accessory …` | The head unit identifies itself differently. **Send me those strings** — it is a one-line fix, and it is new information. |
| Nothing at all in the log | The phone never entered accessory mode. Either the head unit did not try, or Android did not route it to us. See below. |
| A system dialog asking which app to open | Tick "always use this app". On /e/OS we should be the only candidate. |

If nothing appears at all, check `adb shell pm list features | grep accessory`
returns `android.hardware.usb.accessory`, and set the phone's USB mode to
charging-only rather than file transfer before plugging into the car — some head
units are confused by a phone that presents itself as storage first.

---

## P3 — The handshake probe: what does the head unit actually check?

**Why.** This is the experiment the project exists to run. The public record
contains no measurement of whether a production head unit verifies the phone's
certificate, and it has been an open question since 2015.

The app ships in probe mode by default. Each connection presents a different
generated identity and records exactly how far the session got and what the head
unit said when it stopped. Nine identities, each varying one thing.

**Steps**

1. Open the app and press **Start again**, or `adb shell pm clear org.openaap.projection`.
2. Plug into the car. Leave it plugged in for about a minute — a head unit that
   rejects a phone usually retries by itself, and each retry advances the matrix.
3. Unplug and replug a few times. Each new connection advances by at least one.
4. Repeat until you have plugged in roughly a dozen times, or until the app says
   every identity has been tried.
5. The app's screen shows each result as it arrives, so you can tell in the car
   whether it is working rather than finding out at home. When it is done, press
   **Share report** and send it to yourself by any means the phone has — the
   cable is in the head unit, so this is easier than waiting until you can plug
   into a computer.

   If you would rather have the files, back at the computer:

   ```
   adb pull /sdcard/Android/data/org.openaap.projection/files/probe-report.txt
   adb pull /sdcard/Android/data/org.openaap.projection/files/probe-records.jsonl
   ```

**Record.** Both files. The report is the summary; the log has a full transcript
per attempt, which is what to send me if anything is surprising.

**What it means.** The report ends with a verdict, but the detail is in the
`stage` and `alert` columns:

| Stage reached | Meaning |
| --- | --- |
| `NO_CONTACT` | The head unit never spoke. A transport problem, not a certificate one. Go back to P2. |
| `VERSION_EXCHANGED` | We agreed a protocol version and then it stopped. Note which version it offered — that is unpublished for MIB2. |
| `HANDSHAKE_IN_PROGRESS` | TLS started and failed. The alert column names the check. |
| `HANDSHAKE_COMPLETE` | TLS succeeded but the head unit refused afterwards. It validated the certificate *as a certificate* and rejected the identity. |
| `AUTHENTICATED` | **The head unit accepted a certificate we generated.** This would be a new result. Stop and tell me. |

| Alert | Meaning |
| --- | --- |
| `unknown_ca` | It checks the chain against a pinned authority. The hard wall, confirmed. |
| `certificate_expired` / `certificate_unknown` | It checks dates. Which of the two date probes failed tells us which direction. |
| `bad_certificate` | It could not parse what we sent. If the v1 probe passes where v3 fails, that is a fixable problem, not a trust one. |
| `handshake_failure` / `insufficient_security` | Cipher suites or key sizes, not identity. Also fixable. |

**Whatever the outcome, the report captures the certificate the head unit
presented to us** — issuer, validity window, key type. That data does not exist
publicly for any MIB2, and it identifies who actually built the projection stack
in these cars.

**Realistic expectation.** The evidence points at `unknown_ca`. That is a
publishable result and it closes an eleven-year-old question. Do not be
disappointed by it.

---

## P4 — Bench test over real USB, no car needed

**Why.** Exercises the actual USB accessory path — the part that has never run
against real hardware — without driving anywhere. If the accessory attach does
not fire here, fix it here; iterating in a car park is miserable.

**You need:** the computer, and Google's Desktop Head Unit, which can act as a
real USB accessory host. Install with `sdkmanager "extras;google;auto"` from the
Android SDK.

**Steps**

1. Install the app on the phone and plug the phone into the computer.
2. Run the desktop head unit as a USB host: `./desktop-head-unit --usb`
3. Watch: `adb logcat -s openaap.attach openaap.service openaap.probe`

**What it means.** The desktop head unit is not a MIB2 and will not answer the
certificate question — it is Google's own software and behaves like Google's
own software. What it does tell you is whether our accessory handling, framing
and version exchange are correct, which makes any later failure in the car a
fact about the car.

**This is the highest-value test you can run before going to the car.** Do it
first if you have the SDK.

---

## Not yet available

Two more protocols are planned and their pieces are still being written. Listed
here so the numbering does not shift later, and so it is clear what is not
being claimed.

**P5 — bench test against our own head-unit emulator.** The emulator's framing
and TLS layers exist and are tested; its session driver is in progress. When it
lands, this becomes a full end-to-end test with no car and no Google software,
including a switch to make the emulated head unit strict or lenient about
certificates — which is how we check that the probe reports what we think it
reports.

**P6 — Android Auto on /e/OS using Google's app.** The pragmatic path: /e/OS 3.3
and later fixed the Android 15 regression, three stub packages satisfy the
presence checks for the Google app, Maps and text-to-speech, and the remaining
blocker is that Android Auto checks it was preinstalled. The packaging work for
this is in progress. When it lands it also serves as a stronger version of P0:
if Google's app projects onto the car, the car is definitely capable and any
failure of ours is ours.

---

## What to send back

For each protocol you run:

- which protocol, and the date
- the head unit part number and the software version from its system information
  screen
- the files each protocol asks you to pull
- anything the car displayed on screen, including error text, verbatim

The single most valuable artefacts are `probe-report.txt` from P3 and the
accessory strings from P2. Everything else is context.

## A note on what can go wrong

Nothing in these protocols writes to the car, changes its configuration, or
sends it anything other than a projection session it is designed to accept and
free to refuse. The worst realistic outcome is that the head unit ignores the
phone, or shows a connection error and goes back to its own menu. If it does
become confused, an ignition cycle resets it.
