# Field measurements

Raw output from real head units, unedited. These are the primary evidence behind
the claims in [the trust model](../../docs/03-trust-model.md), kept here so that
anyone can check the reasoning against the data rather than against a summary.

Nothing in this directory is used by the build or the tests. It is a record.

## 2026-08-06 — Volkswagen Polo (2017), MIB2

| | |
| --- | --- |
| Phone | Fairphone 6, /e/OS, Android 16 (API 36), build `BP2A.250805.005` |
| Head unit | VW MIB2, wired App-Connect, USB accessory `Android / Android Auto v1.0` |
| Protocol | AAP 1.0 |
| TLS | 1.2, `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` (`ECDSA` variant for the EC identity) |
| Result | **9 of 9 identities refused with `status = -3`** — see the retraction below |

- [`2026-08-06-vw-polo-mib2-records.jsonl`](2026-08-06-vw-polo-mib2-records.jsonl)
  — one JSON object per attempt, with the full handshake transcript
- [`2026-08-06-vw-polo-mib2-report.txt`](2026-08-06-vw-polo-mib2-report.txt)
  — the rendered report as shared from the phone

### Retraction

**These files say `AUTHENTICATED` nine times. That was our decoder being wrong,
not the car accepting anything.**

The head unit answered every run with message `0x0004` carrying
`08 fd ff ff ff ff ff ff ff ff 01` — field 1, varint, **-3**. Our schema
declares that field as a proto2 enum with members 0 and 1, and proto2 reports an
out-of-range enum value as an *absent* field; the code then read absent as no
objection. The car was refusing us the whole time.

The files are kept exactly as the phone produced them. Correcting a raw capture
after the fact would destroy the only thing it is good for. See
[the trust model](../../docs/03-trust-model.md) for the full retraction, and
`UnknownEnumTest` for the eleven bytes pinned as a test.

### What is still worth reading in them

The byte counts. Our outbound flight varies from 560 to 2105 bytes with the
certificate presented; the head unit's two inbound flights are 517 and 126 bytes
in every single run. That invariance is real — it just means the car refuses
every certificate identically, rather than accepting them.

### An earlier run, deliberately not archived

The first run on the same car reported four `NO_CONTACT` rows in alternation.
Those were an artefact of this app, not of the car: a head unit re-attaches the
accessory when a session ends, the re-attach arrived before it was ready to
talk, and the probe advanced the matrix regardless of whether anything had been
said. Fixed in `681e690`. The run is not kept here because publishing a
measurement known to be an artefact, alongside one that is not, invites someone
to cite the wrong one.
