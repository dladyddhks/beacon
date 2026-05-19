package com.example.moduflow;

import java.util.Map;

// AI 서버 분석 결과 데이터 모델
public class PoseResponse {
    public String type;             // "result" 또는 "error"
    public String posture;          // "good" | "bad"
    public String feedback;         // 한국어 피드백 문자열
    public Map<String, Double> angles; // 운동마다 다른 각도 정보 (가변적)
    public String exercise;         // 분석된 운동 종류
    public String message;          // 에러 시 메시지
}
