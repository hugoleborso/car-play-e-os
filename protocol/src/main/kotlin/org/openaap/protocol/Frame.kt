/*
 * Copyright 2026 The openaap authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openaap.protocol

/**
 * The AAP link-layer frame.
 *
 * Every byte that crosses the wire between a phone and a head unit -- over USB
 * bulk endpoints in accessory mode, or over TCP for wireless projection -- is
 * carried in a frame with this shape:
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    channel    |     flags     |         payload length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          total length (only when FIRST is set and LAST is not) |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            payload                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * `payload length` and `total length` are both big-endian and unsigned.
 *
 * The four-byte `total length` field is present **only** on the opening frame
 * of a fragmented message, i.e. when [FrameFlags.FIRST] is set and
 * [FrameFlags.LAST] is not. A message small enough to fit in one frame carries
 * both bits (a "bulk" frame) and therefore has no total-length field. This
 * asymmetry is the single most common source of desynchronisation bugs in AAP
 * implementations, so [FrameHeader.headerSize] derives it from the flags rather
 * than letting callers guess.
 */
public data class Frame(
    val header: FrameHeader,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()

    override fun toString(): String =
        "Frame(channel=${header.channel}, flags=${header.flags}, payload=${payload.size}B)"
}

/**
 * Frame flag bits.
 *
 * The low two bits encode the fragmentation state, the next bit selects the
 * control vs. service message namespace, and the fourth bit marks a payload as
 * carrying TLS ciphertext.
 */
public object FrameFlags {
    /** First fragment of a message. */
    public const val FIRST: Int = 1 shl 0

    /** Last fragment of a message. */
    public const val LAST: Int = 1 shl 1

    /**
     * The payload belongs to the control namespace rather than to the
     * service-specific namespace of the channel it is addressed to.
     */
    public const val CONTROL: Int = 1 shl 2

    /** The payload is a TLS record and must be fed to the TLS engine. */
    public const val ENCRYPTED: Int = 1 shl 3

    /** A complete, unfragmented message: both FIRST and LAST. */
    public const val BULK: Int = FIRST or LAST

    public fun describe(flags: Int): String = buildList {
        when (flags and BULK) {
            BULK -> add("BULK")
            FIRST -> add("FIRST")
            LAST -> add("LAST")
            else -> add("MIDDLE")
        }
        if (flags and CONTROL != 0) add("CONTROL")
        if (flags and ENCRYPTED != 0) add("ENCRYPTED")
    }.joinToString("|")
}

/** The fixed part of a [Frame], excluding the payload. */
public data class FrameHeader(
    val channel: Int,
    val flags: Int,
    val payloadLength: Int,
    /**
     * Total length of the reassembled message, present only on the opening
     * frame of a fragmented message. `null` otherwise.
     */
    val totalLength: Long? = null,
) {
    init {
        require(channel in 0..0xFF) { "channel out of range: $channel" }
        require(flags in 0..0xFF) { "flags out of range: $flags" }
        require(payloadLength in 0..MAX_PAYLOAD) {
            "payload length out of range: $payloadLength"
        }
        val expectsTotal = isFirst && !isLast
        require(expectsTotal == (totalLength != null)) {
            "totalLength must be present exactly on FIRST-without-LAST frames " +
                "(flags=${FrameFlags.describe(flags)}, totalLength=$totalLength)"
        }
    }

    public val isFirst: Boolean get() = flags and FrameFlags.FIRST != 0
    public val isLast: Boolean get() = flags and FrameFlags.LAST != 0
    public val isControl: Boolean get() = flags and FrameFlags.CONTROL != 0
    public val isEncrypted: Boolean get() = flags and FrameFlags.ENCRYPTED != 0

    /** Number of bytes this header occupies on the wire: 4, or 8 when fragmented. */
    public val headerSize: Int get() = if (totalLength != null) EXTENDED_HEADER_SIZE else BASE_HEADER_SIZE

    public companion object {
        /** channel + flags + uint16 length. */
        public const val BASE_HEADER_SIZE: Int = 4

        /** Base header plus the uint32 total-length field. */
        public const val EXTENDED_HEADER_SIZE: Int = 8

        /**
         * Maximum payload a single frame can carry.
         *
         * The length field is 16 bits wide, but head units are only required to
         * accept frames up to 16 KiB, so senders must fragment above that.
         */
        public const val MAX_PAYLOAD: Int = 0xFFFF

        /** Payload size this implementation fragments at when sending. */
        public const val SEND_FRAGMENT_SIZE: Int = 16 * 1024
    }
}
