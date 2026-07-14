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

| 1분 누적 변화량 | 알림 |
|---|---|
| 개인 맞춤 임계값(기본 15m) 이상 | 💧 물을 마시거나 하품해 보세요 |
| 30m 이상 | ⚠️ 하품하거나 침을 삼켜보세요 |
| 50m 이상 | 🚨 발살바법을 시도하세요 |
| 임계값 미만 | 정상 |

1단계 알림 임계값은 기본 15m이지만, 사용자가 실제로 "약간/심하게 먹먹해요"를 기록한 데이터가
5건 이상 쌓이면 해당 기록들의 평균 변화량의 80%(5~15m 범위)로 자동 조정됩니다.

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

## 기록 데이터

증상 기록은 앱 내부 저장소에 `ear_logs.jsonl`(JSON Lines) 형식으로 append 방식 저장됩니다.
파일이 1MB를 넘으면 오래된 기록을 정리하고 최신 3000줄만 유지합니다.

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
│   ├── AltitudeService.kt
│   ├── AltitudeWidgetProvider.kt
│   ├── BootReceiver.kt
│   ├── EarLogData.kt
│   ├── EarLogRepository.kt
│   ├── MainActivity.kt
│   └── SymptomLogReceiver.kt
└── AndroidManifest.xml
```
