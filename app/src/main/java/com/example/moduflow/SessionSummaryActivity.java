package com.example.moduflow;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SessionSummaryActivity extends AppCompatActivity {

    public static final String EXTRA_SUMMARY_JSON = "summary_json";

    private static final int ISSUES_PREVIEW = 3;  // 접힌 상태에서 보여줄 항목 수

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_summary);

        String json = getIntent().getStringExtra(EXTRA_SUMMARY_JSON);
        SessionSummaryResponse.SummaryData summary =
                new Gson().fromJson(json, SessionSummaryResponse.SummaryData.class);

        bindSummary(summary);

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    // ───────────────────────── 요약 화면 구성 ───────────────────────────

    private void bindSummary(SessionSummaryResponse.SummaryData summary) {
        TextView tvTime = findViewById(R.id.tvSessionTime);
        if (summary != null && summary.start_time != null) {
            tvTime.setText("시작: " + formatIso(summary.start_time));
        }

        LinearLayout container = findViewById(R.id.layoutExercises);

        if (summary == null || summary.exercises == null || summary.exercises.isEmpty()) {
            TextView empty = makeText("기록된 운동이 없습니다.", 15, Color.parseColor("#FF888888"));
            empty.setPadding(0, dp(24), 0, 0);
            container.addView(empty);
            return;
        }

        // count 내림차순 정렬
        List<Map.Entry<String, SessionSummaryResponse.ExerciseStats>> entries =
                new ArrayList<>(summary.exercises.entrySet());
        entries.sort((a, b) -> b.getValue().count - a.getValue().count);

        for (Map.Entry<String, SessionSummaryResponse.ExerciseStats> entry : entries) {
            if (entry.getValue().count == 0) continue;
            container.addView(buildExerciseCard(entry.getKey(), entry.getValue()));
        }
    }

    private LinearLayout buildExerciseCard(String exercise,
                                            SessionSummaryResponse.ExerciseStats stats) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#FF1E1E1E"));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(4));
        card.setLayoutParams(lp);

        // ── 운동 이름 헤더 ──
        TextView tvTitle = makeText(exerciseName(exercise), 18, Color.WHITE);
        tvTitle.setTypeface(null, Typeface.BOLD);
        card.addView(tvTitle);

        // ── count / clean_reps 지표 ──
        String metric = stats.count + "회 완료";
        if (stats.clean_reps > 0) {
            metric += "  ·  " + stats.clean_reps + "회 깔끔";
        }
        TextView tvMetric = makeText(metric, 13, Color.parseColor("#FF888888"));
        tvMetric.setPadding(0, dp(2), 0, dp(10));
        card.addView(tvMetric);

        // ── assessment 한 줄 평가 ──
        if (stats.assessment != null && !stats.assessment.isEmpty()) {
            TextView tvAssessment = makeText(stats.assessment, 15, Color.parseColor("#FFDDDDDD"));
            tvAssessment.setPadding(0, 0, 0, dp(10));
            card.addView(tvAssessment);
        }

        // ── issues_detail 목록 (신규 서버 포맷) ──
        if (stats.issues_detail != null) {
            if (stats.issues_detail.isEmpty()) {
                TextView tvOk = makeText("자세가 안정적이었어요!", 14, Color.parseColor("#FF44CC44"));
                card.addView(tvOk);
            } else {
                addIssuesDetail(card, stats.issues_detail);
            }
        } else {
            // ── 하위 호환: issues_detail 없으면 issue_counts 사용 ──
            addLegacyIssueCounts(card, stats.issue_counts);
        }

        return card;
    }

    /** issues_detail 기반 코칭 목록 (message + tip, 상위 3개 + 더 보기) */
    private void addIssuesDetail(LinearLayout parent,
                                  List<SessionSummaryResponse.IssueDetail> details) {
        TextView tvHeader = makeText("주요 교정 사항", 13, Color.parseColor("#FF888888"));
        tvHeader.setPadding(0, 0, 0, dp(6));
        parent.addView(tvHeader);

        int visible = Math.min(ISSUES_PREVIEW, details.size());

        // 항상 보이는 상위 ISSUES_PREVIEW개
        for (int i = 0; i < visible; i++) {
            parent.addView(buildIssueRow(details.get(i)));
        }

        // 나머지 항목 — 접힌 상태로 추가
        if (details.size() > ISSUES_PREVIEW) {
            LinearLayout extraLayout = new LinearLayout(this);
            extraLayout.setOrientation(LinearLayout.VERTICAL);
            extraLayout.setVisibility(View.GONE);

            for (int i = ISSUES_PREVIEW; i < details.size(); i++) {
                extraLayout.addView(buildIssueRow(details.get(i)));
            }

            TextView tvMore = makeText("더 보기 ▼", 13, Color.parseColor("#FF888888"));
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

    /** 이슈 한 항목 (message 줄 + tip 줄) */
    private LinearLayout buildIssueRow(SessionSummaryResponse.IssueDetail detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(rp);

        // "• {message} ({count}회)"
        TextView tvMsg = makeText(
                "•  " + detail.message + "  (" + detail.count + "회)",
                14, Color.parseColor("#FFFF9999"));
        row.addView(tvMsg);

        // tip (작은 회색)
        if (detail.tip != null && !detail.tip.isEmpty()) {
            TextView tvTip = makeText(detail.tip, 12, Color.parseColor("#FF888888"));
            tvTip.setPadding(dp(12), dp(2), 0, 0);
            row.addView(tvTip);
        }

        return row;
    }

    /** 하위 호환: issue_counts 맵으로 상위 3개 표시 */
    private void addLegacyIssueCounts(LinearLayout parent,
                                       Map<String, Integer> issueCounts) {
        if (issueCounts == null || issueCounts.isEmpty()) {
            TextView tvOk = makeText("교정 사항 없음 — 훌륭합니다!", 14,
                    Color.parseColor("#FF44CC44"));
            parent.addView(tvOk);
            return;
        }

        TextView tvHeader = makeText("주요 교정 사항", 13, Color.parseColor("#FF888888"));
        tvHeader.setPadding(0, 0, 0, dp(4));
        parent.addView(tvHeader);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(issueCounts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        int top = Math.min(3, sorted.size());
        for (int i = 0; i < top; i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            TextView tv = makeText(
                    "•  " + issueName(e.getKey()) + "  (" + e.getValue() + "회)",
                    14, Color.parseColor("#FFFF9999"));
            tv.setPadding(0, dp(2), 0, 0);
            parent.addView(tv);
        }
    }

    // ───────────────────────── 헬퍼 ─────────────────────────────────────

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

    /** ISO 8601 → "YYYY-MM-DD HH:mm" */
    private String formatIso(String iso) {
        try {
            SimpleDateFormat in  = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm",       Locale.getDefault());
            Date date = in.parse(iso);
            return date != null ? out.format(date) : iso;
        } catch (ParseException e) {
            return iso;
        }
    }

    private String exerciseName(String key) {
        switch (key) {
            case "squat":  return "스쿼트";
            case "pushup": return "푸쉬업";
            case "lunge":  return "런지";
            default:       return key;
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