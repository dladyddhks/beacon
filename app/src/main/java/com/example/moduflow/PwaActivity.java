package com.example.moduflow;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

public class PwaActivity extends AppCompatActivity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // 시스템 야간모드를 WebView에 반영
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("moduflow".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException e) {
                        Log.e("PwaActivity", "딥링크 처리 실패: " + e.getMessage());
                    }
                    return true;
                }
                return false; // WebView 기본 동작 — SPA 라우팅 정상 처리
            }
        });

        // PWA의 window.confirm() 다이얼로그 처리 (없으면 삭제 확인이 자동 취소됨)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(PwaActivity.this)
                        .setMessage(message)
                        .setPositiveButton("확인", (d, w) -> result.confirm())
                        .setNegativeButton("취소", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        // PWA → Android 브리지: 운동루틴 전달
        // PWA에서 호출 예시: Android.startWorkout("squat,lunge")
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void startWorkout(String exercisesCsv) {
                runOnUiThread(() -> {
                    Intent intent = new Intent(PwaActivity.this, PoseAnalysisActivity.class);
                    intent.putExtra("exercises", exercisesCsv);
                    startActivity(intent);
                });
            }
        }, "Android");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        webView.loadUrl(Config.PWA_URL);
    }
}