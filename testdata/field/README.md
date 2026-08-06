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
| Result | **9 of 9 identities authenticated, including an expired certificate** |

- [`2026-08-06-vw-polo-mib2-records.jsonl`](2026-08-06-vw-polo-mib2-records.jsonl)
  — one JSON object per attempt, with the full handshake transcript
- [`2026-08-06-vw-polo-mib2-report.txt`](2026-08-06-vw-polo-mib2-report.txt)
  — the rendered report as shared from the phone

### Reading it

The load-bearing detail is not the `AUTHENTICATED` verdict — the head unit sent
an empty verdict body every time, so that reading is an inference. It is the
byte counts in the transcripts. Our outbound flight varies from 560 to 2105
bytes with the certificate being presented; the head unit's two inbound flights
are 517 and 126 bytes in every single run. Nine different certificates, one
expired and one signed by an authority we invented, produced byte-identical
responses.

### An earlier run, deliberately not archived

The first run on the same car reported four `NO_CONTACT` rows in alternation.
Those were an artefact of this app, not of the car: a head unit re-attaches the
accessory when a session ends, the re-attach arrived before it was ready to
talk, and the probe advanced the matrix regardless of whether anything had been
said. Fixed in `681e690`. The run is not kept here because publishing a
measurement known to be an artefact, alongside one that is not, invites someone
to cite the wrong one.
