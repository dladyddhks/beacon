package com.example.moduflow;

/**
 * POST /api/v1/update-location 요청 바디.
 *
 * userId는 기기의 Android ID (Settings.Secure.ANDROID_ID)를 사용한다.
 * zoneId는 비콘 minor 값 (53626 / 53630 / 56376) 또는
 * 비콘 범위를 벗어났을 때 0을 전송한다.
 */
public class LocationData {
    private String userId;  // 기기 고유 식별자 (ANDROID_ID)
    private int    zoneId;  // 현재 감지된 비콘 구역 ID, 0이면 구역 이탈
    private String gymName; // 헬스장 이름 (예: "ModuFlow")

    public LocationData(String userId, int zoneId, String gymName) {
        this.userId  = userId;
        this.zoneId  = zoneId;
        this.gymName = gymName;
    }
}