# Provenance rules

This project reimplements a protocol that has been reverse-engineered many
times, always under GPL or AGPL. Our licence is Apache-2.0 and the goal is code
that LineageOS and /e/OS could accept. That combination sets hard rules about
where information may come from, and they are worth writing down because the
cost of breaking them is not a bug — it is the project being unmergeable.

## What is legitimate

**Reverse-engineering for interoperability.** Both jurisdictions that matter
protect it explicitly:

- EU Software Directive 2009/24/EC **Article 6** permits decompilation where
  indispensable to obtain information necessary for the interoperability of an
  independently created program. **Article 8** voids contract terms purporting
  to forbid it. **Article 5(3)** separately permits a lawful user to observe,
  study and test to determine underlying ideas.
- US **17 U.S.C. §1201(f)** permits circumvention and the development of
  circumvention means for the sole purpose of achieving interoperability of an
  independently created program.

**Observing traffic on hardware you own.** Capturing what a phone and a head
unit say to each other, on your own phone and your own car, is the textbook
Article 6 case. It is also the highest-value input available, because it
describes the specific head unit we need to interoperate with rather than a
generic specification.

**Dumping firmware from your own head unit.** Same basis, same purpose.

**Reading open-source implementations for protocol facts.** Byte offsets, field
widths, endianness, numeric constants, protobuf field numbers, message
ordering — these are functionally dictated by interoperability and carry no
expressive choice. They can be read, restated and implemented freely.

## What is not

**Copying expression.** A `.proto` file is source code. Copying one from a
GPLv3 project into an Apache-2.0 repository is a licence violation regardless of
the fact that the field numbers inside it are uncopyrightable. So is copying
comments, file structure, class decomposition or architecture.

**Reusing identifier names.** This is the specific trap. Names are creative
choices, not protocol requirements — the proof is that three independent
projects picked three different sets of names for exactly the same fields. A
copied name proves the copier had the source open. A copied *typo* is the
classic smoking gun in litigation, and at least one of these projects has a
memorable one. Every identifier in this repository is ours.

**Extracting or shipping anybody's private key.** §1201(f) protects identifying
and analysing interface elements; a private key is not an interface element, it
is an access credential, and redistributing one looks like trafficking under
§1201(a)(2). EU Article 6(2) separately forbids passing obtained information to
others beyond what interoperability requires. The key material at issue here is
also almost certainly a trade secret. Two of the projects in this space refuse
to host it and say why. They are right.

**Reading Google's leaked Head Unit Integration Guide.** Hosted publicly at
`milek7.pl/.stuff/galdocs/`. Nobody working on this project may open it, and
that includes any agent or contractor working on our behalf.

This last one deserves its reasoning, because there is a tempting and wrong
argument against it. The argument goes: use a two-team clean-room split — one
team reads the guide and writes a neutral specification or a test suite, another
implements only against that. That structure is real and it works, but it solves
a *copyright* problem, and it only works when the first team has a legal right
to read what it reads. This document is confidential material, so the exposure
is trade-secret misappropriation, and a specification or a test suite derived
from it is a transmission channel rather than a firewall. The split would launder
nothing and would taint the output of both teams.

It is also unnecessary. The specification in `docs/01-aap-wire-format.md` was
assembled entirely from open-source implementations read as facts, and the thing
it does not tell us — how a specific head unit treats a certificate — is not in
the guide either. That answer only exists in the car.

## How the specification was produced

`docs/01-aap-wire-format.md` is the handoff boundary. It records wire facts and
nothing else: no copied text, no borrowed names, no reproduced code. Both
implementations in this repository — the Kotlin phone side and the Python
head-unit emulator — were written from it rather than from any third-party
source, and independently of each other, which is why they catch each other's
misreadings.

## Practical rules for contributors

1. Read open-source implementations for facts if you need to. Do not copy from
   them, and do not carry their names across.
2. Never commit a certificate or private key that did not originate in this
   repository's own test PKI.
3. Do not open the leaked integration guide.
4. Prefer a capture from real hardware over any secondary source. It is both
   more legitimate and more accurate.
5. If a fact's only available source is something on this page's forbidden list,
   the honest answer is that we do not know it yet — record it as an open
   question and design so the answer can be measured.
