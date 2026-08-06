# The trust model, and the one question nobody has answered

This is the document that decides whether the project succeeds. Everything else
is engineering with known answers.

## Answered, on 6 August 2026

**A production VW MIB2 accepted all nine generated identities, including an
expired one.** Measured with the probe in this repository, on a Fairphone 6
running /e/OS, in a 2017 Polo. The raw records are in
[`testdata/field/`](../testdata/field/).

| Identity | What it varies | Result |
| --- | --- | --- |
| `self-signed-v1` | the structure real endpoints use | AUTHENTICATED |
| `self-signed-v3` | v3 with extensions | AUTHENTICATED |
| `own-ca-v1` | a real two-level chain to our own CA | AUTHENTICATED |
| `authority-name-match` | authority pinned by name rather than key | AUTHENTICATED |
| `expired` | **validity window already past** | AUTHENTICATED |
| `not-yet-valid` | validity window not yet begun | AUTHENTICATED |
| `long-validity` | decades, as real certificates use | AUTHENTICATED |
| `rsa-4096` | key size above the usual 2048 | AUTHENTICATED |
| `ec-p256` | elliptic curve rather than RSA | AUTHENTICATED |

The finding does not rest on decoding the head unit's verdict, and that matters,
because the verdict body was empty in all nine runs. It rests on something
harder to misread: **the head unit's bytes never changed.**

```
                        r1 in   r1 out   r2 in   r2 out
self-signed-v1            517     1141     126       51
self-signed-v3            517     1243     126       51
own-ca-v1                 517     1913     126       51
authority-name-match      517     2105     126       51
expired                   517     1142     126       51
ec-p256                   517      560     126       51
```

Our outbound flight ranges from 560 to 2105 bytes, tracking the size and type of
each certificate — so the identities really did differ and really were sent. The
head unit's `ClientHello` is 517 bytes every time and its second flight is 126
bytes every time. Nine different certificates, one of them expired, one signed
by an authority we invented, produced **byte-identical** responses. There is no
certificate-dependent branch in this head unit at this stage of the session.

Two further facts fall out of the same data:

- **No mutual TLS.** We set `wantClientAuth`, so a `CertificateRequest` went out
  in every run. 126 bytes is `ClientKeyExchange` + `ChangeCipherSpec` +
  `Finished` and leaves no room for a certificate. The head unit never sent one.
- **A real TLS stack, not a stub.** The elliptic-curve identity negotiated
  `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256` while the rest got
  `TLS_ECDHE_RSA_…`. It adapts correctly to what it is offered. It simply does
  not judge it.

### What this does not establish

**That the session is usable.** The probe stops at the verdict by design.
Nothing here shows the head unit will then answer a discovery request, open
channels, or display a frame. That is the next measurement, and it is what
projection mode exists to take.

**Anything about head units in general.** This is one unit in one car. "MIB2
does not validate" is not supported by n=1; "this MIB2 did not validate" is.
The 2021 report below, from a 2019 Seat Ateca in the same corporate family,
says the opposite — and both can be true of different units, different model
years, or different firmware.

**That the empty verdict body means `RESULT_OK`.** It is read as acceptance
because the message id is itself the signal and the `result` field is optional.
An implementation that meant something else by it would be indistinguishable
here. The byte-level invariance above is the claim that survives either reading.

The rest of this document was written before that measurement. It is kept as
written, because the reasoning that led to a wrong prediction is worth more than
a document quietly edited to have been right. The prediction was `unknown_ca`.

## The short version

Android Auto protects its session with mutual TLS in which **the phone is the
server** and presents the certificate under scrutiny. Every certificate ever
observed at either end of a real session — head unit or phone — chains to a
single self-signed root: `C=US, ST=California, L=Mountain View, O=Google
Automotive Link`. RSA-2048, SHA-1 self-signature, valid to 2044. It is a flat
two-level PKI: the root signs leaves directly. There is no intermediate, no
cross-signing, no enrolment endpoint, and no certificate-signing-request flow
anywhere in the protocol.

The private key is Google's. There is no path to a valid leaf that does not go
through Google, and Google's path is an OEM licensing programme.

