# altitude-barotrauma-widget

고도 변화 측정으로 ear barotrauma(항공성 중이염) 예방을 돕는 안드로이드 홈 화면 위젯 앱

---

## 기능

- 기압 센서(`TYPE_PRESSURE`)로 실시간 고도 측정, 이동평균(5개 샘플)으로 노이즈 완화
- **1분 누적 변화량**과 **3초 직전 변화량**을 함께 추적
- 고도 변화 단계에 따라 이관가압 완화 행동(물 마시기, 하품, 발살바법)을 알림으로 안내
- 알림에서 바로 "약간/심하게 먹먹해요" 버튼으로 증상 기록 가능 (앱을 열지 않아도 됨)
- 앱 화면(MainActivity)에서 현재 고도, 최근 기록, 개인 맞춤 임계값 확인 및 기록 초기화
- 기록된 실제 증상 데이터를 바탕으로 1단계 알림 임계값을 개인화
- BootReceiver로 기기 재부팅 후 자동 재시작

## 동작 방식

위젯이 활성화되면 포그라운드 서비스(`AltitudeService`)가 3초마다 기압 센서 값을 읽어 고도를 계산하고,
직전 1분간의 고도 변화량(`accumulatedChange`)을 기준으로 알림 단계를 판단합니다.

알림 3단계 임계값(`AlertThresholds.kt`)은 **"이 값을 넘으면 반드시 증상이 생긴다"는 임상적 진단
기준이 아니라, "이 정도 속도로 고도가 변하면 다수의 사람이 불편함을 느낄 수 있으니 미리
대응하라"는 예방적 캐주션(caution) 기준**입니다. 근거는 항공기 객실 여압 시스템이 승객의 귀
불편을 줄이기 위해 설계 시 목표로 하는 분당 객실 고도 변화율(cabin rate of change)입니다.

| 1분 누적 변화량 | 알림 | 근거 |
|---|---|---|
| 개인 맞춤 임계값(기본 90m) 이상 | 💧 물을 마시거나 하품해 보세요 | ≈300ft/min — 최신 항공기가 목표로 하는 쾌적 기준 |
| 150m 이상 | ⚠️ 하품하거나 침을 삼켜보세요 | ≈500ft/min — 전통적인 여압 상한 기준 |
| 230m 이상 | 🚨 발살바법을 시도해 보세요 | ≈750ft/min — 일반 운항 범위를 넘는 급격한 변화 |
| 임계값 미만 | 정상 | — |

1단계 알림 임계값은 기본 90m이지만, 사용자가 실제로 "약간/심하게 먹먹해요"를 기록한 데이터가
5건 이상 쌓이면 해당 기록들의 평균 변화량의 80%(30~90m 범위)로 자동 조정됩니다. 즉 90m는
개인화 이전의 일반화된 출발점이지, "이 값이 곧 증상 발현점"이라는 연구 결론으로 쓸 수 있는
수치는 아닙니다 — 실제 개인별 발현점은 이 앱으로 수집한 증상 기록 데이터로 검증해야 합니다.

## 앱 구성 요소

| 파일 | 역할 |
|---|---|
| `AltitudeWidgetProvider` | 홈 화면 위젯 UI 갱신, 위젯 활성화 시 서비스 시작 |
| `AltitudeService` | 기압 센서 측정, 고도 계산, 알림 발송, 로그 저장을 담당하는 포그라운드 서비스 |
| `MainActivity` | 알림 권한 요청, 현재 상태/최근 기록 표시, 증상 직접 기록, 기록 초기화 |
| `SymptomLogReceiver` | 알림의 액션 버튼("약간/심하게 먹먹해요")을 앱 실행 없이 처리 |
| `BootReceiver` | 기기 재부팅 후 서비스 자동 재시작 |
| `EarLogRepository` | 증상/변화량 기록을 내부 저장소(JSON Lines)에 저장·조회, 개인 맞춤 임계값 계산 |
| `EarLogData` | 기록 1건의 데이터 모델 (시각, 고도, 변화량, 증상 심각도 등) |
| `AlertThresholds` | 알림 2·3단계 임계값과 개인화 기본값(90/150/230m) — 항공기 여압 설계 기준 |

## 기록 데이터

증상 기록은 앱 내부 저장소에 `ear_logs.jsonl`(JSON Lines) 형식으로 append 방식 저장됩니다.
파일이 1MB를 넘으면 오래된 기록을 정리하고 최신 3000줄만 유지합니다.

## 알려진 제한사항

- **기압 센서(barometer) 미탑재 기기**: 일부 중저가 기종은 `TYPE_PRESSURE` 센서 자체가 없습니다.
  이 경우 위젯에 "기압 센서 없음"이 표시됩니다.
- **센서 응답 없음(Non-wakeup 센서 / 배터리 최적화)**: 일부 기종(특히 삼성 등)은 화면이 꺼지거나
  배터리 최적화가 걸려 있으면 기압 센서 콜백 자체가 중단됩니다. 20초 이상 콜백이 없으면
  위젯/앱에 "센서 응답 없음"으로 표시되며, MainActivity에서 배터리 최적화 제외를 요청할 수 있습니다.
- **Android 15(API 35) 포그라운드 서비스 제한**: 이 앱은 `dataSync` 타입 포그라운드 서비스로
  기압 센서를 지속 모니터링합니다. targetSdk 35 기준 Android 15부터는 앱이 백그라운드 상태로
  일정 시간(누적 6시간) 이상 `dataSync` 포그라운드 서비스를 실행하면 시스템이 자동으로
  서비스를 종료할 수 있습니다. 장시간(등산, 비행 등) 사용 시 모니터링이 중간에 멈출 수 있는
  원인 중 하나이며, 아직 해결되지 않은 이슈입니다.
- **절대 고도 표시 오차**: 고도 계산은 실제 해수면 기압이 아니라 표준대기압(1013.25hPa)을
  기준으로 하므로, 화면의 "현재 고도" 절대값은 날씨에 따라 실제 고도와 차이가 날 수 있습니다.
  알림 로직은 절대 고도가 아닌 변화량만 사용하므로 이 오차의 영향을 받지 않습니다.

## 기술 스택

- **minSdk**: 26 (Android 8.0)
- **targetSdk / compileSdk**: 35
- **Kotlin**: 2.2.10 / Android Gradle Plugin 9.2.1
- 외부 백그라운드 스케줄러(WorkManager 등) 없이 포그라운드 서비스 + `Handler` 주기 실행으로 동작

## 파일 구조

```
app/src/main/
├── res/
│   ├── drawable/
│   │   ├── action_bg.xml
│   │   └── widget_background.xml       # 반투명 다크 둥근 모서리 배경
│   ├── layout/
│   │   ├── activity_main.xml           # MainActivity 화면
│   │   └── widget_altitude.xml         # 위젯 UI 레이아웃
│   ├── values/
│   │   ├── strings.xml                 # 문자열 리소스
│   │   └── themes.xml                  # 테마
│   └── xml/
│       └── altitude_widget_info.xml
├── java/com/example/altitudewidget/
│   ├── AlertThresholds.kt
│   ├── AltitudeService.kt
│   ├── AltitudeWidgetProvider.kt
│   ├── BootReceiver.kt
│   ├── EarLogData.kt
│   ├── EarLogRepository.kt
│   ├── MainActivity.kt
│   └── SymptomLogReceiver.kt
└── AndroidManifest.xml
```
