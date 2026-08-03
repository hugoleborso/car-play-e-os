/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.openaap.android.usb.UsbAccessoryTransport
import org.openaap.core.AapLink
import org.openaap.core.PhoneIdentity
import org.openaap.core.PhoneSession
import org.openaap.core.ResolvedChannel
import org.openaap.core.ServiceHandler
import org.openaap.core.ServiceHandlerFactory
import org.openaap.core.SessionListener
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.TlsRole
import org.openaap.protocol.proto.DiscoveryResponse
import org.openaap.protocol.proto.ResultCode

/**
 * Owns a projection session for as long as the cable is in.
 *
 * A foreground service of type `connectedDevice`, which is both accurate and
 * the only type without a runtime cap — `dataSync` stops after six hours in a
 * day and then throws if the app does not stop promptly, which would end a long
 * drive badly.
 *
 * The session runs on its own thread because the transport blocks: a USB read
 * waits until a whole transfer arrives, which on an idle link is indefinite.
 */
public class ProjectionService : Service() {

    private val session = AtomicReference<PhoneSession?>(null)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accessory = intent?.accessory()
        if (accessory == null) {
            Log.w(TAG, "started without an accessory")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.projection_connecting)),
            // Must match the manifest. See the comment there for why this is
            // not connectedDevice, which is what it looks like it should be.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        if (worker?.isAlive == true) {
            Log.i(TAG, "session already running; ignoring duplicate start")
            return START_NOT_STICKY
        }

        worker = thread(name = "openaap-session", isDaemon = false) { runSession(accessory) }
        // Not sticky: if the process dies the cable event is gone too, and
        // restarting without an accessory would just fail.
        return START_NOT_STICKY
    }

    private fun runSession(accessory: UsbAccessory) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val transport = UsbAccessoryTransport.open(manager, accessory)
        if (transport == null) {
            Log.e(TAG, "framework declined to open the accessory")
            ProbeEvents.record(
                this,
                ProbeEvents.Kind.FAULT,
                "Android refused to open the accessory. Usually the per-accessory permission was " +
                    "declined, or the car disconnected first.",
            )
            stopSelf()
            return
        }

        try {
            if (probeMode()) {
                runProbe(transport)
                return
            }
            val tls = AapTlsEngine(TlsRole.SERVER, credentials())
            val link = AapLink(transport, tls)
            val phoneSession = PhoneSession(
                link = link,
                tls = tls,
                identity = PhoneIdentity(model = Build.MODEL, maker = Build.MANUFACTURER),
                handlerFactory = handlerFactory(),
                listener = logging(),
            )
            session.set(phoneSession)
            phoneSession.run()
        } catch (e: Throwable) {
            // The interesting failures land here, and on a phone in a car the
            // log is the only diagnostic anyone will have.
            Log.e(TAG, "session ended with an error", e)
            ProbeEvents.record(
                this,
                ProbeEvents.Kind.FAULT,
                "Session failed: ${e::class.simpleName}: ${e.message}",
            )
        } finally {
            session.set(null)
            runCatching { transport.close() }
            stopSelf()
        }
    }

    /**
     * Whether to spend this connection measuring rather than projecting.
     *
     * The default is to measure. Until we know whether a head unit will accept
     * an identity we are allowed to generate, a full projection session cannot
     * get past its first minute anyway, and the measurement is the thing worth
     * bringing back from the car. Turn it off with:
     *
     * ```
     * adb shell am startservice -n org.openaap.projection/.ProjectionService --ez probe false
     * ```
     */
    private fun probeMode(): Boolean =
        getSharedPreferences("openaap", Context.MODE_PRIVATE).getBoolean("probe", true)

    private fun runProbe(transport: org.openaap.transport.Transport) {
        val runner = ProbeRunner(this)
        ProbeEvents.record(
            this,
            ProbeEvents.Kind.PROGRESS,
            "Presenting identity ${runner.position + 1} of ${runner.size} and waiting for the car",
        )
        val result = runner.runNext(transport)
        if (result == null) {
            Log.i(TAG, "probe matrix already complete; report at ${runner.reportFile.absolutePath}")
            updateNotification(getString(R.string.probe_complete))
            return
        }
        Log.i(TAG, "probe result: ${result.line()}")
        ProbeEvents.record(
            this,
            if (result.succeeded) ProbeEvents.Kind.PROGRESS else ProbeEvents.Kind.ATTENTION,
            "Probe ${runner.position}/${runner.size} ${result.credentialName}: ${result.stage.name}" +
                (result.alert?.let { " (${it.label})" } ?: ""),
        )
        updateNotification(
            getString(
                R.string.probe_progress,
                runner.position,
                result.credentialName,
                result.stage.name,
            )
        )
    }

    /**
     * The identity we present to the head unit.
     *
     * A generated self-signed certificate, because that is the only kind we can
     * lawfully produce: the authority every genuine Android Auto certificate
     * chains to is Google's, and there is no enrolment path to it. Whether a
     * given head unit accepts this is the open question of the whole project —
     * see docs/03-trust-model.md and the probe matrix in the crypto module.
     *
     * Deliberately a single place, so swapping in a differently-provisioned
     * credential is a one-line change rather than surgery.
     */
    private fun credentials(): CredentialProvider = StaticCredentialProvider.of(
        name = "openaap phone",
        leaf = TestPki.selfSigned(
            commonName = "openaap phone",
            // Real endpoints present v1 certificates. A head-unit parser written
            // in 2014 may not cope with the v3 structure a modern library
            // defaults to, and that failure would be indistinguishable from a
            // trust rejection unless we start from the shape it expects.
            version = TestPki.CertificateVersion.V1,
            validityDays = 365 * 10,
        ),
    )

    private fun handlerFactory(): ServiceHandlerFactory = ServiceHandlerFactory { channel ->
        // The media and input handlers land here as the projection module
        // stabilises. Declining a channel is legitimate and non-fatal, so an
        // incomplete factory produces a working-but-quiet session rather than a
        // failed one -- which is the right behaviour while bringing this up.
        object : ServiceHandler {
            override val channel: ResolvedChannel = channel
            override fun onMessage(link: AapLink, message: org.openaap.core.IncomingMessage) {
                Log.v(TAG, "unhandled ${message} on ${channel.kind}")
            }
        }
    }

    private fun logging(): SessionListener = object : SessionListener {
        override fun onVersionAgreed(major: Int, minor: Int) {
            Log.i(TAG, "protocol $major.$minor agreed")
        }

        override fun onHandshakeComplete(protocol: String?, cipherSuite: String?) {
            Log.i(TAG, "TLS established: $protocol / $cipherSuite")
        }

        override fun onAuthenticated() {
            Log.i(TAG, "head unit accepted our certificate")
            updateNotification(getString(R.string.projection_active))
        }

        override fun onDiscovered(response: DiscoveryResponse, channels: List<ResolvedChannel>) {
            Log.i(
                TAG,
                "head unit '${response.unitLabel}' (${response.unitMaker} ${response.unitModel}) " +
                    "offers ${channels.map { "${it.id}:${it.kind}" }}",
            )
        }

        override fun onChannelRefused(channel: ResolvedChannel, result: ResultCode) {
            Log.w(TAG, "head unit refused channel ${channel.id} (${channel.kind}): $result")
        }

        override fun onEnded(reason: String, cause: Throwable?) {
            Log.i(TAG, "session ended: $reason", cause)
        }
    }

    override fun onDestroy() {
        session.get()?.requestTeardown()
        worker?.interrupt()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun Intent.accessory(): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    public companion object {
        private const val TAG = "openaap.service"
        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1

        public fun start(context: Context, accessory: UsbAccessory) {
            val intent = Intent(context, ProjectionService::class.java)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            context.startForegroundService(intent)
        }
    }
}
