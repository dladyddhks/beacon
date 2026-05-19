package com.example.moduflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PoseAnalysisActivity extends AppCompatActivity {

    private static final String TAG                    = "PoseAnalysis";
    private static final int    PERMISSION_CAMERA      = 1001;
    private static final long   MIN_FRAME_INTERVAL_MS  = 100; // 최대 10fps

    // ── 뷰 ──────────────────────────────────────────────────────────────
    private PreviewView  previewView;
    private TextView     tvStatus, tvFeedback, tvAngles, tvMetrics, tvCount, tvStage;
    private View         viewPostureIndicator;
    private LinearLayout layoutLoading;

    // ── 네트워크 ─────────────────────────────────────────────────────────
    private PoseWebSocketClient wsClient;

    // ── 카메라 ──────────────────────────────────────────────────────────
    private ExecutorService     cameraExecutor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private long                lastFrameTime = 0;

    // ── 상태 ─────────────────────────────────────────────────────────────
    private String  currentExercise    = "squat";
    private boolean hasEverConnected   = false;

    // ────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pose_analysis);

        bindViews();
        setupButtons();
        fetchRoutinesAndSetupChips();
        setupWebSocket();
        logDeepLinkSource();

        if (cameraPermissionGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA);
        }

        wsClient.connect();
    }

    // ────────────────── 딥링크 처리 ─────────────────────────────────────

    private void logDeepLinkSource() {
        Uri data = getIntent().getData();
        if (data == null) return;
        String source = data.getQueryParameter("source");
        Log.i(TAG, "딥링크 진입 — URI: " + data + ", source: " + source);
    }

    // ────────────────── 뷰 바인딩 & 초기 설정 ───────────────────────────

    private void bindViews() {
        previewView          = findViewById(R.id.previewView);
        tvStatus             = findViewById(R.id.tvStatus);
        tvFeedback           = findViewById(R.id.tvFeedback);
        tvAngles             = findViewById(R.id.tvAngles);
        tvMetrics            = findViewById(R.id.tvMetrics);
        tvCount              = findViewById(R.id.tvCount);
        tvStage              = findViewById(R.id.tvStage);
        viewPostureIndicator = findViewById(R.id.viewPostureIndicator);
        layoutLoading        = findViewById(R.id.layoutLoading);
    }

    @SuppressLint("HardwareIds")
    private void fetchRoutinesAndSetupChips() {
        String userId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        ApiClient.getInstance(this).getRoutineService()
                .getRoutines(userId)
                .enqueue(new Callback<List<RoutineResponse>>() {
                    @Override
                    public void onResponse(@NonNull Call<List<RoutineResponse>> call,
                                           @NonNull Response<List<RoutineResponse>> response) {
                        List<String> exercises;
                        if (response.isSuccessful()
                                && response.body() != null
                                && !response.body().isEmpty()) {
                            exercises = new ArrayList<>();
                            for (RoutineResponse r : response.body()) {
                                if (r.exerciseId != null && !r.exerciseId.isEmpty()) {
                                    exercises.add(r.exerciseId);
                                }
                            }
                            if (exercises.isEmpty()) exercises = getExercisesFromIntent();
                        } else {
                            Log.w(TAG, "루틴 조회 응답 없음 — 기본값 사용: HTTP " + response.code());
                            exercises = getExercisesFromIntent();
                        }
                        final List<String> finalExercises = exercises;
                        runOnUiThread(() -> setupExerciseChips(finalExercises));
                    }

                    @Override
                    public void onFailure(@NonNull Call<List<RoutineResponse>> call,
                                          @NonNull Throwable t) {
                        Log.e(TAG, "루틴 조회 실패 — 기본값 사용: " + t.getMessage());
                        runOnUiThread(() -> setupExerciseChips(getExercisesFromIntent()));
                    }
                });
    }

    private List<String> getExercisesFromIntent() {
        // 1순위: JS Interface로 전달된 extra (PwaActivity.Android.startWorkout)
        String extra = getIntent().getStringExtra("exercises");
        if (extra != null && !extra.isEmpty()) {
            return Arrays.asList(extra.trim().split(","));
        }
        // 2순위: 딥링크 URI 파라미터 (?exercises=squat,lunge)
        Uri data = getIntent().getData();
        if (data != null) {
            String param = data.getQueryParameter("exercises");
            if (param != null && !param.isEmpty()) {
                return Arrays.asList(param.trim().split(","));
            }
        }
        // 기본값: MainActivity 버튼으로 직접 진입한 경우
        return Arrays.asList("squat", "pushup", "lunge");
    }

    private void setupExerciseChips(List<String> exercises) {
        Map<String, String> labelMap = new HashMap<>();
        labelMap.put("squat",  "스쿼트");
        labelMap.put("pushup", "푸쉬업");
        labelMap.put("lunge",  "런지");

        ChipGroup chipGroup = findViewById(R.id.chipGroupExercise);
        chipGroup.removeAllViews();

        for (int i = 0; i < exercises.size(); i++) {
            String key = exercises.get(i).trim();
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setText(labelMap.getOrDefault(key, key));
            chip.setTag(key);
            if (i == 0) {
                chip.setChecked(true);
                currentExercise = key;
            }
            chipGroup.addView(chip);
        }

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip checked = group.findViewById(checkedIds.get(0));
            if (checked != null) currentExercise = (String) checked.getTag();
        });
    }

    private void setupButtons() {
        // 현재 운동 카운트/이슈 초기화
        findViewById(R.id.btnReset).setOnClickListener(v ->
                wsClient.sendReset(currentExercise));

        // 운동 종료: 세션 요약 요청
        findViewById(R.id.btnFinish).setOnClickListener(v -> {
            if (!wsClient.isConnected()) {
                Toast.makeText(this, "서버에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            wsClient.requestSummary();
        });
    }

    // ──────────────────── WebSocket 설정 ────────────────────────────────

    private void setupWebSocket() {
        wsClient = new PoseWebSocketClient(Config.AI_SERVER_HOST, Config.AI_SERVER_PORT);
        wsClient.setListener(new PoseWebSocketClient.PoseUpdateListener() {

            @Override
            public void onConnecting() {
                runOnUiThread(() -> {
                    tvStatus.setText("● 서버 연결 중...");
                    tvStatus.setTextColor(Color.parseColor("#FFAAAAAA"));
                    if (!hasEverConnected) layoutLoading.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    hasEverConnected = true;
                    layoutLoading.setVisibility(View.GONE);
                    tvStatus.setText("● AI 서버 연결됨");
                    tvStatus.setTextColor(Color.parseColor("#FF44CC44"));
                });
            }

            @Override
            public void onDisconnected() {
                isProcessing.set(false); // 재연결 후 즉시 전송 가능하도록
                runOnUiThread(() -> {
                    tvStatus.setText("● 서버 연결 끊김 (재연결 중...)");
                    tvStatus.setTextColor(Color.parseColor("#FFFF4444"));
                    viewPostureIndicator.setBackgroundColor(Color.parseColor("#FF666666"));
                });
            }

            @Override
            public void onPoseResult(PoseResult result) {
                isProcessing.set(false); // 응답 수신 → 다음 프레임 전송 가능
                runOnUiThread(() -> updatePostureUI(result));
            }

            @Override
            public void onResetOk(@Nullable String exercise) {
                runOnUiThread(() -> {
                    tvCount.setText("0");
                    tvStage.setText("—");
                    String msg = exercise != null
                            ? exercise + " 카운트가 초기화되었습니다."
                            : "세션이 초기화되었습니다.";
                    Toast.makeText(PoseAnalysisActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onSummary(SessionSummaryResponse.SummaryData summary) {
                runOnUiThread(() -> launchSummaryScreen(summary));
            }

            @Override
            public void onServerError(String message) {
                isProcessing.set(false); // 에러 후에도 다음 프레임 전송 허용
                Log.w(TAG, "서버 오류: " + message);
                // 사람 미감지는 피드백 텍스트로만 표시 (UI 오염 최소화)
                if (message.contains("사람이 감지") || message.contains("분석에 실패")) {
                    runOnUiThread(() -> tvFeedback.setText(message));
                }
            }
        });
    }

    // ──────────────────── 자세 분석 UI 업데이트 ─────────────────────────

    private void updatePostureUI(PoseResult result) {
        boolean isGood = "good".equals(result.posture);

        // 자세 인디케이터 색상
        viewPostureIndicator.setBackgroundColor(isGood ? Color.parseColor("#FF44CC44")
                                                       : Color.parseColor("#FFFF4444"));

        // 피드백 텍스트
        tvFeedback.setText(result.feedback != null ? result.feedback : "");
        tvFeedback.setTextColor(isGood ? Color.parseColor("#FF44CC44") : Color.WHITE);

        // Rep 카운트 + Stage
        tvCount.setText(String.valueOf(result.count));
        tvStage.setText(result.stage != null ? result.stage : "—");

        // 각도 정보
        if (result.angles != null && !result.angles.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Double> e : result.angles.entrySet()) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(e.getKey()).append(": ")
                  .append(String.format(Locale.US, "%.1f°", e.getValue()));
            }
            tvAngles.setText(sb.toString());
        } else {
            tvAngles.setText("");
        }

        // 지표
        long latency = System.currentTimeMillis() - lastFrameTime;
        double fps   = latency > 0 ? 1000.0 / latency : 0;
        tvMetrics.setText(String.format(Locale.US, "Latency: %dms | FPS: %.1f", latency, fps));

        // rep 완료 피드백
        if (result.rep_completed) triggerRepCompletedFeedback();
    }

    /** rep 완료 시: 진동 + 카운트 스케일 애니메이션 */
    @SuppressWarnings("deprecation")
    private void triggerRepCompletedFeedback() {
        // 진동
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                        VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(120);
            }
        }

        // 카운트 숫자 강조 애니메이션
        tvCount.animate().scaleX(1.5f).scaleY(1.5f).setDuration(120)
                .withEndAction(() ->
                        tvCount.animate().scaleX(1f).scaleY(1f).setDuration(150).start())
                .start();
    }

    // ──────────────────── 세션 요약 화면 이동 ───────────────────────────

    private void launchSummaryScreen(SessionSummaryResponse.SummaryData summary) {
        if (summary == null) {
            Toast.makeText(this, "요약 데이터를 받지 못했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SessionSummaryActivity.class);
        intent.putExtra(SessionSummaryActivity.EXTRA_SUMMARY_JSON, new Gson().toJson(summary));
        startActivity(intent);
    }

    // ──────────────────── CameraX 설정 ──────────────────────────────────

    private void startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, image -> {
                    long now = System.currentTimeMillis();

                    // throttling: 응답 대기 중이 아니고 최소 간격이 지났을 때만 전송
                    if (!isProcessing.get()
                            && wsClient.isConnected()
                            && (now - lastFrameTime) > MIN_FRAME_INTERVAL_MS) {

                        isProcessing.set(true);
                        lastFrameTime = now;

                        String base64 = ImageUtils.toBase64Jpeg(image, 640, 70);
                        if (base64 != null) {
                            wsClient.sendFrame(base64, currentExercise);
                        } else {
                            isProcessing.set(false);
                        }
                    }
                    image.close();
                });

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX 초기화 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ──────────────────── 권한 처리 ─────────────────────────────────────

    private boolean cameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CAMERA) {
            if (cameraPermissionGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // ──────────────────── 생명주기 ───────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wsClient.disconnect();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
