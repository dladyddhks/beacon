# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

moduflow는 헬스장 이용자를 위한 Android 앱으로, Bluetooth 비콘 기반 구역 감지와 실시간 AI 자세 분석을 결합한다. 4인 팀 프로젝트이며 이 저장소는 **팀원 3 (Android 앱)** 담당 영역이다.

### 팀 구성 및 담당

| 역할 | 담당 영역 | 연동 인터페이스 |
|---|---|---|
| 팀원 1 | AI 자세 인식 (MediaPipe / FastAPI) | Cloud Run WSS 엔드포인트 |
| 팀원 2 | 백엔드 (Spring) | REST API (`/api/v1/update-location`) |
| **팀원 3 (본인)** | **Android 앱** | 이 저장소 |
| 팀원 4 | 웹 앱 (React PWA) | `PwaActivity`의 WebView로 임베드 |

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
├── PwaActivity.java               # 런처 진입점: PWA WebView 전체화면 + 비콘 스캔/구역 감지
├── PoseAnalysisActivity.java      # CameraX + WebSocket 실시간 자세 분석 화면
├── SessionSummaryActivity.java    # 운동 종료 후 세션 전체 요약 화면 (동적 렌더링)
│
├── ApiClient.java                 # Retrofit 싱글턴 (토큰 인터셉터 + 401 Authenticator)
├── TokenManager.java              # JWT 토큰 SharedPreferences 저장/만료 관리
├── PoseWebSocketClient.java       # OkHttp WebSocket 생명주기 + 지수 백오프 재연결
├── ImageUtils.java                # YUV→NV21→JPEG→Base64 변환
├── KalmanFilter.java              # 비콘 거리 안정화용 1D 칼만 필터
├── Config.java                    # 서버 주소 (BuildConfig 값 노출)
│
├── LocationApiService.java        # POST /api/v1/update-location Retrofit 인터페이스
├── RoutineApiService.java         # GET  /api/v1/routines Retrofit 인터페이스
│
├── FrameRequest.java              # WS 송신: 프레임 (type=frame)
├── ResetRequest.java              # WS 송신: 카운트/이슈 초기화 (type=reset)
├── SetEndRequest.java             # WS 송신: 세트 종료 (type=set_end)
├── SummaryRequest.java            # WS 송신: 세션 종료/요약 요청 (type=session_end)
├── LocationData.java              # REST 송신: 구역 정보 페이로드
├── PoseResult.java                # WS 수신: 자세 분석 결과 (type=result)
├── SessionSummaryResponse.java    # WS 수신: 세트/종목/세션 요약 모델
├── RoutineResponse.java           # 루틴 API 응답 항목 모델
├── PoseRequest.java               # (미사용 — FrameRequest로 대체됨)
└── PoseResponse.java              # (미사용 — PoseResult로 대체됨)

app/src/main/res/layout/
├── activity_pose_analysis.xml     # 자세 분석 화면 레이아웃
└── activity_session_summary.xml   # 세션 요약 화면 레이아웃
```

> PWA가 전체 UI를 담당하므로 별도의 네이티브 메인 레이아웃(`activity_main.xml`)은 없다.

### Activity의 역할

**`PwaActivity`** — 런처 진입점 (PWA WebView + 비콘 구역 감지)
- `AndroidManifest`의 LAUNCHER. `Config.PWA_URL`을 전체화면 WebView로 로드 (URL 바 없음)
- JS 브리지(`Android` 인터페이스): `getDeviceId()`로 `ANDROID_ID` 반환(PWA 로그인 시 userId로 사용 → 계정↔기기 연결), `setAuthToken(token)`으로 PWA 로그인 토큰을 네이티브에 저장(`TokenManager`), `startWorkout(csv)`로 `PoseAnalysisActivity` 진입
- `moduflow://` 딥링크는 WebView가 가로채 네이티브 Activity로 라우팅
- 동시에 비콘 스캔/구역 감지 수행:
  - AltBeacon 라이브러리로 iBeacon 스캔, ARMA RSSI 필터로 신호 안정화
  - Minor ID로 구역 구분: `53626` → 비콘1, `53630` → 비콘2, `56376` → 비콘3
  - 칼만 필터(Q=0.1, R=2.0)로 거리 안정화, 히스테리시스 적용(0.8m 마진)으로 경계 전환 억제
  - 8초 미감지 시 해당 비콘 칼만 필터 리셋
- **비콘 자동출석 연동** (`onBeaconDetected`): 유효 비콘 감지 시
  - 포그라운드(WebView 활성): `moduflow:native-event` CustomEvent를 PWA로 전달(`BeaconEvent` 페이로드를 Gson 직렬화해 `evaluateJavascript`로 주입)
  - 백그라운드: `POST /api/v1/update-location` 직접 호출(`LocationData`: userId=ANDROID_ID, zoneId, gymName)
  - 동일 비콘은 7초 디바운스, 이탈(`zoneId=0`)은 출석 트리거로 전달하지 않음(위치 업데이트만 전송)

