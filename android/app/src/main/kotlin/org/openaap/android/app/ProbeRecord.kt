/*
 * Copyright 2026 The openaap authors.
 * Licensed under the Apache License, Version 2.0.
 */

package org.openaap.android.app

import java.io.File
import org.json.JSONObject
import org.openaap.core.HandshakeProbe

/**
 * One probe attempt, in a form that survives the process dying.
 *
 * The service is torn down between connections and a full run spans a dozen of
 * them, so the record of what happened cannot live in memory. It is written as
 * one JSON object per line: append-only, readable by a human over `adb pull`,
 * and parseable by the on-device screen without the two disagreeing about what
 * happened.
 */
public data class ProbeRecord(
    val index: Int,
    val total: Int,
    val credential: String,
    val varies: String,
    val stage: String,
    val alert: String?,
    val alertMeaning: String?,
    val failure: String?,
    val headUnitProtocol: String?,
    val negotiatedTls: String?,
    val negotiatedCipherSuite: String?,
    val headUnitCertificate: String?,
    /**
     * Every step of the exchange, in order.
     *
     * This was built by the probe and then thrown away, which was discovered
     * the only way such things are: the first real result came back and the
     * one line that would have confirmed it -- the head unit's verdict, and
     * whether it was stated or inferred from an empty body -- had never been
     * written to disk. A summary is not evidence. The transcript is.
     */
    val transcript: List<String> = emptyList(),
    val timestamp: String,
) {
    public val accepted: Boolean get() = stage == HandshakeProbe.Stage.AUTHENTICATED.name

    /** True when the head unit never spoke, which is a transport fault rather than a verdict. */
    public val noContact: Boolean get() = stage == HandshakeProbe.Stage.NO_CONTACT.name

    public fun toJson(): String = JSONObject().apply {
        put("index", index)
        put("total", total)
        put("credential", credential)
        put("varies", varies)
        put("stage", stage)
        put("alert", alert ?: JSONObject.NULL)
        put("alertMeaning", alertMeaning ?: JSONObject.NULL)
        put("failure", failure ?: JSONObject.NULL)
        put("headUnitProtocol", headUnitProtocol ?: JSONObject.NULL)
        put("negotiatedTls", negotiatedTls ?: JSONObject.NULL)
        put("negotiatedCipherSuite", negotiatedCipherSuite ?: JSONObject.NULL)
        put("headUnitCertificate", headUnitCertificate ?: JSONObject.NULL)
        put("transcript", org.json.JSONArray(transcript))
        put("timestamp", timestamp)
    }.toString()

    public companion object {

        public fun fromJson(line: String): ProbeRecord? = runCatching {
            val json = JSONObject(line)
            ProbeRecord(
                index = json.getInt("index"),
                total = json.getInt("total"),
                credential = json.getString("credential"),
                varies = json.optString("varies"),
                stage = json.getString("stage"),
                alert = json.optStringOrNull("alert"),
                alertMeaning = json.optStringOrNull("alertMeaning"),
                failure = json.optStringOrNull("failure"),
                headUnitProtocol = json.optStringOrNull("headUnitProtocol"),
                negotiatedTls = json.optStringOrNull("negotiatedTls"),
                negotiatedCipherSuite = json.optStringOrNull("negotiatedCipherSuite"),
                headUnitCertificate = json.optStringOrNull("headUnitCertificate"),
                transcript = json.optJSONArray("transcript")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty(),
                timestamp = json.optString("timestamp"),
            )
        }.getOrNull()

        /**
         * Reads every record written so far.
         *
         * Skips lines it cannot parse rather than failing. A truncated final
         * line is exactly what a process killed mid-write leaves behind, and
         * losing the whole run's history to it would be a poor trade.
         */
        public fun readAll(file: File): List<ProbeRecord> {
            if (!file.isFile) return emptyList()
            return runCatching { file.readLines() }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() }
                .mapNotNull(::fromJson)
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
