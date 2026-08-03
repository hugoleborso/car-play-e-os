# Upstreaming the /e/OS enablement work

What to propose, to whom, in what order, and an honest estimate of what would
be taken and what would not.

The engineering in [`eos-enablement/`](../eos-enablement/) is about four hours
of work. Getting it into a shipped OS is the hard part, and it is mostly not an
engineering problem.

## Where this actually stands

Two things are open at e Foundation and both have been open a long time.

**[e/backlog#9118](https://gitlab.e.foundation/e/backlog/-/issues/9118) —
"Android Auto - Official Support for Google apps stubs."** Opened 4 January
2026 by a community member, labelled `microG::Android Auto` and
`type::User story`, three upvotes, **no assignee, no milestone, still open.**
It asks for exactly one thing: that /e/OS offer an official way to install
stubs for the Google app, Google Maps and Google speech services instead of the
real ones. The discussion on it is entirely between community members. The one
e Foundation account that appears did so to add labels.

**The community feature proposal of
[2 April 2026](https://community.e.foundation/t/feature-proposal-add-fake-dependency-for-android-auto-in-native-build-e-os/80862).**
A Fairphone user found a third-party ROM's fake dependencies, confirmed they
work, published the decompiled sources, and asked for them to be integrated
after "a proper audit of the three dependencies". Two weeks later: *"Hello! No
reaction at all?"* The only advice she received was to go and open a GitLab
ticket, which is how the community found out #9118 already existed. As of
August 2026 no e Foundation developer has replied to either.

That is the gap. It is not a technical gap — the technique has worked since
2020 — it is that nobody has handed e Foundation something they can merge.

**And note what they have already done.** /e/OS solved the harder half by
itself. Since 3.3 (December 2025) it ships a preinstalled Android Auto
placeholder, which is what defeats error 22, gated on `PLATFORM_SDK_VERSION > 34`
(commit `63cf352f` in `e/os/android_prebuilts_prebuiltapks_lfs`, closing the
practical part of [e/backlog#8843](https://gitlab.e.foundation/e/backlog/-/issues/8843)).
They are not indifferent to Android Auto. They have done the part that requires
a decision only they can make, and left undone the part anyone could contribute.

## The proposal, in four steps

Ordered by ascending ask. Each is independently useful, so a rejection at step
*n* does not waste steps 1 to *n*−1. Do not open with step 4.

### Step 1 — Fix the documentation. Cost: nothing. Should be uncontroversial.

[doc.e.foundation/os/how-to/android-auto](https://doc.e.foundation/os/how-to/android-auto)
still instructs users to install the real Google app, Google Maps and Google
speech services, and still carries the line *"We are actively exploring
solutions to eliminate these dependencies."* It does not mention that /e/OS now
preinstalls a placeholder, that the preinstalled entry is a stub which must be
updated from a store before it does anything, or that App Lounge sometimes does
not offer the update. Two participants in #9118 have already noted the docs are
out of date; one asked a staff member directly in February 2026.

The forum is full of people failing at exactly the steps the documentation
omits. A documentation merge request that fixes this is small, obviously
correct, verifiable against a device, and establishes that you are someone who
tests things. It is also, on its own, worth more to users this month than
everything else in this document.

Do this first. It also has a side benefit: writing it forces you to run
[P6](09-android-auto-on-eos.md) properly, and P6's output is the evidence base
for steps 2 to 4.

### Step 2 — Publish the stubs as buildable sources. Cost: a repository.

The three stubs, Apache-2.0, with the build script and the READMEs, as a
standalone repository. Then point #9118 at it.

This is the actual contribution, and its value is not the XML. It is that
today, everything in this space is prebuilt APKs of unclear provenance:
`rik-shaw/aa-stubs` has **no LICENSE file**, `sn-00-x/aa4mg` has none either,
and the April 2026 proposal links to a SourceForge ROM drop and a personal
cloud share. No OS vendor can ship that, and it would be irresponsible of e
Foundation to try. Forty lines of readable XML per package, with a build recipe
that needs only the Android SDK, converts "please audit these three APKs from a
stranger" into "please read three manifests". That is the whole point of the
exercise.

Say plainly what the stubs are: 8.5 KB, no code, no components, no permissions,
one manifest each.

### Step 3 — Offer them through App Lounge. Cost: an e Foundation decision.

This is what #9118 literally asks for: *"The user can use App Lounge to install
the necessary stubs in /e/OS."* It is the right shape, for a reason the ticket
does not spell out.

**A user-installed stub is reversible; a ROM-installed stub is not.**
`PackageManagerServiceUtils.verifySignatures` refuses an update whose signature
does not match the installed package's, with no exception for system packages,
and a system package cannot be uninstalled — only disabled, which does not free
the package name. So a Maps stub in the system image means that device can
never run real Google Maps again without a new ROM build. Some /e/OS users
would consider that a feature. Some of them have a work phone, a hire car, or a
family member, and would consider it a trap. Offering the stubs as installable
apps sidesteps the entire argument.

### Step 4 — A build-time flag in the ROM. Cost: a Soong review.

[`eos-enablement/packaging/product.mk`](../eos-enablement/packaging/product.mk)
plus the three `Android.bp` files, defaulting to **off**, so that ROM builders
who want the stubs baked in can have them and nobody gets them by surprise.

Propose this after step 3 has been accepted or refused, not alongside it. And
do not propose making it default-on. The irreversibility argument above is a
good reason not to, it is the first thing a careful reviewer will raise, and
being the person who raised it yourself is worth more than the feature.

### Explicitly not proposed

**The Android Auto placeholder itself.** /e/OS already ships one. Replacing it
would require supplying a Google-signed binary, which this project does not do,
and the choice of *which* Google build to freeze into every device is
e Foundation's alone. See
[`eos-enablement/packaging/gearhead-slot/README.md`](../eos-enablement/packaging/gearhead-slot/README.md).

One thing there **is** worth raising, though, and it is a bug report rather
than a feature: their placeholder module moved from `presigned: true` to
`certificate: "platform"` in October 2025, and by the AOSP signature rule that
should make the real Android Auto uninstallable on top of it. Either their tree
has an accommodation that is not visible from outside, or something subtler is
happening. P6.1 answers it with one `dumpsys`. A precise, reproducible question
about their own tree, with the AOSP citation attached, is a good way to be
taken seriously — and if it turns out to be a real defect, it is a considerably
more valuable contribution than three stub manifests.

## Gerrit and GitLab are not the same process

[docs/04-android-integration.md](04-android-integration.md) sets out the
general rule for this project: LineageOS via Gerrit first, /e/OS by rebase,
because e Foundation asks contributors to go upstream where possible and
because an /e/OS-only patch means maintaining a fork forever.

**That rule does not apply to this particular work**, and the reasoning is
worth being explicit about, because ignoring project convention needs a better
excuse than convenience.

| | LineageOS | /e/OS |
| --- | --- | --- |
| Mechanism | Gerrit, `review.lineageos.org`, `repo upload` | GitLab merge requests, `gitlab.e.foundation` |
| Unit of review | one commit, amended into patchsets; history stays linear | a branch of commits, merged |
| Iteration | amend and re-push the same Change-Id; reviewers diff patchsets | push more commits; reviewers read the diff |
| Merge gate | +2 from a maintainer, plus +1 verified | maintainer approval |
| Ships microG | no | yes |
| Ships an Android Auto placeholder | no | yes, since 3.3 |
| Has an open ticket asking for this | no | yes, #9118 |

The practical differences that catch people: Gerrit requires a `Change-Id`
footer, generated by the commit hook, and rejects pushes without one; it wants
one logical change per commit and will make you rebase rather than merge; and
the review is on a patchset, so "I pushed a fix" means "I amended and
re-uploaded", not "here is another commit".

**Why /e/OS is the right target here anyway.** LineageOS proper ships no microG
and no Google compatibility layer of any kind; a de-Googled user who wants
Android Auto on LineageOS is expected to use LineageOS for microG or Magisk.
Proposing three packages that impersonate Google package names into
`packages/apps/` of a project whose entire position is that Google's software
is the user's problem is not a promising opening, and it would land on
maintainers who have no Android Auto placeholder to pair it with, so the patch
would do nothing on their ROM. The correct upstream for a microG-shaped problem
is a microG-shipping OS.

Which means being honest about the cost: this is an /e/OS-specific
contribution, and it will need maintaining against /e/OS. iodéOS, which already
integrates the same trick, is the obvious second consumer if anyone wants it.

## What would be accepted, and what would not

Best estimate. The confident rows are confident because there is evidence for
them in the tickets; the rest is judgement.

| Proposal | Likely outcome | Why |
| --- | --- | --- |
| Documentation update to match /e/OS 3.3+ behaviour | **Accepted** | Two people in #9118 have already said the docs are wrong; it costs nothing and helps immediately. |
| Apache-2.0 stub sources in a public repository | **Accepted as a reference**, and that alone unblocks the rest | It is strictly better than what #9118 currently links to, and it is not a request for anyone's time. |
| Stubs offered through App Lounge | **Plausible, slow** | It is what the ticket asks for, but App Lounge is a curated store and adding three packages that impersonate Google package names is a product and legal decision, not an engineering one. Expect it to sit. |
| Build-time flag, default off | **Plausible** | Small, self-contained, reversible, matches how they already gate the placeholder on `PLATFORM_SDK_VERSION`. Main risk is that nobody is assigned to review it. |
| Stubs preinstalled by default | **Rejected, and should be** | Permanently occupies three package names on every device. There is no undo. |
| Anything requiring a Google-signed binary from us | **Rejected** | Not ours to distribute. Already solved on their side. |
| A framework patch to make Android Auto show sideloaded apps | **Rejected** | It is the thing users actually want, and it means patching package-manager trust behaviour to defeat a check in a third-party app. That is a large, invasive change with a security story nobody wants to own. Worth naming so that nobody spends a month on it. |
| A precise bug report about the placeholder's signing | **Accepted, and welcomed** | It is about their code, it is reproducible, and it comes with an AOSP citation. |

The pattern: **things that reduce someone's work get taken, things that add to
it wait.** #9118 has been open since January with three upvotes and no
assignee, not because e Foundation disagrees, but because nobody has put a
mergeable object in front of them. Steps 1 and 2 exist to change that.

## The order to actually do this in

1. Run [P6](09-android-auto-on-eos.md) on the Fairphone 6. Record everything it
   asks for.
2. Post the results as a comment on #9118 and on the April 2026 forum thread.
   Not a proposal — data. Nobody has published a clean account of what /e/OS
   3.3+ actually does on an FP6, which install route works, or whether the
   preinstalled placeholder can be updated from App Lounge. That comment is
   useful to e Foundation whether or not anything else happens.
3. Open the documentation merge request.
4. Publish the stub repository. Link it from #9118.
5. Only then, offer the Soong packaging.

Steps 1 and 2 cost an afternoon and are the ones that make the rest credible.
Somebody replying to a fourteen-month-old ticket with measurements from the
device in their hand is rare enough to be its own argument.

## One risk nobody in the tickets has mentioned

These packages impersonate Google package names and are labelled with Google
product names. For an individual sideloading them, that is a non-issue. For a
foundation that sells handsets, it is a question their lawyers get to answer,
and "the community has been doing it for years" is not the answer lawyers like.

It is worth raising it yourself rather than having it raised at you, and worth
noting the mitigations that are already in the design: the stub labels say
"compatibility stub" rather than "Google Maps", nothing carries a Google icon,
nothing is signed with anything of Google's, and no Google code or binary is
redistributed. A stub that occupies a package name is doing the same job as a
compatibility shim, which is well-trodden ground; a stub that presents itself
to the user *as* the Google app is not. Keep it on the right side of that line
and say so up front.
