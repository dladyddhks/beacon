package com.example.moduflow;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 서버 → 클라이언트: type=="result" 프레임.
 *
 * Gson이 JSON 키와 필드 이름을 자동 매핑한다.
 * 서버 JSON 키가 camelCase이므로 필드 이름도 camelCase를 사용한다.
 */
public class PoseResult {
    public String type;                  // "result"
    public String posture;               // "good" | "bad"
    public String feedback;              // 한국어 피드백 텍스트
    public Map<String, Double> angles;   // 운동마다 키가 다름 (Map으로 처리)
    public String exercise;              // 요청 에코 — 어떤 운동으로 분석했는지
    public List<String> issues;          // 영문 이슈 키 배열 (prefix 없음)
    public int count;                    // 서버가 집계한 현재 rep 횟수
    public String stage;                 // "UP" | "DOWN" | "MID"
    public boolean repCompleted;         // true이면 이 프레임에서 1회 완료 (진동 트리거)
}
