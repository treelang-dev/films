package com.treelang.films;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class LoginActivity extends Activity {

    public static final String EXTRA_ACTIVITY_ID = "activityid";
    public static final String EXTRA_FCODE = "fcode";

    private WebView loginWebView;
    private boolean hasExtracted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("Get Params");
        }

        loginWebView = findViewById(R.id.loginWebView);
        setupWebView();
        loginWebView.loadUrl("https://m.taopiaopiao.com");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = loginWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36");

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 让所有链接都在 WebView 内打开
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                tryExtractParams(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                tryExtractParams(url);
            }
        });
    }

    /**
     * 从 URL query 参数中提取 activityid 和 fcode。
     * 当用户在券详情页点击「适用影院」后，会跳转到
     * applicative-cinemas.html?activityid=xxx&fcode=xxx...
     * 此时即可自动提取并关闭。
     */
    private void tryExtractParams(String url) {
        if (hasExtracted || url == null) return;
        try {
            Uri uri = Uri.parse(url);
            String activityId = uri.getQueryParameter("activityid");
            String fcode = uri.getQueryParameter("fcode");
            if (activityId != null && !activityId.isEmpty()
                    && fcode != null && !fcode.isEmpty()) {
                hasExtracted = true;
                Intent result = new Intent();
                result.putExtra(EXTRA_ACTIVITY_ID, activityId);
                result.putExtra(EXTRA_FCODE, fcode);
                setResult(RESULT_OK, result);
                finish();
            }
        } catch (Exception ignored) {
        }
    }



    @Override
    protected void onDestroy() {
        if (loginWebView != null) {
            loginWebView.loadUrl("about:blank");
            loginWebView.destroy();
        }
        super.onDestroy();
    }
}
