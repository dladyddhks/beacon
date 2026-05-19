# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

moduflow는 헬스장 이용자를 위한 Android 앱으로, Bluetooth 비콘 기반 구역 감지와 실시간 AI 자세 분석을 결합한다. 4인 팀 프로젝트이며 이 저장소는 **팀원 3 (Android 앱)** 담당 영역이다.

### 팀 구성 및 담당

| 역할 | 담당 영역 | 연동 인터페이스 |
|---|---|---|
| 팀원 1 | AI 자세 인식 (MediaPipe / FastAPI) | Cloud Run WSS 엔드포인트 |
| 팀원 2 | 백엔드 (Spring) | REST API (`/api/update-location`) |
| **팀원 3 (본인)** | **Android 앱** | 이 저장소 |
| 팀원 4 | 웹 앱 (React PWA) | Chrome Custom Tabs로 임베드 |

---

## 빌드 명령

프로젝트 루트의 Gradle 래퍼를 사용한다. Windows에서는 `gradlew.bat` 사용.

```bash
gradlew.bat assembleDebug          # 디버그 APK 빌드
gradlew.bat assembleRelease        # 릴리즈 APK 빌드
gradlew.bat clean                  # 빌드 산출물 삭제
gradlew.bat test                   # 유닛 테스트
gradlew.bat connectedAndroidTest   # 기기/에뮬레이터 필요한 계측 테스트
```

---

## 아키텍처

### 파일 구조

```
app/src/main/java/com/example/moduflow/
├── MainActivity.java          # 비콘 스캔 + 구역 감지 + 메인 화면
├── PoseAnalysisActivity.java  # CameraX + WebSocket 자세 분석 화면
├── PoseWebSocketClient.java   # OkHttp WebSocket 생명주기 관리
├── ImageUtils.java            # YUV→JPEG→Base64 변환
├── Config.java                # 서버 URL 중앙 관리
├── PoseRequest.java           # WebSocket 전송 데이터 모델
├── PoseResponse.java          # WebSocket 수신 데이터 모델
├── LocationData.java          # REST API 구역 정보 페이로드
├── KalmanFilter.java          # 비콘 거리 안정화용 1D 칼만 필터
├── RoutineApiService.java     # GET /api/v1/routines Retrofit 인터페이스
├── RoutineResponse.java       # 루틴 API 응답 데이터 모델
└── PwaActivity.java           # PWA WebView 전체화면 (URL 바 없음)

app/src/main/res/layout/
├── activity_main.xml          # 메인 화면 레이아웃
└── activity_pose_analysis.xml # 자세 분석 화면 레이아웃
```

### 두 Activity의 역할


**`MainActivity`** — 비콘 구역 감지
- AltBeacon 라이브러리로 iBeacon 스캔, ARMA RSSI 필터로 신호 안정화
- Minor ID로 구역 구분: `53626` → 비콘1, `53630` → 비콘2, `56376` → 비콘3
- 칼만 필터(Q=0.1, R=2.0)로 거리 안정화, 히스테리시스 적용 (0.8m 마진): 구역 경계에서의 빈번한 전환 방지
- 구역 변경 시 Retrofit으로 `POST /api/update-location` 전송, `userId`는 `ANDROID_ID`
- Chrome Custom Tabs로 PWA 대시보드 오픈, 버튼으로 `PoseAnalysisActivity` 진입

**`PoseAnalysisActivity`** — 실시간 자세 분석
- CameraX 전면 카메라로 프레임 캡처 (`STRATEGY_KEEP_ONLY_LATEST`)
- 전송 스로틀링: 이전 응답 대기 중(`isProcessing`) + 최소 100ms 간격(10fps 상한) 모두 충족 시에만 전송
- `ImageUtils.toBase64Jpeg()`: YUV→NV21→JPEG(quality 70)→Base64, 640px 가로 리사이즈
- 운동 종류 선택: ChipGroup으로 `squat` / `pushup` / `lunge` 전환

### 외부 서비스 연동 (`Config.java`에서 URL 관리)

```java
AI_SERVER_HOST = "moduflow-ai-489316272296.asia-northeast3.run.app"  // 팀원 1 담당
AI_SERVER_PORT = 443                                                   // Cloud Run → wss://
API_SERVER_URL = "http://3.39.194.42:8080/"                         // 팀원 2 담당 (로컬 IP)
// PWA: "https://modu-flow-frontend.vercel.app"                       // 팀원 4 담당
```

서버 주소 변경 시 `Config.java`만 수정하면 된다.

### WebSocket 프로토콜 (`PoseWebSocketClient`)

**전송** (`PoseRequest`):
```json
{ "type": "frame", "image": "<Base64 JPEG>", "exercise": "squat|pushup|lunge" }
```

**수신** (`PoseResponse`):
```json
{ "type": "result|error", "posture": "good|bad", "feedback": "한국어 피드백", "angles": {...}, "exercise": "...", "message": "에러 시" }
```

연결 실패/종료 시 3초 후 자동 재연결 (`attemptReconnect`). UI 상태(`tvStatus`)는 `onConnectionChanged` 콜백으로 업데이트.

### 의존성 관리

라이브러리 버전은 `gradle/libs.versions.toml` (Version Catalog)에서 중앙 관리. 새 의존성 추가 시 `app/build.gradle`에 버전을 하드코딩하지 말고 toml에 먼저 등록.

주요 라이브러리: AltBeacon 2.19.5, CameraX 1.3.0(build.gradle) / 1.3.4(toml), Retrofit 2.9.0, OkHttp 4.11.0, Gson 2.10.1

> **주의**: `app/build.gradle`의 CameraX 버전(1.3.0)과 `libs.versions.toml`의 버전(1.3.4)이 불일치함. `app/build.gradle`의 하드코딩된 camerax 의존성을 toml로 이전 필요.

### 권한

앱 시작 시 런타임 권한 요청 필요: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` (비콘 스캔), `CAMERA` (자세 분석). Android 12(API 31) 미만은 Bluetooth 권한 불필요.