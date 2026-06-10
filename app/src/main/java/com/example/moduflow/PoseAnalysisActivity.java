package com.example.moduflow;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
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
import com.google.gson.JsonElement;

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

/**
 * 실시간 AI 자세 분석 화면.
 *
 * 진입 경로 세 가지:
 *   A. PWA → Android.startWorkout(csv)  : Intent extra "exercises"에 루틴 전달
 *   B. 딥링크 moduflow://app/workout/run : URI 파라미터 ?exercises=squat,lunge
 *   C. MainActivity 버튼              : 진입 후 /api/v1/routines API로 루틴 조회
 *
 * 세트 진행 흐름:
 *   운동 선택 → 카운트 진행 → 목표 횟수 달성 or [세트 종료] 버튼
 *   → 60초 휴식 타이머 → 자동으로 다음 세트 시작
 *   → 마지막 세트 완료 시 "운동 완료" 메시지
 *
 * 카메라 프레임 전송 흐름:
 *   CameraX → YUV→JPEG→Base64 (ImageUtils) → WebSocket 전송 (PoseWebSocketClient)
 *   → AI 서버 분석 → PoseResult 수신 → UI 업데이트
 */
public class PoseAnalysisActivity extends AppCompatActivity {

    private static final String TAG                   = "PoseAnalysis";
    private static final int    PERMISSION_CAMERA     = 1001;
    private static final long   MIN_FRAME_INTERVAL_MS = 100;  // 최대 10fps
    private static final int    REST_SECONDS          = 60;   // 세트 간 휴식 시간
    private static final long   TTS_COOLDOWN_MS       = 3000; // 같은 이슈 재발화 금지 간격

    // ── 뷰 ──────────────────────────────────────────────────────────────
    private PreviewView  previewView;
    private TextView     tvStatus, tvFeedback,
                         tvCount, tvRoutineLabel,
                         tvSetInfo, tvWeight,
                         tvRestTimer, tvRestSetInfo,
                         tvSetFeedbackAssessment, tvSetFeedbackIssues,
                         tvAllDoneAssessment, tvAllDoneIssues;
    private com.google.android.material.button.MaterialButton btnFinish, btnViewResult;
    private View         viewPostureIndicator;
    private LinearLayout layoutLoading, layoutRest, layoutSetFeedback,
                         layoutAllDone, layoutAllDoneFeedback;

    // ── 네트워크 ─────────────────────────────────────────────────────────
    private PoseWebSocketClient wsClient;

    // ── 카메라 ──────────────────────────────────────────────────────────
    private ExecutorService       cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private boolean               isCameraEnabled = true;
    private final AtomicBoolean   isProcessing = new AtomicBoolean(false);
    private long                  lastFrameTime = 0;

    // ── 운동 루틴 상태 ────────────────────────────────────────────────────
    /**
     * 현재 선택된 운동의 내부 키.
     * exerciseId가 있으면 exerciseId, 없으면 서버 UUID(id)를 사용한다.
     */
    private String  currentExercise   = "squat";
    /**
     * AI 서버로 전송할 운동 식별자 (exerciseId).
     * exerciseId가 null인 운동은 currentExercise(UUID)를 그대로 전송하지만
     * AI 인식이 동작하지 않을 수 있다. → 백엔드에서 exerciseId 설정 필요.
     */
    private String  currentAiExercise = "squat";
    private boolean hasEverConnected = false;

    /**
     * 운동 키 → 서버 루틴 정보 (sets, reps, weight, name).
     * 키: exerciseId가 있으면 exerciseId, 없으면 서버 UUID(id).
     */
    private final Map<String, RoutineResponse> routineMap     = new HashMap<>();
    /** 운동 키 → 저장된 세트 번호. 종목 전환 시 진행 상태를 보존한다. */
    private final Map<String, Integer>         setProgressMap  = new HashMap<>();
    /** 모든 세트를 마친 운동 키 집합. 전체 완료 여부 판단에 사용한다. */
    private final java.util.Set<String>        completedExercises = new java.util.HashSet<>();

