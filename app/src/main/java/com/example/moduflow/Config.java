package com.example.moduflow;

/**
 * 서버 주소 상수 모음.
 *
 * 실제 값은 app/build.gradle의 buildConfigField에서 주입된다.
 * 서버 주소를 변경할 때는 build.gradle만 수정하면 된다.
 *
 * - AI_SERVER_HOST / AI_SERVER_PORT: 팀원1 Cloud Run (FastAPI + MediaPipe)
 * - API_SERVER_URL: 팀원2 Spring Boot REST API
 * - PWA_URL: 팀원4 React PWA (Vercel 배포)
 */
public class Config {
    public static final String AI_SERVER_HOST = BuildConfig.AI_SERVER_HOST;
    public static final int    AI_SERVER_PORT  = BuildConfig.AI_SERVER_PORT;
    public static final String API_SERVER_URL  = BuildConfig.API_SERVER_URL;
    public static final String PWA_URL         = BuildConfig.PWA_URL;
}
