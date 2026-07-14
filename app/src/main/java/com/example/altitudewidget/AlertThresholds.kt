package com.example.altitudewidget

/**
 * 귀 압력 경고 3단계의 1분 누적 고도 변화량 임계값(m).
 *
 * 항공기 객실 여압 시스템이 승객의 귀 불편을 줄이기 위해 목표로 하는
 * 분당 객실 고도 변화율(cabin rate of change) 기준을 그대로 가져왔다.
 * - LEVEL1 ≈ 300 ft/min (91m/min) — 최신 항공기가 목표로 하는 쾌적 기준
 * - LEVEL2 ≈ 500 ft/min (152m/min) — 전통적으로 쓰여온 여압 상한 기준
 * - LEVEL3 ≈ 750 ft/min (229m/min) — 일반 운항 범위를 넘는 급격한 변화
 *
 * 이 값들은 "이 속도를 넘으면 대부분 사람이 불편함을 느낄 수 있다"는
 * 항공 엔지니어링 상 안전 마진이며, 개인별 증상 발현 지점을 특정하는
 * 임상적 기준은 아니다. LEVEL1은 개인 맞춤 임계값
 * (EarLogRepository.getPersonalThreshold)의 기본값이자 상한이며,
 * 실제 사용자 기록이 쌓이면 이보다 낮게 조정될 수 있다.
 */
object AlertThresholds {
    const val LEVEL1_DEFAULT_MPM = 90f
    const val LEVEL2_MPM = 150f
    const val LEVEL3_MPM = 230f
}
