package com.example.altitudewidget

/**
 * 귀 먹먹함 로그 데이터 모델
 *
 * @param timestamp 기록 시각 (Unix ms)
 * @param altitude 로그 시점의 현재 고도 (m)
 * @param accumulatedChange 1분 누적 변화량 (m)
 * @param immediateChange 3초 즉각 변화량 (m)
 * @param symptomLevel 증상 심각도: 0=없음, 1=약간, 2=심함
 * @param triggeredByAlert 알림 후 입력인지 (true=알림 이후, false=알림 이전)
 * @param note 사용자 메모 (선택사항)
 */
data class EarLogData(
    val timestamp: Long = System.currentTimeMillis(),
    val altitude: Float,
    val accumulatedChange: Float,
    val immediateChange: Float,
    val symptomLevel: Int,           // 0: 없음, 1: 약간, 2: 심함
    val triggeredByAlert: Boolean,
    val note: String = ""
) {
    companion object {
        const val SYMPTOM_NONE = 0
        const val SYMPTOM_MILD = 1
        const val SYMPTOM_SEVERE = 2
    }
}