**`PoseAnalysisActivity`** — 실시간 자세 분석
- 진입 경로 3가지: PWA `startWorkout(csv)` Intent extra / `moduflow://app/workout/run?exercises=…` 딥링크 / 버튼
- 진입 후 `GET /api/v1/routines`로 오늘 요일 루틴을 조회해 ChipGroup 운동 칩 동적 구성
- CameraX 전면 카메라로 프레임 캡처 (`STRATEGY_KEEP_ONLY_LATEST`)
- 전송 스로틀링: 이전 응답 대기 중(`isProcessing`) + 최소 100ms 간격(10fps 상한) 모두 충족 시에만 전송
- `ImageUtils.toBase64Jpeg()`: YUV→NV21→JPEG(quality 70)→Base64, 640px 가로 리사이즈
- 세트 진행 흐름: 카운트 진행 → 목표 횟수 달성 or [세트 종료] → 60초 휴식 타이머 → 다음 세트 자동 시작 → 마지막 세트 후 "운동 완료"
- TTS 음성 자세 교정 안내(같은 이슈 3초 쿨다운, 위치 안내 문구는 발화 제외) + rep 완료 시 진동/스케일 애니메이션

**`SessionSummaryActivity`** — 세션 요약
- `session_end` 응답(`SummaryData`)을 받아 운동별 카드(세트/횟수/깔끔 rep/교정사항)를 코드로 동적 렌더링
- 운동 식별자·이슈 키를 한국어 표시명으로 매핑, AI 총평(`aiSummary`) 우선 표시

### 외부 서비스 연동 (`Config.java` → `BuildConfig`)

`Config.java`는 더 이상 URL을 하드코딩하지 않고 `app/build.gradle`의 `buildConfigField`에서 주입된 값을 노출만 한다.

```java
AI_SERVER_HOST  // 팀원 1 Cloud Run (FastAPI + MediaPipe), wss://
AI_SERVER_PORT  // 443 (→ wss://)
API_SERVER_URL  // 팀원 2 Spring Boot REST API
PWA_URL         // 팀원 4 React PWA (Vercel)
```

**서버 주소 변경 시 `app/build.gradle`의 `buildConfigField`를 수정**한다 (`Config.java`가 아님).

### WebSocket 프로토콜 (`PoseWebSocketClient`)

엔드포인트: `wss://{AI_SERVER_HOST}/api/v1/ws`

**전송**
- `FrameRequest`   → `{ "type": "frame", "image": "<Base64 JPEG>", "exercise": "..." }`
- `ResetRequest`   → `{ "type": "reset", "exercise": "..." }` (exercise 생략 시 세션 전체 초기화)
- `SetEndRequest`  → `{ "type": "set_end", "exercise": "...", "isLastSet": bool, "count": int }`
- `SummaryRequest` → `{ "type": "session_end" }`

**수신** (`type`으로 분기)
- `result`            → `PoseResult` (posture/feedback/angles/issues/count/stage/repCompleted)
- `reset_ok`          → 초기화 완료
- `set_feedback`      → `SessionSummaryResponse.SetStats` (세트 종료 직후)
- `exercise_feedback` → `SessionSummaryResponse.ExerciseStats` (마지막 세트 직후)
- `session_feedback`  → `SessionSummaryResponse.SummaryData` (session_end 후, 요약 화면 진입)
- `error`             → 오류 메시지 (연결은 유지)

연결 실패/종료 시 **지수 백오프**(2초 → 최대 30초)로 자동 재연결(`scheduleReconnect`). 모든 콜백은 메인 핸들러를 거쳐 UI 스레드에서 실행되며, 연결 상태는 `PoseUpdateListener.onConnecting/onConnected/onDisconnected`로 전달된다.

### 인증 흐름 (`ApiClient` / `TokenManager`)

PWA가 이메일/비밀번호 로그인 후 `Android.setAuthToken(token)`으로 accessToken을 전달 → `TokenManager`가 SharedPreferences에 저장(55분 유효 처리) → `ApiClient`의 인터셉터가 모든 REST 요청에 `Authorization: Bearer` 헤더 자동 첨부. 401 수신 시 토큰을 비우고 로그만 남기며, 재로그인은 사용자가 PWA에서 수행한다.

### 의존성 관리

라이브러리 버전은 `gradle/libs.versions.toml` (Version Catalog)에서 중앙 관리하며, `app/build.gradle`의 모든 의존성은 `libs.*` 카탈로그 참조를 사용한다(하드코딩 버전 없음). 새 의존성 추가 시에도 toml에 먼저 등록한 뒤 `libs.*`로 참조한다.

주요 라이브러리: AltBeacon 2.19.5, CameraX 1.4.1, Retrofit 2.9.0, OkHttp 4.12.0, Gson 2.10.1, Material 1.13.0

### 권한

앱 시작 시 런타임 권한 요청 필요: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` (비콘 스캔), `CAMERA` (자세 분석). Android 12(API 31) 미만은 Bluetooth 권한 불필요.