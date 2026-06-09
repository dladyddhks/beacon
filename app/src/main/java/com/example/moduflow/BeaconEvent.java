package com.example.moduflow;

/**
 * 비콘 감지 시 WebView로 전달하는 이벤트 페이로드.
 *
 * PWA는 다음 이벤트를 수신한다:
 *   window.addEventListener("moduflow:native-event", e => { ... e.detail ... })
 *
 * Gson으로 직렬화해 evaluateJavascript의 CustomEvent detail로 주입한다.
 * (문자열 직접 조합 대신 JSON 직렬화를 사용해 escape를 안전하게 처리)
 */
public class BeaconEvent {
    public final String type   = "beacon";
    public final String source = "beacon";
    public String userId;     // ANDROID_ID
    public int    zoneId;     // 53626 / 53630 / 56376 (0=이탈, 이벤트로 전송하지 않음)
    public String gymName;    // "ModuFlow"
    public String occurredAt; // ISO-8601 (예: 2026-06-09T16:00:00+09:00)

    public BeaconEvent(String userId, int zoneId, String gymName, String occurredAt) {
        this.userId     = userId;
        this.zoneId     = zoneId;
        this.gymName    = gymName;
        this.occurredAt = occurredAt;
    }
}