    private int     currentSet    = 1;  // 현재 진행 중인 세트 번호 (1-based)
    private int     targetSets    = 0;  // 현재 운동의 목표 세트 수 (0=정보 없음)
    private int     targetReps    = 0;  // 현재 운동의 목표 횟수 (0=자동완료 비활성)
    private int     lastRepCount       = 0;     // AI 서버에서 마지막으로 받은 횟수
    private boolean setCompleted       = false; // 현재 세트 완료 여부 (중복 트리거 방지)
    private boolean sessionEndRequested = false; // 운동 종료 요청 중복 방지

    private CountDownTimer restTimer = null;

    // ── TTS ──────────────────────────────────────────────────────────────
    private TextToSpeech              tts;
    private boolean                   ttsEnabled    = true;
    private final Map<String, Long>   lastSpokenMap = new HashMap<>();

    // ────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pose_analysis);

        bindViews();
        setupButtons();
        fetchRoutinesAndSetupChips();
        setupWebSocket();
        setupTts();
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

    // ────────────────── 뷰 바인딩 ───────────────────────────────────────

    private void bindViews() {
        previewView          = findViewById(R.id.previewView);
        tvStatus             = findViewById(R.id.tvStatus);
        tvRoutineLabel       = findViewById(R.id.tvRoutineLabel);
        tvFeedback           = findViewById(R.id.tvFeedback);
        tvCount              = findViewById(R.id.tvCount);
        tvSetInfo            = findViewById(R.id.tvSetInfo);
        tvWeight             = findViewById(R.id.tvWeight);
        tvRestTimer              = findViewById(R.id.tvRestTimer);
        tvRestSetInfo            = findViewById(R.id.tvRestSetInfo);
        tvSetFeedbackAssessment  = findViewById(R.id.tvSetFeedbackAssessment);
        tvSetFeedbackIssues      = findViewById(R.id.tvSetFeedbackIssues);
        viewPostureIndicator     = findViewById(R.id.viewPostureIndicator);
        layoutLoading            = findViewById(R.id.layoutLoading);
        layoutRest               = findViewById(R.id.layoutRest);
        layoutSetFeedback        = findViewById(R.id.layoutSetFeedback);
        layoutAllDone            = findViewById(R.id.layoutAllDone);
        layoutAllDoneFeedback    = findViewById(R.id.layoutAllDoneFeedback);
        tvAllDoneAssessment      = findViewById(R.id.tvAllDoneAssessment);
        tvAllDoneIssues          = findViewById(R.id.tvAllDoneIssues);
    }

    // ────────────────── 루틴 조회 & 칩 설정 ─────────────────────────────

