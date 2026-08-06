/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * The screen someone actually looks at while standing next to a car.
 *
 * It exists because of a constraint that is easy to miss from a desk: the phone
 * has one USB port, and during a test it is plugged into the head unit. `adb`
 * cannot reach the phone while any of the interesting things are happening.
 *
 * Its hardest job is explaining **nothing happening**, which is the most common
 * outcome and the least actionable. A missing device feature, a refused
 * permission, a bad cable and a car that never tried all look identical from
 * the outside and have different fixes. So the screen leads with what it can
 * check locally, then shows a timestamped log of everything the cable actually
 * did, and only then the results.
 */
public class ProbeActivity : Activity() {

    private lateinit var runner: ProbeRunner
    private lateinit var container: LinearLayout

    private val refresh = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runner = ProbeRunner(this)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            addView(container)
            isFillViewport = true
        }
        setContentView(scroll)

        // Apps targeting API 35 draw edge to edge, so without this the first
        // heading sits under the status bar and the last control under the
        // navigation bar -- which is exactly what makes a button look dead.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        requestNotificationPermission()
        render()
    }

    /**
     * The notification is the only sign of life while the phone is in the car,
     * and on API 33+ it needs a runtime grant that the app never asked for.
     * Without it a working probe is indistinguishable from a dead one.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ProbeRunner.ACTION_PROBE_UPDATED)
            addAction(ProbeEvents.ACTION_EVENTS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refresh, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refresh, filter)
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(refresh) }
    }

    private fun render() {
        container.removeAllViews()
        val records = runner.records()
        val events = ProbeEvents.recent(this)

        title(getString(R.string.probe_title))
        buildStamp()
        paragraph(getString(R.string.probe_intro))
        modeCard()
        statusCard(records, events)

        diagnostics()
        variantMatrix()
        projectionReport()
        if (events.isNotEmpty()) eventLog(events)
        if (records.isEmpty()) instructions()

        if (records.isNotEmpty()) {
            heading(getString(R.string.probe_results_heading))
            records.forEach { resultRow(it) }
            records.firstOrNull { it.headUnitCertificate != null }?.let { record ->
                heading(getString(R.string.probe_certificate_heading))
                paragraph(getString(R.string.probe_certificate_note))
                monospace(record.headUnitCertificate!!)
            }
            verdict(records)
        }

        actions(records)
    }

    /**
     * Which build this is, in the one place someone will look before driving
     * somewhere.
     *
     * The version string is hand-maintained and has said 0.1.0 through every
     * release, so it cannot answer "did the new one install?". The commit can,
     * and it costs one line on screen. Testing the wrong build in a car park is
     * a wasted trip that looks exactly like a failed experiment.
     */
    private fun buildStamp() = container.addView(
        TextView(this).apply {
            text = getString(
                R.string.probe_build,
                packageManager.getPackageInfo(packageName, 0).versionName,
                BuildConfig.GIT_REVISION,
            )
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.6f
        },
        marginParams(top = 2)
    )

    /**
     * Chooses what the next connection does: measure, or project.
     *
     * On screen rather than behind an `adb` flag, because the whole design
     * constraint of this app is that the cable is in the car when it matters.
     * A mode only reachable from a computer is a mode nobody switches at the
     * moment they want to switch it.
     *
     * Measuring stays the default. Projection is worth trying only once a car
     * has been observed accepting an identity we generate, and until then a
     * projection session cannot get past its first minute.
     */
    private fun modeCard() {
        val projecting = !ProjectionService.probeMode(this)
        val card = card()
        card.addView(
            TextView(this).apply {
                text = getString(
                    if (projecting) R.string.mode_projecting else R.string.mode_probing
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (projecting) ACCENT_GOOD else ACCENT_NEUTRAL)
            }
        )
        card.addView(
            TextView(this).apply {
                text = getString(
                    if (projecting) R.string.mode_projecting_detail else R.string.mode_probing_detail
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.8f
                setPadding(0, dp(4), 0, 0)
            }
        )
        card.addView(
            Button(this).apply {
                setText(if (projecting) R.string.mode_switch_to_probe else R.string.mode_switch_to_project)
                setOnClickListener {
                    ProjectionService.setProbeMode(this@ProbeActivity, projecting)
                    ProbeEvents.record(
                        this@ProbeActivity,
                        ProbeEvents.Kind.PROGRESS,
                        if (projecting) "Switched to measuring." else "Switched to projecting.",
                    )
                    render()
                }
            }
        )
        container.addView(card, marginParams(top = 8))
    }

    /**
     * How the projection variants have fared so far.
     *
     * Above the session report on purpose: the comparison across attempts is
     * what tells someone whether to keep unplugging and replugging, and the
     * single most recent transcript is the detail behind it.
     */
    private fun variantMatrix() {
        val runner = VariantRunner(this)
        val summary = runner.summary()
        val pending = runner.next()
        if (summary == null && pending == null) return

        heading(getString(R.string.variant_heading))
        val card = card()
        card.addView(
            TextView(this).apply {
                text = if (pending == null) {
                    getString(R.string.variant_complete)
                } else {
                    getString(
                        R.string.variant_next,
                        runner.position + 1,
                        runner.size,
                        pending.id,
                        pending.varies,
                    )
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ACCENT_NEUTRAL)
            }
        )
        container.addView(card, marginParams(bottom = 8))

        summary ?: return
        container.addView(
            ScrollView(this).apply {
                setBackgroundColor(surfaceColour())
                addView(
                    TextView(this@ProbeActivity).apply {
                        text = summary
                        setTypeface(Typeface.MONOSPACE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setHorizontallyScrolling(true)
                    }
                )
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(240)).apply { topMargin = dp(4) },
        )
    }

    /**
     * The last projection session, in full, on screen.
     *
     * Shown rather than only shareable because the person who needs it first is
     * standing next to the car deciding whether to try again. It is long, so it
     * sits in its own scrolling box instead of stretching the page.
     */
    private fun projectionReport() {
        val file = SessionTrace(this).reportFile
        if (!file.isFile) return
        val content = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        heading(getString(R.string.projection_report_heading))
        val box = ScrollView(this).apply {
            setBackgroundColor(surfaceColour())
            addView(
                TextView(this@ProbeActivity).apply {
                    text = content
                    setTypeface(Typeface.MONOSPACE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    // The transcript lines are wide and wrapping them makes the
                    // timings impossible to follow, so it scrolls both ways.
                    setHorizontallyScrolling(true)
                }
            )
        }
        container.addView(
            box,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(320)).apply { topMargin = dp(4) },
        )
    }

    private fun statusCard(records: List<ProbeRecord>, events: List<ProbeEvents.Event>) {
        val (statusText, colour) = when {
            records.any { it.accepted } -> getString(R.string.probe_status_accepted) to ACCENT_GOOD
            runner.complete -> getString(R.string.probe_status_complete, records.size) to ACCENT_NEUTRAL
            records.isEmpty() && events.isEmpty() ->
                getString(R.string.probe_status_waiting) to ACCENT_NEUTRAL
            records.isEmpty() ->
                getString(R.string.probe_status_seen_nothing_recorded) to ACCENT_WARN
            records.all { it.noContact } -> getString(R.string.probe_status_no_contact) to ACCENT_WARN
            else -> getString(R.string.probe_status_running, runner.position, runner.size) to ACCENT_NEUTRAL
        }

        val card = card()
        card.addView(
            TextView(this).apply {
                text = statusText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colour)
            }
        )
        card.addView(
            TextView(this).apply {
                text = getString(R.string.probe_progress_detail, records.size, runner.size)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.75f
                setPadding(0, dp(6), 0, 0)
            }
        )
        events.lastOrNull()?.let { last ->
            card.addView(
                TextView(this).apply {
                    text = getString(R.string.probe_last_activity, last.at, last.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.75f
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
        container.addView(card, marginParams(top = 8, bottom = 4))
    }

    /**
     * The section that answers "nothing is happening". Failures come first,
     * because a passing check is not what anyone is here to read.
     */
    private fun diagnostics() {
        val checks = ProbeDiagnostics.run(this)
        heading(getString(R.string.diag_heading))

        val problems = checks.filterNot { it.passed }
        if (problems.isEmpty()) {
            paragraph(getString(R.string.diag_all_clear))
        }

        (problems + checks.filter { it.passed }).forEach { check ->
            val colour = when {
                check.passed -> ACCENT_GOOD
                check.severity == ProbeDiagnostics.Severity.BLOCKING -> ACCENT_WALL
                check.severity == ProbeDiagnostics.Severity.DEGRADED -> ACCENT_WARN
                else -> ACCENT_NEUTRAL
            }
            val row = card()
            row.addView(
                TextView(this).apply {
                    text = (if (check.passed) "✓  " else "✕  ") + check.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colour)
                }
            )
            row.addView(
                TextView(this).apply {
                    text = check.detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.8f
                    setPadding(0, dp(4), 0, 0)
                }
            )
            check.remedy?.let { remedy ->
                row.addView(
                    TextView(this).apply {
                        text = remedy
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(colour)
                        setPadding(0, dp(6), 0, 0)
                    }
                )
            }
            container.addView(row, marginParams(bottom = 8))
        }

        val fixes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (checks.any { it.title == getString(R.string.diag_notifications) && !it.passed }) {
            fixes.addView(
                Button(this).apply {
                    setText(R.string.diag_open_settings)
                    setOnClickListener { openAppSettings() }
                }
            )
        }
        if (fixes.childCount > 0) container.addView(fixes, marginParams(bottom = 8))
    }

    /**
     * Everything the cable actually did, with times. This is what distinguishes
     * "the car never tried" from "the car tried and we failed", and neither is
     * visible any other way while the phone is plugged into a head unit.
     */
    private fun eventLog(events: List<ProbeEvents.Event>) {
        heading(getString(R.string.events_heading))
        val card = card()
        events.forEach { event ->
            card.addView(
                TextView(this).apply {
                    text = "${event.at}  ${event.text}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(Typeface.MONOSPACE)
                    setTextColor(
                        when (event.kind) {
                            ProbeEvents.Kind.FAULT -> ACCENT_WALL
                            ProbeEvents.Kind.ATTENTION -> ACCENT_WARN
                            ProbeEvents.Kind.PROGRESS -> ACCENT_NEUTRAL
                        }
                    )
                    setPadding(0, dp(3), 0, dp(3))
                }
            )
        }
        container.addView(card, marginParams(bottom = 8))
    }

    private fun instructions() {
        heading(getString(R.string.probe_howto_heading))
        listOf(
            R.string.probe_howto_1,
            R.string.probe_howto_2,
            R.string.probe_howto_3,
            R.string.probe_howto_4,
        ).forEachIndexed { index, resource ->
            container.addView(
                TextView(this).apply {
                    text = "${index + 1}.  ${getString(resource)}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, dp(4), 0, dp(4))
                },
                marginParams()
            )
        }
    }

    private fun resultRow(record: ProbeRecord) {
        val colour = when {
            record.accepted -> ACCENT_GOOD
            record.noContact -> ACCENT_WARN
            else -> ACCENT_WALL
        }
        val row = card()
        row.addView(
            TextView(this).apply {
                text = "${record.index}/${record.total}  ${record.credential}"
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        )
        row.addView(
            TextView(this).apply {
                text = record.stage + (record.alert?.let { "  ·  $it" } ?: "")
                setTypeface(Typeface.MONOSPACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(colour)
                setPadding(0, dp(4), 0, 0)
            }
        )
        // The interpretation, not just the code. Nobody should have to look up
        // what alert 48 means while standing in a car park.
        record.alertMeaning?.let { meaning ->
            row.addView(
                TextView(this).apply {
                    text = meaning
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.75f
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
        record.headUnitProtocol?.let { protocol ->
            row.addView(
                TextView(this).apply {
                    text = getString(R.string.probe_head_unit_protocol, protocol)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.75f
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
        container.addView(row, marginParams(bottom = 8))
    }

    private fun verdict(records: List<ProbeRecord>) {
        val text = when {
            records.any { it.accepted } -> getString(R.string.probe_verdict_accepted)
            records.all { it.noContact } -> getString(R.string.probe_verdict_no_contact)
            records.any { it.stage == "HANDSHAKE_COMPLETE" } -> getString(R.string.probe_verdict_after_tls)
            else -> getString(R.string.probe_verdict_rejected)
        }
        heading(getString(R.string.probe_verdict_heading))
        paragraph(text)
    }

    private fun actions(records: List<ProbeRecord>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        // Sharing is offered whenever there is anything to send, including a log
        // with no results in it -- "the cable did nothing" is a finding and is
        // worth receiving.
        if (records.isNotEmpty() ||
            ProbeEvents.all(this).isNotEmpty() ||
            SessionTrace(this).reportFile.isFile ||
            VariantRunner(this).summaryFile.isFile
        ) {
            row.addView(
                Button(this).apply {
                    setText(R.string.probe_share)
                    setOnClickListener { shareReport() }
                }
            )
        }
        row.addView(
            Button(this).apply {
                setText(R.string.probe_restart)
                setOnClickListener {
                    runner.reset()
                    VariantRunner(this@ProbeActivity).reset()
                    ProbeEvents.clear(this@ProbeActivity)
                    // Without this the button looks dead when there was nothing
                    // to clear, which is exactly when someone presses it.
                    Toast.makeText(
                        this@ProbeActivity,
                        R.string.probe_restarted,
                        Toast.LENGTH_SHORT,
                    ).show()
                    render()
                }
            }
        )
        container.addView(row, marginParams(top = 16))

        container.addView(
            TextView(this).apply {
                text = getString(R.string.probe_file_location, runner.reportFile.absolutePath)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                alpha = 0.6f
            },
            marginParams(top = 12)
        )
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    /**
     * Shares by any means the phone has, deliberately not tied to `adb`: the
     * whole point is that the cable is in the car. The report goes as an
     * attachment and the event log inline, so a short reply carries both.
     */
    private fun shareReport() {
        val file = runner.reportFile
        val events = ProbeEvents.all(this).joinToString("\n") { "${it.at}  ${it.kind}  ${it.text}" }
        // Both files, because the first field result came back with only the
        // summary attached and the raw records -- the ones that could confirm
        // it -- stayed on the phone. A share that silently drops the evidence
        // is worse than no share, because it looks complete.
        val attachments = listOf(
            file,
            runner.recordsFile,
            SessionTrace(this).reportFile,
            SessionTrace(this).archiveFile,
            VariantRunner(this).summaryFile,
        )
            .filter { it.isFile }
            .mapNotNull { candidate ->
                runCatching { FileProvider.getUriForFile(this, "$packageName.reports", candidate) }
                    .getOrNull()
            }
        val intent = Intent(
            if (attachments.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        ).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.probe_share_subject))
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    appendLine(getString(R.string.probe_share_subject))
                    // Which build produced these numbers. A report from an old
                    // APK is worse than no report, because it looks like fresh
                    // evidence and quietly contradicts the current code.
                    appendLine("build ${BuildConfig.GIT_REVISION} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                    appendLine()
                    appendLine(runCatching { file.readText() }.getOrDefault(""))
                    appendLine("--- events ---")
                    appendLine(events)
                },
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        when {
            attachments.size > 1 ->
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
            attachments.size == 1 -> intent.putExtra(Intent.EXTRA_STREAM, attachments.single())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.probe_share)))
    }

    // --- small view helpers ------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(surfaceColour())
    }

    private fun title(content: String) = container.addView(
        TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        },
        marginParams()
    )

    private fun heading(content: String) = container.addView(
        TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            alpha = 0.8f
        },
        marginParams(top = 20, bottom = 8)
    )

    private fun paragraph(content: String) = container.addView(
        TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
        },
        marginParams(top = 6)
    )

    private fun monospace(content: String) = container.addView(
        TextView(this).apply {
            text = content
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(surfaceColour())
        },
        marginParams(top = 4)
    )

    private fun marginParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun surfaceColour(): Int {
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.rgb(28, 32, 34) else Color.rgb(236, 239, 237)
    }

    private companion object {
        val ACCENT_GOOD: Int = Color.rgb(61, 107, 57)
        val ACCENT_WALL: Int = Color.rgb(168, 68, 42)
        val ACCENT_WARN: Int = Color.rgb(150, 110, 30)
        val ACCENT_NEUTRAL: Int = Color.rgb(31, 95, 91)
    }
}
