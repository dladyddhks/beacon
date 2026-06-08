package com.example.moduflow;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * AI 서버와의 WebSocket 연결을 관리하는 클라이언트.
 *
 * 연결 끊김 시 지수 백오프(2초 → 4초 → ... → 최대 30초)로 자동 재연결한다.
 * 모든 콜백은 mainHandler를 통해 메인 스레드에서 실행되므로
 * Activity에서 UI를 직접 업데이트할 수 있다.
 *
 * 메시지 타입:
 *   송신: frame(프레임 전송), reset(카운트 초기화), summary(세션 요약 요청)
 *   수신: result(자세 분석), reset_ok(초기화 완료), summary(세션 통계), error(서버 오류)
 */
public class PoseWebSocketClient {

    private static final String TAG            = "PoseWS";
    private static final int    BASE_BACKOFF_MS = 2_000;
    private static final int    MAX_BACKOFF_MS  = 30_000;

    // ──────────────────────── 리스너 인터페이스 ────────────────────────

    public interface PoseUpdateListener {
        /** 연결 시도 중 (콜드스타트 포함) */
        void onConnecting();
        /** WebSocket 핸드셰이크 완료 */
        void onConnected();
        /** 연결 끊김 (자동 재연결 예정) */
        void onDisconnected();
        /** type=="result" 프레임 수신 */
        void onPoseResult(PoseResult result);
        /** type=="reset_ok" 수신. exercise==null 이면 전체 세션 초기화 */
        void onResetOk(@Nullable String exercise);
        /** type=="set_feedback" — 세트 종료 직후 */
        void onSetFeedback(SessionSummaryResponse.SetStats summary);
        /** type=="exercise_feedback" — 마지막 세트(isLastSet=true) 직후 */
        void onExerciseFeedback(SessionSummaryResponse.ExerciseStats summary);
        /** type=="session_feedback" — session_end 전송 후 */
        void onSessionFeedback(SessionSummaryResponse.SummaryData summary);
        /** type=="error" 수신 — 연결은 유지됨 */
        void onServerError(String message);
    }

    // ──────────────────────────── 상태 ────────────────────────────────

    private final OkHttpClient httpClient;
    private final String       wsUrl;
    private final Gson         gson = new Gson();
    private final Handler      mainHandler = new Handler(Looper.getMainLooper());

    private WebSocket          webSocket;
    private PoseUpdateListener listener;
    private volatile boolean   isConnected      = false;
    private volatile boolean   shouldReconnect  = true;
    private int                reconnectAttempt = 0;

    private final Runnable reconnectRunnable = this::connect;

    // ────────────────────────── 생성자 ────────────────────────────────

    public PoseWebSocketClient(String host, int port) {
        // 443 / 80 / 0 이면 포트 생략
        if (port == 443 || port == 80 || port == 0) {
            this.wsUrl = "wss://" + host + "/api/v1/ws";
        } else {
            this.wsUrl = "wss://" + host + ":" + port + "/api/v1/ws";
        }

        // 콜드스타트 대비 connectTimeout 30초, readTimeout 무제한(WebSocket 유지)
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0,  TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        Log.d(TAG, "WebSocket URL: " + wsUrl);
    }

    public void setListener(PoseUpdateListener listener) {
        this.listener = listener;
    }

    // ────────────────────────── 연결 ──────────────────────────────────

