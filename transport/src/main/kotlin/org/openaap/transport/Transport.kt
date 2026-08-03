/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.transport

import java.io.Closeable

/**
 * A bidirectional byte pipe to a head unit.
 *
 * AAP runs unchanged over three very different links -- USB bulk endpoints in
 * Android Open Accessory mode, a TCP socket for wireless projection, and an
 * in-process pipe for tests -- so everything above this interface is written
 * once. Implementations only have to move bytes; framing, reassembly and
 * encryption all live above.
 *
 * Implementations are not required to be thread-safe. [AapLink] serialises all
 * access through a single reader and a single writer.
 */
public interface Transport : Closeable {

    /** A short human-readable description used in logs and diagnostics. */
    public val description: String

    /**
     * Reads at least one byte into [destination], blocking until data arrives.
     *
     * Returns the number of bytes read, or -1 at end of stream. Short reads are
     * expected and normal: USB transfers and TCP segments do not align with
     * frame boundaries.
     */
    public fun read(destination: ByteArray, offset: Int, length: Int): Int

    /** Writes all [length] bytes, blocking until they have been handed to the link. */
    public fun write(source: ByteArray, offset: Int, length: Int)

    /**
     * Largest write this transport accepts in one call.
     *
     * USB accessory mode caps a bulk transfer at 16 KiB on most kernels, and
     * exceeding it silently truncates on some head units, so senders must
     * respect this rather than assuming the transport will split for them.
     */
    public val maxWriteSize: Int get() = 16 * 1024
}

/** Raised when a transport fails in a way that ends the session. */
public class TransportException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
