/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.content.Context
import android.content.Intent
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
 * matrix by one. Either way the record accumulates.
 *
 * Results land in three places, deliberately: a machine-readable log the
 * on-device screen renders, a human-readable report to bring back, and a
 * broadcast so the screen updates while the phone is still in the car. That
 * last one matters more than it sounds — the cable is in the head unit, so
 * `adb` cannot see the phone while any of this is happening.
 */
public class ProbeRunner(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val matrix = CredentialProbe.matrix()

    private val directory: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /** Human-readable report, the thing to pull off the phone afterwards. */
    public val reportFile: File get() = File(directory, "probe-report.txt")

    /** One JSON object per attempt. What the on-device screen renders. */
    public val recordsFile: File get() = File(directory, "probe-records.jsonl")

    /** Index of the identity the next connection will present. */
    public val position: Int get() = preferences.getInt(KEY_POSITION, 0)

    /** How many identities the matrix holds. */
    public val size: Int get() = matrix.size

    /** True once every identity has been tried. */
    public val complete: Boolean get() = position >= matrix.size

    /** Everything recorded so far, oldest first. */
    public fun records(): List<ProbeRecord> = ProbeRecord.readAll(recordsFile)

    /** Starts a fresh run, discarding the previous one. */
    public fun reset() {
        preferences.edit().putInt(KEY_POSITION, 0).apply()
        runCatching { reportFile.delete() }
        runCatching { recordsFile.delete() }
        context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))
    }

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

        // Advance before writing. If rendering the report throws, the next
        // connection should still move on rather than retry the same identity
        // forever and never finish the matrix.
        preferences.edit().putInt(KEY_POSITION, index + 1).apply()

        appendRecord(index, probe, result)
        writeReport()
        context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))

        Log.i(TAG, "probe ${probe.id}: ${result.line()}")
        return result
    }

    private fun appendRecord(index: Int, probe: CredentialProbe.Probe, result: HandshakeProbe.Result) {
        val record = ProbeRecord(
            index = index + 1,
            total = matrix.size,
            credential = probe.id,
            varies = probe.dimension,
            stage = result.stage.name,
            alert = result.alert?.label,
            alertMeaning = result.alert?.meaning,
            failure = result.failure,
            headUnitProtocol = result.headUnitProtocol?.let { "${it.first}.${it.second}" },
            negotiatedTls = result.negotiatedTls,
            negotiatedCipherSuite = result.negotiatedCipherSuite,
            headUnitCertificate = result.headUnitChain.firstOrNull()?.let {
                "${it.subjectX500Principal.name} | issued by ${it.issuerX500Principal.name} | " +
                    "valid ${it.notBefore}..${it.notAfter} | ${it.publicKey.algorithm} v${it.version}"
            },
            timestamp = timestamp(),
        )
        runCatching { recordsFile.appendText(record.toJson() + "\n") }
            .onFailure { Log.w(TAG, "could not append the probe record", it) }
    }

    private fun writeReport() {
        val results = records()
        runCatching {
            reportFile.writeText(
                buildString {
                    appendLine(
                        ProbeReport.render(
                            emptyList(),
                            context = mapOf(
                                "generated" to timestamp(),
                                "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
                                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                "build" to Build.DISPLAY,
                                "probes run" to "${results.size} of ${matrix.size}",
                            ),
                        ).substringBefore("Results")
                    )
                    appendLine("Results")
                    appendLine("-".repeat(78))
                    results.forEach { record ->
                        appendLine("  ${record.credential.padEnd(24)}${record.stage.padEnd(22)}${record.alert ?: ""}")
                        appendLine("      varies: ${record.varies}")
                        record.alertMeaning?.let { appendLine("      meaning: $it") }
                        record.headUnitProtocol?.let { appendLine("      head unit protocol: $it") }
                        record.negotiatedTls?.let {
                            appendLine("      TLS: $it / ${record.negotiatedCipherSuite ?: "?"}")
                        }
                        record.failure?.takeIf { record.alert == null }?.let { appendLine("      detail: $it") }
                        appendLine()
                    }
                    results.firstOrNull { it.headUnitCertificate != null }?.let {
                        appendLine("Certificate the head unit presented to us")
                        appendLine("-".repeat(78))
                        appendLine("  ${it.headUnitCertificate}")
                        appendLine()
                    }
                }
            )
        }.onFailure { Log.w(TAG, "could not write the probe report", it) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    public companion object {
        private const val TAG = "openaap.probe"
        private const val PREFERENCES = "probe"
        private const val KEY_POSITION = "position"

        /** Sent whenever a probe finishes, so the screen can refresh while in the car. */
        public const val ACTION_PROBE_UPDATED: String = "org.openaap.projection.PROBE_UPDATED"
    }
}
