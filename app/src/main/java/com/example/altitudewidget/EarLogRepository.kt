package com.example.altitudewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 귀 먹먹함 로그를 내부 저장소(JSON 파일)에 저장/불러오는 레포지토리
 *
 * - 저장 위치: /data/data/패키지/files/ear_logs.json
 * - 인터넷 버전: org.json 사용 (외부 라이브러리 불필요)
 */
object EarLogRepository {

    private const val FILE_NAME = "ear_logs.json"

    // ============ 저장 ============

    fun save(context: Context, log: EarLogData) {
        val logs = loadAll(context).toMutableList()
        logs.add(log)
        writeAll(context, logs)
    }

    // ============ 읽기 ============

    fun loadAll(context: Context): List<EarLogData> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EarLogData(
                    timestamp = obj.getLong("timestamp"),
                    altitude = obj.getDouble("altitude").toFloat(),
                    accumulatedChange = obj.getDouble("accumulatedChange").toFloat(),
                    immediateChange = obj.getDouble("immediateChange").toFloat(),
                    symptomLevel = obj.getInt("symptomLevel"),
                    triggeredByAlert = obj.getBoolean("triggeredByAlert"),
                    note = obj.optString("note", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ 지우기 ============

    fun clearAll(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    // ============ 통계 ============

    fun getCount(context: Context): Int = loadAll(context).size

    /**
     * 증상이 있었던 로그(symptomLevel > 0)뤼 반환
     * ThresholdAnalyzer에서 분석에 사용
     */
    fun getSymptomLogs(context: Context): List<EarLogData> =
        loadAll(context).filter { it.symptomLevel > 0 }

    // ============ 내부: 전체 쓰기 ============

    private fun writeAll(context: Context, logs: List<EarLogData>) {
        val arr = JSONArray()
        logs.forEach { log ->
            val obj = JSONObject().apply {
                put("timestamp", log.timestamp)
                put("altitude", log.altitude.toDouble())
                put("accumulatedChange", log.accumulatedChange.toDouble())
                put("immediateChange", log.immediateChange.toDouble())
                put("symptomLevel", log.symptomLevel)
                put("triggeredByAlert", log.triggeredByAlert)
                put("note", log.note)
            }
            arr.put(obj)
        }
        File(context.filesDir, FILE_NAME).writeText(arr.toString())
    }
}
