/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.protocol

/**
 * Message identifiers and the rule that decides which namespace a message id is
 * read from.
 */
public object Messages {

    /** The only channel id fixed by the protocol. Every other one is assigned by the head unit. */
    public const val CONTROL_CHANNEL: Int = 0

    // --- Control namespace -------------------------------------------------

    public const val VERSION_REQUEST: Int = 0x0001
    public const val VERSION_RESPONSE: Int = 0x0002
    public const val TLS_HANDSHAKE: Int = 0x0003
    public const val AUTH_SUCCEEDED: Int = 0x0004
    public const val DISCOVERY_REQUEST: Int = 0x0005
    public const val DISCOVERY_RESPONSE: Int = 0x0006
    public const val CHANNEL_OPEN_REQUEST: Int = 0x0007
    public const val CHANNEL_OPEN_RESPONSE: Int = 0x0008
    public const val PING_REQUEST: Int = 0x000b
    public const val PING_RESPONSE: Int = 0x000c
    public const val NAVIGATION_FOCUS_REQUEST: Int = 0x000d
    public const val NAVIGATION_FOCUS_RESPONSE: Int = 0x000e
    public const val TEARDOWN_REQUEST: Int = 0x000f
    public const val TEARDOWN_RESPONSE: Int = 0x0010
    public const val VOICE_SESSION_REQUEST: Int = 0x0011
    public const val AUDIO_FOCUS_REQUEST: Int = 0x0012
    public const val AUDIO_FOCUS_RESPONSE: Int = 0x0013

    // --- Media channel namespace -------------------------------------------

    public const val MEDIA_WITH_STAMP: Int = 0x0000
    public const val MEDIA_PLAIN: Int = 0x0001
    public const val MEDIA_SETUP_REQUEST: Int = 0x8000
    public const val MEDIA_START_INDICATION: Int = 0x8001
    public const val MEDIA_STOP_INDICATION: Int = 0x8002
    public const val MEDIA_SETUP_RESPONSE: Int = 0x8003
    public const val MEDIA_ACK: Int = 0x8004
    public const val MICROPHONE_OPEN_REQUEST: Int = 0x8005
    public const val MICROPHONE_OPEN_RESPONSE: Int = 0x8006
    public const val VIDEO_FOCUS_REQUEST: Int = 0x8007
    public const val VIDEO_FOCUS_INDICATION: Int = 0x8008

    // --- Input channel namespace -------------------------------------------

    public const val INPUT_EVENT: Int = 0x8001
    public const val KEY_BINDING_REQUEST: Int = 0x8002
    public const val KEY_BINDING_RESPONSE: Int = 0x8003

    // --- Sensor channel namespace ------------------------------------------

    public const val SENSOR_SUBSCRIBE_REQUEST: Int = 0x8001
    public const val SENSOR_SUBSCRIBE_RESPONSE: Int = 0x8002
    public const val SENSOR_READINGS: Int = 0x8003

    // --- Bluetooth channel namespace ---------------------------------------

    public const val BLUETOOTH_PAIRING_REQUEST: Int = 0x8001
    public const val BLUETOOTH_PAIRING_RESPONSE: Int = 0x8002
    public const val BLUETOOTH_AUTH_DATA: Int = 0x8003

    /**
     * Whether a message carries the control flag.
     *
     * The flag does not mean "channel 0" -- it means "read this message id from
     * the control namespace rather than from this channel's own namespace". A
     * channel-open request addressed to the video channel is the everyday case:
     * it travels on the video channel but its id, 0x0007, belongs to the control
     * enumeration, so the flag is set.
     *
     * The lower bound of 2 exists to exclude the two media ids, 0x0000 and
     * 0x0001, which are service messages despite being numerically below the
     * control ids. Without that clause every video frame would be mislabelled
     * and the head unit would try to parse H.264 as a control message.
     */
    public fun needsControlFlag(channel: Int, messageId: Int): Boolean =
        channel != CONTROL_CHANNEL && messageId >= 2 && messageId < 0x8000

    /** Splits a reassembled payload into its message id and body. */
    public fun split(payload: ByteArray): Pair<Int, ByteArray> {
        require(payload.size >= 2) { "message payload of ${payload.size} bytes has no id" }
        return readUInt16(payload, 0) to payload.copyOfRange(2, payload.size)
    }

