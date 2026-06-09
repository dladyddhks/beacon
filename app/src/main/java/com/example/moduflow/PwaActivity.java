package com.example.moduflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.google.gson.Gson;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.ArmaRssiFilter;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 앱의 진입점. React PWA를 전체화면 WebView로 표시하면서
 * 비콘 스캔과 구역 감지를 함께 수행한다.
 */
@SuppressWarnings("deprecation")
public class PwaActivity extends AppCompatActivity implements BeaconConsumer {

    private static final String TAG = "PwaActivity";

    // ── 비콘 칼만 필터 파라미터 ───────────────────────────────────────────
    private static final double KALMAN_Q          = 0.1;
    private static final double KALMAN_R          = 2.0;
    private static final long   BEACON_TIMEOUT_MS = 8_000L;

    private final Map<Integer, KalmanFilter> kalmanFilters  = new HashMap<>();
    private final Map<Integer, Long>         beaconLastSeen = new HashMap<>();

    private int    currentZone         = -1;
    private double currentZoneDistance = Double.MAX_VALUE;
    private BeaconManager beaconManager;

    // ── WebView ──────────────────────────────────────────────────────────
    private WebView webView;

    // ── 비콘 자동출석 연동 ────────────────────────────────────────────────
    private static final String GYM_NAME                 = "ModuFlow";
    private static final long   BEACON_EVENT_DEBOUNCE_MS = 7_000L; // 동일 비콘 재트리거 방지 (5~10초 권장)
    /** zoneId → 마지막 이벤트 전송 시각. 동일 비콘 과도 트리거를 디바운스한다. */
    private final Map<Integer, Long> lastBeaconEventTime = new HashMap<>();
    private final Gson      gson         = new Gson();
    /** WebView(PWA) 활성 여부. true=포그라운드(WebView로 이벤트 전달), false=백그라운드(직접 API 호출). */
    private volatile boolean isForeground = false;

    // ────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();
        setupBeacon();

