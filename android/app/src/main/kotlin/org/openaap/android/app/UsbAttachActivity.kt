/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.openaap.android.usb.UsbAccessoryTransport

/**
 * Turns "a head unit was plugged in" into a running projection service.
 *
 * The Android framework delivers an accessory connection by launching an
 * activity, not by broadcasting, so this exists purely to receive that launch
 * and hand off. It shows nothing and finishes immediately — the user's screen
 * is not where projection happens, and on a phone that is locked in a dock,
 * putting anything on screen would be both useless and in the way.
 *
 * Being launched here also means the framework has already granted permission
 * for this accessory. The first time, the user sees a system dialog with an
 * "always use this app" checkbox; on a de-Googled ROM we are the only app
 * claiming the filter, so every subsequent connection is silent.
 */
public class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val accessory = intent.accessory()
        if (accessory == null) {
            Log.w(TAG, "launched without an accessory; ignoring")
            finish()
            return
        }

        if (!UsbAccessoryTransport.isHeadUnit(accessory)) {
            // Another app's accessory, or a head unit identifying itself in a
            // way we have not seen. Log the strings rather than swallowing them:
            // this is exactly the diagnostic needed to widen the filter for
            // hardware nobody has tested against.
            Log.i(
                TAG,
                "ignoring accessory manufacturer='${accessory.manufacturer}' " +
                    "model='${accessory.model}' version='${accessory.version}'",
            )
            finish()
            return
        }

        Log.i(TAG, "head unit attached: ${accessory.manufacturer}/${accessory.model}")
        ProjectionService.start(this, accessory)
        finish()
    }

    private fun Intent.accessory(): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    private companion object {
        const val TAG = "openaap.attach"
    }
}
