# The trust model, and the one question nobody has answered

This is the document that decides whether the project succeeds. Everything else
is engineering with known answers.

## Retracted, on 6 August 2026

**An earlier version of this document claimed that a production VW MIB2
accepted all nine generated identities. That claim was wrong, and the error was
ours.** It is retracted in full. The corrected reading is below; the retracted
text is preserved in the git history of this file rather than deleted, because a
project whose method is "stop guessing" cannot quietly tidy away the guess it
got wrong.

### What the head unit actually said

Every session, including all nine probe runs, ended with the head unit sending
message `0x0004` with this body:

```
08 fd ff ff ff ff ff ff ff ff 01
```

That is field 1, wire type 0, varint — and the varint is the sign-extended
64-bit encoding of **-3**. The head unit answered every generated certificate
with `status = -3`, then tore down USB accessory mode a few seconds later. It
was refusing us the whole time.

### Why we read a refusal as an acceptance

Our schema declares that field as a proto2 enum with members `RESULT_OK = 0` and
`RESULT_FAILED = 1`. **Proto2 stores an out-of-range enum value as an unknown
field**: the bytes survive on the wire, but the generated `hasResult()` reports
false, exactly as it would for a field the peer never sent. The code then read
absent as "nothing to object to" and defaulted to OK.

So the report said "empty verdict body" about eleven bytes that contained an
explicit rejection, and the on-screen result said `AUTHENTICATED` nine times.

A closed enum cannot represent what a peer actually said. In a protocol
reconstructed from observation, values outside our enumeration are not an edge
case — they are the ordinary way of finding out the enumeration is incomplete.
The verdict is now read as a raw varint (`AuthVerdict`), and *absent* is kept
distinct from *zero*, because one is a head unit that said nothing and the other
is one that said OK. `UnknownEnumTest` pins the behaviour with the exact eleven
bytes the car sent.

### What survives, and what does not

**Does not survive:** any claim that this head unit accepts generated
certificates, that it skips date checks, or that it validates nothing. All of
that rested on reading `-3` as `0`.

**Survives, because it never depended on the verdict:** the head unit completes
a TLS 1.2 handshake with a certificate we generated, negotiating
`TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` and adapting to `ECDHE_ECDSA` for an
elliptic-curve identity. It speaks AAP protocol 1.0 and identifies over USB as
`Android / Android Auto v1.0`. Its two inbound handshake flights are 517 and 126
bytes regardless of what we present.

**Also retracted, on the same day and for the same kind of reason:** the claim
that the head unit "presents no certificate of its own despite our
`CertificateRequest`". It presents none, but there was no request. `AapTlsEngine`
set `wantClientAuth = true` and then `needClientAuth = false`, and those are two
setters for one field in JSSE — the second cleared the first. Every session this
project has run, including all nine credential probes, asked the head unit for
nothing. A peer that sends no certificate when none was requested is behaving
correctly and tells us nothing.

The pattern is the same as the `-3` error and worth naming as such: a fact was
recorded about what the code was believed to do rather than about what crossed
the wire. It was caught by a test that made a peer which certainly had a
certificate refuse to send one — the calibration step, again, doing the work
that reading the code did not.

**Newly established:** the refusal code is `-3`, and it is identical across nine
certificates that varied structure, chain depth, validity window, key size and
key algorithm. That invariance now reads the other way round: the head unit's
answer does not depend on the certificate *because it refuses all of them the
same way*. Whether it would accept a Google-signed one is untested — we have
never had one to present.

