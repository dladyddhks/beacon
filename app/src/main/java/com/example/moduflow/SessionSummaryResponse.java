package com.example.moduflow;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 수신 메시지 데이터 모델.
 *
 * type별 summary 구조:
 *   set_feedback      → SetStats
 *   exercise_feedback → ExerciseStats
 *   session_feedback  → SummaryData
 */
public class SessionSummaryResponse {
    public String      type;    // "session_feedback"
    public SummaryData summary;

    /** session_feedback.summary — 전체 세션 */
    public static class SummaryData {
        public String sessionId;
        public String startTime;                         // ISO8601
        public String assessment;                        // 세션 한 줄 평가 (템플릿 문구)
        public String aiSummary;                         // Gemini AI 총평 (옵셔널, null 가능)
        public Map<String, ExerciseStats> exercises;     // 운동 키 → 종목 통계
    }

    /** exercise_feedback.summary — 운동 종목 전체 */
    public static class ExerciseStats {
        public String  exercise;
        public int     totalSets;
        public int     totalReps;
        public int     cleanReps;
        public String  assessment;
        public Map<String, Integer>  issueCounts;
        public List<IssueDetail>     issuesDetail;
        public List<SetStats>        sets;
    }

    /** set_feedback.summary — 개별 세트 */
    public static class SetStats {
        public int    setNumber;
        public int    count;
        public int    cleanReps;
        public String assessment;
        public String stage;
        public String lastPosture;
        public Map<String, Integer> issueCounts;
        public List<IssueDetail>    issuesDetail;
        public List<RepRecord>      repRecords;
    }

    public static class IssueDetail {
        public String key;
        public int    count;
        public String message;
        public String tip;
    }

    public static class RepRecord {
        public int         rep;
        public List<String> issues;
    }
}