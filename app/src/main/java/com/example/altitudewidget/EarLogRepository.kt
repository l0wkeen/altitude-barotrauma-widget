package com.example.altitudewidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 귀 먹먹함 로그를 내부 저장소(JSON 파일)에 저장/불러오는 레포지토리
 *
 * - 저장 위치: /data/data/패키지/files/ear_logs.jsonl
 * - save() 최적화: 전체 재작성 대신 파일 끝에 한 줄 append (O(1))
 * - 파일이 MAX_FILE_BYTES를 넘으면 오래된 기록을 정리해 무한정 커지지 않게 한다
 * - getPersonalThreshold(): 누적 데이터로 개인 맞춤 임계값 계산
 */
object EarLogRepository {
    private const val FILE_NAME = "ear_logs.jsonl"  // JSON Lines 포맷으로 변경
    private const val FILE_NAME_LEGACY = "ear_logs.json"

    // ============ 저장 (append) ============

    fun save(context: Context, log: EarLogData) {
        val file = File(context.filesDir, FILE_NAME)
        val line = logToJson(log).toString()
        file.appendText(line + "\n")

        // 파일이 일정 크기를 넘으면 오래된 기록을 정리한다.
        // 평소에는 length() 확인만 하므로 append의 O(1) 특성을 해치지 않는다.
        if (file.length() > MAX_FILE_BYTES) {
            trimOldEntries(file)
        }
    }

    private fun trimOldEntries(file: File) {
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size <= TRIM_KEEP_LINES) return
            file.writeText(lines.takeLast(TRIM_KEEP_LINES).joinToString("\n") + "\n")
        } catch (_: Exception) {
            // 정리에 실패해도 다음 save() 호출에서 다시 시도된다.
        }
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

    /**
     * 개인 맞춤 임계값 계산
     *
     * 누적된 로그로부터 사용자가 실제로 귀 먹먹함을 경험한(symptomLevel > 0) 경우의
     * accumulatedChange 평균을 구하여 개인의 민감 구간에 맞게 임계값을 조정한다.
     *
     * 로그 수가 MIN_LOGS_FOR_PERSONAL 미만이면 기본값 반환
     *
     * @return 개인 맞춤 임계값(m) — 이 값 이상 변화시 1단계 알림
     */
    fun getPersonalThreshold(context: Context): Float {
        val symptomLogs = getSymptomLogs(context)
        if (symptomLogs.size < MIN_LOGS_FOR_PERSONAL) return DEFAULT_THRESHOLD_LEVEL1

        val avgChange = symptomLogs
            .map { abs(it.accumulatedChange) }
            .average()
            .toFloat()

        // 평균의 80%를 임계값으로 설정
        // (예: 평균 80m에서 증상 발생 시 임계값 = 64m)
        // 최소 30m, 최대 기본값 90m 범위 내로 제한
        val personal = (avgChange * 0.8f)
            .coerceAtLeast(MIN_THRESHOLD)
            .coerceAtMost(DEFAULT_THRESHOLD_LEVEL1)

        return personal
    }

    /**
     * 고도 변화 이벤트 분포 통계 반환
     * — 이력 데이터 확인용
     *
     * @return 심각 / 주의 / 경미 단계별 회수
     */
    fun getAlertStats(context: Context): Triple<Int, Int, Int> {
        val logs = loadAll(context)
        val severe = logs.count { abs(it.accumulatedChange) >= AlertThresholds.LEVEL3_MPM && it.triggeredByAlert }
        val moderate = logs.count {
            val change = abs(it.accumulatedChange)
            change >= AlertThresholds.LEVEL2_MPM && change < AlertThresholds.LEVEL3_MPM && it.triggeredByAlert
        }
        val mild = logs.count {
            val change = abs(it.accumulatedChange)
            change >= AlertThresholds.LEVEL1_DEFAULT_MPM && change < AlertThresholds.LEVEL2_MPM && it.triggeredByAlert
        }
        return Triple(severe, moderate, mild)
    }

    /**
     * 최근 N일간의 로그 요약
     */
    fun getRecentSummary(context: Context, days: Int = 7): Map<String, Any> {
        val cutoff = System.currentTimeMillis() - days * 24 * 60 * 60 * 1000L
        val recent = loadAll(context).filter { it.timestamp >= cutoff }
        return mapOf(
            "totalLogs" to recent.size,
            "symptomEvents" to recent.count { it.symptomLevel > 0 },
            "alertEvents" to recent.count { it.triggeredByAlert },
            "avgChange" to if (recent.isEmpty()) 0f
                          else recent.map { abs(it.accumulatedChange) }.average().toFloat(),
            "personalThreshold" to getPersonalThreshold(context)
        )
    }

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
        timestamp = obj.getLong("timestamp"),
        altitude = obj.getDouble("altitude").toFloat(),
        accumulatedChange = obj.getDouble("accumulatedChange").toFloat(),
        immediateChange = obj.getDouble("immediateChange").toFloat(),
        symptomLevel = obj.getInt("symptomLevel"),
        triggeredByAlert = obj.getBoolean("triggeredByAlert"),
        note = obj.optString("note", "")
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

    // ============ 상수 ============
    private const val MIN_LOGS_FOR_PERSONAL = 5   // 개인화 최소 샘플 수
    const val DEFAULT_THRESHOLD_LEVEL1 = AlertThresholds.LEVEL1_DEFAULT_MPM  // 기본 1단계 임계값 (m)
    private const val MIN_THRESHOLD = 30f             // 최소 허용 임계값 (m) — 기본값의 1/3
    private const val MAX_FILE_BYTES = 1_000_000L  // 이 크기를 넘으면 오래된 기록 정리 (약 1MB)
    private const val TRIM_KEEP_LINES = 3000       // 정리 후 남길 최신 기록 수
}
