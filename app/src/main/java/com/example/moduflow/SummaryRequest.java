package com.example.moduflow;

// WebSocket 클라이언트 → 서버: 세션 종료 및 전체 요약 요청
public class SummaryRequest {
    public final String type = "session_end";
}
