package com.example.moduflow;

/**
 * GET /api/v1/routines 응답 Map의 각 항목.
 *
 * sets/reps/weight 가 null 로 올 수 있으므로 Integer/Double 사용.
 */
public class RoutineResponse {
    public String  id;         // UUID (내부 식별자)
    public String  name;       // 화면 표시용 이름 (예: "벤치프레스")
    public Integer sets;
    public Integer reps;
    public Double  weight;
    public String  exerciseId; // AI 분석 키 (예: "bench-press")
}