**Still unknown, and the next thing to measure:** what `-3` means. See
[The status matrix](#the-status-matrix) below. Until that is settled, `-3`
supports no conclusion about certificates at all.

### The methodological point, kept deliberately

The retracted claim was hedged in the right places and still wrong. It said the
finding did not rest on decoding the verdict but on the head unit's bytes never
changing. That argument was sound as far as it went and did not go far enough:
byte-invariance distinguishes "the certificate is not what decides" from
nothing else. It cannot tell *accepts everything* from *refuses everything*, and
the text asserted it could.

The lesson is not "hedge harder". It is that a decoder which cannot represent
what the peer said will quietly substitute something it can, and no amount of
care in the surrounding prose detects that. What detected it was dumping the raw
bytes and decoding them without reference to our own schema.

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

## The status matrix

The certificate matrix has been run and it produced one number, nine times. That
number is the whole result, and nobody knows what it says.

`-3` has exactly two readings, and the evidence in hand cannot separate them:

- **specific** — "your certificate is not one I trust". Then the number locates
  the wall precisely, and nine identical answers mean the wall is above all nine
  identities.
- **generic** — "something went wrong". Then the number locates nothing, and
  nine identical answers are nine readings of an uninformative constant.

Presenting a tenth certificate cannot tell these apart, because both readings
predict the same answer to it. What can is breaking the session in a way that
has nothing to do with the certificate and seeing whether the number moves.

### What is reachable, and what is not

The verdict arrives the instant the TLS handshake settles, with no message from
the phone in between. **Only three things can reach it:** the version exchange,
the handshake, and the certificate inside the handshake. That single fact
disqualifies the two experiments that suggest themselves first — sending a
malformed message after TLS, and sending nothing after TLS — because by then the
head unit has already sent the number. The malformed-message idea survives by
being moved to the version response, which is the last message the phone
controls before the verdict; the say-nothing idea has already been run in the
projection variant matrix and ended in the same teardown.

Cipher-suite variants are excluded on their own merits. The phone is the TLS
server and therefore chooses the suite out of what the head unit offered: a
suite it did not offer cannot be selected, and one it did offer it can hardly
object to afterwards. The variant either degenerates into "no handshake" or asks
the car whether it dislikes something it volunteered.

### The matrix

| Step | Varies | What a *different* code would prove |
| --- | --- | --- |
| `baseline` | nothing, except that the certificate request is now really sent | reference: re-establishes -3 with the corrected decoder |
| `no-peer-cert-request` | we do not ask for a certificate — what all nine earlier runs actually did | -3 was the car reporting *its own* failure to authenticate, not a verdict on ours |
| `invite-car-certificate` | we name the authority the car's certificate chains to as one we accept | the car's silence about its own identity was our doing — and we come home with its certificate either way |
| `version-status-mismatch` | the version response says the versions disagree | a verdict here is reached with no certificate in evidence, so -3 cannot mean "certificate refused" |
| `no-certificate` | we present none, so TLS cannot complete | whether the verdict requires a completed handshake at all |
| `version-major-mismatch` | we announce a major version the car cannot speak | whether the car compares versions itself or trusts our status word |
| `version-truncated` | four bytes where six are defined | separates "could not understand you" from "understood and refused you" |

Run in that order. A visit to a car ends when it ends, and the rows whose result
would most change what we believe are the ones at the top.

An eighth row was designed and then measured out of existence. A certificate
with an **empty subject** would ask whether the head unit's rejection comes from
its parser or its policy — and the platform will not parse one back:
`Empty subject DN not allowed in v1 certificate`. It is permitted on v3 only
alongside a critical subject alternative name, which varies the structure and
the name together and could attribute neither. `TestPkiTest` pins the constraint
so the idea is not designed a second time.

### The limit of the whole exercise, stated plainly

**There is no positive control.** Producing a session this head unit accepts
needs a certificate signed by Google, which this project will not obtain. So the
code for success cannot be measured, `0` remains an assumption read off our own
schema, and every result here is a comparison *between kinds of failure*. If the
number never moves, that is a real finding about `-3` — it is a general failure
indicator and says nothing about the trust wall — but it is not, and cannot be
turned into, evidence that the wall is somewhere else.

That limit is also the argument for the QNX route below. Two hours with a
diagnostic tool reads the meaning of `-3` out of the binary that produces it,
and no number of connections in a car park can do the same.

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
