/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.openaap.core.SessionVariant

/**
 * Walks the projection variants across successive connections, one per session.
 *
 * Exactly the shape of [ProbeRunner], for the same reason: a head unit gives us
 * one attempt per connection, the position has to survive the process being
 * torn down between them, and the result is only worth anything if the person
 * in the car can see it accumulating.
 *
 * The difference from the credential matrix is what counts as an outcome. A
 * certificate probe had a verdict to record; here the outcome is *how far the
 * session got*, which is why every attempt keeps the milestone it reached and
 * the fault that stopped it rather than a pass or fail.
 */
public class VariantRunner(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val matrix = SessionVariant.matrix()

    private val directory: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /** The accumulated comparison across every variant tried. */
    public val summaryFile: File get() = File(directory, "projection-matrix.txt")

    /** Index of the variant the next connection will use. */
    public val position: Int get() = preferences.getInt(KEY_POSITION, 0)

    public val size: Int get() = matrix.size

    public val complete: Boolean get() = position >= matrix.size

    /** The variant the next connection will run, or `null` once every one has. */
    public fun next(): SessionVariant? = matrix.getOrNull(position)

    public fun reset() {
        preferences.edit().putInt(KEY_POSITION, 0).putInt(KEY_ABORTS, 0).apply()
        runCatching { summaryFile.delete() }
        broadcast()
    }

    /**
     * Records how one variant fared and decides what the next connection does.
     *
     * A session where the head unit never spoke measured nothing about the
     * variant, so it does not consume it — the same defect that cost four of
     * nine credential probes in the first field run, and the same bounded
     * retry that fixed it. Without the bound, a cable that has come loose would
     * sit on variant one forever and never produce the summary saying so.
     */
    public fun record(variant: SessionVariant, outcome: Outcome) {
        if (outcome.reachedNothing) {
            val aborts = preferences.getInt(KEY_ABORTS, 0) + 1
            if (aborts < MAX_ABORTS) {
                preferences.edit().putInt(KEY_ABORTS, aborts).apply()
                ProbeEvents.record(
                    context,
                    ProbeEvents.Kind.PROGRESS,
                    "The car said nothing this time. Retrying ${variant.id} " +
                        "(attempt ${aborts + 1} of $MAX_ABORTS) — no variant was used up.",
                )
                broadcast()
                return
            }
        }

        preferences.edit().putInt(KEY_POSITION, position + 1).putInt(KEY_ABORTS, 0).apply()
        append(variant, outcome)
        broadcast()
        Log.i(TAG, "variant ${variant.id}: ${outcome.milestone} / ${outcome.fault ?: "no fault"}")
    }

    /** What one variant achieved. */
    public data class Outcome(
        /** The furthest point the session reached, in words. */
        val milestone: String,
        /** What stopped it, if anything. */
        val fault: String?,
        /** How long the session lasted. */
        val durationMillis: Long,
        /** Whether any media message was sent. */
        val streamed: Boolean,
        /** True when the head unit never spoke at all, so nothing was measured. */
        val reachedNothing: Boolean,
    )

    private fun append(variant: SessionVariant, outcome: Outcome) {
        val line = buildString {
            append(CLOCK.format(Date()))
            append("  ")
            append(variant.id.padEnd(26))
            append(outcome.milestone.padEnd(34))
            append(if (outcome.streamed) "STREAMED  " else "          ")
            append(outcome.durationMillis.toString().padStart(6))
            append(" ms")
            outcome.fault?.let { append("  ").append(it) }
        }
        runCatching {
            if (!summaryFile.isFile) summaryFile.writeText(header())
            summaryFile.appendText(line + "\n")
        }.onFailure { Log.w(TAG, "could not append to the matrix summary", it) }
    }

    private fun header(): String = buildString {
        appendLine("openaap projection variant matrix")
        appendLine("=".repeat(78))
        appendLine("Each connection runs one variant of what the phone does after the car")
        appendLine("accepts its certificate. Nothing here is a measured fact about the")
        appendLine("protocol: every variant is a reading the public record leaves open, and")
        appendLine("the point is to find out which one this car agrees with.")
        appendLine()
        matrix.forEachIndexed { index, variant ->
            appendLine("  ${index + 1}. ${variant.id.padEnd(26)}${variant.varies}")
        }
        appendLine()
        appendLine("Results")
        appendLine("-".repeat(78))
    }

    /** The summary so far, for the screen. */
    public fun summary(): String? =
        summaryFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun broadcast() =
        context.sendBroadcast(Intent(ACTION_PROBE_UPDATED).setPackage(context.packageName))

    public companion object {
        private const val TAG = "openaap.variant"
        private const val PREFERENCES = "variant"
        private const val KEY_POSITION = "position"
        private const val KEY_ABORTS = "aborts"
        private const val MAX_ABORTS = 3

        /** Reuses the probe's refresh signal: one screen, one reason to redraw. */
        public const val ACTION_PROBE_UPDATED: String = ProbeRunner.ACTION_PROBE_UPDATED

        private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}
