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

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.ArmaRssiFilter;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

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
                    sendLocationToServer(newMinor);
                }
            } else {
                if (currentZone != -1) {
                    currentZone         = -1;
                    currentZoneDistance = Double.MAX_VALUE;
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

    // ──────────────────── 서버 전송 ──────────────────────────────────────

    @SuppressLint("HardwareIds")
    private void sendLocationToServer(int zoneId) {
        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        LocationData data = new LocationData(androidId, zoneId);

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