/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * AAP over TCP.
 *
 * This is the transport wireless Android Auto uses once the Bluetooth bootstrap
 * has handed the head unit the phone's Wi-Fi credentials: the phone listens and
 * the head unit dials in. It is also what the head-unit emulator under
 * `emulator/` speaks, which is what makes the whole stack testable without a
 * car or a USB cable.
 */
public class TcpTransport private constructor(
    private val socket: Socket,
    override val description: String,
) : Transport {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        try {
            input.read(destination, offset, length)
        } catch (e: IOException) {
            throw TransportException("read failed on $description", e)
        }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        try {
            output.write(source, offset, length)
            output.flush()
        } catch (e: IOException) {
            throw TransportException("write failed on $description", e)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    public companion object {
        /**
         * The port a phone listens on for wireless Android Auto.
         *
         * The Bluetooth bootstrap hands this port to the head unit inside the
         * Wi-Fi info exchange, so it is negotiable in principle; in practice
         * every implementation uses 5288.
         */
        public const val DEFAULT_WIRELESS_PORT: Int = 5288

        /** Accepts a single head unit connection, the role a phone plays. */
        public fun accept(port: Int = DEFAULT_WIRELESS_PORT, bindAddress: String = "0.0.0.0"): TcpTransport {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(bindAddress, port))
                val socket = server.accept()
                socket.tcpNoDelay = true
                return TcpTransport(socket, "tcp:accepted from ${socket.remoteSocketAddress}")
            }
        }

        /**
         * Dials out to a head unit.
         *
         * Not what a real phone does, but exactly what the test harness needs
         * when driving the emulator.
         */
        public fun connect(host: String, port: Int = DEFAULT_WIRELESS_PORT, timeoutMs: Int = 10_000): TcpTransport {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            return TcpTransport(socket, "tcp:connected to $host:$port")
        }

        /** Wraps an already-established socket, e.g. one accepted by a listener that outlives the session. */
        public fun wrap(socket: Socket, description: String = "tcp:${socket.remoteSocketAddress}"): TcpTransport {
            socket.tcpNoDelay = true
            return TcpTransport(socket, description)
        }
    }
}

/**
 * A listener that can be held open across reconnects.
 *
 * Head units drop and redial the TCP session routinely -- on ignition cycles,
 * on projection restarts -- so the phone side keeps the listening socket bound
 * rather than racing to rebind between sessions.
 */
public class TcpTransportListener(
    port: Int = TcpTransport.DEFAULT_WIRELESS_PORT,
    bindAddress: String = "0.0.0.0",
) : Closeable {

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port))
    }

    /** The port actually bound, which differs from the requested one when 0 was passed. */
    public val port: Int get() = server.localPort

    public fun accept(): TcpTransport = TcpTransport.wrap(server.accept())

    override fun close() {
        runCatching { server.close() }
    }
}
