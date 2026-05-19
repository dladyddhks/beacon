package com.example.moduflow;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

// 비콘 구역 정보 전송 — 인증 필요 (AuthInterceptor가 자동으로 Bearer 헤더 주입)
public interface LocationApiService {

    @POST("/api/update-location")
    Call<Void> updateLocation(@Body LocationData data);
}