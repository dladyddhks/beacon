package com.example.moduflow;

// WebSocket 클라이언트 → 서버: 프레임 전송
public class FrameRequest {
    public final String type = "frame";
    public String image;    // Base64 JPEG
    public String exercise; // "squat" | "pushup" | "lunge"

    public FrameRequest(String image, String exercise) {
        this.image    = image;
        this.exercise = exercise;
    }
}
