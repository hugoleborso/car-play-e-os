/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.usb

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.openaap.transport.Transport
import org.openaap.transport.TransportException

/**
 * AAP over USB accessory mode — the transport a real car actually uses.
 *
 * The head unit is the USB host. It switches the phone into accessory mode with
 * a short sequence of vendor control requests, and the Android framework then
 * hands the connection to whichever app declared a matching accessory filter.
 * From that point the link is two bulk endpoints, exposed to us as a single
 * file descriptor.
 *
 * Nothing here requires a privileged permission, a platform signature or a ROM
 * modification: AOSP's USB stack has no Android-Auto-specific path, so an
 * ordinary installable APK receives this connection like any other accessory.
 * On a de-Googled ROM we are additionally the only app claiming the filter,
 * which means the framework grants access directly instead of showing a chooser.
 */
public class UsbAccessoryTransport private constructor(
    private val descriptor: ParcelFileDescriptor,
    accessory: UsbAccessory,
) : Transport {

    // One descriptor, both directions. The kernel's accessory driver is a single
    // character device with a bulk IN and a bulk OUT endpoint behind it, so the
    // two streams are views onto the same fd rather than separate connections.
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    override val description: String =
        "usb:${accessory.manufacturer}/${accessory.model}" +
            (accessory.version?.let { " v$it" } ?: "")

    /**
     * The driver clamps a single transfer to 16 KiB. Writing more does not fail
     * loudly -- it truncates on some hardware -- so the limit is enforced here
     * rather than trusted to the caller.
     */
    override val maxWriteSize: Int get() = TRANSFER_LIMIT

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        try {
            input.read(destination, offset, minOf(length, TRANSFER_LIMIT))
        } catch (e: IOException) {
            throw TransportException("USB read failed on $description", e)
        }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val chunk = minOf(TRANSFER_LIMIT, length - written)
            try {
                output.write(source, offset + written, chunk)
            } catch (e: IOException) {
                throw TransportException("USB write failed on $description", e)
            }
            written += chunk
        }
        try {
            output.flush()
        } catch (e: IOException) {
            throw TransportException("USB flush failed on $description", e)
        }
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
    }

    public companion object {
        /** Maximum bytes the kernel accessory driver moves in one transfer. */
        public const val TRANSFER_LIMIT: Int = 16 * 1024

        /**
         * The manufacturer string head units identify themselves with.
         *
         * Match on manufacturer and model only. The version string differs
         * between vendors -- implementations in the wild send "1.0" and "2.0.1"
         * and a real car may send something else entirely -- and the framework
         * compares declared filter fields by exact equality, so pinning the
         * version would silently stop us matching on hardware we have not seen.
         */
        public const val HEAD_UNIT_MANUFACTURER: String = "Android"

        /** Model strings observed from head units and from open head-unit stacks. */
        public val HEAD_UNIT_MODELS: Set<String> = setOf(
            "Android Auto",
            "Android Open Automotive Protocol",
        )

        /** Whether an attached accessory looks like a projection-capable head unit. */
        public fun isHeadUnit(accessory: UsbAccessory): Boolean =
            accessory.manufacturer == HEAD_UNIT_MANUFACTURER && accessory.model in HEAD_UNIT_MODELS

        /**
         * Opens the accessory.
         *
         * Returns `null` when the framework declines, which in practice means
         * the per-accessory permission was not granted or the head unit went
         * away between the attach broadcast and this call — both routine.
         *
         * Never call this on the main thread: [read] blocks until a whole USB
         * transfer arrives, which on an idle link is indefinitely.
         */
        public fun open(manager: UsbManager, accessory: UsbAccessory): UsbAccessoryTransport? {
            val descriptor = manager.openAccessory(accessory) ?: return null
            return UsbAccessoryTransport(descriptor, accessory)
        }
    }
}
