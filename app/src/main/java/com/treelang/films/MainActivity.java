package com.treelang.films;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_SAVE_FILE = 101;
    // URL 模板
    private static final String URL_TEMPLATE = "https://m.taopiaopiao.com/movies/coupons/applicative-cinemas.html?activityid=%s&fcode=%s&citycode=%s&cityname=%s";

    // === 核心配置 (与原代码保持一致) ===
    private static final int CONCURRENT_WEBVIEWS = 4;
    private static final long WORKER_TIMEOUT_MS = 12000;
    private static final int MAX_RETRY_COUNT = 3;

    private EditText fcodeEditText, activityIdEditText;
    private TextView logTextView, progressText, tvWorkerStatus;
    private ScrollView logScrollView;
    private Button startButton;
    private ProgressBar progressBar;
    private LinearLayout webViewContainer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Queue<City> cityQueue = new ConcurrentLinkedQueue<>();
    private List<WebViewWrapper> webViewPool = new ArrayList<>();
    private Set<String> processedCityCodes = new HashSet<>();
    private List<City> failedCities = new ArrayList<>();

    private File tempCsvFile;
    private BufferedWriter csvWriter;

    private boolean isScrapingCities = false;
    private boolean isScrapingData = false;
    private int totalCities = 0;

    private AtomicInteger processedCount = new AtomicInteger(0);
    private AtomicInteger successRecordCount = new AtomicInteger(0);
    private AtomicInteger activeWorkers = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        initWebViewPool();
    }

    private void initViews() {
        fcodeEditText = findViewById(R.id.fcodeEditText);
        activityIdEditText = findViewById(R.id.activityIdEditText);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);
        progressText = findViewById(R.id.progressText);
        tvWorkerStatus = findViewById(R.id.tvWorkerStatus);
        progressBar = findViewById(R.id.progressBar);
        startButton = findViewById(R.id.startButton);
        webViewContainer = findViewById(R.id.webViewContainer);

        startButton.setOnClickListener(v -> {
            if (isScrapingData || isScrapingCities) {
                stopScraping();
            } else {
                startStep1_GetCities();
            }
        });
    }

    private void initWebViewPool() {
        webViewPool.clear();
        webViewContainer.removeAllViews();

        for (int i = 0; i < CONCURRENT_WEBVIEWS; i++) {
            WebView wv = new WebView(this);
            wv.setVisibility(View.INVISIBLE); // 必须是INVISIBLE，GONE可能会停止渲染
            setupWebViewSettings(wv, i);
            webViewContainer.addView(wv, new LinearLayout.LayoutParams(1, 1));
            webViewPool.add(new WebViewWrapper(wv, i));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViewSettings(WebView webView, int index) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // === 回归原代码的 UserAgent 逻辑 ===
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));

        // === 回归原代码的激进配置 ===
        settings.setBlockNetworkImage(true);
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);

        webView.addJavascriptInterface(new JsBridge(index), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if ("about:blank".equals(url)) return;

                if (index == 0 && isScrapingCities) {
                    injectCityListScript(view);
                } else if (isScrapingData) {
                    injectCinemaDataScriptFast(view);
                }
            }

            // === 回归原代码的拦截逻辑 (这是关键，正是因为拦截了统计代码才绕过了部分风控逻辑) ===
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString().toLowerCase();
                // 1. 拦截静态资源
                if (url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")
                        || url.endsWith(".gif") || url.endsWith(".svg") || url.endsWith(".css")
                        || url.endsWith(".woff") || url.endsWith(".ttf") || url.endsWith(".ico")) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                }
                // 2. 拦截阿里统计、Google分析 (产生CORS错误但能加速页面)
                if (url.contains("log.mmstat.com") || url.contains("fourier") || url.contains("google-analytics")) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    // ================= 阶段一：获取城市列表 =================

    private void startStep1_GetCities() {
        String fcode = fcodeEditText.getText().toString().trim();
        String actId = activityIdEditText.getText().toString().trim();
        if (fcode.isEmpty() || actId.isEmpty()) return;

        isScrapingCities = true;
        isScrapingData = false;
        startButton.setText("停止任务");
        log(">>> 正在初始化城市列表...");

        android.webkit.CookieManager.getInstance().removeAllCookies(null);

        WebView mainWv = webViewPool.get(0).webView;
        try {
            String url = String.format(URL_TEMPLATE, actId, fcode, "440100", URLEncoder.encode("广州", "UTF-8"));
            mainWv.loadUrl(url);
        } catch (UnsupportedEncodingException e) { e.printStackTrace(); }
    }

    // ================= 阶段二：并发抓取 =================

    private void startStep2_ScrapeQueue(List<City> cities) {
        try {
            tempCsvFile = new File(getCacheDir(), "cinemas_temp.csv");
            FileOutputStream fos = new FileOutputStream(tempCsvFile);
            fos.write(0xef); fos.write(0xbb); fos.write(0xbf); // BOM
            csvWriter = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            csvWriter.write("城市,影院名称,详细地址\n");
        } catch (IOException e) { return; }

        cityQueue.clear();
        processedCityCodes.clear();
        failedCities.clear();
        for (City c : cities) {
            if (!processedCityCodes.contains(c.code)) {
                cityQueue.add(c);
                processedCityCodes.add(c.code);
            }
        }

        isScrapingCities = false;
        isScrapingData = true;
        totalCities = cityQueue.size();
        processedCount.set(0);
        successRecordCount.set(0);
        activeWorkers.set(0);

        progressBar.setMax(totalCities);
        updateProgressUI();
        log(">>> 开启 " + CONCURRENT_WEBVIEWS + " 线程抓取 " + totalCities + " 个城市...");

        for (WebViewWrapper wrapper : webViewPool) {
            if (!wrapper.isBusy) {
                assignTaskToWrapper(wrapper);
            }
        }
    }

    private void assignTaskToWrapper(final WebViewWrapper wrapper) {
        if (!isScrapingData) return;

        final City city = cityQueue.poll();
        if (city == null) {
            if (activeWorkers.get() == 0) finishScraping();
            return;
        }

        wrapper.isBusy = true;
        wrapper.currentCity = city;
        activeWorkers.incrementAndGet();
        updateWorkerUI();

        // 1. 看门狗
        wrapper.timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.e("Watchdog", "Worker " + wrapper.index + " 超时: " + city.name);
                wrapper.webView.stopLoading();

                if (city.retryCount < MAX_RETRY_COUNT) {
                    city.retryCount++;
                    log("!! " + city.name + " 重试 (" + city.retryCount + ")");
                    cityQueue.add(city);
                } else {
                    log("XX " + city.name + " 失败");
                    failedCities.add(city);
                }

                releaseWorker(wrapper);
                assignTaskToWrapper(wrapper);
            }
        };
        mainHandler.postDelayed(wrapper.timeoutRunnable, WORKER_TIMEOUT_MS);

        // 2. 加载
        String fcode = fcodeEditText.getText().toString().trim();
        String actId = activityIdEditText.getText().toString().trim();
        try {
            String encodedName = URLEncoder.encode(city.name, "UTF-8");
            final String targetUrl = String.format(URL_TEMPLATE, actId, fcode, city.code, encodedName);

            // 内存洗涤
            wrapper.webView.loadUrl("about:blank");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (wrapper.isBusy && wrapper.currentCity == city) {
                        wrapper.webView.loadUrl(targetUrl);
                    }
                }
            }, 50);

        } catch (UnsupportedEncodingException e) {
            releaseWorker(wrapper);
            assignTaskToWrapper(wrapper);
        }
    }

    private void releaseWorker(WebViewWrapper wrapper) {
        if (wrapper.timeoutRunnable != null) {
            mainHandler.removeCallbacks(wrapper.timeoutRunnable);
            wrapper.timeoutRunnable = null;
        }
        wrapper.isBusy = false;
        wrapper.currentCity = null;
        activeWorkers.decrementAndGet();
        updateWorkerUI();
    }

    // ================= JS 脚本 (保持原版高容错逻辑) =================

    private void injectCityListScript(WebView view) {
        String js = "javascript:(function(){" +
                "  function simulateRealTap(element) {" +
                "      if(!element) return;" +
                "      var rect = element.getBoundingClientRect();" +
                "      var clientX = rect.left + rect.width / 2;" +
                "      var clientY = rect.top + rect.height / 2;" +
                "      var touch = new Touch({ identifier: Date.now(), target: element, clientX: clientX, clientY: clientY, radiusX: 2.5, radiusY: 2.5, rotationAngle: 10, force: 0.5 });" +
                "      var touchstartEvent = new TouchEvent('touchstart', { bubbles: true, cancelable: true, view: window, touches: [touch], targetTouches: [touch], changedTouches: [touch] });" +
                "      element.dispatchEvent(touchstartEvent);" +
                "      var touchendEvent = new TouchEvent('touchend', { bubbles: true, cancelable: true, view: window, touches: [], targetTouches: [], changedTouches: [touch] });" +
                "      element.dispatchEvent(touchendEvent);" +
                "      element.click();" +
                "  }" +
                "  var attempts = 0;" +
                "  var interval = setInterval(function(){" +
                "      var items = document.querySelectorAll('.city-g:not(.hot):not(.gps):not(.current) .city-item');" +
                "      if (items.length > 20) {" +
                "          clearInterval(interval);" +
                "          var cities = [];" +
                "          var seen = new Set();" +
                "          for (var i = 0; i < items.length; i++) {" +
                "              var name = items[i].getAttribute('data-name') || items[i].innerText;" +
                "              var code = items[i].getAttribute('data-code');" +
                "              if(name) name = name.replace(/\\s/g, '');" +
                "              if (name && code && code !== '-1' && !seen.has(code)) {" +
                "                  seen.add(code);" +
                "                  cities.push({ name: name, code: code });" +
                "              }" +
                "          }" +
                "          Android.onCitiesParsed(JSON.stringify(cities));" +
                "      } else if (attempts > 30) {" +
                "          clearInterval(interval);" +
                "          Android.onCityParseError('超时：请手动点击城市！');" +
                "      } else {" +
                "          if (attempts % 2 === 0) {" +
                "              var btn = document.querySelector('#J_citySelector');" +
                "              if(btn) simulateRealTap(btn);" +
                "              else { var p = document.querySelector('.city-selector'); if(p) simulateRealTap(p); }" +
                "          }" +
                "      }" +
                "      attempts++;" +
                "  }, 800);" +
                "})()";
        view.loadUrl(js);
    }

    private void injectCinemaDataScriptFast(WebView view) {
        String js = "javascript:(function(){" +
                "  var attempts = 0;" +
                "  var interval = setInterval(function(){" +
                "      var items = document.querySelectorAll('.list-item');" +
                "      if(items.length === 0) items = document.querySelectorAll('.cinema-item, li');" +
                "      " +
                "      if(items.length > 0) {" +
                "          clearInterval(interval);" +
                "          var results = [];" +
                "          for(var i=0; i<items.length; i++){" +
                "              var nameEl = items[i].querySelector('.list-title') || items[i].querySelector('.cinema-name');" +
                "              var addrEl = items[i].querySelector('.list-location') || items[i].querySelector('.cinema-address');" +
                "              if(nameEl) {" +
                "                  results.push({ name: nameEl.innerText, addr: addrEl ? addrEl.innerText : '' });" +
                "              }" +
                "          }" +
                "          Android.onDataParsed(JSON.stringify(results));" +
                "      } else if(attempts > 30) {" +
                "          clearInterval(interval);" +
                "          Android.onDataParsed('[]');" +
                "      }" +
                "      attempts++;" +
                "  }, 100);" +
                "})()";
        view.loadUrl(js);
    }

    // ================= JS 桥接 =================

    private class JsBridge {
        private int webViewIndex;
        JsBridge(int index) { this.webViewIndex = index; }

        @JavascriptInterface
        public void onCitiesParsed(final String json) {
            if(webViewIndex != 0) return;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONArray ja = new JSONArray(json);
                        List<City> allCities = new ArrayList<>();
                        for (int i = 0; i < ja.length(); i++) {
                            JSONObject jo = ja.getJSONObject(i);
                            allCities.add(new City(jo.getString("name"), jo.getString("code")));
                        }
                        log("列表获取成功！" + allCities.size() + " 个城市。");
                        startButton.setEnabled(true);
                        startStep2_ScrapeQueue(allCities);
                    } catch (Exception e) {
                        log("解析错误: " + e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void onCityParseError(final String msg) {
            if(webViewIndex != 0) return;
            mainHandler.post(new Runnable() {
                @Override
                public void run() { if (isScrapingCities) log(msg); }
            });
        }

        @JavascriptInterface
        public void onDataParsed(final String json) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isScrapingData) return;

                    WebViewWrapper wrapper = webViewPool.get(webViewIndex);
                    City city = wrapper.currentCity;
                    if (city == null) return;

                    try {
                        JSONArray ja = new JSONArray(json);
                        List<String> records = new ArrayList<>();
                        if (ja.length() > 0) {
                            for (int i = 0; i < ja.length(); i++) {
                                JSONObject jo = ja.getJSONObject(i);
                                String name = jo.getString("name").replace(",", "，").replace("\n", "");
                                String addr = jo.getString("addr").replace(",", "，").replace("\n", "");
                                records.add("," + name + "," + addr);
                            }
                            saveRecords(city.name, records);
                        }
                    } catch (Exception e) { }

                    processedCount.incrementAndGet();
                    updateProgressUI();

                    releaseWorker(wrapper);
                    assignTaskToWrapper(wrapper);
                }
            });
        }
    }

    private synchronized void saveRecords(String cityName, List<String> records) {
        if (csvWriter == null) return;
        try {
            successRecordCount.addAndGet(records.size());
            for (String line : records) {
                csvWriter.write(cityName + line + "\n");
            }
            csvWriter.flush();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void finishScraping() {
        if (!isScrapingData) return;
        isScrapingData = false;
        try {
            if (csvWriter != null) csvWriter.close();
        } catch (IOException e) { e.printStackTrace(); }

        log(">>> 全部完成！共 " + successRecordCount.get() + " 条数据。");
        if (!failedCities.isEmpty()) {
            log("失败: " + failedCities.size());
        }
        startButton.setText("开始抓取");
        startButton.setBackgroundColor(0xFF2196F3); // Blue
        startButton.setEnabled(true);
        saveFileToUserDevice();
    }

    private void stopScraping() {
        isScrapingData = false;
        isScrapingCities = false;
        cityQueue.clear();
        for(WebViewWrapper w : webViewPool) {
            if(w.timeoutRunnable != null) mainHandler.removeCallbacks(w.timeoutRunnable);
            w.webView.stopLoading();
            w.isBusy = false;
        }
        finishScraping();
    }

    private void log(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                logTextView.append(msg + "\n");
                logScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        logScrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void updateProgressUI() {
        int current = processedCount.get();
        progressBar.setProgress(current);
        progressText.setText(String.format("%d/%d (已存 %d 条)", current, totalCities, successRecordCount.get()));
    }

    private void updateWorkerUI() {
        tvWorkerStatus.setText("线程: " + activeWorkers.get());
    }

    private void saveFileToUserDevice() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "Cinemas_" + System.currentTimeMillis() + ".csv");
        startActivityForResult(intent, REQUEST_CODE_SAVE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SAVE_FILE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                copyFile(data.getData());
            }
        }
    }

    private void copyFile(final Uri targetUri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = new FileInputStream(tempCsvFile);
                    OutputStream out = getContentResolver().openOutputStream(targetUri);
                    if (out == null) return;
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    in.close(); out.close();
                    log("文件导出成功！");
                } catch (Exception e) {
                    log("导出失败：" + e.getMessage());
                }
            }
        }).start();
    }

    static class City {
        String name, code;
        int retryCount = 0;
        City(String name, String code) { this.name = name; this.code = code; }
    }

    static class WebViewWrapper {
        WebView webView;
        int index;
        boolean isBusy = false;
        City currentCity = null;
        Runnable timeoutRunnable = null;

        WebViewWrapper(WebView w, int i) {
            this.webView = w;
            this.index = i;
        }
    }
}