        webView.loadUrl(Config.PWA_URL);
    }

    // ──────────────────── WebView 설정 ───────────────────────────────────

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("moduflow".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "딥링크 처리 실패: " + e.getMessage());
                    }
                    return true;
                }
                return false;
            }
        });

        // PWA의 window.confirm() 다이얼로그 처리
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(PwaActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", (d, w) -> result.confirm())
                        .setNegativeButton("취소", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        webView.addJavascriptInterface(new Object() {
            /**
             * 기기 고유 식별자(ANDROID_ID)를 반환한다.
             * PWA 로그인 화면에서 window.Android.getDeviceId()로 호출해
             * 로그인 API의 userId로 전송 → 회원 계정과 기기를 연결한다.
             */
            @JavascriptInterface
            public String getDeviceId() {
                return getAndroidId();
            }

            /**
             * 로그인 계정의 userId를 네이티브에 저장한다.
             * 루틴 등 "사용자 본인 데이터" 조회 시 기기 ANDROID_ID가 아니라
             * 이 계정 userId를 사용해, 한 기기에서 계정이 바뀌어도 올바른 계정 데이터를 가져온다.
             */
            @JavascriptInterface
            public void setUserId(String userId) {
                if (userId == null || userId.isEmpty()) {
                    Log.w(TAG, "setUserId: 빈 userId 무시");
                    return;
                }
                TokenManager.getInstance(PwaActivity.this).saveUserId(userId);
                Log.d(TAG, "PWA로부터 계정 userId 저장 완료");
            }

            @JavascriptInterface
            public void setAuthToken(String token) {
                if (token == null || token.isEmpty()) {
                    Log.w(TAG, "setAuthToken: 빈 토큰 무시");
                    return;
                }
                TokenManager.getInstance(PwaActivity.this).saveToken(token);
                Log.d(TAG, "PWA로부터 토큰 저장 완료");
            }

            @JavascriptInterface
            public void startWorkout(String exercisesCsv) {
                runOnUiThread(() -> {
                    Intent intent = new Intent(PwaActivity.this, PoseAnalysisActivity.class);
                    intent.putExtra("exercises", exercisesCsv);
                    startActivity(intent);
                });
            }
        }, "Android");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    // ──────────────────── 비콘 설정 ──────────────────────────────────────

    private void setupBeacon() {
        beaconManager = BeaconManager.getInstanceForApplication(this);
        BeaconManager.setRssiFilterImplClass(ArmaRssiFilter.class);
        beaconManager.setForegroundScanPeriod(1100L);
        beaconManager.setForegroundBetweenScanPeriod(0L);
        try { beaconManager.updateScanPeriods(); }
        catch (Exception e) { Log.e(TAG, "스캔 주기 설정 실패: " + e.getMessage()); }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.CAMERA
            }, 1);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA
            }, 1);
        }

        beaconManager.getBeaconParsers().add(
                new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
        beaconManager.bind(this);
    }

    // ──────────────────── 비콘 감지 콜백 ─────────────────────────────────

    @Override
    public void onBeaconServiceConnect() {
        beaconManager.addRangeNotifier((beacons, region) -> {
            long now = System.currentTimeMillis();

            Map<Integer, Double> filteredDistances = new HashMap<>();
            for (Beacon beacon : beacons) {
                try {
                    int minor = beacon.getId3().toInt();
                    if (minor != 53626 && minor != 53630 && minor != 56376) continue;

                    Long lastSeen = beaconLastSeen.get(minor);
                    if (lastSeen != null && now - lastSeen > BEACON_TIMEOUT_MS) {
                        KalmanFilter stale = kalmanFilters.get(minor);
                        if (stale != null) stale.reset();
                    }
                    beaconLastSeen.put(minor, now);

                    KalmanFilter kf = kalmanFilters.computeIfAbsent(
                            minor, k -> new KalmanFilter(KALMAN_Q, KALMAN_R));
                    double filtered = kf.filter(beacon.getDistance());
                    filteredDistances.put(minor, filtered);

                    Log.d(TAG, String.format(Locale.US,
                            "minor=%d  raw=%.2fm  filtered=%.2fm",
                            minor, beacon.getDistance(), filtered));
                } catch (Exception e) {
                    Log.e(TAG, "Minor 파싱 오류: " + e.getMessage());
                }
            }

            int    closestMinor = -1;
            double minFiltered  = Double.MAX_VALUE;
            for (Map.Entry<Integer, Double> entry : filteredDistances.entrySet()) {
                if (entry.getValue() < minFiltered) {
                    minFiltered  = entry.getValue();
                    closestMinor = entry.getKey();
                }
            }

            if (closestMinor != -1) {
                final int    newMinor    = closestMinor;
                final double newDistance = minFiltered;

                Double currentFilteredBoxed = filteredDistances.get(currentZone);
                double currentFiltered = (currentFilteredBoxed != null)
                        ? currentFilteredBoxed : currentZoneDistance;

                boolean shouldSwitch = (currentZone == -1)
                        || (newMinor != currentZone && newDistance < currentFiltered - 0.8);

                if (!shouldSwitch && newMinor == currentZone) {
                    currentZoneDistance = newDistance;
                }

                if (shouldSwitch) {
                    currentZone         = newMinor;
                    currentZoneDistance = newDistance;
                    onBeaconDetected(newMinor);
                }
            } else {
                if (currentZone != -1) {
                    currentZone         = -1;
                    currentZoneDistance = Double.MAX_VALUE;
                    // 이탈(zoneId=0)은 출석 트리거(CustomEvent)로 보내지 않는다.
                    // 단, 혼잡도/구역 상태 반영을 위해 위치 업데이트는 그대로 전송한다.
                    sendLocationToServer(0);
                }
            }
        });

        try {
            beaconManager.startRangingBeaconsInRegion(
                    new Region("myRangingRegion", null, null, null));
        } catch (Exception e) {
            Log.e(TAG, "스캔 시작 실패: " + e.getMessage());
        }
    }

    // ──────────────────── 비콘 자동출석 ──────────────────────────────────

    /** 기기 고유 식별자(ANDROID_ID). 로그인/위치/비콘 이벤트의 userId로 사용한다. */
    @SuppressLint("HardwareIds")
    private String getAndroidId() {
        return Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * 유효 비콘(53626/53630/56376) 감지 시 호출.
     *   - 포그라운드(WebView 활성): moduflow:native-event CustomEvent를 PWA로 전달
     *   - 백그라운드: POST /api/v1/update-location 직접 호출
     * 동일 비콘은 BEACON_EVENT_DEBOUNCE_MS 내 재트리거를 디바운스한다.
     */
    private void onBeaconDetected(int zoneId) {
        if (zoneId == 0) return; // 이탈은 출석 트리거가 아님

        long now  = System.currentTimeMillis();
        Long last = lastBeaconEventTime.get(zoneId);
        if (last != null && now - last < BEACON_EVENT_DEBOUNCE_MS) {
            Log.d(TAG, "비콘 이벤트 디바운스: zoneId=" + zoneId);
            return;
        }
        lastBeaconEventTime.put(zoneId, now);

        if (isForeground) {
            dispatchBeaconEvent(new BeaconEvent(getAndroidId(), zoneId, GYM_NAME, nowIso8601()));
        } else {
            Log.d(TAG, "백그라운드 — update-location 직접 호출: zoneId=" + zoneId);
            sendLocationToServer(zoneId);
        }
    }

    /** 비콘 이벤트를 WebView로 전달. JSON 직렬화(Gson)로 detail을 안전하게 escape한다. */
    private void dispatchBeaconEvent(BeaconEvent event) {
        String payloadJson = gson.toJson(event);
        String script =
                "window.dispatchEvent(new CustomEvent('moduflow:native-event', { detail: "
                        + payloadJson + " }));";
        webView.post(() -> webView.evaluateJavascript(script, null));
        Log.d(TAG, "비콘 이벤트 dispatch: " + payloadJson);
    }

    /** 현재 시각을 ISO-8601(오프셋 포함, 예: 2026-06-09T16:00:00+09:00)로 반환한다. */
    private String nowIso8601() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
    }

    // ──────────────────── 서버 전송 ──────────────────────────────────────

    private void sendLocationToServer(int zoneId) {
        LocationData data = new LocationData(getAndroidId(), zoneId, GYM_NAME);

        ApiClient.getInstance(this)
                .getLocationService()
                .updateLocation(data)
                .enqueue(new Callback<Void>() {
                    @Override
                    public void onResponse(@NonNull Call<Void> call,
                                           @NonNull Response<Void> response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "위치 전송 성공: zoneId=" + zoneId);
                        } else {
                            Log.e(TAG, "위치 전송 실패: HTTP " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                        Log.e(TAG, "위치 전송 오류: " + t.getMessage());
                    }
                });
    }

    // ────────────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;  // WebView 활성 — 비콘 감지를 CustomEvent로 전달
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false; // 백그라운드 — 비콘 감지를 update-location 직접 호출로 전환
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            beaconManager.stopRangingBeaconsInRegion(
                    new Region("myRangingRegion", null, null, null));
        } catch (Exception e) {
            Log.e(TAG, "스캔 중지 실패", e);
        }
        beaconManager.unbind(this);
    }
}