So the phone side of Android Auto has exactly one unsolved problem, and it is
not a protocol problem.

## What is actually known, and how well

**Certain.** The root exists, its subject is as quoted, and every leaf in public
circulation verifies against it. The leaves are RSA-2048, **X.509 version 1** —
no extensions, no subject alternative name, no key usage — with `notBefore`
dates clustered in July 2014, which is when Android Auto launched. That
structure is unusual enough in 2026 that modern TLS libraries reject it: rustls
and webpki refuse v1 client certificates outright, and at least two projects
have had to work around it.

**Certain.** Every open-source head-unit implementation disables peer
verification outright. They therefore tell us **nothing** about what production
hardware does. Any argument of the form "openauto accepts anything, so head
units accept anything" is worthless.

**Strong single-source evidence, pointing the wrong way.** The author of the only
real phone-side implementation reported, in 2021, that his head unit validated
both the certificate's root authority and its validity dates. His car was a 2019
Seat Ateca — VW Group, LG-built head unit, the same corporate family and
projection stack generation as the 2017 Polo this project targets. His
workaround was to set the car's clock back, which only helps if the certificate
is otherwise Google-signed and merely expired. That is one report, about a
different model year, never reproduced, and never broken down into which check
failed.

**Corroborating, indirect.** Google's head-unit documentation requires units to
obtain accurate time from GPS, the mobile network or NTP, and describes
`Certificate not yet valid` and `Certificate expired` as authentication failure
modes. A head unit that ignored certificate validity would not need a correct
clock. That is Google telling manufacturers to validate.

**Corroborating, from the mirror image.** The phone's validation of the *head
unit* is visible in the field: `/e/OS` users see "Communication error 8 — Your
car's software didn't pass Android Auto security checks. Make sure the car's
date and time are set correctly." Google's app has a dedicated user-facing
string for a car whose certificate failed. The design intent is symmetric.

**The one crack.** A widely used proxy runs in a mode that terminates TLS toward
a real head unit using the leaked phone-side certificate — which expired in
August 2022. People report using it. Either many head units do not check expiry,
or those users are sourcing a newer credential privately. This could not be
resolved from public sources, because the maintainers deliberately keep
certificate guidance off their issue tracker.

**Unknown, and this is the point.** In roughly eleven years of community
reverse-engineering, with strong incentive and many capable people, **nobody has
published a single instance of a production head unit accepting a phone
certificate that Google did not sign.** Nobody has published the opposite
either. The experiment has never been run and reported.

## Why we can run it and they could not

Every prior attempt got a Google certificate first, because that was the fastest
route to a working session, and then had no reason to ask what would have
happened without one. Starting from the constraint of never touching Google's
key material inverts the incentive: the only way forward is to find out exactly
what the head unit checks.

That reframes an unanswerable question — "would a certificate we are allowed to
generate be accepted?" — into a measurement. The credential the session presents
is a pluggable input (`CredentialProvider`), and the probe matrix presents a
series of identities that differ in one dimension each.

## The probe matrix

TLS fails with an alert, and the alert names the check that failed. That is why
this is worth doing properly rather than trying one self-signed certificate:
"it didn't connect" cannot distinguish a chain check from a date check from a
parser that choked on a structure it has never seen.

| Probe | Varies | What acceptance would mean |
| --- | --- | --- |
| `self-signed-v1` | baseline, the structure real endpoints use | no authority is checked at all; a clean-room phone side works today |
| `self-signed-v3` | certificate structure | if v1 passes and v3 fails, the obstacle is the parser, not the trust policy — and we simply comply |
| `own-ca-v1` | a real two-level chain | the unit wants a chain but does not check where it terminates |
| `authority-name-match` | issuer name matches the Google root, signed by our key | the unit compares names without verifying signatures — a serious flaw, and an open door |
| `expired` | validity, already past | dates are not checked |
| `not-yet-valid` | validity, starts in future | separates a date check from an expiry-only check, and detects a head unit whose clock is behind ours |
| `long-validity` | decades, as real certificates use | rules out a short-validity heuristic |
| `rsa-4096` | key size | detects a hard key-size ceiling in an old embedded stack |
| `ec-p256` | key algorithm | if EC fails where RSA passes, the constraint is cipher suites, not identity |