    @SuppressLint("HardwareIds")
    private void fetchRoutinesAndSetupChips() {
        String dayOfWeek = todayDayOfWeek();
        final List<String> presetExercises = resolvePresetExercises();
        Log.d(TAG, "presetExercises: " + presetExercises);

        // 루틴은 "사용자 본인 데이터"이며, 백엔드가 JWT 인증 사용자 기준으로 조회한다.
        // userId/ANDROID_ID는 더 이상 전달하지 않는다. (JWT는 ApiClient 인터셉터가 자동 첨부)
        Log.d(TAG, "API 루틴 조회: JWT 기준, dayOfWeek=" + dayOfWeek);

        ApiClient.getInstance(this).getRoutineService()
                .getRoutines(dayOfWeek)
                .enqueue(new Callback<Map<String, JsonElement>>() {
                    @Override
                    public void onResponse(@NonNull Call<Map<String, JsonElement>> call,
                                           @NonNull Response<Map<String, JsonElement>> response) {
                        Log.d(TAG, "루틴 API 응답: HTTP " + response.code());

                        List<String> serverExercises = new ArrayList<>();
                        Map<String, String> nameMap   = new HashMap<>();
                        routineMap.clear();

                        if (response.isSuccessful() && response.body() != null) {
                            Log.d(TAG, "루틴 응답 키 목록: " + response.body().keySet());
                            // 백엔드가 "mon","tue" 등 3글자 소문자 약어를 키로 반환
                            // "restDays" 등 비-요일 키는 JsonArray<String>이라 직접 파싱
                            String dayKey = dayOfWeek.substring(0, 3).toLowerCase(Locale.US);
                            JsonElement dayEl = response.body().get(dayKey);
                            Gson gson = new Gson();
                            if (dayEl != null && dayEl.isJsonArray()) {
                                for (JsonElement item : dayEl.getAsJsonArray()) {
                                    if (!item.isJsonObject()) continue;
                                    RoutineResponse r = gson.fromJson(item, RoutineResponse.class);
                                    boolean hasExerciseId = r.exerciseId != null
                                            && !r.exerciseId.isEmpty();
                                    String key = hasExerciseId ? r.exerciseId : r.id;
                                    if (key == null || key.isEmpty()) continue;

                                    Log.d(TAG, "  key=" + key
                                            + ", exerciseId=" + r.exerciseId
                                            + ", name=" + r.name
                                            + ", sets=" + r.sets
                                            + ", reps=" + r.reps
                                            + (hasExerciseId ? "" : "  ⚠ exerciseId 없음(AI 미지원)"));

                                    if (!serverExercises.contains(key)) serverExercises.add(key);
                                    if (r.name != null && !r.name.isEmpty()) nameMap.put(key, r.name);
                                    routineMap.put(key, r);
                                }
                            }
                        }

                        // 칩 목록 우선순위: 서버 루틴 > presetExercises > 기본값
                        // presetExercises는 "처음 선택할 운동" 힌트로만 사용한다.
                        // (PWA가 startWorkout("pushup") 처럼 일부만 보내도
                        //  서버 루틴 전체가 칩에 표시된다.)
                        List<String> exercises = !serverExercises.isEmpty() ? serverExercises
                                : !presetExercises.isEmpty()                ? presetExercises
                                : defaultExercises();

                        // 초기 선택 힌트: presetExercises[0] → 서버 목록에서 매칭 시도
                        final String initialHint = presetExercises.isEmpty()
                                ? null : presetExercises.get(0).trim();

                        final List<String> finalExercises = exercises;
                        final Map<String, String> finalNameMap =
                                !nameMap.isEmpty() ? nameMap : null;
                        final boolean fromServer = !serverExercises.isEmpty();

                        runOnUiThread(() -> {
                            updateRoutineLabel(dayOfWeek, finalExercises.size(), fromServer);
                            setupExerciseChips(finalExercises, finalNameMap, initialHint);
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Call<Map<String, JsonElement>> call,
                                          @NonNull Throwable t) {
                        Log.e(TAG, "루틴 API 호출 실패: " + t.getMessage());
                        List<String> fallback = !presetExercises.isEmpty()
                                ? presetExercises : defaultExercises();
                        runOnUiThread(() -> {
                            updateRoutineLabel(dayOfWeek, fallback.size(),
                                    !presetExercises.isEmpty());
                            setupExerciseChips(fallback, null, null);
                        });
                    }
                });
    }

    /** Intent extra → 딥링크 순서로 미리 결정된 운동 목록을 반환. 없으면 빈 리스트. */
    private List<String> resolvePresetExercises() {
        String intentExtra = getIntent().getStringExtra("exercises");
        if (intentExtra != null && !intentExtra.isEmpty()) {
            return new ArrayList<>(Arrays.asList(intentExtra.trim().split(",")));
        }
        Uri deepLink = getIntent().getData();
        if (deepLink != null) {
            String param = deepLink.getQueryParameter("exercises");
            if (param != null && !param.isEmpty()) {
                return new ArrayList<>(Arrays.asList(param.trim().split(",")));
            }
        }
        return new ArrayList<>();
    }

    /**
     * 운동 칩 목록을 구성한다.
     *
     * @param exercises  표시할 운동 키 목록 (exerciseId 또는 UUID)
     * @param nameMap    키 → 표시 이름 매핑 (null이면 fallback 레이블 사용)
     * @param initialHint 처음 선택할 운동 힌트 (PWA preset의 exerciseId).
     *                    null이면 첫 번째 칩을 선택한다.
     */
    private void setupExerciseChips(List<String> exercises,
                                    @Nullable Map<String, String> nameMap,
                                    @Nullable String initialHint) {
        Map<String, String> fallbackLabels = new HashMap<>();
        fallbackLabels.put("squat",         "스쿼트");
        fallbackLabels.put("pushup",        "푸쉬업");
        fallbackLabels.put("lunge",         "런지");
        fallbackLabels.put("pullup",        "풀업");
        fallbackLabels.put("situp",         "싯업");
        fallbackLabels.put("lateralraise",  "레터럴 레이즈");
        fallbackLabels.put("shoulderpress", "숄더 프레스");
        fallbackLabels.put("bench-press",   "벤치프레스");
        fallbackLabels.put("biceps-curl",   "바이셉 컬");

        // 힌트와 매칭되는 키 탐색
        // 1순위: 키 자체가 힌트와 일치 (exerciseId가 키인 경우)
        // 2순위: 루틴 정보의 exerciseId가 힌트와 일치 (UUID 키인 경우)
        String initialKey = exercises.isEmpty() ? null : exercises.get(0);
        if (initialHint != null) {
            outer:
            for (String key : exercises) {
                if (key.equals(initialHint)) { initialKey = key; break; }
                RoutineResponse r = routineMap.get(key);
                if (r != null && initialHint.equals(r.exerciseId)) {
                    initialKey = key;
                    break outer;
                }
            }
        }

        ChipGroup chipGroup = findViewById(R.id.chipGroupExercise);
        chipGroup.removeAllViews();

        int limit = Math.min(exercises.size(), 10);
        for (int i = 0; i < limit; i++) {
            String key  = exercises.get(i).trim();
            Chip   chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setTextSize(13f);

            String label = (nameMap != null && nameMap.containsKey(key))
                    ? nameMap.get(key)
                    : fallbackLabels.getOrDefault(key, key);
            chip.setText(label);
            chip.setTag(key);

            if (key.equals(initialKey)) {
                chip.setChecked(true);
                selectExercise(key);
            }
            chipGroup.addView(chip);
        }

        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip checked = group.findViewById(checkedIds.get(0));
            if (checked != null) selectExercise((String) checked.getTag());
        });
    }

