package com.example.moduflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Region;
import org.altbeacon.beacon.service.ArmaRssiFilter;

import android.provider.Settings;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity implements BeaconConsumer {

    // 칼만 필터 파라미터 (헬스장 환경 튜닝)
    private static final double KALMAN_Q           = 0.1;    // 낮을수록 부드럽게 변화
    private static final double KALMAN_R           = 2.0;    // 높을수록 노이즈 억제
    private static final long   BEACON_TIMEOUT_MS  = 8_000L; // 미감지 8초 후 필터 리셋

    private final Map<Integer, KalmanFilter> kalmanFilters  = new HashMap<>();
    private final Map<Integer, Long>         beaconLastSeen = new HashMap<>();

    private int    currentZone         = -1;
    private double currentZoneDistance = Double.MAX_VALUE;
    private BeaconManager beaconManager;
    private TextView tvUuid, tvDistance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        tvUuid     = findViewById(R.id.tv_uuid);
        tvDistance = findViewById(R.id.tv_distance);

        Button btnDashboard    = findViewById(R.id.btn_dashboard);
        Button btnPoseAnalysis = findViewById(R.id.btn_pose_analysis);

        btnDashboard.setOnClickListener(v -> openPwaDashboard());
        btnPoseAnalysis.setOnClickListener(v ->
                startActivity(new Intent(this, PoseAnalysisActivity.class)));

        setupBeacon();
        openPwaDashboard();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    // ──────────────────── 비콘 설정 ─────────────────────────────────

    private void setupBeacon() {
        beaconManager = BeaconManager.getInstanceForApplication(this);
        BeaconManager.setRssiFilterImplClass(ArmaRssiFilter.class);
        beaconManager.setForegroundScanPeriod(1100L);
        beaconManager.setForegroundBetweenScanPeriod(0L);
        try { beaconManager.updateScanPeriods(); }
        catch (Exception e) { Log.e("Beacon", "스캔 주기 설정 실패: " + e.getMessage()); }

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

    // ──────────────────── 비콘 감지 콜백 ────────────────────────────

    @Override
    public void onBeaconServiceConnect() {
        beaconManager.addRangeNotifier((beacons, region) -> {
            long now = System.currentTimeMillis();

            // ── 칼만 필터 적용 + 타임아웃 리셋 ─────────────────────────
            Map<Integer, Double> filteredDistances = new HashMap<>();
            for (Beacon beacon : beacons) {
                try {
                    int minor = beacon.getId3().toInt();
                    if (minor != 53626 && minor != 53630 && minor != 56376) continue;

                    // 오래 미감지된 비콘의 필터 리셋
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

                    Log.d("Kalman", String.format(Locale.US,
                            "minor=%d  raw=%.2fm  filtered=%.2fm", minor, beacon.getDistance(), filtered));
                } catch (Exception e) {
                    Log.e("Beacon", "Minor 파싱 오류: " + e.getMessage());
                }
            }

            // ── 필터링된 거리 기준 최근접 비콘 선택 ─────────────────────
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

                // 히스테리시스: 현재 구역 비콘의 최신 필터 거리와 비교
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

                    String zoneName;
                    if      (newMinor == 53626) zoneName = "비콘1";
                    else if (newMinor == 53630) zoneName = "비콘2";
                    else if (newMinor == 56376) zoneName = "비콘3";
                    else                        zoneName = "알 수 없는 구역";

                    final String finalZone = zoneName;
                    runOnUiThread(() -> {
                        tvUuid.setText(String.format("현재 구역: %s", finalZone));
                        sendLocationToServer(newMinor);
                    });
                }

                runOnUiThread(() ->
                        tvDistance.setText(String.format(Locale.US, "거리: %.2f m", newDistance)));

            } else {
                if (currentZone != -1) {
                    currentZone         = -1;
                    currentZoneDistance = Double.MAX_VALUE;
                    runOnUiThread(() -> {
                        tvUuid.setText("현재 구역: 감지 안됨");
                        tvDistance.setText("비콘 범위를 벗어났습니다.");
                        sendLocationToServer(0);
                    });
                }
            }
        });

        try {
            beaconManager.startRangingBeaconsInRegion(
                    new Region("myRangingRegion", null, null, null));
        } catch (Exception e) {
            Log.e("Beacon", "스캔 시작 실패: " + e.getMessage());
        }
    }

    // ──────────────────── 서버 전송 ─────────────────────────────────

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
                            Log.d("API", "위치 전송 성공: zoneId=" + zoneId);
                        } else {
                            Log.e("API", "위치 전송 실패: HTTP " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                        Log.e("API", "위치 전송 오류: " + t.getMessage());
                    }
                });
    }

    // ──────────────────── 유틸 ──────────────────────────────────────

    private void openPwaDashboard() {
        startActivity(new Intent(this, PwaActivity.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            beaconManager.stopRangingBeaconsInRegion(
                    new Region("myRangingRegion", null, null, null));
        } catch (Exception e) {
            Log.e("Beacon", "스캔 중지 실패", e);
        }
        beaconManager.unbind(this);
    }
}