Alerts worth telling apart:

| Alert | Meaning for us |
| --- | --- |
| `unknown_ca` (48) | the chain does not terminate in a trusted authority — the hard wall |
| `certificate_expired` (45) | dates are checked, and the head unit's clock matters |
| `bad_certificate` (42) | structurally rejected or unparseable — possibly fixable |
| `handshake_failure` (40) | often cipher suites rather than certificates; retry with a narrowed suite list |
| `insufficient_security` (71) | our key sizes or suites were refused |
| `certificate_unknown` (46) | path build failed for an unstated reason |

Note that the alert code for the same condition differs by implementation: the
JDK reports a failed path build as `certificate_unknown` where OpenSSL, which is
what head units run, reports `unknown_ca`. The matrix's own tests are calibrated
against head units whose behaviour we control, so that a reading taken in a car
park can be trusted to mean what it says.

**Deliberately not in the matrix:** the leaked phone-side certificate. It would
give a cleaner answer and it would poison the project.

## What the head unit tells us about itself

The probe also captures the certificate the head unit presents to *us*. That
data does not exist publicly for any MIB2: its issuer, its validity window, and
the subject naming would identify who actually built the unit — the projection
stack in these cars is subcontracted, and the certificate says which
subcontractor. It costs nothing to collect and may be the most durable thing
this exercise produces.

## The second source of truth, which is better than any of this

The target head unit runs QNX and can be opened up without removing it from the
car. Developer mode is set through the diagnostic interface on module 5F, after
which the engineering menu can enable Ethernet on the projection USB port; the
unit then answers on 192.168.1.4 with FTP and an unauthenticated telnet root
shell. The projection stack is a set of sibling processes — one supervisor over
separate Android Auto, CarPlay and MirrorLink clients — and the Android Auto
binary can simply be copied off.

That binary settles the question directly: whether it carries a trust anchor,
and what it validates. It is the user's own vehicle, and the purpose is
interoperability, so this is squarely within what both EU Article 6 and
§1201(f) protect.

**Do this before spending more engineering time on the certificate question.**
Two hours with a diagnostic tool beats any amount of inference.

## What each outcome means

**The head unit validates the chain against a pinned Google root.** The expected
result. A clean-room phone side cannot connect, full stop, and no amount of
protocol work changes that. The project then delivers: a complete, documented,
independently-implemented AAP stack; a head-unit emulator; the first published
measurement of where the wall is; and a working fallback path for /e/OS users.
That is a real contribution, and it closes a question that has been open since
2015.

**The head unit checks dates but not the chain.** We generate a long-validity
certificate and it works. This would be the single most valuable result in the
space.

**The head unit rejects our structure rather than our identity.** A `v3`-only
or key-size failure. We comply and try again.

**The head unit checks nothing.** A clean-room phone side works today. Publish
immediately.

← **This is the one that happened.** See the measurement at the top of this
document. Written when it was thought to be the least likely of the four.

## What this changes about the design

Nothing, which is the point of having found out early. The credential is one
pluggable object behind one interface. Every other layer — framing, TLS
transport, session state machine, discovery, channels, media — is identical
under all four outcomes, and all of it is needed to run the experiment in the
first place. There is no version of this project where the protocol work is
wasted.

### After the measurement

Still nothing, and that is worth stating plainly rather than treating as luck.
The generated identity in `ProjectionService.credentials()` was written as the
one place a differently-provisioned credential could be swapped in, on the
assumption it would eventually have to be. It does not have to be, on this car.
It stays pluggable anyway, because the next car may not agree.

What the result *does* change is which question is worth spending time on. The
trust wall was the project's single point of failure and it is not there. The
remaining unknowns are ordinary engineering with checkable answers: whether
discovery and channel setup follow the verdict, whether a head unit of this
generation decodes what our encoder produces, and how the session behaves over
a drive rather than a minute in a car park.
