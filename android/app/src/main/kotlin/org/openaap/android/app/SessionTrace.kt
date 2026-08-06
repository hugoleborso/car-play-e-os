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
import java.util.concurrent.atomic.AtomicLong
import org.openaap.core.IncomingMessage
import org.openaap.core.ResolvedChannel
import org.openaap.protocol.Messages
import org.openaap.protocol.proto.ChannelAdvert
import org.openaap.protocol.proto.DiscoveryResponse

/**
 * A complete account of one projection session, written for someone who was not
 * in the car.
 *
 * The probe had a report and the projection path did not, which showed the
 * moment the first real session failed: it reported "the connection failed"
 * with six candidate causes and no way to tell them apart. This is the
 * equivalent instrument for the half of the protocol that begins after the
 * certificate is accepted.
 *
 * ### What it deliberately does and does not keep
 *
 * Every control-channel message is recorded individually, because there are
 * tens of them and each one is a decision point. Media frames are **counted,
 * not listed**: a minute of 30fps video is 1800 messages, and writing them all
 * would bury the twenty that matter and fill the phone. The counters still
 * answer the questions a frame log would — whether frames flowed, how many,
 * how big, and whether the head unit kept acknowledging them.
 *
 * No message bodies are stored. They are protobufs whose interesting fields are
 * extracted into structured entries anyway, and a full body dump would put the
 * head unit's certificate and the car's identifiers into a file people paste
 * into issue trackers.
 */
public class SessionTrace(private val context: Context) {

    /** One thing that happened, in order. */
    public data class Entry(
        val atMillis: Long,
        val kind: Kind,
        val text: String,
    )

    public enum class Kind {
        /** A session milestone: version agreed, TLS up, channel opened. */
        MILESTONE,

        /** A message we sent. */
        SENT,

        /** A message we received. */
        RECEIVED,

        /** Something the head unit told us about itself. */
        FACT,

        /** Something went wrong. */
        FAULT,
    }

    private val startedAt = System.currentTimeMillis()
    private val entries = mutableListOf<Entry>()

    // Media traffic is counted rather than listed. Atomic because frames are
    // produced on the encoder's pump thread while the session runs on its own.
    private val mediaMessages = AtomicLong()
    private val mediaBytes = AtomicLong()
    private val mediaAcks = AtomicLong()

    private var outcome: String = "session did not report an ending"
    private var succeeded = false

    /**
     * The furthest point the session reached, in the order it can reach them.
     *
     * Kept as an ordinal rather than derived from the transcript afterwards,
     * because "how far did it get" is the whole result of a variant run and
     * inferring it by pattern-matching log lines would break the first time a
     * message is reworded.
     */
    private var furthest = Reached.NOTHING

    /** Whether the head unit said anything at all. */
    private var heardFromCar = false

    /** How far a session got, in order. */
    public enum class Reached {
        NOTHING,
        VERSION,
        TLS,
        AUTHENTICATED,
        ASKED_DISCOVERY,
        DISCOVERED,
        CHANNEL_OPEN,
        STREAMING,
    }

    private fun reach(point: Reached) {
        if (point.ordinal > furthest.ordinal) furthest = point
    }

    // ---------------------------------------------------------------- capture

    @Synchronized
    private fun add(kind: Kind, text: String) {
        if (entries.size >= MAX_ENTRIES) return
        entries += Entry(System.currentTimeMillis() - startedAt, kind, text)
    }

    public fun milestone(text: String): Unit = add(Kind.MILESTONE, text)

    /** Marks progress through the sequence. Separate from the prose milestone. */
    public fun reached(point: Reached) {
        reach(point)
    }

    public fun fact(text: String): Unit = add(Kind.FACT, text)

    public fun fault(text: String): Unit = add(Kind.FAULT, text)

    /**
     * A message going out.
     *
     * Media messages are folded into counters. The test is the message id
     * rather than the channel, because a video channel also carries setup and
     * focus traffic that is very much worth seeing individually.
     */
    public fun sent(channel: Int, messageId: Int, size: Int, encrypted: Boolean, control: Boolean) {
        if (isMediaPayload(messageId)) {
            reach(Reached.STREAMING)
            mediaMessages.incrementAndGet()
            mediaBytes.addAndGet(size.toLong())
            return
        }
        if (messageId == Messages.DISCOVERY_REQUEST) reach(Reached.ASKED_DISCOVERY)
        add(
            Kind.SENT,
            "ch$channel ${Messages.describe(channel, messageId)} ${size}B" +
                (if (encrypted) " enc" else " plain") +
                (if (control) " CONTROL" else ""),
        )
    }

