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
import org.openaap.core.ProbeStep
import org.openaap.core.StatusProbe
import org.openaap.core.asProbeStep
import org.openaap.crypto.probe.CredentialProbe
import org.openaap.transport.Transport

/**
 * Runs a matrix across successive connections to a head unit.
 *
 * A head unit gives us one attempt per session: it opens the link, we present a
 * certificate and behave in one particular way, and it decides. There is no way
 * to try a second on the same session. So the matrix is spread across sessions,
 * with the position remembered on disk, and each connection runs the next step.
 *
 * In practice a head unit that rejects a phone usually retries by itself within
 * a few seconds, so a single plug-in can walk several steps without the user
 * doing anything. If it does not retry, unplugging and replugging advances the
 * matrix by one. Either way the record accumulates.
 *
 * Results land in three places, deliberately: a machine-readable log the
 * on-device screen renders, a human-readable report to bring back, and a
 * broadcast so the screen updates while the phone is still in the car. That
 * last one matters more than it sounds — the cable is in the head unit, so
 * `adb` cannot see the phone while any of this is happening.
 *
 * ### Why one runner and not two
 *
 * There are two matrices and there will be more: the credential matrix varies
 * the identity presented, the status matrix varies everything around it. They
 * are run identically, and the second was a copy of the first waiting to happen
 * — at which point the retry rule, the position bookkeeping and the report
 * format would each have had to be fixed twice, and would eventually have been
 * fixed once. [ProbeStep] exists so that both are the same list of the same
 * thing, and [name] keeps their files and their positions apart.
 */
