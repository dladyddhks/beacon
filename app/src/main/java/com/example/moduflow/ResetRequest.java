package com.example.moduflow;

// WebSocket 클라이언트 → 서버: 카운트/이슈 초기화
// exercise == null 이면 세션 전체 초기화 (Gson이 null 필드는 직렬화하지 않음)
public class ResetRequest {
    public final String type = "reset";
    public String exercise;

    public ResetRequest(String exercise) {
        this.exercise = exercise;
    }
}
