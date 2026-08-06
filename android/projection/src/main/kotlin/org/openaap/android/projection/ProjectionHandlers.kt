/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.content.Context
import android.util.Log
import org.openaap.core.ResolvedChannel
import org.openaap.core.ServiceHandler
import org.openaap.core.ServiceHandlerFactory
import org.openaap.core.ServiceKind
import org.openaap.services.MediaService

/**
 * Builds the handlers for a projection session, and holds the pieces that two
 * channels have to agree about.
 *
 * Video and input are negotiated as separate channels but describe one screen:
 * the touch handler has to deliver into the same view hierarchy the video
 * handler is encoding, and it has to know the geometry that hierarchy was laid
 * out at. Those two facts are only known once the video channel has resolved a
 * format, and the head unit is free to open the channels in either order.
 *
 * So this outlives both handlers and hands the input channel a renderer that
 * may not exist yet. An input channel that opens first is not an error — a few
 * early touches are dropped, which nobody notices, and the alternative is
 * refusing the channel and never getting touches at all.
 */
public class ProjectionHandlers(
    private val context: Context,
    private val listener: MediaService.Listener = MediaService.Listener.NONE,
) : ServiceHandlerFactory {

    private var video: VideoProducer? = null

    /** The format chosen for the projected display, once video has been negotiated. */
    public var format: ResolvedVideoFormat? = null
        private set

    override fun create(channel: ResolvedChannel): ServiceHandler? = when (channel.kind) {
        ServiceKind.VIDEO -> videoHandler(channel)
        ServiceKind.INPUT -> inputHandler(channel)

        // Declined for now, and deliberately rather than by omission. Audio
        // needs a source: this app has none of its own yet, and capturing
        // another app's output needs a MediaProjection consent dialog that
        // cannot be raised from a car. Declining a channel is legitimate and
        // leaves a working, quiet session rather than a failed one.
        ServiceKind.MEDIA_AUDIO, ServiceKind.SPEECH_AUDIO, ServiceKind.SYSTEM_AUDIO,
        ServiceKind.MICROPHONE -> {
            Log.i(TAG, "declining ${channel.kind} on channel ${channel.id}: no audio path yet")
            null
        }

        else -> {
            Log.i(TAG, "declining ${channel.kind} on channel ${channel.id}")
            null
        }
    }

    private fun videoHandler(channel: ResolvedChannel): ServiceHandler? {
        // Video geometry rides on the media-sink descriptor rather than a
        // descriptor of its own: one channel type carries both audio and video
        // sinks, distinguished by which format list is populated.
        val advertised = channel.advert
            .takeIf { it.hasMediaSink() }
            ?.mediaSink
            ?.videoFormatsList
            .orEmpty()
        val chosen = VideoFormats.choose(advertised)
        if (chosen == null) {
            // Every advertised format used values we cannot interpret. Refusing
            // is right: projecting at a guessed geometry fails as a garbled or
            // wrongly-scaled picture, which is far harder to diagnose than a
            // channel that never opened.
            Log.w(
                TAG,
                "head unit advertised ${advertised.size} video format(s), none interpretable; " +
                    "declining the video channel",
            )
            return null
        }

        val (_, resolved) = chosen
        format = resolved
        Log.i(TAG, "projecting at $resolved")

        val producer = VideoProducer(context, resolved) { displayContext ->
            CarUi.build(displayContext, resolved)
        }
        video = producer
        return MediaService(channel, producer, listener)
    }

    private fun inputHandler(channel: ResolvedChannel): ServiceHandler? {
        val producer = video
        val resolved = format
        if (producer == null || resolved == null) {
            // The head unit opened input before video. Accepting anyway would
            // mean touching a renderer that does not exist; declining loses
            // touch for the whole session. Neither is good, and this is the
            // rarer case -- head units open video first in every trace we have.
            Log.w(TAG, "input channel offered before video; declining, touches will not work")
            return null
        }
        return TouchService(channel, producer.touchTarget, resolved)
    }

    /** Tears down anything still running. Safe to call more than once. */
    public fun close() {
        video?.stop()
        video = null
    }

    private companion object {
        const val TAG = "openaap.handlers"
    }
}
