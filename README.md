# altitude-barotrauma-widget

고도 변화 측정으로 ear barotrauma 예방 알림 안드로이드 위젯 앱

---

## 기능

- 기압 센서(`TYPE_PRESSURE`)를 이용해 실시간 고도 계산
- **5초 누적 변화량** 및 **30초 직전 변화량** 동시 표시
- 고도 변화에 따른 이관가압 완화 알림 (툁집기, 하하음, 삼키기 등)
- WorkManager로 15분마다 센서 재등록 + BootReceiver로 부팅 시 자동 시작

## 동작 방식

| 고도 변화 | 알림 |
|---|---|
| 급겝 상승 (50m 이상/30s) | 툁집기 방법 시도 |
| 밀해 상승 (30m 이상/30s) | 하하하음 하기 |
| 앭간 상승 (15m 이상/30s) | 마시거나 삼켜보기 |
| -15m~+15m 범위 | 정상 |

## 기술 스택

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35
- **Kotlin**: 1.9.0
- **WorkManager**: 2.9.1

## 파일 구조

```
app/src/main/
├── res/
│   ├── drawable/
│   │   └── widget_background.xml   # 반투명 다크 둥규마니 배경
│   ├── layout/
│   │   └── widget_altitude.xml     # 위젯 UI 레이아웃
│   ├── values/
│   │   ├── strings.xml             # 문자열 리소스
│   │   └── themes.xml              # 테마
│   └── xml/
│       └── altitude_widget_info.xml
├── java/.../
│   ├── AltitudeWidgetProvider.kt
└── AndroidManifest.xml
```
