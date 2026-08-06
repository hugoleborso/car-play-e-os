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
import org.openaap.android.projection.ProjectionHandlers
import org.openaap.android.usb.UsbAccessoryTransport
import org.openaap.core.AapLink
import org.openaap.core.PhoneIdentity
import org.openaap.core.PhoneSession
import org.openaap.core.ResolvedChannel
import org.openaap.core.SessionListener
import org.openaap.core.SessionVariant
import org.openaap.crypto.AapTlsEngine
import org.openaap.crypto.CredentialProvider
import org.openaap.crypto.StaticCredentialProvider
import org.openaap.crypto.TestPki
import org.openaap.crypto.TlsRole
import org.openaap.protocol.proto.DiscoveryResponse
import org.openaap.services.MediaService
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

    /**
     * What the next connection is for.
     *
     * Three jobs rather than two because the first question has been answered
     * and the second has not. The credential matrix ran and every identity got
     * the same refusal; the open question moved from "which certificate" to
     * "what does that refusal actually say", and those need different matrices
     * over the same short visit to a car.
     *
     * Declared on the class rather than in the companion beside the accessors
     * that use it: a class nested in a companion object is `Foo.Companion.Bar`
     * to every caller, which reads as a mistake at every call site.
     */
    public enum class Job {
        /** The nine identities. Answered: all refused, identically. */
        CREDENTIALS,

        /** The status matrix, which provokes different kinds of failure. */
        STATUS,

        /** Actually project. Only worth trying once a car has accepted an identity. */
        PROJECT,
    }

    private val session = AtomicReference<PhoneSession?>(null)
    private val handlers = AtomicReference<ProjectionHandlers?>(null)
    private val trace = AtomicReference<SessionTrace?>(null)
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
            when (job(this)) {
                Job.CREDENTIALS -> {
                    runProbe(transport, ProbeRunner.credentials(this))
                    return
                }

                Job.STATUS -> {
                    runProbe(transport, ProbeRunner.status(this))
                    return
                }

                Job.PROJECT -> Unit
            }
            val variants = VariantRunner(this)
            val variant = variants.next() ?: SessionVariant.matrix().last().also {
                Log.i(TAG, "variant matrix complete; reusing ${it.id}")
            }
            val record = SessionTrace(this).also(trace::set)
            record.milestone("accessory opened: ${transport.description}")
            record.milestone("variant ${variant.id}: ${variant.varies}")
            ProbeEvents.record(
                this,
                ProbeEvents.Kind.PROGRESS,
                "Projection attempt ${variants.position + 1} of ${variants.size} — " +
                    "${variant.id}: ${variant.varies}",
            )
            val tls = AapTlsEngine(TlsRole.SERVER, credentials())
            // Wired at the message layer rather than the session layer, so the
            // transcript shows what actually crossed the cable rather than what
            // the state machine believed it had sent.
            val link = AapLink(
                transport,
                tls,
                wireTrace(record),
                controlFlagOnControlChannel = variant.controlFlagOnControlChannel,
            )
            val projection = handlerFactory(record).also(handlers::set)
            val phoneSession = PhoneSession(
                link = link,
                tls = tls,
                identity = PhoneIdentity(model = Build.MODEL, maker = Build.MANUFACTURER),
                handlerFactory = projection,
                listener = logging(record),
                variant = variant,
            )
            session.set(phoneSession)
            try {
                phoneSession.run()
            } finally {
                variants.record(variant, record.outcome())
            }
        } catch (e: Throwable) {
            // The interesting failures land here, and on a phone in a car the
            // log is the only diagnostic anyone will have.
            Log.e(TAG, "session ended with an error", e)
            val described = e.describe()
            trace.get()?.fault("threw: $described")
            ProbeEvents.record(this, ProbeEvents.Kind.FAULT, "Session failed: $described")
        } finally {
            session.set(null)
            // Written here rather than on a clean ending, because the sessions
            // worth reporting on are the ones that did not have one.
            trace.getAndSet(null)?.let { record ->
                val file = record.write()
                ProbeEvents.record(
                    this,
                    ProbeEvents.Kind.PROGRESS,
                    "Projection report written to ${file.name}. Share it from the app.",
                )
            }
            // Before the transport: the encoder holds a hardware codec that the
            // next session cannot acquire until it is released, and a cable
            // pulled mid-drive is followed by a reconnection within seconds.
            handlers.getAndSet(null)?.close()
            runCatching { transport.close() }
            stopSelf()
        }
    }

    private fun runProbe(transport: org.openaap.transport.Transport, runner: ProbeRunner) {
        val step = runner.next()
        ProbeEvents.record(
            this,
            ProbeEvents.Kind.PROGRESS,
            if (step == null) {
                "Every step of this matrix has been run. Share the report."
            } else {
                "Step ${runner.position + 1} of ${runner.size} — ${step.id}: ${step.varies}"
            },
        )
        val result = runner.runNext(transport)
        if (result == null) {
            Log.i(TAG, "matrix already complete; report at ${runner.reportFile.absolutePath}")
            updateNotification(getString(R.string.probe_complete))
            return
        }
        Log.i(TAG, "probe result: ${result.line()}")
        ProbeEvents.record(
            this,
            if (result.succeeded) ProbeEvents.Kind.PROGRESS else ProbeEvents.Kind.ATTENTION,
            // The code, on screen, in the car. It is the entire output of the
            // status matrix, and a run whose only record of it is a file nobody
            // opens until they get home is a run that cannot be adjusted while
            // the car is still there.
            "Step ${runner.position}/${runner.size} ${result.credentialName}: ${result.stage.name}, " +
                "${result.verdictLabel()}" + (result.alert?.let { " (${it.label})" } ?: ""),
        )
        updateNotification(
            getString(
                R.string.probe_progress,
                runner.position,
                result.credentialName,
                result.verdictLabel(),
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

    /**
     * Builds the handlers that actually project.
     *
     * Held on the service rather than created inline because video and input
     * are separate channels describing one screen, and something has to outlive
     * both to keep them agreeing about geometry. It also has to be torn down
     * when the cable comes out: an encoder left running holds a hardware codec
     * that the next session then cannot acquire.
     */
    /**
     * Records what crossed the cable.
     *
     * At the message layer, so the transcript is evidence rather than a
     * restatement of what the state machine intended. When those two disagree
     * -- a message built but never written, a frame the head unit never
     * acknowledged -- the disagreement is the bug.
     */
    private fun wireTrace(record: SessionTrace): AapLink.Listener = object : AapLink.Listener {
        override fun onSend(
            channel: Int,
            messageId: Int,
            size: Int,
            encrypted: Boolean,
            control: Boolean,
        ) {
            record.sent(channel, messageId, size, encrypted, control)
        }

        override fun onReceive(message: org.openaap.core.IncomingMessage) {
            record.received(message)
        }
    }

    private fun handlerFactory(record: SessionTrace): ProjectionHandlers = ProjectionHandlers(
        context = this,
        listener = object : MediaService.Listener {
            override fun onReady(channel: ResolvedChannel, creditWindow: Int) {
                // The credit window decides how far ahead of the head unit we
                // may run. A window of one is a head unit asking for strict
                // lockstep, and explains a stream that looks starved rather
                // than broken.
                record.milestone("ch${channel.id} ready, credit window $creditWindow")
            }

            override fun onStarted(channel: ResolvedChannel, format: Int) {
                Log.i(TAG, "streaming to channel ${channel.id}")
                record.milestone("ch${channel.id} streaming, format $format")
                ProbeEvents.record(
                    this@ProjectionService,
                    ProbeEvents.Kind.PROGRESS,
                    "Projecting to the car screen.",
                )
                updateNotification(getString(R.string.projection_active))
            }

            override fun onStopped(channel: ResolvedChannel, reason: String) {
                Log.i(TAG, "channel ${channel.id} stopped: $reason")
                record.fault("ch${channel.id} stopped: $reason")
                ProbeEvents.record(
                    this@ProjectionService,
                    ProbeEvents.Kind.ATTENTION,
                    "The car stopped the ${channel.kind} channel: $reason",
                )
            }

            override fun onCreditExhausted(channel: ResolvedChannel, droppedFrames: Long) {
                record.fault("ch${channel.id} out of credit, $droppedFrames frames dropped")
                // Worth surfacing rather than only logging: a car that stops
                // acknowledging shows a frozen picture, and without this the
                // only symptom is a still image that looks like an encoder bug.
                Log.w(TAG, "channel ${channel.id} out of credit, dropped $droppedFrames")
            }
        },
    )

    /**
     * Narrates the session onto the phone's own screen.
     *
     * Every one of these used to go only to `logcat`, which is unreachable while
     * the cable is in the head unit -- the same mistake that made the accessory
     * strings invisible, made twice. A projection session that fails somewhere
     * between "authenticated" and "a picture" has about six places it could have
     * stopped, and they need different fixes. Naming the last step reached turns
     * "the connection failed" into a bug report.
     */
    private fun logging(record: SessionTrace): SessionListener = object : SessionListener {
        override fun onVersionAgreed(major: Int, minor: Int) {
            Log.i(TAG, "protocol $major.$minor agreed")
            event(ProbeEvents.Kind.PROGRESS, "Protocol $major.$minor agreed.")
            record.milestone("protocol $major.$minor agreed")
            record.reached(SessionTrace.Reached.VERSION)
        }

        override fun onHandshakeComplete(protocol: String?, cipherSuite: String?) {
            Log.i(TAG, "TLS established: $protocol / $cipherSuite")
            event(ProbeEvents.Kind.PROGRESS, "TLS established: $protocol / $cipherSuite")
            record.milestone("TLS established: $protocol / $cipherSuite")
            record.reached(SessionTrace.Reached.TLS)
        }

        override fun onAuthenticated() {
            Log.i(TAG, "head unit accepted our certificate")
            // Everything after this point is encrypted, and this is the first
            // time that path has run against real hardware. Worth marking
            // precisely, because a failure just after it means something quite
            // different from a failure just before.
            event(
                ProbeEvents.Kind.PROGRESS,
                "Car accepted our certificate. Switching to encrypted framing and asking what it can do.",
            )
            record.milestone("car accepted our certificate; framing is encrypted from here")
            record.reached(SessionTrace.Reached.AUTHENTICATED)
            updateNotification(getString(R.string.projection_active))
        }

        override fun onListening() {
            Log.i(TAG, "listening; this variant does not open the discovery exchange")
            event(
                ProbeEvents.Kind.PROGRESS,
                "Staying quiet on purpose to see whether the car leads the exchange.",
            )
            record.milestone("listening: this variant never asks")
        }

        override fun onDiscovered(response: DiscoveryResponse, channels: List<ResolvedChannel>) {
            Log.i(
                TAG,
                "head unit '${response.unitLabel}' (${response.unitMaker} ${response.unitModel}) " +
                    "offers ${channels.map { "${it.id}:${it.kind}" }}",
            )
            // The channel list is the single most useful unpublished fact a
            // session produces: which services this unit offers, and on which
            // ids. Channel ids are not fixed by the protocol and a unit is free
            // to scramble them.
            event(
                ProbeEvents.Kind.PROGRESS,
                "Car identified itself as '${response.unitLabel}' " +
                    "(${response.unitMaker} ${response.unitModel}) offering " +
                    channels.joinToString(", ") { "${it.id}:${it.kind}" },
            )
            record.discovered(response, channels)
        }

        override fun onChannelOpened(channel: ResolvedChannel) {
            Log.i(TAG, "channel ${channel.id} (${channel.kind}) open")
            event(ProbeEvents.Kind.PROGRESS, "Channel ${channel.id} (${channel.kind}) opened.")
            record.milestone("ch${channel.id} (${channel.kind}) opened")
            record.reached(SessionTrace.Reached.CHANNEL_OPEN)
        }

        override fun onChannelRefused(channel: ResolvedChannel, result: ResultCode) {
            Log.w(TAG, "head unit refused channel ${channel.id} (${channel.kind}): $result")
            event(
                ProbeEvents.Kind.ATTENTION,
                "Car refused channel ${channel.id} (${channel.kind}): $result",
            )
            record.fault("ch${channel.id} (${channel.kind}) refused: $result")
        }

        override fun onEnded(reason: String, cause: Throwable?) {
            Log.i(TAG, "session ended: $reason", cause)
            event(
                if (cause == null) ProbeEvents.Kind.PROGRESS else ProbeEvents.Kind.FAULT,
                "Session ended: $reason" +
                    (cause?.let { " (${it::class.simpleName}: ${it.message})" } ?: ""),
            )
            record.ended(reason, cause)
        }
    }

    private fun event(kind: ProbeEvents.Kind, text: String) =
        ProbeEvents.record(this, kind, text)

    /**
     * The whole cause chain, plus where it was thrown.
     *
     * The top-level message is routinely null or uninformative — an
     * `SSLException` wrapping the thing that actually went wrong says nothing
     * useful on its own, and a wrapper's `message` is often just the inner
     * class name. On a phone in a car this string is the entire bug report, so
     * it carries the chain and the first frame of our own code.
     */
    private fun Throwable.describe(): String {
        val chain = generateSequence(this, Throwable::cause)
            .take(MAX_CAUSE_DEPTH)
            .joinToString(" ← ") { "${it::class.simpleName}: ${it.message ?: "(no message)"}" }
        val origin = stackTrace.firstOrNull { it.className.startsWith("org.openaap") }
            ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .orEmpty()
        return chain + origin
    }

    override fun onDestroy() {
        handlers.getAndSet(null)?.close()
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
        private const val MAX_CAUSE_DEPTH = 5
        private const val PREFERENCES = "openaap"
        private const val KEY_JOB = "job"

        /**
         * Defaults to [Job.STATUS].
         *
         * The default used to be the credential matrix, because nothing was
         * known. Now nine of nine are known and identical, so a connection spent
         * on a tenth identity is a connection spent on a constant.
         */
        public fun job(context: Context): Job {
            val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_JOB, null)
            return Job.entries.firstOrNull { it.name == stored } ?: Job.STATUS
        }

        public fun setJob(context: Context, job: Job) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY_JOB, job.name).apply()
        }
        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1

        public fun start(context: Context, accessory: UsbAccessory) {
            val intent = Intent(context, ProjectionService::class.java)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            context.startForegroundService(intent)
        }
    }
}
