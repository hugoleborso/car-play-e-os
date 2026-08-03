# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.
#
# Product fragment that adds the Android Auto dependency stubs to a build.
#
# Include it from a device or vendor product makefile:
#
#     $(call inherit-product, vendor/openaap/eos-enablement/packaging/product.mk)
#
# or, in a /e/OS tree, from the same place e Foundation already adds
# AndroidAutoStub -- config/common.mk in e/os/android_prebuilts_prebuiltapks_lfs.
#
# ---------------------------------------------------------------------------
# READ THIS BEFORE TURNING IT ON BY DEFAULT
#
# Once one of these is in the system image, the corresponding real Google app
# can never be installed on that device: the package name is taken by a package
# signed with a different key, and PackageManagerServiceUtils.verifySignatures
# rejects the update with INSTALL_FAILED_UPDATE_INCOMPATIBLE. System packages
# cannot be uninstalled, only disabled, and disabling does not release the
# name. Recovery means a new ROM build.
#
# That is a defensible default for a de-Googled OS and an indefensible surprise
# for the user who wanted real Google Maps for one trip. Hence the opt-in flag
# rather than an unconditional PRODUCT_PACKAGES line, and hence the
# recommendation in docs/10-upstreaming.md to offer these through App Lounge
# first, where an install is reversible, and bake them into the image second.
# ---------------------------------------------------------------------------

# Off unless the product asks for it.
OPENAAP_INCLUDE_AA_DEP_STUBS ?= false

ifeq ($(OPENAAP_INCLUDE_AA_DEP_STUBS),true)

# Android Auto itself is only supported from Android 13 (API 33) upwards on
# /e/OS, and e Foundation gates its own placeholder on API > 34. The stubs are
# harmless below that but pointless, so match the gate rather than invent one.
ifeq ($(shell test $(PLATFORM_SDK_VERSION) -ge 33; echo $$?),0)

PRODUCT_PACKAGES += \
    AndroidAutoDepStubGoogleApp \
    AndroidAutoDepStubMaps \
    AndroidAutoDepStubTts

endif
endif

# The gearhead placeholder is NOT here. It needs a Google-signed binary that
# this repository does not contain. See packaging/gearhead-slot/README.md.
