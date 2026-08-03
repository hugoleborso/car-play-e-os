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

/** Raised when the peer sends something that cannot be parsed as a frame. */
public class FrameFormatException(message: String) : RuntimeException(message)

/**
 * Incremental frame parser.
 *
 * A transport hands it whatever bytes it managed to read -- USB bulk transfers
 * and TCP segments both split frames at arbitrary offsets -- and it emits whole
 * frames as they become available. It never blocks and never assumes a read
 * boundary lines up with a frame boundary.
 */
public class FrameDecoder(initialCapacity: Int = 64 * 1024) {

    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    /** Bytes currently held back waiting for the rest of their frame. */
    public val buffered: Int get() = size

    /** Appends [length] bytes from [data] starting at [offset] to the parse buffer. */
    public fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "feed out of bounds: offset=$offset length=$length capacity=${data.size}"
        }
        ensureCapacity(size + length)
        data.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    /**
     * Returns the next complete frame, or `null` when more bytes are needed.
     *
     * Call in a loop until it returns `null`: a single [feed] can yield many
     * frames, which happens constantly on the video channel.
     */
    public fun poll(): Frame? {
        if (size < FrameHeader.BASE_HEADER_SIZE) return null

        val channel = buffer[0].toInt() and 0xFF
        val flags = buffer[1].toInt() and 0xFF
        val payloadLength = readUInt16(buffer, 2)

        val fragmented = (flags and FrameFlags.FIRST != 0) && (flags and FrameFlags.LAST == 0)
        val headerSize =
            if (fragmented) FrameHeader.EXTENDED_HEADER_SIZE else FrameHeader.BASE_HEADER_SIZE

        if (size < headerSize) return null

        val totalLength: Long? = if (fragmented) readUInt32(buffer, 4) else null
        // The two lengths are only comparable on a plaintext frame. On an
        // encrypted one the payload length counts this frame's ciphertext while
        // the total counts the whole message's plaintext, so TLS overhead makes
        // the first legitimately exceed the second whenever a message is barely
        // longer than one fragment. Applying the check there would reject valid
        // traffic.
        if (totalLength != null &&
            (flags and FrameFlags.ENCRYPTED == 0) &&
            totalLength < payloadLength
        ) {
            throw FrameFormatException(
                "total length $totalLength smaller than first fragment $payloadLength on channel $channel"
            )
        }

        val frameSize = headerSize + payloadLength
        if (size < frameSize) return null

        val payload = buffer.copyOfRange(headerSize, frameSize)
        consume(frameSize)

        return Frame(FrameHeader(channel, flags, payloadLength, totalLength), payload)
    }

    private fun consume(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}

/** Serialises frames, fragmenting payloads that exceed the per-frame limit. */
public object FrameEncoder {

    /**
     * Encodes one logical message as one or more frames.
     *
     * [flags] should carry only the semantic bits ([FrameFlags.CONTROL],
     * [FrameFlags.ENCRYPTED]); the fragmentation bits are computed here.
     *
     * [encrypt] is applied to each fragment *after* splitting. This ordering is
     * mandated by the protocol rather than chosen: the announced total length
     * describes the plaintext message, while each frame's length field
     * describes that frame's ciphertext. Encrypting first and splitting the
     * result would put whole TLS records across frame boundaries and make the
     * two length fields inconsistent. Splitting first also means every frame
     * carries complete records, so a receiver can decrypt frame by frame
     * without buffering a whole message.
     *
     * Passing no [encrypt] while setting [FrameFlags.ENCRYPTED] is allowed and
     * means the caller has already encrypted a single frame's worth of bytes.
     * It is not valid for a payload large enough to fragment, because the
     * announced total would then describe ciphertext. Keeping the flag and the
     * encryptor consistent is the session layer's job; this is a codec.
     */
    public fun encode(
        channel: Int,
        flags: Int,
        payload: ByteArray,
        fragmentSize: Int = FrameHeader.SEND_FRAGMENT_SIZE,
        encrypt: ((ByteArray) -> ByteArray)? = null,
    ): List<ByteArray> {
        require(fragmentSize in 1..FrameHeader.MAX_PAYLOAD) { "bad fragment size: $fragmentSize" }
        val semanticFlags = flags and (FrameFlags.CONTROL or FrameFlags.ENCRYPTED)

        if (payload.size <= fragmentSize) {
            val body = encrypt?.invoke(payload) ?: payload
            return listOf(serialise(channel, semanticFlags or FrameFlags.BULK, body, null))
        }

        val out = ArrayList<ByteArray>((payload.size + fragmentSize - 1) / fragmentSize)
        var offset = 0
        while (offset < payload.size) {
            val chunk = minOf(fragmentSize, payload.size - offset)
            val first = offset == 0
            val last = offset + chunk >= payload.size
            var frameFlags = semanticFlags
            if (first) frameFlags = frameFlags or FrameFlags.FIRST
            if (last) frameFlags = frameFlags or FrameFlags.LAST

            val plaintext = payload.copyOfRange(offset, offset + chunk)
            val body = encrypt?.invoke(plaintext) ?: plaintext
            // Only the opening fragment announces the size, and it announces the
            // size of the plaintext message, not of the bytes on the wire.
            val total = if (first) payload.size.toLong() else null
            out += serialise(channel, frameFlags, body, total)
            offset += chunk
        }
        return out
    }

