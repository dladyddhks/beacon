package com.example.moduflow;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    private static ApiClient instance;

    private final LocationApiService locationApiService;
    private final RoutineApiService  routineApiService;

    private ApiClient(Context context) {
        String baseUrl = BuildConfig.API_SERVER_URL;

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        locationApiService = retrofit.create(LocationApiService.class);
        routineApiService  = retrofit.create(RoutineApiService.class);
    }

    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new ApiClient(context.getApplicationContext());
        }
        return instance;
    }

    public LocationApiService getLocationService() { return locationApiService; }
    public RoutineApiService  getRoutineService()   { return routineApiService; }
}