    /**
     * 운동 종목 변경 시 호출.
     * 세트 카운터를 초기화하고 해당 운동의 목표 세트/횟수를 UI에 반영한다.
     *
     * @param key routineMap의 키 (exerciseId 또는 UUID)
     */
    private void selectExercise(String key) {
        // 현재 진행 중인 세트 저장 (다시 돌아올 때 복원)
        setProgressMap.put(currentExercise, currentSet);

        currentExercise = key;
        // 이전에 진행하던 세트가 있으면 복원, 처음이면 1세트부터 시작
        Integer saved   = setProgressMap.get(key);
        currentSet      = (saved != null) ? saved : 1;
        setCompleted    = false;
        cancelRestTimer();

        RoutineResponse routine = routineMap.get(key);
        if (routine != null) {
            targetSets = (routine.sets != null && routine.sets > 0) ? routine.sets : 0;
            targetReps = (routine.reps != null && routine.reps > 0) ? routine.reps : 0;
            // AI 서버에는 exerciseId를 사용. 없으면 key를 그대로 사용(AI 미지원 가능)
            currentAiExercise = normalizeExerciseId(
                    (routine.exerciseId != null && !routine.exerciseId.isEmpty())
                    ? routine.exerciseId : key);
        } else {
            // presetExercises 경로: key 자체가 exerciseId
            targetSets        = 0;
            targetReps        = 0;
            currentAiExercise = normalizeExerciseId(key);
        }

        Log.d(TAG, "selectExercise: key=" + key + ", aiId=" + currentAiExercise
                + ", sets=" + targetSets + ", reps=" + targetReps);

        updateSetInfoUI();
        wsClient.sendReset(currentAiExercise);
    }