    public fun received(message: IncomingMessage) {
        heardFromCar = true
        if (message.messageId == Messages.MEDIA_ACK) {
            mediaAcks.incrementAndGet()
            return
        }
        // The head unit's own flags, recorded verbatim. Whether it sets CONTROL
        // on channel 0 is the fact that settles whether we should -- and it was
        // being decoded and thrown away.
        add(
            Kind.RECEIVED,
            "ch${message.channel} ${Messages.describe(message.channel, message.messageId)} " +
                "${message.body.size}B" +
                (if (message.wasEncrypted) " enc" else " plain") +
                (if (message.hadControlFlag) " CONTROL" else ""),
        )
    }

    private fun isMediaPayload(messageId: Int): Boolean =
        messageId == Messages.MEDIA_WITH_STAMP || messageId == Messages.MEDIA_PLAIN

    /**
     * Everything the head unit said about itself, in full.
     *
     * This is the part with lasting value beyond debugging one session. No
     * public source records what a MIB2 advertises: which services it offers,
     * on which channel ids — they are not fixed by the protocol and a unit may
     * scramble them — which video geometries and frame rates it will accept,
     * which audio formats, and how big its touch surface is. All of it arrives
     * in one message and is otherwise discarded.
     */
    public fun discovered(response: DiscoveryResponse, channels: List<ResolvedChannel>) {
        reach(Reached.DISCOVERED)
        fact("head unit label   : ${response.unitLabel}")
        fact("head unit maker   : ${response.unitMaker}")
        fact("head unit model   : ${response.unitModel}")
        if (response.hasFirmwareBuild()) fact("firmware build    : ${response.firmwareBuild}")
        if (response.hasFirmwareVersion()) fact("firmware version  : ${response.firmwareVersion}")
        if (response.hasVehicleModel()) fact("vehicle model     : ${response.vehicleModel}")
        if (response.hasVehicleYear()) fact("vehicle year      : ${response.vehicleYear}")
        if (response.hasSteeringOnLeft()) fact("steering on left  : ${response.steeringOnLeft}")

        // Deliberately not recorded in full. This is a vehicle identifier, and
        // the whole purpose of this report is that people send it to someone
        // else or paste it into an issue. Its presence and length are the only
        // parts anyone debugging needs; the value itself identifies a car.
        if (response.hasVehicleSerial()) {
            fact("vehicle serial    : present, ${response.vehicleSerial.length} chars (not recorded)")
        }
        fact("channels offered  : ${channels.size}")
        channels.forEach { channel -> fact(describe(channel)) }
    }

    private fun describe(channel: ResolvedChannel): String = buildString {
        append("  ch${channel.id} ${channel.kind}")
        val advert: ChannelAdvert = channel.advert

        if (advert.hasMediaSink()) {
            val sink = advert.mediaSink
            append("\n      stream=${sink.stream}")
            if (sink.hasPurpose()) append(" purpose=${sink.purpose}")
            sink.videoFormatsList.forEachIndexed { index, format ->
                append(
                    "\n      video[$index] geometry=${format.geometry} rate=${format.rate}" +
                        " density=${format.density} inset=${format.insetWidth}x${format.insetHeight}"
                )
            }
            sink.audioFormatsList.forEachIndexed { index, format ->
                append(
                    "\n      audio[$index] ${format.sampleRate}Hz ${format.sampleBits}bit" +
                        " ${format.channels}ch"
                )
            }
        }
        if (advert.hasMediaSource()) {
            val source = advert.mediaSource
            append("\n      source stream=${source.stream}")
            if (source.hasAudioFormat()) {
                val format = source.audioFormat
                append(" ${format.sampleRate}Hz ${format.sampleBits}bit ${format.channels}ch")
            }
        }
        if (advert.hasInput()) {
            val input = advert.input
            if (input.hasTouchscreen()) {
                append("\n      touchscreen ${input.touchscreen.width}x${input.touchscreen.height}")
            }
            if (input.hasTouchpad()) {
                append("\n      touchpad ${input.touchpad.width}x${input.touchpad.height}")
            }
            if (input.knownScanCodesCount > 0) {
                append("\n      scan codes ${input.knownScanCodesList}")
            }
        }
        if (advert.hasSensors()) {
            append("\n      sensors ${advert.sensors.sensorsList.map { it.kind }}")
        }
        if (advert.hasBluetooth()) {
            val bluetooth = advert.bluetooth
            append("\n      bluetooth ${bluetooth.unitAddress} ${bluetooth.pairingKindsList}")
        }
    }

    public fun ended(reason: String, cause: Throwable?) {
        outcome = reason
        succeeded = cause == null
        add(if (cause == null) Kind.MILESTONE else Kind.FAULT, "session ended: $reason")
    }