    private fun serialise(
        channel: Int,
        flags: Int,
        body: ByteArray,
        totalLength: Long?,
    ): ByteArray {
        require(body.size <= FrameHeader.MAX_PAYLOAD) {
            "frame body of ${body.size} bytes exceeds the 16-bit length field"
        }
        val headerSize =
            if (totalLength != null) FrameHeader.EXTENDED_HEADER_SIZE else FrameHeader.BASE_HEADER_SIZE
        val out = ByteArray(headerSize + body.size)
        out[0] = channel.toByte()
        out[1] = flags.toByte()
        writeUInt16(out, 2, body.size)
        if (totalLength != null) writeUInt32(out, 4, totalLength)
        body.copyInto(out, headerSize)
        return out
    }
}

/**
 * Reassembles fragmented messages, one independent stream per channel.
 *
 * Channels interleave freely on the wire: a large video frame is fragmented
 * across many frames while ping and sensor traffic slips between them, so
 * reassembly state must be per-channel rather than global.
 *
 * **Feed this decrypted payloads.** The two length fields in an AAP frame are
 * not measured in the same units: `payloadLength` counts the bytes in *this*
 * frame after encryption, while `totalLength` counts the bytes of the whole
 * message *before* encryption. A sender fragments the plaintext first and
 * encrypts each fragment independently, so every frame holds whole TLS records
 * and can be decrypted on its own.
 *
 * Concretely, that means decryption happens per frame and reassembly happens
 * after it. Feeding ciphertext here would compare encrypted byte counts against
 * a plaintext total and reject every fragmented encrypted message — with a
 * length mismatch that looks like a framing bug and is not one. Hence the
 * [accept] overload that takes the payload separately from the header.
 */
public class MessageAssembler(private val maxMessageBytes: Int = 8 * 1024 * 1024) {

    private class Partial(val totalLength: Long, val flags: Int) {
        val chunks = ArrayList<ByteArray>()
        var received = 0L
    }

    private val partials = HashMap<Int, Partial>()

    /** Convenience for plaintext frames, where the payload needs no decryption. */
    public fun accept(frame: Frame): AssembledMessage? = accept(frame.header, frame.payload)

    /**
     * Feeds one frame's decrypted payload and returns the completed message, or
     * `null` while the message is still being assembled.
     *
     * [payload] is the plaintext for this frame, which for an encrypted frame is
     * a different length from [FrameHeader.payloadLength].
     */
    public fun accept(header: FrameHeader, payload: ByteArray): AssembledMessage? {
        val channel = header.channel

        if (header.isFirst && header.isLast) {
            if (partials.remove(channel) != null) {
                throw FrameFormatException(
                    "channel $channel sent an unfragmented message while a fragmented one was open"
                )
            }
            return AssembledMessage(channel, header.flags, payload)
        }

        if (header.isFirst) {
            val total = header.totalLength
                ?: throw FrameFormatException("first fragment on channel $channel has no total length")
            if (total > maxMessageBytes) {
                throw FrameFormatException("message of $total bytes on channel $channel exceeds cap")
            }
            val partial = Partial(total, header.flags)
            partial.chunks += payload
            partial.received = payload.size.toLong()
            partials[channel] = partial
            return null
        }

        val partial = partials[channel]
            ?: throw FrameFormatException("continuation frame on channel $channel with no open message")

        // The ENCRYPTED/CONTROL bits must not flip mid-message; if they do we are
        // desynchronised and any reassembled payload would be garbage.
        val semantic = FrameFlags.CONTROL or FrameFlags.ENCRYPTED
        if (header.flags and semantic != partial.flags and semantic) {
            throw FrameFormatException(
                "fragment flags changed mid-message on channel $channel: " +
                    "${FrameFlags.describe(partial.flags)} -> ${FrameFlags.describe(header.flags)}"
            )
        }

        partial.chunks += payload
        partial.received += payload.size
        if (partial.received > partial.totalLength) {
            partials.remove(channel)
            throw FrameFormatException(
                "channel $channel overran its announced length: ${partial.received} > ${partial.totalLength}"
            )
        }

        // A zero-length final fragment is legal and does occur: some senders
        // split at ">= fragment size", so a message that is an exact multiple
        // ends with an empty LAST frame.
        if (!header.isLast) return null

        partials.remove(channel)
        if (partial.received != partial.totalLength) {
            throw FrameFormatException(
                "channel $channel ended short: ${partial.received} of ${partial.totalLength}"
            )
        }

        val out = ByteArray(partial.received.toInt())
        var offset = 0
        for (chunk in partial.chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return AssembledMessage(channel, partial.flags, out)
    }

    /** Drops any half-assembled state, e.g. after a transport reset. */
    public fun reset() {
        partials.clear()
    }
}

/** A fully reassembled message, before the message-id prefix is stripped. */
public data class AssembledMessage(
    val channel: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    public val isControl: Boolean get() = flags and FrameFlags.CONTROL != 0
    public val isEncrypted: Boolean get() = flags and FrameFlags.ENCRYPTED != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssembledMessage) return false
        return channel == other.channel && flags == other.flags && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        31 * (31 * channel + flags) + payload.contentHashCode()

    override fun toString(): String =
        "AssembledMessage(channel=$channel, flags=${FrameFlags.describe(flags)}, payload=${payload.size}B)"
}

internal fun readUInt16(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

internal fun readUInt32(source: ByteArray, offset: Int): Long =
    ((source[offset].toLong() and 0xFF) shl 24) or
        ((source[offset + 1].toLong() and 0xFF) shl 16) or
        ((source[offset + 2].toLong() and 0xFF) shl 8) or
        (source[offset + 3].toLong() and 0xFF)

internal fun writeUInt16(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 1] = (value and 0xFF).toByte()
}

internal fun writeUInt32(target: ByteArray, offset: Int, value: Long) {
    target[offset] = ((value ushr 24) and 0xFF).toByte()
    target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}
