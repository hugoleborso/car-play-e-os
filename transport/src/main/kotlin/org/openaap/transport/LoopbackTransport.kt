/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.transport

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A pair of transports wired back-to-back in one process.
 *
 * Lets a phone-side session and a head-unit-side session run against each other
 * with no sockets, no cable and no timing noise, which is what makes handshake
 * regressions cheap to reproduce. The [chunkSize] knob deliberately hands the
 * reader fewer bytes than were written, so tests exercise the same partial-read
 * paths a real USB or TCP link produces.
 */
public class LoopbackTransport private constructor(
    private val inbound: ArrayBlockingQueue<ByteArray>,
    private val outbound: ArrayBlockingQueue<ByteArray>,
    private val closed: AtomicBoolean,
    override val description: String,
    private val chunkSize: Int,
) : Transport {

    private var pending: ByteArray? = null
    private var pendingOffset = 0

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        var current = pending
        if (current == null) {
            while (true) {
                if (closed.get() && inbound.isEmpty()) return -1
                current = inbound.poll(50, TimeUnit.MILLISECONDS) ?: continue
                break
            }
            pending = current
            pendingOffset = 0
        }
        val source = current!!
        val available = source.size - pendingOffset
        val count = minOf(length, available, chunkSize)
        source.copyInto(destination, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        if (pendingOffset == source.size) {
            pending = null
            pendingOffset = 0
        }
        return count
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        if (closed.get()) throw TransportException("write on closed $description")
        outbound.put(source.copyOfRange(offset, offset + length))
    }

    override fun close() {
        closed.set(true)
    }

    public companion object {
        /**
         * Creates a connected pair. The first element is the phone end, the
         * second the head-unit end.
         */
        public fun pair(
            capacity: Int = 1024,
            chunkSize: Int = Int.MAX_VALUE,
        ): Pair<LoopbackTransport, LoopbackTransport> {
            val phoneToHeadUnit = ArrayBlockingQueue<ByteArray>(capacity)
            val headUnitToPhone = ArrayBlockingQueue<ByteArray>(capacity)
            val closed = AtomicBoolean(false)
            val phone = LoopbackTransport(headUnitToPhone, phoneToHeadUnit, closed, "loopback:phone", chunkSize)
            val headUnit = LoopbackTransport(phoneToHeadUnit, headUnitToPhone, closed, "loopback:head-unit", chunkSize)
            return phone to headUnit
        }
    }
}