    /** 세트/횟수/무게 정보 텍스트를 갱신한다. */
    private void updateSetInfoUI() {
        RoutineResponse routine = routineMap.get(currentExercise);
        if (routine != null && routine.sets != null && routine.sets > 0) {
            int reps = (routine.reps != null) ? routine.reps : 0;
            tvSetInfo.setText(String.format("세트 %d / %d   |   목표 %d회",
                    currentSet, routine.sets, reps));
            tvWeight.setText((routine.weight != null && routine.weight > 0)
                    ? String.format(Locale.US, "%.1f kg", routine.weight) : "");
        } else {
            tvSetInfo.setText("—");
            tvWeight.setText("");
        }
    }

    // ────────────────── 세트 완료 & 휴식 타이머 ─────────────────────────

    /**
     * 현재 세트를 완료 처리하고 60초 휴식 타이머를 시작한다.
     *
     * @param manual true=버튼으로 수동 종료, false=목표 횟수 달성 자동 완료
     *               수동 종료 시 목표 횟수를 채운 판정으로 count를 전달한다.
     */
    private void completeCurrentSet(boolean manual) {
        if (setCompleted) return; // 중복 방지
        setCompleted = true;
        cancelRestTimer();

        // 수동 종료: 실제 횟수 vs 목표 횟수 중 큰 값 전송 (목표를 채운 것으로 판정)
        int finalCount = manual && targetReps > 0
                ? Math.max(lastRepCount, targetReps)
                : lastRepCount;

        boolean isLastSet = (targetSets > 0 && currentSet >= targetSets);
        wsClient.sendSetEnd(currentAiExercise, isLastSet, finalCount);

        if (isLastSet) {
            showAllSetsCompleted();
            return;
        }

        // 휴식 타이머 시작
        startRestTimer();
    }

