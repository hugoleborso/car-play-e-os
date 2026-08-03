# Packaging the stubs for a ROM

How to get the three dependency stubs, and optionally a slot for Android Auto
itself, into a LineageOS or /e/OS build.

## Layout in a build tree

The stubs are ordinary Soong modules and can live anywhere the tree looks.
Two placements, in order of preference:

```
packages/apps/AndroidAutoDepStubs/        # LineageOS convention
    google-app/{Android.bp,src/main/AndroidManifest.xml}
    maps/...
    tts/...
```

```
vendor/e/prebuilts/...                    # what /e/OS does today
```

The first is the one to propose. `packages/apps/<Name>` is where LineageOS puts
its own apps — the template named in [docs/04-android-integration.md](../../docs/04-android-integration.md)
is `LineageOS/android_packages_apps_LineageParts` — and it means the thing being
reviewed is a manifest a reviewer can read, not an APK they must trust. /e/OS's
current Android Auto placeholder is a 129 KB binary in a git-lfs repository with
no source; that is the bar this work is trying to clear, not match.

Then add the product fragment:

```make
$(call inherit-product, packages/apps/AndroidAutoDepStubs/packaging/product.mk)
OPENAAP_INCLUDE_AA_DEP_STUBS := true
```

## What the modules are, and what they are not

Each stub is an `android_app` with `srcs: []`, `resource_dirs: []`, no
permissions and no components. Three properties are worth defending:

**`product_specific: true`.** They land in `/product/app`. `/product` is the
partition an OS vendor owns on a Treble device, which is the right home for a
compatibility shim aimed at one third-party application. It also keeps them out
of `/system`, where a Google-compatibility package would be a harder sell
upstream.

**`privileged: false`.** They hold no permissions, so there is nothing to
allowlist and no partition-matching constraint to get wrong. There is no
`privapp_whitelist` file for them and there should never be one. Contrast with
the gearhead slot, which needs both.

**No `certificate:` line**, so they take the build's default app key rather than
`platform`. Nothing about them needs platform signing: the property they depend
on is `isSystem()`, which is about where the package is installed, not what
signed it. Platform-signing a package that needs no signature permissions is a
habit worth breaking; see the `forceQueryable` discussion in
[`../stubs/README.md`](../stubs/README.md#package-visibility-and-the-one-attribute-that-is-not-decoration)
for what `isSystem()` actually buys.

## Verifying a build

The stubs are 8.5 KB and contain one file that matters, so verification is
cheap and there is no excuse for skipping it:

```sh
# the package names are right
for p in com.google.android.apps.maps com.google.android.tts \
         com.google.android.googlequicksearchbox; do
    adb shell pm path $p
done
# each is on a system partition, i.e. forceQueryable is honoured
adb shell dumpsys package com.google.android.apps.maps | grep -E 'codePath|flags|pkgFlags'
# and nothing resolves to them
adb shell pm query-services --components -a android.intent.action.TTS_SERVICE
adb shell pm query-activities --components -a android.intent.action.VIEW -d geo:0,0
```

The last two are the important ones. A stub that appears in either output is
broken in the way described in [`../stubs/tts/README.md`](../stubs/tts/README.md)
— it has taken a job it cannot do.

## The gearhead slot

Separate directory, separate problem, and it is the one that needs a decision
from e Foundation rather than a patch from us:
[`gearhead-slot/README.md`](gearhead-slot/README.md).

## What has been verified, and what has not

**Verified.** The manifests build and produce correctly-named, dex-free,
permission-free APKs: `aapt2 link` + `zipalign` + `apksigner` with build-tools
35.0.0, via [`../tools/build-stub.sh`](../tools/build-stub.sh). Package names,
`hasCode=false`, `forceQueryable=true` and the absence of any component were
confirmed with `aapt2 dump xmltree`.

**Not verified.** The `Android.bp` files have not been built in an AOSP tree —
there isn't one here. They are modelled on `LineageParts` and on the
`AndroidAutoStub` module /e/OS ships, both of which are real, but a Soong
`android_app` with no sources and no resources is an unusual shape and may want
an extra property (`optimize: { enabled: false }` is already set for that
reason). Treat the first build in a real tree as part of the work, not as a
formality.

**Not verified.** That these stubs satisfy Android Auto on a device. Nobody
here has run them against a car. The evidence that stubs of this shape work is
four years of community use of functionally identical manifests; the evidence
that *these* ones work is nil until someone runs
[docs/09-android-auto-on-eos.md](../../docs/09-android-auto-on-eos.md).
