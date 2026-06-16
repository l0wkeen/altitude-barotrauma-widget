package com.example.altitudewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 귀 먹먹함 로그를 내부 저장소(JSON 파일)에 저장/불러오는 레포지토리
 *
 * - 저장 위치: /data/data/패키지/files/ear_logs.json
 * - save() 최적화: 전체 재작성 대신 파일 끝에 한 줄 append (O(1))
 */
object EarLogRepository {

    private const val FILE_NAME = "ear_logs.jsonl"  // JSON Lines 포맷으로 변경
    private const val FILE_NAME_LEGACY = "ear_logs.json"

    // ============ 저장 (append) ============

    fun save(context: Context, log: EarLogData) {
        val line = logToJson(log).toString()
        File(context.filesDir, FILE_NAME).appendText(line + "\n")
    }

    // ============ 읽기 ============

    fun loadAll(context: Context): List<EarLogData> {
        migrateLegacyIfNeeded(context)
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { jsonToLog(JSONObject(line)) }.getOrNull()
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============ 지우기 ============

    fun clearAll(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, FILE_NAME_LEGACY).delete()
    }

    // ============ 통계 ============

    fun getCount(context: Context): Int = loadAll(context).size

    fun getSymptomLogs(context: Context): List<EarLogData> =
        loadAll(context).filter { it.symptomLevel > 0 }

    // ============ 내부 유틸 ============

    private fun logToJson(log: EarLogData): JSONObject = JSONObject().apply {
        put("timestamp", log.timestamp)
        put("altitude", log.altitude.toDouble())
        put("accumulatedChange", log.accumulatedChange.toDouble())
        put("immediateChange", log.immediateChange.toDouble())
        put("symptomLevel", log.symptomLevel)
        put("triggeredByAlert", log.triggeredByAlert)
        put("note", log.note)
    }

    private fun jsonToLog(obj: JSONObject) = EarLogData(
        timestamp          = obj.getLong("timestamp"),
        altitude           = obj.getDouble("altitude").toFloat(),
        accumulatedChange  = obj.getDouble("accumulatedChange").toFloat(),
        immediateChange    = obj.getDouble("immediateChange").toFloat(),
        symptomLevel       = obj.getInt("symptomLevel"),
        triggeredByAlert   = obj.getBoolean("triggeredByAlert"),
        note               = obj.optString("note", "")
    )

    /** 기존 ear_logs.json → ear_logs.jsonl 1회 마이그레이션 */
    private fun migrateLegacyIfNeeded(context: Context) {
        val legacy = File(context.filesDir, FILE_NAME_LEGACY)
        if (!legacy.exists()) return
        try {
            val arr = JSONArray(legacy.readText())
            val dest = File(context.filesDir, FILE_NAME)
            (0 until arr.length()).forEach { i ->
                dest.appendText(arr.getJSONObject(i).toString() + "\n")
            }
        } catch (_: Exception) {}
        legacy.delete()
    }
}
