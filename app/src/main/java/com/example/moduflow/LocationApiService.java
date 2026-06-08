package com.example.moduflow;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/**
 * Spring 서버의 위치 업데이트 API.
 * ApiClient를 통해 인스턴스를 얻는다.
 */
public interface LocationApiService {

    /** 비콘 감지 결과(구역 ID)를 서버에 전송한다. 응답 바디는 없다. */
    @POST("/api/v1/update-location")
    Call<Void> updateLocation(@Body LocationData data);
}