public class ProbeRunner private constructor(
    private val context: Context,
    /**
     * Distinguishes this matrix's files and saved position from the others'.
     *
     * `"probe"` is deliberately the historical value: it keeps the credential
     * matrix's files and position exactly where they were, so a phone that
     * already holds field results does not silently start over on upgrade.
     */
    private val name: String,
    private val matrix: List<ProbeStep>,
    /** One line describing what this matrix is for, printed at the top of its report. */
    private val purpose: String,
) {

    // The matrix name is also the preferences name, which is what keeps the
    // credential matrix's saved position exactly where it was.
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val directory: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /** Human-readable report, the thing to pull off the phone afterwards. */
    public val reportFile: File get() = File(directory, "$name-report.txt")

    /** One JSON object per attempt. What the on-device screen renders. */
    public val recordsFile: File get() = File(directory, "$name-records.jsonl")

    /** Index of the step the next connection will run. */
    public val position: Int get() = preferences.getInt(KEY_POSITION, 0)

    /** How many steps the matrix holds. */
    public val size: Int get() = matrix.size

    /** True once every step has been run. */
    public val complete: Boolean get() = position >= matrix.size

    /** The step the next connection will run, or `null` once every one has. */
    public fun next(): ProbeStep? = matrix.getOrNull(position)

    /** Everything recorded so far, oldest first. */
    public fun records(): List<ProbeRecord> = ProbeRecord.readAll(recordsFile)

    /** Starts a fresh run, discarding the previous one. */
    public fun reset() {
        preferences.edit().putInt(KEY_POSITION, 0).putInt(KEY_ABORTS, 0).apply()
        runCatching { reportFile.delete() }
        runCatching { recordsFile.delete() }
        context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))
    }

    /**
     * Runs the next step over [transport] and records what happened.
     *
     * Returns the result, or `null` when the matrix has been exhausted.
     */
    public fun runNext(transport: Transport): HandshakeProbe.Result? {
        val index = position
        if (index >= matrix.size) {
            Log.i(TAG, "$name matrix complete; report at ${reportFile.absolutePath}")
            return null
        }

        val step = matrix[index]
        Log.i(TAG, "$name ${index + 1}/${matrix.size}: ${step.id} (${step.varies})")

        val result = HandshakeProbe(
            step = step,
            identity = PhoneIdentity(model = Build.MODEL, maker = Build.MANUFACTURER),
        ).run(transport)

        // A connection where the head unit never spoke measured nothing about
        // this step, so it must not consume it.
        //
        // This was wrong in the first field run and it cost four of nine
        // results. A head unit re-attaches the accessory after a session ends,
        // and the re-attach arrives before it is ready to talk, so every other
        // connection died on the first read. Advancing regardless made those
        // land against whichever step was next in line, and the report then
        // showed NO_CONTACT beside "expired" and "own-ca" as though the car had
        // said something about them. It had not. Perfect alternation between
        // NO_CONTACT and a verdict is the signature of that bug, and it reads
        // exactly like a finding.
        //
        // Retrying is bounded, because a genuinely dead link -- wrong cable, no
        // App-Connect -- would otherwise sit on step one forever and never
        // produce the report that says so.
        if (result.stage == HandshakeProbe.Stage.NO_CONTACT) {
            val aborts = preferences.getInt(KEY_ABORTS, 0) + 1
            if (aborts < MAX_ABORTS) {
                preferences.edit().putInt(KEY_ABORTS, aborts).apply()
                Log.i(TAG, "head unit never spoke; retrying ${step.id} (attempt ${aborts + 1})")
                ProbeEvents.record(
                    context,
                    ProbeEvents.Kind.PROGRESS,
                    "The car re-attached before it was ready to talk. Retrying ${step.id} " +
                        "(attempt ${aborts + 1} of $MAX_ABORTS) — nothing was used up.",
                )
                context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))
                return result
            }
            ProbeEvents.record(
                context,
                ProbeEvents.Kind.ATTENTION,
                "The car stayed silent through $MAX_ABORTS attempts at ${step.id}. Recording it as " +
                    "no contact and moving on.",
            )
        }

        // Advance before writing. If rendering the report throws, the next
        // connection should still move on rather than retry the same step
        // forever and never finish the matrix.
        preferences.edit().putInt(KEY_POSITION, index + 1).putInt(KEY_ABORTS, 0).apply()

        appendRecord(index, step, result)
        writeReport()
        context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))

        Log.i(TAG, "$name ${step.id}: ${result.line()}")
        return result
    }

    private fun appendRecord(index: Int, step: ProbeStep, result: HandshakeProbe.Result) {
        val record = ProbeRecord(
            index = index + 1,
            total = matrix.size,
            credential = step.id,
            varies = step.varies,
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
            verdictSeen = result.verdictSeen,
            verdictBody = result.verdictBody,
            verdictStatus = result.verdictStatus,
            transcript = result.transcript,
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
                                "matrix" to name,
                                "purpose" to purpose,
                                "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
                                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                "build" to Build.DISPLAY,
                                "steps run" to "${results.size} of ${matrix.size}",
                            ),
                        ).substringBefore("Results")
                    )
                    appendLine("What each step would have shown")
                    appendLine("-".repeat(78))
                    matrix.forEachIndexed { index, step ->
                        appendLine("  ${index + 1}. ${step.id}")
                        appendLine("      varies: ${step.varies}")
                        appendLine("      tells : ${step.tells}")
                    }
                    appendLine()
                    appendLine(codesSeen(results))
                    appendLine("Results")
                    appendLine("-".repeat(78))
                    results.forEach { record ->
                        appendLine("  ${record.credential.padEnd(24)}${record.stage.padEnd(22)}${record.alert ?: ""}")
                        appendLine("      varies: ${record.varies}")
                        appendLine("      verdict: ${record.verdictLabel}")
                        // The bytes, always, even when we think we understand
                        // them. The one line that would have caught the -3
                        // misreading a month early is a hex dump nobody had to
                        // interpret.
                        record.verdictBody?.let { appendLine("      verdict body: $it") }
                        record.alertMeaning?.let { appendLine("      meaning: $it") }
                        record.headUnitProtocol?.let { appendLine("      head unit protocol: $it") }
                        record.negotiatedTls?.let {
                            appendLine("      TLS: $it / ${record.negotiatedCipherSuite ?: "?"}")
                        }
                        record.failure?.takeIf { record.alert == null }?.let { appendLine("      detail: $it") }
                        // In full, not summarised. A verdict of AUTHENTICATED is
                        // the strongest claim this project can make, and the
                        // line that distinguishes a stated verdict from one
                        // inferred out of an empty body lives here. Anyone
                        // checking the result -- including me, a week later --
                        // needs the exchange, not my description of it.
                        if (record.transcript.isNotEmpty()) {
                            appendLine("      transcript:")
                            record.transcript.forEach { appendLine("        $it") }
                        }
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

    /**
     * The comparison the whole matrix exists to produce, from the records
     * rather than from live results.
     *
     * Records are what survive the process being killed between connections, so
     * the table has to be built from them; the shared renderer takes
     * [HandshakeProbe.Result] and cannot see them. Rebuilding the minimum it
     * needs is cheaper than making the record round-trip a whole result.
     */
    private fun codesSeen(results: List<ProbeRecord>): String = buildString {
        appendLine("Codes seen")
        appendLine("-".repeat(78))
        val spoke = results.filterNot { it.noContact }
        if (spoke.isEmpty()) {
            appendLine("  Nothing measured yet.")
            return@buildString
        }
        spoke.groupBy { it.verdictLabel }.forEach { (label, rows) ->
            appendLine("  ${label.padEnd(20)}${rows.joinToString(", ") { it.credential }}")
        }
        appendLine()
        val distinct = spoke.filter { it.verdictSeen }.map { it.verdictStatus }.distinct()
        when {
            distinct.size > 1 ->
                appendLine(
                    "  Different provocations get different codes. The code space is meaningful\n" +
                        "  and part of it is now mapped, which no public source records."
                )

            distinct.size == 1 && spoke.any { !it.verdictSeen } ->
                appendLine(
                    "  One code, and only where the session got far enough to earn a verdict.\n" +
                        "  That bounds what it can mean without showing it to be about certificates."
                )

            distinct.size == 1 ->
                appendLine(
                    "  One code for every kind of failure provoked, including ones with nothing to\n" +
                        "  do with the certificate. It is a generic failure indicator."
                )

            else -> appendLine("  No verdict message has arrived yet.")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    public companion object {
        private const val TAG = "openaap.probe"
        private const val KEY_POSITION = "position"
        private const val KEY_ABORTS = "aborts"

        /** How many silent connections a step gets before we give up on it. */
        private const val MAX_ABORTS = 3

        /** Sent whenever a step finishes, so the screen can refresh while in the car. */
        public const val ACTION_PROBE_UPDATED: String = "org.openaap.projection.PROBE_UPDATED"

        /** The nine identities, varying what the phone presents. */
        public fun credentials(context: Context): ProbeRunner = ProbeRunner(
            context,
            name = "probe",
            matrix = CredentialProbe.matrix().map { it.asProbeStep() },
            purpose = "which certificate this head unit will accept",
        )

        /**
         * The status matrix, varying everything except what the phone presents.
         *
         * The credential matrix has been run and answered: nine identities, one
         * code, -3 every time. What that code *means* is the open question, and
         * varying the certificate a tenth time cannot answer it.
         */
        public fun status(context: Context): ProbeRunner = ProbeRunner(
            context,
            name = "status",
            matrix = StatusProbe.matrix(),
            purpose = "whether -3 is a verdict on the certificate or a generic failure",
        )
    }
}
