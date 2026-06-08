package com.example.moduflow;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 운동 종료 후 세션 전체 요약을 표시하는 화면.
 */
public class SessionSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_SUMMARY_JSON = "summary_json";

    private static final int ISSUES_PREVIEW = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_summary);

        // 하단 버튼 행이 네비게이션 바에 가리지 않도록 bottom inset 적용
        View layoutBottomButtons = findViewById(R.id.layoutBottomButtons);
        ViewCompat.setOnApplyWindowInsetsListener(layoutBottomButtons, (v, insets) -> {
            int navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.setMargins(dp(16), 0, dp(16), dp(16) + navBar);
            v.setLayoutParams(lp);
            return insets;
        });

        String json = getIntent().getStringExtra(EXTRA_SUMMARY_JSON);
        SessionSummaryResponse.SummaryData summary =
                new Gson().fromJson(json, SessionSummaryResponse.SummaryData.class);

        bindSummary(summary);

        // 돌아가기: 요약 화면 닫고 자세 분석 화면으로 복귀
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        // 자세 분석 종료: PwaActivity까지 백스택을 모두 정리하고 복귀
        findViewById(R.id.btnExitAnalysis).setOnClickListener(v -> {
            Intent intent = new Intent(this, PwaActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    // ───────────────────────── 요약 화면 구성 ───────────────────────────

    private void bindSummary(SessionSummaryResponse.SummaryData summary) {
        // 시작 시간 (연도 제외)
        TextView tvTime = findViewById(R.id.tvSessionTime);
        if (summary != null && summary.startTime != null) {
            tvTime.setText("시작: " + formatIso(summary.startTime));
        }

        // AI 총평 섹션: aiSummary 있으면 그걸, 없으면 assessment
        View layoutAiSection = findViewById(R.id.layoutAiSection);
        TextView tvAiHeadline = findViewById(R.id.tvAiHeadline);
        if (summary != null) {
            String headline = (summary.aiSummary != null && !summary.aiSummary.isEmpty())
                    ? summary.aiSummary : summary.assessment;
            if (headline != null && !headline.isEmpty()) {
                tvAiHeadline.setText(headline);
                layoutAiSection.setVisibility(View.VISIBLE);
            }
        }

        LinearLayout container = findViewById(R.id.layoutExercises);

        if (summary == null || summary.exercises == null || summary.exercises.isEmpty()) {
            TextView empty = makeText("기록된 운동이 없습니다.", 15, color(R.color.summary_empty_text));
            empty.setPadding(0, dp(24), 0, 0);
            container.addView(empty);
            return;
        }

        // totalReps 내림차순 정렬
        List<Map.Entry<String, SessionSummaryResponse.ExerciseStats>> entries =
                new ArrayList<>(summary.exercises.entrySet());
        entries.sort((a, b) -> b.getValue().totalReps - a.getValue().totalReps);

        for (Map.Entry<String, SessionSummaryResponse.ExerciseStats> entry : entries) {
            if (entry.getValue().totalReps == 0) continue;
            container.addView(buildExerciseCard(entry.getKey(), entry.getValue()));
        }
    }

    private LinearLayout buildExerciseCard(String exercise,
                                            SessionSummaryResponse.ExerciseStats stats) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(color(R.color.summary_card_background));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        card.setLayoutParams(lp);

        // ── 운동 이름 헤더 ──
        TextView tvTitle = makeText(exerciseName(exercise), 18, color(R.color.summary_exercise_title));
        tvTitle.setTypeface(null, Typeface.BOLD);
        card.addView(tvTitle);

        // ── 세트 · 횟수 · 깔끔 지표 ──
        StringBuilder metricSb = new StringBuilder();
        if (stats.totalSets > 0) metricSb.append(stats.totalSets).append("세트  ·  ");
        metricSb.append(stats.totalReps).append("회 완료");
        if (stats.cleanReps > 0) metricSb.append("  ·  ").append(stats.cleanReps).append("회 깔끔");
        TextView tvMetric = makeText(metricSb.toString(), 13, color(R.color.summary_metric_text));
        tvMetric.setPadding(0, dp(4), 0, dp(10));
        card.addView(tvMetric);

        // ── 자세 분석 요약 (assessment) ──
        if (stats.assessment != null && !stats.assessment.isEmpty()) {
            TextView tvAss = makeText(stats.assessment, 15, color(R.color.summary_assessment_text));
            tvAss.setPadding(0, 0, 0, dp(10));
            card.addView(tvAss);
        }

        // ── 교정 사항 목록 ──
        if (stats.issuesDetail != null) {
            if (stats.issuesDetail.isEmpty()) {
                card.addView(makeText("자세가 안정적이었어요!", 14, color(R.color.summary_positive_text)));
            } else {
                addIssuesDetail(card, stats.issuesDetail);
            }
        } else {
            addLegacyIssueCounts(card, stats.issueCounts);
        }

        return card;
    }

    /** issuesDetail 기반 코칭 목록 (message + tip, 상위 3개 + 더 보기) */
    private void addIssuesDetail(LinearLayout parent,
                                  List<SessionSummaryResponse.IssueDetail> details) {
        TextView tvHeader = makeText("주요 교정 사항", 13, color(R.color.summary_section_header));
        tvHeader.setPadding(0, 0, 0, dp(6));
        parent.addView(tvHeader);

        int visible = Math.min(ISSUES_PREVIEW, details.size());
        for (int i = 0; i < visible; i++) {
            parent.addView(buildIssueRow(details.get(i)));
        }

        if (details.size() > ISSUES_PREVIEW) {
            LinearLayout extraLayout = new LinearLayout(this);
            extraLayout.setOrientation(LinearLayout.VERTICAL);
            extraLayout.setVisibility(View.GONE);
            for (int i = ISSUES_PREVIEW; i < details.size(); i++) {
                extraLayout.addView(buildIssueRow(details.get(i)));
            }

            TextView tvMore = makeText("더 보기 ▼", 13, color(R.color.summary_more_link));
            tvMore.setPadding(0, dp(6), 0, 0);
            tvMore.setOnClickListener(v -> {
                if (extraLayout.getVisibility() == View.GONE) {
                    extraLayout.setVisibility(View.VISIBLE);
                    tvMore.setText("접기 ▲");
                } else {
                    extraLayout.setVisibility(View.GONE);
                    tvMore.setText("더 보기 ▼");
                }
            });

            parent.addView(extraLayout);
            parent.addView(tvMore);
        }
    }

    private LinearLayout buildIssueRow(SessionSummaryResponse.IssueDetail detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(rp);

        row.addView(makeText(
                "•  " + detail.message + "  (" + detail.count + "회)",
                14, color(R.color.summary_issue_text)));

        if (detail.tip != null && !detail.tip.isEmpty()) {
            TextView tvTip = makeText(detail.tip, 12, color(R.color.summary_tip_text));
            tvTip.setPadding(dp(12), dp(2), 0, 0);
            row.addView(tvTip);
        }

        return row;
    }

    private void addLegacyIssueCounts(LinearLayout parent, Map<String, Integer> issueCounts) {
        if (issueCounts == null || issueCounts.isEmpty()) {
            parent.addView(makeText("교정 사항 없음 — 훌륭합니다!", 14,
                    color(R.color.summary_positive_text)));
            return;
        }

        TextView tvHeader = makeText("주요 교정 사항", 13, color(R.color.summary_section_header));
        tvHeader.setPadding(0, 0, 0, dp(4));
        parent.addView(tvHeader);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(issueCounts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        int top = Math.min(3, sorted.size());
        for (int i = 0; i < top; i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            TextView tv = makeText(
                    "•  " + issueName(e.getKey()) + "  (" + e.getValue() + "회)",
                    14, color(R.color.summary_issue_text));
            tv.setPadding(0, dp(2), 0, 0);
            parent.addView(tv);
        }
    }

    // ───────────────────────── 헬퍼 ─────────────────────────────────────

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private TextView makeText(String text, float sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** ISO 8601 → "MM월 DD일 HH:mm" (연도 제외) */
    private String formatIso(String iso) {
        try {
            SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            SimpleDateFormat out = new SimpleDateFormat("MM월 dd일 HH:mm",        Locale.getDefault());
            Date date = in.parse(iso);
            return date != null ? out.format(date) : iso;
        } catch (ParseException e) {
            return iso;
        }
    }

    private String exerciseName(String key) {
        switch (key) {
            case "squat":           return "스쿼트";
            case "pushup":          return "푸쉬업";
            case "lunge":           return "런지";
            case "bench-press":     return "벤치프레스";
            case "plank":           return "플랭크";
            case "biceps-curl":     return "바이셉 컬";
            case "lateralraise":
            case "lateral_raise":   return "레터럴 레이즈";
            case "shoulderpress":
            case "shoulder_press":  return "숄더 프레스";
            case "pullup":          return "풀업";
            case "situp":           return "싯업";
            default:                return key;
        }
    }

    private String issueName(String key) {
        String k = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        switch (k) {
            case "left_knee_forward":  return "왼쪽 무릎이 너무 앞으로 나옴";
            case "right_knee_forward": return "오른쪽 무릎이 너무 앞으로 나옴";
            case "trunk_lean":         return "상체가 앞으로 과도하게 숙여짐";
            case "knee_asymmetry":     return "무릎 좌우 비대칭";
            case "hip_sag":            return "엉덩이가 처짐";
            case "hip_pike":           return "엉덩이가 너무 높이 들림";
            case "camera_angle":       return "카메라 각도 불량";
            case "elbow_flare":        return "팔꿈치가 과도하게 벌어짐";
            case "front_knee_forward": return "앞 무릎이 너무 앞으로 나옴";
            case "unknown_front_leg":  return "앞다리 인식 실패";
            default:                   return k;
        }
    }
}