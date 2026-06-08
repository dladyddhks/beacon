package com.example.moduflow;

import com.google.gson.JsonElement;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Spring 서버의 루틴 조회 API.
 * 응답에 "restDays": ["sun"] 같은 문자열 배열이 섞여 있으므로
 * Map<String, JsonElement> 로 받아 Activity에서 직접 파싱한다.
 */
public interface RoutineApiService {

    @GET("/api/v1/routines")
    Call<Map<String, JsonElement>> getRoutines(
            @Query("userId") String userId,
            @Query("dayOfWeek") String dayOfWeek
    );
}