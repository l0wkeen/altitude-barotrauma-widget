package com.example.altitudewidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 위도·경도로 지형 고도(m)를 조회하는 헬퍼.
 *
 * Open-Meteo Elevation API(무료, API 키 불필요, HTTPS)를 사용한다.
 * 응답 예: {"latitude":33.33,"longitude":126.83,"elevation":[27.0]}
 *
 * 네트워크 호출이므로 반드시 백그라운드 스레드에서 호출해야 한다.
 * 실패 시 null을 반환한다(인터넷 없음, 시간 초과 등).
 */
object ElevationApi {
    private const val ENDPOINT = "https://api.open-meteo.com/v1/elevation"
    private const val TIMEOUT_MS = 10_000

    fun fetchElevation(latitude: Double, longitude: Double): Double? {
        return try {
            val url = URL("$ENDPOINT?latitude=$latitude&longitude=$longitude")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val elevation = JSONObject(body).optJSONArray("elevation") ?: return null
                if (elevation.length() > 0) elevation.getDouble(0) else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
