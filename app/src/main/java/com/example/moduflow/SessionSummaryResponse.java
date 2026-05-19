package com.example.moduflow;

import java.util.List;
import java.util.Map;

// WebSocket 서버 → 클라이언트: type=="summary" 응답
public class SessionSummaryResponse {
    public String type;         // "summary"
    public SummaryData summary;

    public static class SummaryData {
        public String session_id;
        public String start_time;                          // ISO8601
        public Map<String, ExerciseStats> exercises;       // 운동 키 → 통계
    }

    public static class ExerciseStats {
        public int count;                                  // 총 rep 횟수
        public int clean_reps;                             // 이슈 없이 끝난 rep 수 (★ 추가)
        public String stage;
        public String last_posture;                        // "good" | "bad"
        public Map<String, Integer> issue_counts;          // 원자료 (화면 직접 표시 안 함)
        public List<IssueDetail> issues_detail;            // 빈도 내림차순 코칭 목록 (★ 추가)
        public String assessment;                          // 한 줄 평가 (★ 추가)
        public List<RepRecord> rep_records;
    }

    // issues_detail 항목 (★ 추가)
    public static class IssueDetail {
        public String key;      // e.g. "squat.trunk_lean"
        public int count;
        public String message;  // e.g. "상체를 펴세요"
        public String tip;      // 상세 코칭 팁
    }

    public static class RepRecord {
        public int rep;
        public List<String> issues;
    }
}