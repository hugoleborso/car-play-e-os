/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.projection

import android.content.Context
import android.util.Log
import android.view.View
import kotlin.concurrent.thread
import org.openaap.services.MediaProducer

/**
 * The video half of a projection session: a view hierarchy, an encoder, and a
 * thread that moves frames between them.
 *
 * Owns the ordering that makes a car display light up rather than stay black.
 * The encoder must exist before the renderer, because the renderer draws onto a
 * surface the encoder creates; and the first thing sent after focus must be a
 * keyframe, because the head unit starts decoding when the driver selects
 * projection rather than when we start sending.
 */
public class VideoProducer(
    private val context: Context,
    private val format: ResolvedVideoFormat,
    /**
     * Builds the interface to project.
     *
     * A factory rather than a view, because the hierarchy has to be created
     * against a display context sized for the car and cannot outlive a session:
     * a view already attached to a previous session's renderer would silently
     * draw nothing.
     */
    private val content: (Context) -> View,
) : MediaProducer {

    private val encoder = H264Encoder(format)
    private val renderer = SurfaceRenderer(context, format)

    private var pump: Thread? = null

    @Volatile
    private var running = false

    /** Exposed so the input channel can route touches into the projected UI. */
    public val touchTarget: SurfaceRenderer get() = renderer

    override fun start(format: Int, sink: (frameMicros: Long, payload: ByteArray) -> Unit) {
        if (running) return
        running = true

        val surface = encoder.start()
        renderer.start(surface, content(context))

        // The head unit has just taken focus and has no reference frame. Asking
        // now means the first access unit it sees is decodable, rather than the
        // up-to-a-second wait for the periodic keyframe -- which reads as
        // projection that took a moment to wake up.
        encoder.requestKeyframe()

        pump = thread(name = "openaap-video", isDaemon = true) {
            Log.i(TAG, "video pump started for $format")
            // Sends inline rather than queueing. MediaService owns the credit
            // window and blocks when the head unit has not acknowledged, so
            // back-pressure arrives here as a slow sink -- which is exactly the
            // right place for it. A queue in between would absorb the signal
            // and then overflow.
            while (running) {
                val unit = encoder.drain() ?: continue
                runCatching { sink(unit.presentationMicros, unit.payload) }
                    .onFailure {
                        Log.w(TAG, "sink rejected a frame; ending the video stream", it)
                        running = false
                    }
            }
            Log.i(TAG, "video pump ended")
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        // Renderer first: it draws onto a surface the encoder owns, and
        // releasing that surface while a draw is in flight is the one ordering
        // that produces a native crash rather than an exception.
        renderer.stop()
        encoder.stop()
        pump?.interrupt()
        pump = null
    }

    private companion object {
        const val TAG = "openaap.video"
    }
}