    /** Prefixes a body with its big-endian message id. */
    public fun frame(messageId: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(2 + body.size)
        writeUInt16(out, 0, messageId)
        body.copyInto(out, 2)
        return out
    }

    /** Renders an id for logs, resolving it in the namespace the channel implies. */
    public fun describe(channel: Int, messageId: Int): String {
        val name = if (channel == CONTROL_CHANNEL || needsControlFlag(channel, messageId)) {
            controlNames[messageId]
        } else {
            null
        } ?: "0x%04x".format(messageId)
        return name
    }

    private val controlNames: Map<Int, String> = mapOf(
        VERSION_REQUEST to "VersionRequest",
        VERSION_RESPONSE to "VersionResponse",
        TLS_HANDSHAKE to "TlsHandshake",
        AUTH_SUCCEEDED to "AuthSucceeded",
        DISCOVERY_REQUEST to "DiscoveryRequest",
        DISCOVERY_RESPONSE to "DiscoveryResponse",
        CHANNEL_OPEN_REQUEST to "ChannelOpenRequest",
        CHANNEL_OPEN_RESPONSE to "ChannelOpenResponse",
        PING_REQUEST to "PingRequest",
        PING_RESPONSE to "PingResponse",
        NAVIGATION_FOCUS_REQUEST to "NavigationFocusRequest",
        NAVIGATION_FOCUS_RESPONSE to "NavigationFocusResponse",
        TEARDOWN_REQUEST to "TeardownRequest",
        TEARDOWN_RESPONSE to "TeardownResponse",
        VOICE_SESSION_REQUEST to "VoiceSessionRequest",
        AUDIO_FOCUS_REQUEST to "AudioFocusRequest",
        AUDIO_FOCUS_RESPONSE to "AudioFocusResponse",
    )
}

/**
 * The version exchange, which is raw bytes rather than protobuf.
 *
 * It happens before TLS and before any protobuf is involved, so it is the one
 * place in the protocol where field layout is expressed in offsets.
 */
public object VersionExchange {

    public const val STATUS_MATCH: Int = 0x0000
    public const val STATUS_MISMATCH: Int = 0xFFFF

    public data class Request(val major: Int, val minor: Int)

    public data class Response(val major: Int, val minor: Int, val status: Int) {
        public val matched: Boolean get() = status == STATUS_MATCH
    }

    public fun parseRequest(body: ByteArray): Request {
        require(body.size >= 4) { "version request is ${body.size} bytes, expected at least 4" }
        return Request(readUInt16(body, 0), readUInt16(body, 2))
    }

    public fun encodeRequest(request: Request): ByteArray = ByteArray(4).also {
        writeUInt16(it, 0, request.major)
        writeUInt16(it, 2, request.minor)
    }

    public fun parseResponse(body: ByteArray): Response {
        require(body.size >= 6) { "version response is ${body.size} bytes, expected at least 6" }
        return Response(readUInt16(body, 0), readUInt16(body, 2), readUInt16(body, 4))
    }

    public fun encodeResponse(response: Response): ByteArray = ByteArray(6).also {
        writeUInt16(it, 0, response.major)
        writeUInt16(it, 2, response.minor)
        writeUInt16(it, 4, response.status)
    }
}

/**
 * The 8-byte presentation stamp that precedes media bytes on a timestamped
 * media message. Big-endian microseconds on a monotonic media clock.
 */
public object MediaStamp {

    public const val SIZE: Int = 8

    public fun read(payload: ByteArray, offset: Int = 0): Long {
        require(payload.size - offset >= SIZE) { "media payload too short to hold a stamp" }
        var value = 0L
        for (index in 0 until SIZE) {
            value = (value shl 8) or (payload[offset + index].toLong() and 0xFF)
        }
        return value
    }

    public fun write(microseconds: Long): ByteArray = ByteArray(SIZE).also {
        for (index in 0 until SIZE) {
            it[index] = ((microseconds ushr ((SIZE - 1 - index) * 8)) and 0xFF).toByte()
        }
    }
}
