package com.example.moduflow;

// AI 서버에 보낼 프레임 데이터 모델
public class PoseRequest {
    String type = "frame";
    String image;      // Base64 JPEG
    String exercise;   // "squat", "pushup", "lunge"

    public PoseRequest(String image, String exercise) {
        this.image = image;
        this.exercise = exercise;
    }
}
