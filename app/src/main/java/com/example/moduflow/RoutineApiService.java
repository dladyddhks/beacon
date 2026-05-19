package com.example.moduflow;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface RoutineApiService {

    @GET("/api/v1/routines")
    Call<List<RoutineResponse>> getRoutines(@Query("userId") String userId);
}