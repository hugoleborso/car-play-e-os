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

/**
 * A timestamped record of everything that happened, shown on the phone.
 *
 * This exists because of the same constraint that drove the screen, taken one
 * step further. During a test the cable is in the car, so `logcat` is
 * unreachable — and the most valuable diagnostic of the whole exercise, the six
 * strings a head unit uses to identify itself, was only ever written to
 * `logcat`. It was therefore invisible exactly when it mattered.
 *
 * Every notable moment is appended here and shown on screen, so the difference
 * between "the cable did nothing", "the car connected but said nothing" and
 * "the car rejected us" is visible while standing next to the car rather than
 * hours later.
 */
public object ProbeEvents {

    /** What kind of moment this was, which decides how the screen colours it. */
    public enum class Kind {
        /** Something progressed. */
        PROGRESS,

        /** Worth acting on: the head unit did not behave as expected. */
        ATTENTION,

        /** A fault in the phone or the cable rather than a verdict from the car. */
        FAULT,
    }

    public data class Event(val at: String, val kind: Kind, val text: String) {
        public fun serialise(): String = "$at\t${kind.name}\t$text"

        public companion object {
            public fun parse(line: String): Event? {
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) return null
                val kind = runCatching { Kind.valueOf(parts[1]) }.getOrNull() ?: return null
                return Event(parts[0], kind, parts[2])
            }
        }
    }

    /** Broadcast whenever anything is appended, so the screen refreshes live. */
    public const val ACTION_EVENTS_CHANGED: String = "org.openaap.projection.EVENTS_CHANGED"

    private const val TAG = "openaap.events"
    private const val MAX_LINES = 400

    private fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "probe-events.log")

    /** Appends one moment and tells the screen. */
    public fun record(context: Context, kind: Kind, text: String) {
        val event = Event(timestamp(), kind, text)
        Log.i(TAG, "${kind.name}: $text")
        runCatching {
            val target = file(context)
            target.appendText(event.serialise() + "\n")
            // A test involves a dozen reconnections and the log is only ever
            // read from the end, so cap it rather than let it grow unbounded on
            // a device nobody is going to clean up.
            val lines = target.readLines()
            if (lines.size > MAX_LINES) {
                target.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }.onFailure { Log.w(TAG, "could not append an event", it) }

        context.sendBroadcast(Intent(ACTION_EVENTS_CHANGED).setPackage(context.packageName))
    }

    /** Everything recorded, oldest first. */
    public fun all(context: Context): List<Event> {
        val target = file(context)
        if (!target.isFile) return emptyList()
        return runCatching { target.readLines() }
            .getOrDefault(emptyList())
            .mapNotNull(Event::parse)
    }

    /** The most recent moments, newest last. */
    public fun recent(context: Context, count: Int = 12): List<Event> = all(context).takeLast(count)

    public fun clear(context: Context) {
        runCatching { file(context).delete() }
        context.sendBroadcast(Intent(ACTION_EVENTS_CHANGED).setPackage(context.packageName))
    }

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
}
