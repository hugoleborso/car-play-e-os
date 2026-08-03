# The gearhead slot — what actually defeats "Communication error 22"

> **Android Auto was not pre-installed on this device.**
> *Android Auto n'était pas préinstallé sur cet appareil.*

This directory holds everything needed to reserve a preinstalled slot for
`com.google.android.projection.gearhead` **except the one file that cannot come
from us.** That absence is the point of the directory, so it is documented
first.

## The mechanism, end to end

Android Auto checks that it is a system package. Installed as an ordinary app
from a store it is not one, and it stops with error 22. This has been true
since at least 2022 — the oldest /e/OS report is
[e/backlog#5658](https://gitlab.e.foundation/e/backlog/-/issues/5658), a
Fairphone 4 on /e/OS 1.0.

The fix everyone converges on is the same, whether it is done with Magisk
(`sn-00-x/aa4mg`), by hand on a GSI
([XDA](https://xdaforums.com/t/gsi-fix-communication-error-22-on-android-auto.4456645/)),
or in the ROM (iodéOS, and /e/OS since 3.3): **put a package with that name in
a `priv-app` directory of the system image, then let the user install the real
Android Auto over the top.**

Why that works is one AOSP rule. When a user-installed APK replaces a package
that also exists on a system partition, the scan is redone with the system
package's flags inherited — `ScanPackageUtils.scanPackageOnlyLI`,
[android15-release](https://cs.android.com/android/platform/superproject/+/android15-release:frameworks/base/services/core/java/com/android/server/pm/ScanPackageUtils.java):

```java
if (systemPkgSetting != null)  {
    // updated system application, must at least have SCAN_AS_SYSTEM
    scanFlags |= SCAN_AS_SYSTEM;
    if ((systemPkgSetting.getPrivateFlags()
            & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
        scanFlags |= SCAN_AS_PRIVILEGED;
    }
    ...
```

So the real Android Auto, running from `/data`, reports itself as a system and
privileged package, because a placeholder by that name shipped in the image.
The privileged part is what makes the allowlist in this directory apply to it.

Note what is *not* involved: no SafetyNet, no Play Integrity, no DroidGuard, no
attestation of any kind. This is a package-manager flag.

## The file we cannot ship, and why

The placeholder has to be signed with **Google's Android Auto signing key**.

`PackageManagerServiceUtils.verifySignatures`
([android15-release](https://cs.android.com/android/platform/superproject/+/android15-release:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java))
throws `INSTALL_FAILED_UPDATE_INCOMPATIBLE` when an update's signature does not
match the installed package's. There is no exception for system packages. So a
placeholder signed with the ROM's platform key would *reserve* the name and
then *refuse* the real app — and a system package cannot be uninstalled to
recover, only disabled, which does not free the name.

That leaves exactly one option: the placeholder is a genuine Google-signed
Android Auto APK, shipped `presigned`. Which is a Google binary. Which this
project does not distribute — see [docs/07-provenance.md](../../../docs/07-provenance.md).

**This is the thing /e/OS has to decide, and nobody outside e Foundation can
decide it for them.** The decision has three parts:

1. *May they redistribute a Google-signed Android Auto binary at all?* It is
   not open-source and has no redistribution licence. Note that /e/OS already
   answers "yes" in practice for `GmsCore` and friends, and shipped a 3.7 MB
   `presigned` Android Auto placeholder from October 2025 (commit `63cf352f` in
   `e/os/android_prebuilts_prebuiltapks_lfs`, released in /e/OS 3.3).
2. *Which build?* Once chosen, that signature is baked into every device. If
   Google ever rotates the Android Auto signing key, every /e/OS device with a
   placeholder from the old key can no longer install the new app.
3. *Where does the binary come from and who audits it?* It is a prebuilt blob
   in a git-lfs repository with no source and no build recipe.

## An unresolved discrepancy in /e/OS's own tree

Worth knowing before proposing anything, because a reviewer will know it.

`e/os/android_prebuilts_prebuiltapks_lfs` shipped `AndroidAutoStub` as
`presigned: true, preprocessed: true` in October 2025 — consistent with the
mechanism above. Two weeks later, commit `f4d13f09` ("Update AndroidAutoStubs
with a real stub", 16 October 2025) changed it to:

```
android_app_import {
    name: "AndroidAutoStub",
    privileged: true,
    certificate: "platform",
    required: ["privapp-permissions-com.google.android.projection.gearhead.xml"],
    apk: "app-release-unsigned.apk",
}
```

`certificate: "platform"` with an unsigned APK means Soong signs it with the
/e/OS platform key. By the rule above, the real Android Auto should then be
uninstallable on top of it. Yet users on /e/OS 4.x report installing Android
Auto 17.1 from APKMirror successfully over the preinstalled stub
([forum, July 2026](https://community.e.foundation/t/cannot-manage-to-install-android-auto/82309)),
and others report the preinstalled version updating itself normally.

Three readings, and we could not distinguish them from outside:

- the placeholder's package name is not `com.google.android.projection.gearhead`
  after all, and the privapp file is there for the store-installed app;
- /e/OS carries a framework-side accommodation. A forum moderator states that
  "the sn-00-x approach was merged in android_frameworks_base" around mid-2024
  ([thread](https://community.e.foundation/t/how-to-run-android-auto-without-google-apps/64578));
  we could not find the commit in the public tree, whose search API needs
  authentication;
- or the users who report success had already replaced the placeholder by some
  other route.

Resolving this is one adb command on a device, and it is step 3 of
[docs/09-android-auto-on-eos.md](../../../docs/09-android-auto-on-eos.md).
Anyone opening a merge request against /e/OS should run it first: whether their
own tree is self-consistent is a fair thing to be asked, and being the person
who answers it is a better opening than being the person who asks.

## What is in this directory

| File | Status |
| --- | --- |
| `Android.bp.example` | Complete, deliberately not named `Android.bp`. Builds nothing without the APK. |
| `privapp_whitelist_com.google.android.projection.gearhead.xml` | Complete. Needs trimming against a real boot log; the file says how. |
| `default-permissions-com.google.android.projection.gearhead.xml` | Complete except the certificate digest, which is left blank on purpose. Optional. |
| `AndroidAutoPlaceholder.apk` | **Absent, permanently.** |

The three dependency stubs in [`../../stubs/`](../../stubs/) have none of these
problems. They occupy package names whose real occupants a de-Googled user does
not want, they are built from source in this repository, and they can be signed
with anything.
