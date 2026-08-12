package com.birddrop.birddropgame.surface

import com.birddrop.birddropgame.relay.Env
import com.birddrop.birddropgame.relay.GorgeResult
import com.birddrop.birddropgame.store.Trace
import com.birddrop.birddropgame.store.UrlGuard
import com.birddrop.birddropgame.store.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Config endpoint client. One responsibility, one method: POST the attribution
 * body and return the parsed answer.
 *
 * The URL out of a successful response is checked against [UrlGuard] before it
 * is handed back. A destination outside the allowlist is treated the same as
 * `ok:false` — the app opens the native part, and the mode is not persisted
 * (the endpoint did answer, but its answer was rejected by our own gate, so
 * the "did the server rule on this install" question is still open next launch).
 */
class GorgeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Env.configTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(Env.configTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchChannel(body: JSONObject): GorgeResult = withContext(Dispatchers.IO) {
        val endpoint = Env.resolveConfigEndpoint()
        if (endpoint.isBlank()) {
            Trace.w(TAG, "endpoint is blank — nobody to ask")
            return@withContext GorgeResult.unreachable()
        }
        Trace.i(TAG, "POST config endpoint")
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", UserAgent.value)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw = resp.body?.string().orEmpty()
                Trace.i(TAG, "HTTP $code (${raw.length} chars)")

                if (code == 404) return@withContext GorgeResult.native()
                if (code !in 200..299) return@withContext GorgeResult.native()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Trace.w(TAG, "request never landed: ${e.message}")
            GorgeResult.unreachable()
        }
    }

    private fun parseResponse(raw: String): GorgeResult {
        if (raw.isBlank()) return GorgeResult.native()
        return try {
            val j = JSONObject(raw)
            val ok = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                if (!UrlGuard.accepts(url)) {
                    Trace.w(TAG, "endpoint URL rejected by allowlist")
                    return GorgeResult.native()
                }
                GorgeResult.stream(url, exp)
            } else {
                GorgeResult.native()
            }
        } catch (e: Exception) {
            Trace.w(TAG, "JSON parse error: ${e.message}")
            GorgeResult.native()
        }
    }

    private companion object { const val TAG = "GorgeClient" }
}
