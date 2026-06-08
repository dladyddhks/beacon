package com.example.moduflow;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * JWT 액세스 토큰을 SharedPreferences에 저장하고 만료 여부를 관리한다.
 *
 * 서버 토큰 유효기간은 1시간이지만 5분 여유를 두고 55분으로 처리하여
 * 경계 시점의 401을 예방한다.
 */
public class TokenManager {

    private static final String PREF_NAME         = "auth_prefs";
    private static final String KEY_TOKEN          = "access_token";
    private static final String KEY_EXPIRY         = "token_expiry";
    private static final long   TOKEN_VALIDITY_MS  = 55 * 60 * 1000L; // 55분

    private final SharedPreferences prefs;
    private static TokenManager instance;

    public static synchronized TokenManager getInstance(Context context) {
        if (instance == null) instance = new TokenManager(context.getApplicationContext());
        return instance;
    }

    private TokenManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 토큰을 저장하고 만료 시각을 현재로부터 55분 후로 설정한다. */
    public void saveToken(String token) {
        prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_EXPIRY, System.currentTimeMillis() + TOKEN_VALIDITY_MS)
                .apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    /** 토큰이 존재하고 아직 만료되지 않았으면 true. */
    public boolean isTokenValid() {
        return getToken() != null
                && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRY, 0);
    }

    public void clearToken() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRY).apply();
    }
}