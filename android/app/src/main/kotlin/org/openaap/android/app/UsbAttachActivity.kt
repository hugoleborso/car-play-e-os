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
            ProbeEvents.record(
                this,
                ProbeEvents.Kind.FAULT,
                "A cable event arrived with no accessory attached to it.",
            )
            finish()
            return
        }

        // The six strings, verbatim, on the phone's own screen. They are the
        // cheapest useful measurement in the whole project and no public source
        // records them for a MIB2, so they are written down before any decision
        // is taken about them -- including the decision to ignore this device.
        val identity = accessory.describe()
        Log.i(TAG, "accessory attached: $identity")

        if (!UsbAccessoryTransport.isHeadUnit(accessory)) {
            // Another app's accessory, or a head unit identifying itself in a
            // way we have not seen. Record the strings rather than swallowing
            // them: this is exactly the diagnostic needed to widen the match for
            // hardware nobody has tested against.
            Log.i(TAG, "not a recognised head unit; ignoring")
            ProbeEvents.record(
                this,
                ProbeEvents.Kind.ATTENTION,
                "Connected, but not recognised as a head unit:\n$identity\n" +
                    "Send these lines back -- teaching the app to recognise it is a one-line " +
                    "change, and nobody has published what this car sends.",
            )
            finish()
            return
        }

        ProbeEvents.record(
            this,
            ProbeEvents.Kind.PROGRESS,
            "Head unit recognised, starting a session:\n$identity",
        )
        ProjectionService.start(this, accessory)
        finish()
    }

    /** All six identifying strings, including the empty ones. */
    private fun UsbAccessory.describe(): String = listOf(
        "manufacturer" to manufacturer,
        "model" to model,
        "description" to description,
        "version" to version,
        "uri" to uri,
        // Since Android 10 the serial is withheld from apps without a grant for
        // this accessory, and on some builds asking throws rather than returning
        // null. It is the least interesting of the six, so it is never worth a
        // crash at the moment the cable goes in.
        "serial" to runCatching { serial }.getOrNull(),
    ).joinToString("\n") { (label, value) -> "  $label = ${value ?: "(none)"}" }

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
