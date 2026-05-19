package com.example.moduflow;

public class RoutineResponse {
    // public int    id;
    @com.google.gson.annotations.SerializedName("exerciseId")
    public String exerciseId;    // AI 서버 키: "squat" | "pushup" | "lunge"
    // public String name;          // 한국어 이름 (선택 — 없으면 labelMap 사용)
}