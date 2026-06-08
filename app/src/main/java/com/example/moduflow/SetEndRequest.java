package com.example.moduflow;

public class SetEndRequest {
    public final String  type      = "set_end";
    public final String  exercise;
    public final boolean isLastSet;
    public final int     count;     // 최종 횟수. 수동 종료 시 목표 횟수로 채워 전송

    public SetEndRequest(String exercise, boolean isLastSet, int count) {
        this.exercise  = exercise;
        this.isLastSet = isLastSet;
        this.count     = count;
    }
}