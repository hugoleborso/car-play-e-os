/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.PowerManager

/**
 * Answers the question "why is nothing happening?" on the phone itself.
 *
 * Nothing happening is the hardest outcome to act on, because it is what a
 * missing device feature, a refused permission, a bad cable and a car that
 * never tried all look like. Each of those has a different fix and only one of
 * them is our fault. This computes what can be checked locally, so what remains
 * unexplained is genuinely about the car.
 */
public object ProbeDiagnostics {

    /** How much a failed check matters. */
    public enum class Severity {
        /** The probe cannot work at all until this is fixed. */
        BLOCKING,

        /** The probe works, but something will be harder or invisible. */
        DEGRADED,

        /** Context rather than a problem. */
        INFORMATION,
    }

    public data class Check(
        val title: String,
        val passed: Boolean,
        val detail: String,
        val severity: Severity,
        /** What the user can do about it, when there is something. */
        val remedy: String? = null,
    )

    public fun run(context: Context): List<Check> = buildList {
        add(usbAccessorySupport(context))
        add(attachedAccessories(context))
        add(notifications(context))
        add(reportStorage(context))
        add(batteryOptimisation(context))
        add(activity(context))
    }

    /**
     * Without this feature the framework never enters accessory mode, so the
     * app is never launched and nothing else in the list matters.
     */
    private fun usbAccessorySupport(context: Context): Check {
        val supported = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)
        return Check(
            title = context.getString(R.string.diag_usb_feature),
            passed = supported,
            detail = context.getString(
                if (supported) R.string.diag_usb_feature_ok else R.string.diag_usb_feature_missing
            ),
            severity = Severity.BLOCKING,
            remedy = if (supported) null else context.getString(R.string.diag_usb_feature_remedy),
        )
    }

    /**
     * Whether anything is connected right now, and whether we may talk to it.
     *
     * The distinction matters: an accessory listed without permission means the
     * connection happened and Android withheld it from us, which is a different
     * problem from the car never trying.
     */
    private fun attachedAccessories(context: Context): Check {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val accessories = manager?.accessoryList?.toList().orEmpty()

        if (accessories.isEmpty()) {
            return Check(
                title = context.getString(R.string.diag_accessory),
                passed = false,
                detail = context.getString(R.string.diag_accessory_none),
                severity = Severity.INFORMATION,
                remedy = context.getString(R.string.diag_accessory_none_remedy),
            )
        }

        val described = accessories.joinToString("\n") { accessory ->
            val granted = manager?.hasPermission(accessory) == true
            val recognised = org.openaap.android.usb.UsbAccessoryTransport.isHeadUnit(accessory)
            "${accessory.manufacturer} / ${accessory.model}" +
                (if (recognised) "" else context.getString(R.string.diag_accessory_unrecognised)) +
                (if (granted) "" else context.getString(R.string.diag_accessory_no_permission))
        }
        val allUsable = accessories.all { manager?.hasPermission(it) == true }
        return Check(
            title = context.getString(R.string.diag_accessory),
            passed = allUsable,
            detail = described,
            severity = Severity.BLOCKING,
            remedy = if (allUsable) null else context.getString(R.string.diag_accessory_permission_remedy),
        )
    }

    /**
     * The notification is the only sign of life while the phone is plugged into
     * the car, so a refused permission makes a working probe look like a dead
     * one. It does not stop the probe.
     */
    private fun notifications(context: Context): Check {
        val required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = !required || context.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return Check(
            title = context.getString(R.string.diag_notifications),
            passed = granted,
            detail = context.getString(
                if (granted) R.string.diag_notifications_ok else R.string.diag_notifications_denied
            ),
            severity = Severity.DEGRADED,
            remedy = if (granted) null else context.getString(R.string.diag_notifications_remedy),
        )
    }

    private fun reportStorage(context: Context): Check {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val writable = runCatching {
            directory.mkdirs()
            val probe = java.io.File(directory, ".writable")
            probe.writeText("")
            probe.delete()
            true
        }.getOrDefault(false)
        return Check(
            title = context.getString(R.string.diag_storage),
            passed = writable,
            detail = directory.absolutePath,
            severity = Severity.BLOCKING,
            remedy = if (writable) null else context.getString(R.string.diag_storage_remedy),
        )
    }

    private fun batteryOptimisation(context: Context): Check {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        return Check(
            title = context.getString(R.string.diag_battery),
            passed = exempt,
            detail = context.getString(
                if (exempt) R.string.diag_battery_ok else R.string.diag_battery_restricted
            ),
            severity = Severity.DEGRADED,
            remedy = if (exempt) null else context.getString(R.string.diag_battery_remedy),
        )
    }

    /**
     * Whether the app has ever been woken by a cable.
     *
     * This is the check that separates "our side is broken" from "the car never
     * tried", and it is the one worth reading first when nothing happens.
     */
    private fun activity(context: Context): Check {
        val events = ProbeEvents.all(context)
        return Check(
            title = context.getString(R.string.diag_activity),
            passed = events.isNotEmpty(),
            detail = if (events.isEmpty()) {
                context.getString(R.string.diag_activity_none)
            } else {
                context.getString(R.string.diag_activity_some, events.size, events.last().at)
            },
            severity = Severity.INFORMATION,
            remedy = if (events.isEmpty()) context.getString(R.string.diag_activity_remedy) else null,
        )
    }
}