    public void connect() {
        if (!shouldReconnect) return;

        notifyConnecting();
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                isConnected      = true;
                reconnectAttempt = 0;
                mainHandler.removeCallbacks(reconnectRunnable);
                Log.d(TAG, "연결 완료");
                mainHandler.post(() -> { if (listener != null) listener.onConnected(); });
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                isConnected = false;
                Log.d(TAG, "연결 종료: " + reason);
                mainHandler.post(() -> { if (listener != null) listener.onDisconnected(); });
                scheduleReconnect();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t,
                                  @Nullable Response response) {
                isConnected = false;
                Log.e(TAG, "연결 실패: " + t.getMessage());
                mainHandler.post(() -> { if (listener != null) listener.onDisconnected(); });
                scheduleReconnect();
            }
        });
    }

    public void disconnect() {
        shouldReconnect = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        if (webSocket != null) {
            webSocket.close(1000, "앱 종료");
            webSocket = null;
        }
        isConnected = false;
    }

    public boolean isConnected() { return isConnected; }

    // ───────────────────────── 메시지 송신 ────────────────────────────

    public void sendFrame(String base64Jpeg, String exercise) {
        send(gson.toJson(new FrameRequest(base64Jpeg, exercise)));
    }

    /** exercise==null 이면 세션 전체 초기화 */
    public void sendReset(@Nullable String exercise) {
        send(gson.toJson(new ResetRequest(exercise)));
    }

    /** 세트 종료. count: 최종 횟수 (수동 종료 시 목표 횟수를 채워 전송) */
    public void sendSetEnd(String exercise, boolean isLastSet, int count) {
        send(gson.toJson(new SetEndRequest(exercise, isLastSet, count)));
    }

    /** 세션 전체 종료 — session_feedback 응답을 받는다 */
    public void sendSessionEnd() {
        send(gson.toJson(new SummaryRequest()));
    }

    private void send(String json) {
        if (isConnected && webSocket != null) {
            webSocket.send(json);
        }
    }

    // ───────────────────────── 수신 파싱 ──────────────────────────────

    private void handleMessage(String text) {
        try {
            JsonObject obj  = JsonParser.parseString(text).getAsJsonObject();
            String     type = obj.has("type") ? obj.get("type").getAsString() : "";

            switch (type) {
                case "result": {
                    PoseResult result = gson.fromJson(obj, PoseResult.class);
                    mainHandler.post(() -> { if (listener != null) listener.onPoseResult(result); });
                    break;
                }
                case "reset_ok": {
                    String exercise = (obj.has("exercise") && !obj.get("exercise").isJsonNull())
                            ? obj.get("exercise").getAsString() : null;
                    mainHandler.post(() -> { if (listener != null) listener.onResetOk(exercise); });
                    break;
                }
                case "set_feedback": {
                    SessionSummaryResponse.SetStats stats = gson.fromJson(
                            obj.get("summary"), SessionSummaryResponse.SetStats.class);
                    mainHandler.post(() -> { if (listener != null) listener.onSetFeedback(stats); });
                    break;
                }
                case "exercise_feedback": {
                    SessionSummaryResponse.ExerciseStats stats = gson.fromJson(
                            obj.get("summary"), SessionSummaryResponse.ExerciseStats.class);
                    mainHandler.post(() -> { if (listener != null) listener.onExerciseFeedback(stats); });
                    break;
                }
                case "session_feedback": {
                    SessionSummaryResponse.SummaryData data = gson.fromJson(
                            obj.get("summary"), SessionSummaryResponse.SummaryData.class);
                    mainHandler.post(() -> { if (listener != null) listener.onSessionFeedback(data); });
                    break;
                }
                case "error": {
                    String msg = obj.has("message") ? obj.get("message").getAsString() : "알 수 없는 오류";
                    Log.w(TAG, "서버 오류: " + msg);
                    mainHandler.post(() -> { if (listener != null) listener.onServerError(msg); });
                    break;
                }
                default:
                    Log.w(TAG, "알 수 없는 메시지 타입: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "메시지 파싱 오류: " + e.getMessage());
        }
    }

    // ──────────────────────── 재연결 (지수 백오프) ────────────────────

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        long delay = Math.min((long) BASE_BACKOFF_MS * (1 << reconnectAttempt), MAX_BACKOFF_MS);
        reconnectAttempt = Math.min(reconnectAttempt + 1, 5); // 최대 30초에서 고정
        Log.d(TAG, reconnectAttempt + "번째 재연결 " + delay + "ms 후 시도");
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void notifyConnecting() {
        mainHandler.post(() -> { if (listener != null) listener.onConnecting(); });
    }
}
