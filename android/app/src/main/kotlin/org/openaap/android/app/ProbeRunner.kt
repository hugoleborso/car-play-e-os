/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openaap.core.HandshakeProbe
import org.openaap.core.PhoneIdentity
import org.openaap.core.ProbeReport
import org.openaap.crypto.probe.CredentialProbe
import org.openaap.transport.Transport

/**
 * Runs the credential probe across successive connections to a head unit.
 *
 * A head unit gives us one identity per session: it opens the link, we present
 * a certificate, and it decides. There is no way to try a second certificate on
 * the same session. So the matrix is spread across sessions, with the position
 * remembered on disk, and each connection tests the next identity.
 *
 * In practice a head unit that rejects a phone usually retries by itself within
 * a few seconds, so a single plug-in can walk several probes without the user
 * doing anything. If it does not retry, unplugging and replugging advances the
 * matrix by one. Either way the report accumulates.
 *
 * The report is written where `adb pull` can reach it without root, because the
 * person running this is standing in a car park with a laptop.
 */
public class ProbeRunner(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val matrix = CredentialProbe.matrix()

    /** Where the accumulated report lives. Survives app restarts; cleared by [reset]. */
    public val reportFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe-report.txt")

    private val resultsFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe-results.log")

    /** Index of the identity the next connection will present. */
    public val position: Int get() = preferences.getInt(KEY_POSITION, 0)

    /** True once every identity in the matrix has been tried. */
    public val complete: Boolean get() = position >= matrix.size

    /** Starts a fresh run, discarding any previous report. */
    public fun reset() {
        preferences.edit().putInt(KEY_POSITION, 0).apply()
        runCatching { reportFile.delete() }
        runCatching { resultsFile.delete() }
        collected.clear()
    }

    private val collected = mutableListOf<HandshakeProbe.Result>()

    /**
     * Presents the next identity over [transport] and records what happened.
     *
     * Returns the result, or `null` when the matrix has been exhausted.
     */
    public fun runNext(transport: Transport): HandshakeProbe.Result? {
        val index = position
        if (index >= matrix.size) {
            Log.i(TAG, "probe matrix complete; report at ${reportFile.absolutePath}")
            return null
        }

        val probe = matrix[index]
        Log.i(TAG, "probe ${index + 1}/${matrix.size}: ${probe.id} (${probe.dimension})")

        val result = HandshakeProbe(
            credentials = probe.credentials,
            identity = PhoneIdentity(model = Build.MODEL, maker = Build.MANUFACTURER),
        ).run(transport)

        // Advance before writing. If something goes wrong while rendering the
        // report, the next connection should still move on rather than retry
        // the same identity forever.
        preferences.edit().putInt(KEY_POSITION, index + 1).apply()

        collected += result
        appendResult(index, probe, result)
        writeReport()

        Log.i(TAG, "probe ${probe.id}: ${result.line()}")
        return result
    }

    /**
     * Appends one line per probe to a log that survives process death.
     *
     * The in-memory list does not: the service is torn down between
     * connections, and a probe run spans several. The log is the record the
     * final report is rebuilt from.
     */
    private fun appendResult(index: Int, probe: CredentialProbe.Probe, result: HandshakeProbe.Result) {
        runCatching {
            resultsFile.appendText(
                buildString {
                    appendLine("[${timestamp()}] probe ${index + 1}/${matrix.size} ${probe.id}")
                    appendLine("  varies : ${probe.dimension}")
                    appendLine("  tells  : ${probe.tells}")
                    appendLine("  result : ${result.line()}")
                    result.transcript.forEach { appendLine("    $it") }
                    appendLine()
                }
            )
        }.onFailure { Log.w(TAG, "could not append to the probe log", it) }
    }

    private fun writeReport() {
        runCatching {
            reportFile.writeText(
                ProbeReport.render(
                    collected,
                    context = mapOf(
                        "generated" to timestamp(),
                        "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
                        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "build" to Build.DISPLAY,
                        "probes run" to "${collected.size} of ${matrix.size}",
                        "detail log" to resultsFile.name,
                    ),
                )
            )
        }.onFailure { Log.w(TAG, "could not write the probe report", it) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    private companion object {
        const val TAG = "openaap.probe"
        const val PREFERENCES = "probe"
        const val KEY_POSITION = "position"
    }
}
