package com.caesiumstudio.bitstream.data

import java.util.concurrent.Executors

object Analytics {

    private const val ENDPOINT = "https://apporb.net/api/v1/track"
    private const val APP_ID = "bitstream"
    private const val CODE = "1c34dd8d1eb69c3c66d5824ac7207983317e29b00f6ed7d810801558e0774957"

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Fire-and-forget analytics event.
     * @param event  snake_case event name
     * @param data   optional primary dimension (plain string)
     * @param param  optional secondary dimension (plain string)
     */
    fun track(event: String, data: String? = null, param: String? = null) {
        executor.execute {
            try {
                val body = buildString {
                    append("{\"appid\":\"$APP_ID\",\"code\":\"$CODE\",\"event\":\"$event\"")
                    if (data != null) append(",\"data\":\"${data.replace("\"", "\\\"")}\"")
                    if (param != null) append(",\"param\":\"${param.replace("\"", "\\\"")}\"")
                    append("}")
                }
                val url = java.net.URL(ENDPOINT)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode // consume response
                conn.disconnect()
            } catch (_: Exception) {
                // fire-and-forget: never surface errors
            }
        }
    }
}