    // ----------------------------------------------------------------- render

    public val reportFile: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "projection-report.txt",
        )

    /**
     * Writes the report.
     *
     * Called from the session's `finally`, so it runs whether the session ended
     * cleanly, threw, or had the cable pulled out of it — the three cases are
     * equally worth a report and the failing ones more so.
     */
    public fun write(): File {
        val file = reportFile
        runCatching { file.writeText(render()) }
            .onFailure { Log.w(TAG, "could not write the projection report", it) }
        return file
    }

    public fun render(): String = buildString {
        appendLine("openaap projection session report")
        appendLine("=".repeat(78))
        appendLine("generated  : ${CLOCK.format(Date())}")
        appendLine("app build  : ${BuildConfig.GIT_REVISION}")
        appendLine("phone      : ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("rom build  : ${Build.DISPLAY}")
        appendLine("duration   : ${System.currentTimeMillis() - startedAt} ms")
        appendLine("outcome    : $outcome")
        appendLine()

        appendLine("Media")
        appendLine("-".repeat(78))
        val frames = mediaMessages.get()
        if (frames == 0L) {
            // The single most important line when a car screen stays black, and
            // the reason media is counted at all.
            appendLine("  No media message was ever sent. The session never reached the point")
            appendLine("  of streaming, so nothing could have appeared on the car screen.")
        } else {
            appendLine("  messages sent      : $frames")
            appendLine("  bytes sent         : ${mediaBytes.get()}")
            appendLine("  acknowledgements   : ${mediaAcks.get()}")
            appendLine(
                "  average size       : ${mediaBytes.get() / frames} B"
            )
            if (mediaAcks.get() == 0L) {
                appendLine()
                appendLine("  The head unit acknowledged none of them. Frames left the phone and")
                appendLine("  the car did not confirm any, so suspect the stream's contents rather")
                appendLine("  than the link: wrong profile, missing codec config, or a geometry it")
                appendLine("  cannot decode.")
            }
        }
        appendLine()

        val facts = entries.filter { it.kind == Kind.FACT }
        if (facts.isNotEmpty()) {
            appendLine("What the head unit says it is")
            appendLine("-".repeat(78))
            appendLine("  No public source records this for a MIB2. Channel ids are not fixed by")
            appendLine("  the protocol, so which service sits on which id is itself a finding.")
            appendLine()
            facts.forEach { appendLine("  ${it.text}") }
            appendLine()
        }

        appendLine("Transcript")
        appendLine("-".repeat(78))
        if (entries.none { it.kind != Kind.FACT }) {
            appendLine("  Nothing was recorded. The session did not start.")
        }
        entries.filter { it.kind != Kind.FACT }.forEach { entry ->
            appendLine("  ${entry.atMillis.toString().padStart(6)} ms  ${marker(entry.kind)} ${entry.text}")
        }
        if (entries.size >= MAX_ENTRIES) {
            // Never let a truncated transcript read as a complete one.
            appendLine()
            appendLine("  [transcript truncated at $MAX_ENTRIES entries]")
        }
        appendLine()

        appendLine("Legend")
        appendLine("-".repeat(78))
        appendLine("  ->  sent by the phone        <-  sent by the car")
        appendLine("  **  session milestone        !!  fault")
        appendLine()
        appendLine("  Media payload messages are counted above rather than listed here; a")
        appendLine("  minute of video is well over a thousand of them. Everything else is")
        appendLine("  listed individually.")
    }

    private fun marker(kind: Kind): String = when (kind) {
        Kind.SENT -> "->"
        Kind.RECEIVED -> "<-"
        Kind.MILESTONE -> "**"
        Kind.FAULT -> "!!"
        Kind.FACT -> "  "
    }

    /** What this session achieved, for the variant matrix. */
    public fun outcome(): VariantRunner.Outcome = VariantRunner.Outcome(
        milestone = furthest.name,
        fault = outcome.takeIf { !succeeded },
        durationMillis = System.currentTimeMillis() - startedAt,
        streamed = mediaMessages.get() > 0,
        // "Never spoke" is about the head unit, not about us. A variant that
        // deliberately stays silent still counts as measured if the car said
        // anything at all.
        reachedNothing = !heardFromCar,
    )

    private companion object {
        const val TAG = "openaap.trace"

        /**
         * Cap on individually-recorded entries.
         *
         * Media is already counted rather than listed, so reaching this means
         * genuinely pathological control traffic — a head unit in a retry loop,
         * say. Generous enough that no normal session comes close.
         */
        const val MAX_ENTRIES = 2000

        val CLOCK = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
