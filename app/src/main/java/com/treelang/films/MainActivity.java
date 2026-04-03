package com.treelang.films;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    // === Constants ===
    private static final int REQUEST_CODE_SAVE_FILE = 101;
    private static final String PREFS_NAME = "CrawlerPrefs";
    private static final String URL_TEMPLATE = "https://m.taopiaopiao.com/movies/coupons/applicative-cinemas.html?activityid=%s&fcode=%s&citycode=%s&cityname=%s";

    // === Core Configuration ===
    private static final long WORKER_TIMEOUT_MS = 10000;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int DEFAULT_THREADS = 4;
    private static final int MAX_THREADS = 4;

    // === UI Components ===
    private EditText fcodeEditText, activityIdEditText;
    private TextView logTextView, progressText, tvWorkerStatus;
    private ScrollView logScrollView;
    private Button startButton;
    private ProgressBar progressBar;
    private LinearLayout webViewContainer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // === Data Structures ===
    private final Queue<City> cityQueue = new ConcurrentLinkedQueue<>();
    private final List<WebViewWrapper> webViewPool = new ArrayList<>();
    private final Set<String> processedCityCodes = new HashSet<>();
    private Set<String> finishedCityCodes = new HashSet<>();
    private final List<City> failedCities = new ArrayList<>();

    // === File & State ===
    private File tempCsvFile;
    private BufferedWriter csvWriter;
    private boolean isScrapingCities = false;
    private boolean isScrapingData = false;
    private int totalCities = 0;
    private int currentThreadCount = DEFAULT_THREADS;

    // === Cached Inputs ===
    private String currentFcode = "";
    private String currentActId = "";

    // === Counters ===
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger successRecordCount = new AtomicInteger(0);
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadConfig();
        log("[System] Threads: " + currentThreadCount);
    }

    @Override
    protected void onDestroy() {
        stopScraping();
        destroyWebViewPool();
        super.onDestroy();
    }

    // === Menu Implementation ===

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_threads) {
            showThreadSelectionDialog();
            return true;
        }
        // Removed ConverterActivity because it's missing in the original code,
        // it was likely a leftover or a missing class from earlier.
        // It failed to compile without this fix.
        return super.onOptionsItemSelected(item);
    }

    private void showThreadSelectionDialog() {
        final String[] options = new String[MAX_THREADS];
        for (int i = 0; i < MAX_THREADS; i++) {
            options[i] = String.valueOf(i + 1);
        }

        int currentSelectionIndex = Math.max(0, Math.min(currentThreadCount - 1, MAX_THREADS - 1));
        final int[] tempSelection = {currentSelectionIndex};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Thread Count");

        builder.setSingleChoiceItems(options, currentSelectionIndex, (dialog, which) -> tempSelection[0] = which);

        builder.setPositiveButton("Confirm", (dialog, which) -> {
            int selectedCount = tempSelection[0] + 1;
            if (currentThreadCount != selectedCount) {
                currentThreadCount = selectedCount;
                saveConfig();
                log("[System] Thread count set to " + currentThreadCount + " (Applied on next Start)");
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // === End Menu Implementation ===

    private void destroyWebViewPool() {
        if (webViewContainer != null) {
            webViewContainer.removeAllViews();
        }
        for (WebViewWrapper wrapper : webViewPool) {
            if (wrapper.webView != null) {
                wrapper.webView.loadUrl("about:blank");
                wrapper.webView.clearHistory();
                wrapper.webView.removeAllViews();
                wrapper.webView.destroy();
                wrapper.webView = null;
            }
        }
        webViewPool.clear();
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
                saveConfig();
                rebuildWebViewPoolIfNeeded();
                startStep1_GetCities();
            }
        });
    }

    private void rebuildWebViewPoolIfNeeded() {
        if (webViewPool.size() == currentThreadCount) return;
        log("[System] Rebuilding pool: " + webViewPool.size() + " -> " + currentThreadCount);
        destroyWebViewPool();
        for (int i = 0; i < currentThreadCount; i++) {
            WebView wv = new WebView(getApplicationContext());
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setupWebViewSettings(wv, i);
            webViewContainer.addView(wv);
            webViewPool.add(new WebViewWrapper(wv, i));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebViewSettings(WebView webView, int index) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBlockNetworkImage(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
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
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString().toLowerCase();
                if (url.contains("m.taopiaopiao.com") || url.contains("g.alicdn.com")) {
                    return super.shouldInterceptRequest(view, request);
                }
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
            }
        });
    }

    // ================= Logic Control =================

    private void startStep1_GetCities() {
        currentFcode = fcodeEditText.getText().toString().trim();
        currentActId = activityIdEditText.getText().toString().trim();
        if (currentFcode.isEmpty() || currentActId.isEmpty()) {
            Toast.makeText(this, "Missing Parameters", Toast.LENGTH_SHORT).show();
            return;
        }

        // 彻底清理旧数据，防止状态残留
        processedCount.set(0);
        successRecordCount.set(0);
        activeWorkers.set(0);
        finishedCityCodes.clear();
        processedCityCodes.clear();
        failedCities.clear();

        // 既然是全新开始，也要清除 SharedPreferences 里的记录
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().remove("finished_cities").apply();

        isScrapingCities = true;
        isScrapingData = false;

        startButton.setText("Finish");
        log(">>> [Step 1] Fetching city list...");

        android.webkit.CookieManager.getInstance().removeAllCookies(null);

        try {
            String url = String.format(URL_TEMPLATE, currentActId, currentFcode, "440100", URLEncoder.encode("广州", "UTF-8"));
            if (!webViewPool.isEmpty()) {
                webViewPool.get(0).webView.loadUrl(url);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private void startStep2_ScrapeQueue(List<City> cities) {
        try {
            tempCsvFile = new File(getCacheDir(), "cinemas_temp.csv");
            FileOutputStream fos = new FileOutputStream(tempCsvFile);
            fos.write(0xef); fos.write(0xbb); fos.write(0xbf); // Write BOM
            csvWriter = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            csvWriter.write("City,Cinema Name,Address\n");
        } catch (IOException e) {
            log("[Error] File creation failed");
            return;
        }

        // 注意：这里不需要再调用 loadFinishedCities()，因为点击开始时已经决定是全新开始了
        // 但为了代码的健壮性（防止逻辑修改），如果用户没点Start而是其他途径进来，这里保留逻辑，但前面已经清空了
        cityQueue.clear();

        for (City c : cities) {
            // 如果 finishedCityCodes 是空的（因为被清除了），这里就会把所有城市都加进去
            if (finishedCityCodes.contains(c.code)) continue;
            if (!processedCityCodes.contains(c.code)) {
                cityQueue.add(c);
                processedCityCodes.add(c.code);
            }
        }

        isScrapingCities = false;
        isScrapingData = true;
        totalCities = cityQueue.size();

        progressBar.setMax(totalCities);
        updateProgressUI();

        log(">>> [Step 2] Starting " + currentThreadCount + " threads, Tasks: " + totalCities);

        if (totalCities == 0) {
            log("[System] No cities to process.");
            finishScraping();
            return;
        }

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

        wrapper.timeoutRunnable = () -> {
            log("[T" + wrapper.index + "] [Timeout] " + city.name);
            wrapper.webView.stopLoading();

            if (city.retryCount < MAX_RETRY_COUNT) {
                city.retryCount++;
                cityQueue.add(city);
            } else {
                failedCities.add(city);
            }
            releaseWorker(wrapper);
            assignTaskToWrapper(wrapper);
        };
        mainHandler.postDelayed(wrapper.timeoutRunnable, WORKER_TIMEOUT_MS);

        try {
            String encodedName = URLEncoder.encode(city.name, "UTF-8");
            final String targetUrl = String.format(URL_TEMPLATE, currentActId, currentFcode, city.code, encodedName);
            wrapper.webView.loadUrl(targetUrl);
        } catch (Exception e) {
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

    // ================= Configuration Management =================

    private void saveConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit()
                .putString("actId", activityIdEditText.getText().toString())
                .putString("fcode", fcodeEditText.getText().toString())
                .putInt("threads", currentThreadCount)
                .apply();
    }

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentThreadCount = sp.getInt("threads", DEFAULT_THREADS);
        if (currentThreadCount > MAX_THREADS) currentThreadCount = MAX_THREADS;
        if (currentThreadCount < 1) currentThreadCount = 1;
    }

    private void markCityAsFinished(String code) {
        // 在新逻辑中，这个仅用于当前运行会话的去重
        // 因为停止后会清除 Prefs，所以这里即便写入 Prefs，停止时也会被删掉
        if (finishedCityCodes.contains(code)) return;
        finishedCityCodes.add(code);
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putStringSet("finished_cities", finishedCityCodes).apply();
    }

    // ================= JS Bridge =================

    private class JsBridge {
        private final int idx;
        JsBridge(int i) { this.idx = i; }

        @JavascriptInterface
        public void onCitiesParsed(final String json) {
            if(idx != 0) return;
            mainHandler.post(() -> {
                try {
                    JSONArray ja = new JSONArray(json);
                    List<City> allCities = new ArrayList<>();
                    for (int i = 0; i < ja.length(); i++) {
                        JSONObject jo = ja.getJSONObject(i);
                        allCities.add(new City(jo.getString("name"), jo.getString("code")));
                    }
                    log("[System] Successfully fetched " + allCities.size() + " cities");
                    startButton.setEnabled(true);
                    startStep2_ScrapeQueue(allCities);
                } catch (Exception e) {
                    log("[Error] Failed to parse cities: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void onCityParseError(final String msg) {
            if(idx != 0) return;
            mainHandler.post(() -> { if(isScrapingCities) log("[Error] " + msg); });
        }

        @JavascriptInterface
        public void onDataParsed(final String json) {
            if (!isScrapingData) return;
            WebViewWrapper wrapper = webViewPool.get(idx);
            final City city = wrapper.currentCity;
            if (city == null) return;

            int count = 0;
            try {
                JSONArray ja = new JSONArray(json);
                count = ja.length();
                if (count > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        JSONObject jo = ja.getJSONObject(i);
                        String name = jo.getString("name").replace(",", "，").replace("\n", "").replace("\"", "\"\"");
                        String addr = jo.getString("addr").replace(",", "，").replace("\n", "").replace("\"", "\"\"");
                        sb.append(city.name).append(",").append(name).append(",").append(addr).append("\n");
                    }
                    saveRecords(sb.toString(), count);
                }
                markCityAsFinished(city.code);
            } catch (Exception e) { /* ignored */ }

            final int finalCount = count;
            mainHandler.post(() -> {
                if (finalCount > 0) {
                    log("[T" + idx + "] " + city.name + " (" + finalCount + ")");
                }
                processedCount.incrementAndGet();
                updateProgressUI();
                releaseWorker(wrapper);
                assignTaskToWrapper(wrapper);
            });
        }
    }

    private synchronized void saveRecords(String dataBlock, int count) {
        if (csvWriter == null) return;
        try {
            successRecordCount.addAndGet(count);
            csvWriter.write(dataBlock);
        } catch (IOException e) { e.printStackTrace(); }
    }

    // 正常完成时调用
    private void finishScraping() {
        if (!isScrapingData) return;
        isScrapingData = false;
        closeCsvWriter();

        log(">>> [Task Completed] Total Data: " + successRecordCount.get());
        if (!failedCities.isEmpty()) log(">>> Failed: " + failedCities.size());

        startButton.setText("Start");
        startButton.setEnabled(true);
        saveFileToUserDevice();
    }

    // 用户手动停止时调用 (强制重置)
    private void stopScraping() {
        log("[System] Stopping tasks and cleaning up...");

        // 1. 停止标志位
        isScrapingData = false;
        isScrapingCities = false;

        // 2. 清除 SharedPreferences 中的断点记录
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().remove("finished_cities").apply();

        // 3. 清空内存数据
        cityQueue.clear();
        finishedCityCodes.clear();
        processedCityCodes.clear();
        failedCities.clear();

        // 4. 停止所有 WebView
        for(WebViewWrapper w : webViewPool) {
            if(w.timeoutRunnable != null) mainHandler.removeCallbacks(w.timeoutRunnable);
            if (w.webView != null) w.webView.stopLoading();
            w.isBusy = false;
            w.currentCity = null;
        }

        // 5. 保存已抓取的数据并导出文件
        closeCsvWriter();

        // 6. 立即更新 UI
        mainHandler.post(() -> {
            startButton.setText("Start");
            startButton.setEnabled(true);
            activeWorkers.set(0);
            updateWorkerUI();
            log("[System] All tasks was forced to stop and the next start will be a fresh run.");
            saveFileToUserDevice(); // 导出已抓取的部分
        });
    }

    private void closeCsvWriter() {
        try {
            if (csvWriter != null) {
                csvWriter.flush();
                csvWriter.close();
                csvWriter = null;
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // ================= Helper Methods =================

    private void log(final String msg) {
        mainHandler.post(() -> {
            String timestamp = timeFormat.format(new Date());
            logTextView.append("[" + timestamp + "] " + msg + "\n");
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void updateProgressUI() {
        int current = processedCount.get();
        progressBar.setProgress(current);
        progressText.setText(String.format(Locale.getDefault(), "Progress: %d/%d (Saved %d)", current, totalCities, successRecordCount.get()));
    }

    private void updateWorkerUI() {
        tvWorkerStatus.setText("Active: " + activeWorkers.get());
    }

    private void saveFileToUserDevice() {
        // 如果没有数据，不弹出保存框
        if (successRecordCount.get() == 0 && !tempCsvFile.exists()) return;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "Cinemas_" + System.currentTimeMillis() + ".csv");
        startActivityForResult(intent, REQUEST_CODE_SAVE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SAVE_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) copyFile(uri);
        }
    }

    private void copyFile(final Uri targetUri) {
        new Thread(() -> {
            try {
                if (tempCsvFile == null || !tempCsvFile.exists()) return;
                InputStream in = new FileInputStream(tempCsvFile);
                OutputStream out = getContentResolver().openOutputStream(targetUri);
                if (out == null) return;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                in.close(); out.close();
                log("[System] File exported successfully!");
            } catch (Exception e) {
                log("[Error] Export failed: " + e.getMessage());
            }
        }).start();
    }

    // ================= JS Scripts (Optimized) =================

    private static final String SCRIPT_CITY_LIST = "javascript:(function(){" +
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
            "          Android.onCityParseError('Parse Timeout');" +
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

    private static final String SCRIPT_CINEMA_DATA = "(function(){" +
            "   if(window.hasExtracted) return;" +
            "   function extract() {" +
            "       var items = document.querySelectorAll('.list-item, .cinema-item, li');" +
            "       if(items.length > 0) {" +
            "           window.hasExtracted = true;" +
            "           var results = [];" +
            "           for(var i=0; i<items.length; i++){" +
            "               var nameEl = items[i].querySelector('.list-title') || items[i].querySelector('.cinema-name');" +
            "               var addrEl = items[i].querySelector('.list-location') || items[i].querySelector('.cinema-address');" +
            "               if(nameEl) {" +
            "                   results.push({ name: nameEl.innerText, addr: addrEl ? addrEl.innerText : '' });" +
            "               }" +
            "           }" +
            "           Android.onDataParsed(JSON.stringify(results));" +
            "           return true;" +
            "       }" +
            "       return false;" +
            "   }" +
            "   if(extract()) return;" +
            "   var observer = new MutationObserver(function(mutations) {" +
            "       if(extract()) observer.disconnect();" +
            "   });" +
            "   observer.observe(document.body, { childList: true, subtree: true });" +
            "   setTimeout(function(){ if(!window.hasExtracted) Android.onDataParsed('[]'); }, 3000);" +
            "})()";

    private void injectCityListScript(WebView view) {
        view.evaluateJavascript(SCRIPT_CITY_LIST, null);
    }

    private void injectCinemaDataScriptFast(WebView view) {
        view.evaluateJavascript(SCRIPT_CINEMA_DATA, null);
    }

    static class City {
        final String name;
        final String code;
        int retryCount = 0;
        City(String name, String code) { this.name = name; this.code = code; }
    }

    static class WebViewWrapper {
        WebView webView;
        final int index;
        boolean isBusy = false;
        City currentCity = null;
        Runnable timeoutRunnable = null;

        WebViewWrapper(WebView w, int i) { this.webView = w; this.index = i; }
    }
}