    private void startRestTimer() {
        // 다음 세트 정보 미리 계산
        int nextSet = currentSet + 1;
        String nextSetText;
        if (targetSets > 0 && nextSet <= targetSets) {
            nextSetText = String.format("다음 세트: %d / %d", nextSet, targetSets);
        } else if (targetSets > 0) {
            nextSetText = "세트 완료";
        } else {
            nextSetText = "";
        }

        runOnUiThread(() -> {
            tvRestSetInfo.setText(nextSetText);
            layoutRest.setVisibility(View.VISIBLE);
            tvRestTimer.setText(String.valueOf(REST_SECONDS));
        });

        restTimer = new CountDownTimer(REST_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                runOnUiThread(() -> tvRestTimer.setText(String.valueOf(sec)));
            }

            @Override
            public void onFinish() {
                runOnUiThread(() -> advanceToNextSet());
            }
        }.start();
    }

    /** 휴식 타이머를 취소하고 오버레이를 숨긴다. */
    private void cancelRestTimer() {
        if (restTimer != null) {
            restTimer.cancel();
            restTimer = null;
        }
        runOnUiThread(() -> {
            layoutRest.setVisibility(View.GONE);
            layoutSetFeedback.setVisibility(View.GONE);
            tvSetFeedbackIssues.setVisibility(View.GONE);
        });
    }

    /** 타이머 종료 또는 '바로 시작하기' 후 다음 세트로 전환한다. */
    private void advanceToNextSet() {
        cancelRestTimer();
        // 이미 완료된 운동(마지막 세트 이후)이면 타이머만 닫고 세트 진행 없음
        if (targetSets > 0 && currentSet >= targetSets) {
            setCompleted = false;
            return;
        }
        currentSet++;
        setCompleted = false;
        updateSetInfoUI();
        // set_end 후 서버가 rep 카운터를 자동 리셋하므로 별도 reset 불필요.
        // reset을 보내면 서버의 누적 세트 데이터까지 초기화될 수 있어 제거.
        Log.d(TAG, "다음 세트 시작: " + currentSet + "/" + targetSets);
    }

    private void showAllSetsCompleted() {
        completedExercises.add(currentExercise);

        // routineMap 중 sets > 0 인 운동이 모두 완료됐는지 확인
        boolean allDone = !routineMap.isEmpty();
        for (Map.Entry<String, RoutineResponse> e : routineMap.entrySet()) {
            if (e.getValue().sets > 0 && !completedExercises.contains(e.getKey())) {
                allDone = false;
                break;
            }
        }

        if (allDone) {
            runOnUiThread(() -> {
                layoutRest.setVisibility(View.GONE);
                layoutSetFeedback.setVisibility(View.GONE);
                layoutAllDone.setVisibility(View.VISIBLE);
            });
        } else {
            // 개별 운동 완료: 60초 휴식 타이머 + 세트 피드백 표시
            startRestTimer();
        }
    }

    // ────────────────── 세션 종료 요청 ─────────────────────────────────

    /**
     * 운동 종료 / 결과 보기 버튼 공통 처리.
     * 첫 클릭에서 버튼을 비활성화하고 "요약 준비 중..." 텍스트를 표시한 뒤
     * session_end 메시지를 서버로 전송한다. 중복 클릭은 무시된다.
     */
    private void requestSessionEnd() {
        if (sessionEndRequested) return;
        if (!wsClient.isConnected()) {
            Toast.makeText(this, "서버에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        sessionEndRequested = true;
        cancelRestTimer();

        // 두 버튼 모두 즉시 비활성화 + 안내 텍스트
        btnFinish.setEnabled(false);
        btnFinish.setText("요약 준비 중...");
        btnViewResult.setEnabled(false);
        btnViewResult.setText("요약 준비 중...");

        wsClient.sendSessionEnd();
    }

    // ────────────────── 버튼 설정 ────────────────────────────────────────

    private void setupButtons() {
        // 카메라 토글
        findViewById(R.id.btnCameraToggle).setOnClickListener(v -> toggleCamera());

        // TTS 토글
        findViewById(R.id.btnTtsToggle).setOnClickListener(v -> toggleTts());

        // 카운트 초기화
        findViewById(R.id.btnReset).setOnClickListener(v ->
                wsClient.sendReset(currentExercise));

        // 세트 종료 → 60초 타이머 시작 (수동 종료 = 목표 횟수 달성 판정)
        findViewById(R.id.btnEndSet).setOnClickListener(v -> {
            if (completedExercises.contains(currentExercise)) {
                Toast.makeText(this, "세트가 종료되었습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            completeCurrentSet(true);
        });

        // 바로 시작하기 (타이머 건너뛰기)
        findViewById(R.id.btnSkipRest).setOnClickListener(v ->
                advanceToNextSet());

        // 운동 종료 / 결과 보기 — 공통 핸들러
        btnFinish     = findViewById(R.id.btnFinish);
        btnViewResult = findViewById(R.id.btnViewResult);

        btnFinish.setOnClickListener(v -> requestSessionEnd());
        btnViewResult.setOnClickListener(v -> requestSessionEnd());
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
                isProcessing.set(false);
                runOnUiThread(() -> {
                    tvStatus.setText("● 서버 연결 끊김 (재연결 중...)");
                    tvStatus.setTextColor(Color.parseColor("#FFFF4444"));
                    viewPostureIndicator.setBackgroundColor(Color.parseColor("#FF666666"));
                });
            }

            @Override
            public void onPoseResult(PoseResult result) {
                isProcessing.set(false);
                runOnUiThread(() -> {
                    lastRepCount = result.count;
                    updatePostureUI(result);
                    speakIfNeeded(result);

                    // 목표 횟수 달성 자동 감지 (정보가 있고, 아직 세트 완료 전일 때만)
                    if (!setCompleted && targetReps > 0 && result.count >= targetReps) {
                        completeCurrentSet(false); // 자동 완료
                    }
                });
            }

            @Override
            public void onResetOk(@Nullable String exercise) {
                runOnUiThread(() -> tvCount.setText("0"));
            }

            @Override
            public void onSetFeedback(SessionSummaryResponse.SetStats summary) {
                if (summary == null) return;
                runOnUiThread(() -> {
                    // 이슈 텍스트 공통 생성
                    String issueText = buildIssueText(summary.issuesDetail);

                    if (layoutAllDone.getVisibility() == View.VISIBLE) {
                        // 전체 완료 오버레이에 마지막 세트 요약 표시
                        if (summary.assessment != null) {
                            tvAllDoneAssessment.setText(summary.assessment);
                        }
                        if (issueText != null) {
                            tvAllDoneIssues.setText(issueText);
                            tvAllDoneIssues.setVisibility(View.VISIBLE);
                        }
                        layoutAllDoneFeedback.setVisibility(View.VISIBLE);
                    } else {
                        // 휴식 타이머 오버레이에 세트 요약 표시
                        if (summary.assessment != null) {
                            tvSetFeedbackAssessment.setText(summary.assessment);
                        }
                        if (issueText != null) {
                            tvSetFeedbackIssues.setText(issueText);
                            tvSetFeedbackIssues.setVisibility(View.VISIBLE);
                        }
                        layoutSetFeedback.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onExerciseFeedback(SessionSummaryResponse.ExerciseStats summary) {
                // 운동 종목 완료 피드백은 세션 요약 화면에서 확인
            }

            @Override
            public void onSessionFeedback(SessionSummaryResponse.SummaryData summary) {
                runOnUiThread(() -> launchSummaryScreen(summary));
            }

            @Override
            public void onServerError(String message) {
                isProcessing.set(false);
                Log.w(TAG, "서버 오류: " + message);
                if (message.contains("사람이 감지") || message.contains("분석에 실패")) {
                    runOnUiThread(() -> tvFeedback.setText(message));
                }
            }
        });
    }

    // ──────────────────── 자세 분석 UI 업데이트 ─────────────────────────

    private void updatePostureUI(PoseResult result) {
        boolean isGood = "good".equals(result.posture);

        viewPostureIndicator.setBackgroundColor(isGood
                ? Color.parseColor("#FF44CC44") : Color.parseColor("#FFFF4444"));

        tvFeedback.setText(result.feedback != null ? result.feedback : "");
        tvFeedback.setTextColor(isGood ? Color.parseColor("#FF44CC44") : Color.WHITE);

        tvCount.setText(String.valueOf(result.count));

        if (result.repCompleted) triggerRepCompletedFeedback();
    }

    /** rep 완료 시: 진동 + 카운트 스케일 애니메이션 */
    @SuppressWarnings("deprecation")
    private void triggerRepCompletedFeedback() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                        VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(120);
            }
        }
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
        intent.putExtra(SessionSummaryActivity.EXTRA_SUMMARY_JSON,
                new Gson().toJson(summary));
        startActivity(intent);
    }

    // ──────────────────── TTS ───────────────────────────────────────────

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
            } else {
                Log.w(TAG, "TTS 초기화 실패: status=" + status);
            }
        });
    }

    /**
     * issues 배열이 비어있지 않을 때만 feedback 첫 문구를 TTS로 발화한다.
     * 같은 이슈는 3초 쿨다운 내 재발화를 막는다.
     */
    private void speakIfNeeded(PoseResult result) {
        if (!ttsEnabled) return;    // 음성 토글 OFF면 중단
        if (result.issues == null || result.issues.isEmpty()) return;   // 교정할 이슈 있을 때만
        String issue = result.issues.get(0);
        long now = System.currentTimeMillis();
        Long last = lastSpokenMap.get(issue);
        if (last != null && now - last < TTS_COOLDOWN_MS) return;   // 같은 이슈 3초 쿨다운
        String text = result.feedback != null
                ? result.feedback.split(" \\| ")[0].trim() : "";    // 피드백 첫 문구만
        // 위치 안내 메시지는 발화 스킵 — lastSpokenMap에도 기록하지 않아 쿨다운 오염 방지
        if (text.isEmpty() || isPositioningMessage(text)) return;   // 위치안내 문구는 제외
        lastSpokenMap.put(issue, now);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, issue); // 직전 발화 끊고 즉시 발화
    }

    /** AI 서버 운동 식별자를 현행 규격(소문자 붙여쓰기)으로 정규화한다. */
    private String normalizeExerciseId(String id) {
        if (id == null) return null;
        switch (id) {
            case "lateral_raise":  return "lateralraise";
            case "shoulder_press": return "shoulderpress";
            default:               return id;
        }
    }

    private boolean isPositioningMessage(String text) {
        return text.contains("준비 자세")
                || text.contains("사람이 보이지 않")
                || text.contains("전신이 화면");
    }

    private void toggleTts() {
        ttsEnabled = !ttsEnabled;
        if (!ttsEnabled) tts.stop();
        com.google.android.material.button.MaterialButton btn =
                findViewById(R.id.btnTtsToggle);
        btn.setText(ttsEnabled ? "🔊 음성" : "🔇 음성");
    }

    // ──────────────────── 유틸 ──────────────────────────────────────────

    /** 이슈 목록에서 상위 3개 메시지를 "• 메시지" 형식으로 합쳐 반환. 없으면 null. */
    private String buildIssueText(List<SessionSummaryResponse.IssueDetail> issues) {
        if (issues == null || issues.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, issues.size());
        for (int i = 0; i < limit; i++) {
            String msg = issues.get(i).message;
            if (msg != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("• ").append(msg);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String todayDayOfWeek() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.time.DayOfWeek.from(java.time.LocalDate.now()).name();
        }
        String[] days = {"SUNDAY","MONDAY","TUESDAY","WEDNESDAY",
                          "THURSDAY","FRIDAY","SATURDAY"};
        return days[java.util.Calendar.getInstance()
                .get(java.util.Calendar.DAY_OF_WEEK) - 1];
    }

    private List<String> defaultExercises() {
        return new ArrayList<>(Arrays.asList("squat", "pushup", "lunge"));
    }

    private void updateRoutineLabel(String dayOfWeek, int count, boolean fromServer) {
        Map<String, String> dayMap = new HashMap<>();
        dayMap.put("MONDAY",    "월요일"); dayMap.put("TUESDAY",  "화요일");
        dayMap.put("WEDNESDAY", "수요일"); dayMap.put("THURSDAY", "목요일");
        dayMap.put("FRIDAY",    "금요일"); dayMap.put("SATURDAY", "토요일");
        dayMap.put("SUNDAY",    "일요일");
        String dayKo = dayMap.getOrDefault(dayOfWeek, dayOfWeek);
        tvRoutineLabel.setText(fromServer
                ? String.format("%s 루틴 (%d종목)", dayKo, count)
                : String.format("%s 루틴 (기본)", dayKo));
    }

    private boolean cameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
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
                    if (!isProcessing.get()
                            && wsClient.isConnected()
                            && (now - lastFrameTime) > MIN_FRAME_INTERVAL_MS) {

                        isProcessing.set(true);
                        lastFrameTime = now;

                        String base64 = ImageUtils.toBase64Jpeg(image, 640, 70);
                        if (base64 != null) {
                            wsClient.sendFrame(base64, currentAiExercise);
                        } else {
                            isProcessing.set(false);
                        }
                    }
                    image.close();
                });

                cameraProvider = provider;
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

    private void toggleCamera() {
        isCameraEnabled = !isCameraEnabled;
        com.google.android.material.button.MaterialButton btn =
                findViewById(R.id.btnCameraToggle);

        if (isCameraEnabled) {
            previewView.setVisibility(View.VISIBLE);
            startCamera();
            tvFeedback.setText("자세를 잡아주세요");
            tvFeedback.setTextColor(android.graphics.Color.WHITE);
            btn.setText("📷 끄기");
        } else {
            if (cameraProvider != null) cameraProvider.unbindAll();
            if (cameraExecutor != null) {
                cameraExecutor.shutdown();
                cameraExecutor = null;
            }
            isProcessing.set(false);
            previewView.setVisibility(View.INVISIBLE);
            tvFeedback.setText("카메라가 꺼진 상태입니다.");
            tvFeedback.setTextColor(android.graphics.Color.parseColor("#FFAAAAAA"));
            btn.setText("📷 켜기");
        }
    }

    // ──────────────────── 권한 처리 ─────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CAMERA) {
            if (cameraPermissionGranted()) startCamera();
            else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // ──────────────────── 생명주기 ───────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // 세션 요약 화면에서 돌아올 때 버튼 원래 상태로 복원
        if (sessionEndRequested) {
            sessionEndRequested = false;
            btnFinish.setEnabled(true);
            btnFinish.setText("운동 종료");
            btnViewResult.setEnabled(true);
            btnViewResult.setText("결과 보기");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelRestTimer();
        wsClient.disconnect();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }
}