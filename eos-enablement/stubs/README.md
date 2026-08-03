# Presence stubs for Android Auto's package checks

Three Android packages that exist and do nothing, so that Google's Android Auto
app (`com.google.android.projection.gearhead`) will run on a phone that has
none of the Google applications it expects.

| Directory | Package it occupies | Real app it stands in for |
| --- | --- | --- |
| `google-app/` | `com.google.android.googlequicksearchbox` | the Google app, internally *Velvet* |
| `maps/` | `com.google.android.apps.maps` | Google Maps |
| `tts/` | `com.google.android.tts` | Speech Recognition & Synthesis |

Each directory has its own README stating precisely which check it satisfies.
This file holds what is true of all three.

## What these are not

They are not a fourth stub for Android Auto itself. That one is a different
problem with a different answer — see [`../packaging/gearhead-slot/`](../packaging/gearhead-slot/).

They are not derived from any existing stub. Every file here was written from
the manifest semantics in the Android documentation and the AOSP sources cited
below. The reason is licensing: `rik-shaw/aa-stubs`, the repository the /e/OS
community points at, **has no LICENSE file at all** (verified: `LICENSE` is 404
on both `main` and `master`), and neither does `sn-00-x/aa4mg`, the Magisk
module it credits for `Maps.apk`. The two stubs it takes from `SolidEva` trace
back to `SolidHal/Gapp-Package-Spoof` and `SolidHal/SpeechServices-Package-Spoof`,
which *are* MIT — MIT is compatible with Apache-2.0, but the aggregate that
everyone actually downloads is not licensed at all, and the file people install
is a prebuilt APK of unknown provenance. Writing forty lines of XML is cheaper
than resolving that.

## Why a package with nothing in it is enough

The claim being made is narrow: **Android Auto tests that these packages are
installed, not that they work.** Everything below is the evidence for it.

**Verified.** The two stubs in longest community use declare literally nothing.
`SolidHal/Gapp-Package-Spoof`'s entire manifest is a `<manifest>` naming
`com.google.android.googlequicksearchbox` wrapping an `<application>` element
with no children ([source](https://github.com/SolidHal/Gapp-Package-Spoof/blob/master/app/src/main/AndroidManifest.xml)),
and the speech-services one is the same file with the package name changed
([source](https://github.com/SolidHal/SpeechServices-Package-Spoof/blob/master/app/src/main/AndroidManifest.xml)).
Those two have been the working answer on de-Googled ROMs since 2020. If
Android Auto resolved a component inside them, they would not work, and they do.

**Verified.** Sideloading them is sufficient — users report success with plain
`adb install` of the three stubs on /e/OS, with no root and no ROM change
([/e/OS forum, December 2024](https://community.e.foundation/t/how-to-run-android-auto-without-google-apps/64578)).
This matters more than it looks: see the package-visibility note below.

**Assumed, and not cheaply verifiable.** *Which* API Android Auto uses to make
the test — `getPackageInfo`, `getApplicationInfo`, `getInstalledPackages`,
`resolveActivity` — is unknown to us. Establishing it means decompiling
gearhead, which this project does not do (see
[docs/07-provenance.md](../../docs/07-provenance.md)). The behaviour is
consistent with any of the first three and inconsistent with the fourth. If a
future Android Auto release starts resolving a component, these stubs stop
working and the fix is not obvious from the outside; that risk is real and
belongs in the honest column.

**Assumed.** That the check is a hard gate rather than a feature toggle. What
users report is that Android Auto will not proceed at all without them, but a
"communication error" screen is not a stack trace.

## Package visibility, and the one attribute that is not decoration

Every manifest here sets `android:forceQueryable="true"`. Android 11 (API 30)
introduced package-visibility filtering: an app targeting API 30+ cannot see
another package unless it declares `<queries>`, holds `QUERY_ALL_PACKAGES`, or
the target is visible to everyone. If Android Auto could not see these stubs,
they would be exactly as useless as not installing them.

The attribute is honoured **only for packages on a system partition**. From
AOSP `AppsFilterImpl.addPackage` ([android15-release](https://cs.android.com/android/platform/superproject/+/android15-release:frameworks/base/services/core/java/com/android/server/pm/AppsFilterImpl.java)):

```java
newIsForceQueryable = mForceQueryable.contains(newPkgSetting.getAppId())
        || newPkgSetting.isForceQueryableOverride() /* adb override */
        || (newPkgSetting.isSystem() && (mSystemAppsQueryable
        || newPkg.isForceQueryable()
        || ArrayUtils.contains(mForceQueryableByDevicePackageNames,
        newPkg.getPackageName())));
```

`newPkg.isForceQueryable()` — the manifest attribute — is inside the
`isSystem()` branch. A sideloaded stub's `forceQueryable` is ignored. The
Android documentation for the attribute does not mention this restriction, so
the source is the only place to learn it.

Three consequences, and they drive the whole design:

1. **Built into a ROM, this attribute does the work.** That is the case these
   `Android.bp` files are for.
2. **Sideloaded, it does not** — and yet sideloading demonstrably works, which
   is evidence that Android Auto declares `<queries>` entries for these three
   packages (or holds `QUERY_ALL_PACKAGES`) and so can see them anyway. We have
   not read gearhead's manifest to confirm which; the community's success with
   `adb install` is the whole of the evidence.
3. **If a sideloaded stub is ever invisible, there is a one-flag fix.**
   `adb install --force-queryable app.apk` sets `isForceQueryableOverride()`,
   the second clause above, which is not gated on `isSystem()`
   ([`PackageManagerShellCommand`](https://cs.android.com/android/platform/superproject/+/android15-release:frameworks/base/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)).
   [docs/09-android-auto-on-eos.md](../../docs/09-android-auto-on-eos.md) uses
   it by default, because it costs nothing and removes a variable.

## The trade nobody mentions

A stub installed **into the system image cannot be replaced by the real Google
app.** `PackageManagerServiceUtils.verifySignatures` throws
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` when an update's signature does not match
the installed package's, with no exception for system apps
([android15-release](https://cs.android.com/android/platform/superproject/+/android15-release:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java)).
A stub signed with the ROM's platform key permanently occupies
`com.google.android.apps.maps`; a user who later wants real Google Maps needs a
new ROM build, not an uninstall — system packages cannot be removed, only
disabled, and disabling does not free the package name.

A sideloaded stub has no such problem: uninstall it and install the real thing.

This is the strongest single argument against /e/OS bundling these three into
the image, and it is why [docs/10-upstreaming.md](../../docs/10-upstreaming.md)
proposes offering them through App Lounge first and the build-time flag second.

## Building

See [`../tools/build-stub.sh`](../tools/build-stub.sh). It uses only `aapt2`,
`zipalign` and `apksigner` from the Android SDK build-tools — no Gradle, no
AGP, no network. In an AOSP or /e/OS tree, use the `Android.bp` files instead.

No APK is committed to this repository, here or anywhere else.
