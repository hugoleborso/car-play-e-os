/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider

/**
 * The screen someone actually looks at while standing next to a car.
 *
 * This exists because of a constraint that is easy to miss when designing from
 * a desk: the phone has one USB port, and during a test it is plugged into the
 * head unit. `adb` cannot see the phone at all while the interesting things are
 * happening. Without a screen, the person running the test has no idea whether
 * anything worked until they get home, unplug, and pull a file — by which point
 * they have lost the chance to try the obvious next thing.
 *
 * So the screen shows what to do, which identity is being tried, and what the
 * head unit said about each one, live, and offers to share the report by any
 * means the phone has.
 */
public class ProbeActivity : Activity() {

    private lateinit var runner: ProbeRunner
    private lateinit var container: LinearLayout

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runner = ProbeRunner(this)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(container) })
        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ProbeRunner.ACTION_PROBE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updates, filter)
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(updates) }
    }

    private fun render() {
        container.removeAllViews()
        val records = runner.records()

        title(getString(R.string.probe_title))
        paragraph(getString(R.string.probe_intro))

        statusCard(records)
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
     * The status card carries the one thing the person needs at a glance, and it
     * distinguishes "nothing happened" from "nothing worked" — which look
     * identical in a log and mean completely different things.
     */
    private fun statusCard(records: List<ProbeRecord>) {
        val accepted = records.any { it.accepted }
        val (statusText, colour) = when {
            accepted -> getString(R.string.probe_status_accepted) to ACCENT_GOOD
            runner.complete -> getString(R.string.probe_status_complete, records.size) to ACCENT_NEUTRAL
            records.isEmpty() -> getString(R.string.probe_status_waiting) to ACCENT_NEUTRAL
            records.all { it.noContact } ->
                getString(R.string.probe_status_no_contact) to ACCENT_WARN
            else -> getString(
                R.string.probe_status_running,
                runner.position,
                runner.size,
            ) to ACCENT_NEUTRAL
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(surfaceColour())
        }
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
        container.addView(card, marginParams(top = 8, bottom = 20))
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

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(surfaceColour())
        }
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
        // The interpretation, not just the code. Someone reading this in a car
        // park should not have to look up what alert 48 means.
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
        if (records.isNotEmpty()) {
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
                    render()
                }
            }
        )
        container.addView(row, marginParams(top = 20))

        container.addView(
            TextView(this).apply {
                text = getString(R.string.probe_file_location, runner.reportFile.absolutePath)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                alpha = 0.6f
            },
            marginParams(top = 12)
        )
    }

    /**
     * Shares the report through whatever the phone has.
     *
     * Deliberately not tied to `adb`: the whole point is that the cable is in
     * the car. Email, a messaging app or a file manager all work, and the person
     * can send the report before they have driven home.
     */
    private fun shareReport() {
        val file = runner.reportFile
        if (!file.isFile) return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.reports", file)
        }.getOrNull() ?: return

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.probe_share_subject))
                    putExtra(Intent.EXTRA_TEXT, runCatching { file.readText() }.getOrDefault(""))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.probe_share),
            )
        )
    }

    // --- small view helpers ------------------------------------------------

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
        marginParams(top = 22, bottom = 8)
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** A surface that reads as raised in light mode and recessed in dark. */
    private fun surfaceColour(): Int {
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.rgb(28, 32, 34) else Color.rgb(238, 240, 238)
    }

    private companion object {
        val ACCENT_GOOD: Int = Color.rgb(61, 107, 57)
        val ACCENT_WALL: Int = Color.rgb(168, 68, 42)
        val ACCENT_WARN: Int = Color.rgb(150, 110, 30)
        val ACCENT_NEUTRAL: Int = Color.rgb(31, 95, 91)
    }
}
