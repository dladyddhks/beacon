package com.example.moduflow;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Retrofit 싱글턴 클라이언트.
 *
 * 인증 흐름:
 *   1. PWA에서 사용자가 이메일/비밀번호로 로그인한다.
 *   2. PWA가 Android.setAuthToken(token)으로 accessToken을 네이티브에 전달한다.
 *   3. 토큰 인터셉터가 저장된 토큰을 모든 요청에 Authorization 헤더로 자동 첨부한다.
 *   4. 토큰이 만료(401)되면 Authenticator가 이를 감지하고 로그를 남긴다.
 *      → 재로그인은 PWA를 통해 사용자가 직접 수행한다 (email/password 방식).
 *
 * 로그: BODY 레벨로 설정되어 있으므로 logcat 'OkHttp' 태그로 JSON 전체를 확인할 수 있다.
 */
public class ApiClient {

    private static ApiClient instance;

    private final LocationApiService locationApiService;
    private final RoutineApiService  routineApiService;

    private ApiClient(Context context) {
        String baseUrl = BuildConfig.API_SERVER_URL;
        TokenManager tokenManager = TokenManager.getInstance(context);

        // ── 메인 클라이언트 (로그 + 토큰 첨부) ─────────────────────────
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
                msg -> Log.d("OkHttp", msg));
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient mainHttp = new OkHttpClient.Builder()
                .addInterceptor(logging)
                // PWA로부터 받아 저장된 토큰을 모든 요청 헤더에 자동 첨부
                .addInterceptor(chain -> {
                    String token = tokenManager.getToken();
                    if (token == null) return chain.proceed(chain.request());
                    return chain.proceed(chain.request().newBuilder()
                            .header("Authorization", "Bearer " + token)
                            .build());
                })
                // 401 수신 시: 토큰 만료 감지 후 로그만 남긴다.
                // 재로그인은 사용자가 PWA에서 직접 수행한다 (email/password 방식).
                .authenticator((route, response) -> {
                    if (countRetries(response) >= 1) return null; // 무한 루프 방지
                    Log.w("ApiClient", "401 수신 — 토큰 만료. PWA에서 재로그인 필요: "
                            + response.request().url());
                    tokenManager.clearToken();
                    return null; // 재시도하지 않음
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(mainHttp)
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

    /** 이전 401 재시도 횟수를 센다. priorResponse 체인을 역추적한다. */
    private static int countRetries(Response response) {
        int count = 0;
        while ((response = response.priorResponse()) != null) count++;
        return count;
    }

    public LocationApiService getLocationService() { return locationApiService; }
    public RoutineApiService  getRoutineService()   { return routineApiService; }
}