package com.treelang.films;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "CinemaScraper";
    private static final String WEBVIEW_CONSOLE_TAG = "WebViewConsole";
    private static final int SAVE_FILE_REQUEST_CODE = 1001;
    private static final String URL_TEMPLATE = "https://m.taopiaopiao.com/movies/coupons/applicative-cinemas.html?activityid=1589369&citycode=%s&cityCode=%s&cityname=%s&cityName=%s&fcode=%s";
    private static final int TIMEOUT_DURATION = 30000; // 30 seconds

    private Button startButton;
    private TextView statusTextView;
    private EditText fcodeEditText;
    private ProgressBar progressBar;
    private File tempExcelFile;

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    private enum ScraperState {IDLE, FETCHING_CITIES, FETCHING_CINEMAS}
    private ScraperState currentState = ScraperState.IDLE;

    private List<City> cityList = new ArrayList<>();
    private List<City> failedCities = new ArrayList<>();
    private List<City> currentScrapingList = new ArrayList<>();
    private List<CinemaInfo> allCinemas = new ArrayList<>();
    private int currentCityIndex;
    private String currentFcode;

    public static class CinemaInfo {
        final String city;
        final String name;
        final String address;

        CinemaInfo(String city, String name, String address) {
            this.city = city;
            this.name = name;
            this.address = address;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startButton);
        statusTextView = findViewById(R.id.statusTextView);
        fcodeEditText = findViewById(R.id.fcodeEditText);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();
        startButton.setOnClickListener(v -> startFullScrapingProcess());
    }

    private void setupWebView() {
        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new JavaScriptInterface(this), "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(WEBVIEW_CONSOLE_TAG, consoleMessage.message());
                return true;
            }
        });
    }

    private void startFullScrapingProcess() {
        currentFcode = fcodeEditText.getText().toString().trim();
        if (currentFcode.isEmpty()) {
            fcodeEditText.setError("FCODE不能为空！");
            return;
        }

        // Reset state and UI
        startButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        allCinemas.clear();
        failedCities.clear();

        currentState = ScraperState.FETCHING_CITIES;
        statusTextView.setText("阶段一：加载页面并准备模拟触摸...");

        String sampleUrl = String.format(URL_TEMPLATE, "310100", "310100", "%E4%B8%8A%E6%B5%B7", "%E4%B8%8A%E6%B5%B7", currentFcode);

        setTimeoutAction(() -> {
            Log.e(TAG, "获取城市列表页面加载超时");
            updateStatus("错误：获取城市列表超时，请重试", true);
        }, TIMEOUT_DURATION);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (currentState == ScraperState.FETCHING_CITIES) {
                    // Allow page to fully render before executing JS
                    mainHandler.postDelayed(() -> {
                        String js = "javascript:(function(){" +
                        "  function simulateRealTap(element) {" +
                        "    var rect = element.getBoundingClientRect();" +
                        "    var clientX = rect.left + rect.width / 2, clientY = rect.top + rect.height / 2;" +
                        "    var touch = new Touch({ identifier: Date.now(), target: element, clientX: clientX, clientY: clientY });" +
                        "    var touchstartEvent = new TouchEvent('touchstart', { bubbles: true, cancelable: true, view: window, touches: [touch], targetTouches: [touch], changedTouches: [touch] });" +
                        "    var touchendEvent = new TouchEvent('touchend', { bubbles: true, cancelable: true, view: window, changedTouches: [touch] });" +
                        "    element.dispatchEvent(touchstartEvent);" +
                        "    element.dispatchEvent(touchendEvent);" +
                        "  }" +
                        "  var cityButton = document.querySelector('#J_citySelector');" +
                        "  if (!cityButton) { Android.processCityListError('未找到城市选择按钮'); return; }" +
                        "  simulateRealTap(cityButton);" +
                        "  var attempts = 0;" +
                        "  var interval = setInterval(function(){" +
                        "    attempts++;" +
                        "    var items = document.querySelectorAll('div.city-g:not(.current):not(.gps):not(.hot) li.city-item');" +
                        "    if (items.length > 250) {" +
                        "      clearInterval(interval);" +
                        "      var cities = [];" +
                        "      for (var i = 0; i < items.length; i++) {" +
                        "        cities.push({ regionName: items[i].getAttribute('data-name'), cityCode: items[i].getAttribute('data-code') });" +
                        "      }" +
                        "      Android.processCityList(JSON.stringify(cities));" +
                        "    } else if (attempts > 20) {" +
                        "      clearInterval(interval);" +
                        "      Android.processCityListError('触摸后10秒内未找到城市列表');" +
                        "    }" +
                        "  }, 500);" +
                        "})();";
                        view.loadUrl(js);
                    }, 3000);
                }
            }
        });
        webView.loadUrl(sampleUrl);
    }

    private void startScrapingLoop() {
        currentState = ScraperState.FETCHING_CINEMAS;

        if (currentCityIndex >= currentScrapingList.size()) {
            onScrapingFinished();
            return;
        }

        City currentCity = currentScrapingList.get(currentCityIndex);
        String progressText = String.format("正在抓取 %d/%d: %s...",
                (currentCityIndex + 1), currentScrapingList.size(), currentCity.getRegionName());
        updateStatus(progressText, false);
        progressBar.setProgress(currentCityIndex + 1);

        setTimeoutAction(() -> {
            Log.e(TAG, "页面加载超时: " + currentCity.getRegionName());
            failedCities.add(currentCity);
            webView.stopLoading();
            currentCityIndex++;
            startScrapingLoop();
        }, 10000);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (currentState == ScraperState.FETCHING_CINEMAS) {
                    String js = "javascript:(function(){" +
                        "var attempts=0;" +
                        "var maxAttempts=5;" +
                        "var interval=setInterval(function(){" +
                        "  attempts++;" +
                        "  var items=document.querySelectorAll('.list-item');" +
                        "  if(items.length>0){" +
                        "    clearInterval(interval);" +
                        "    var cinemas=[];" +
                        "    for(var i=0;i<items.length;i++){" +
                        "      var nameElement=items[i].querySelector('.list-title');" +
                        "      var addressElement=items[i].querySelector('.list-location');" +
                        "      if(nameElement&&addressElement){" +
                        "        cinemas.push({name:nameElement.innerText,address:addressElement.innerText})" +
                        "      }" +
                        "    }" +
                        "    Android.processCinemaList(JSON.stringify(cinemas))" +
                        "  }else if(document.querySelector('.empty-wrapper')){" +
                        "    clearInterval(interval);" +
                        "    Android.processCinemaList('[]')" +
                        "  }else if(attempts>=maxAttempts){" +
                        "    clearInterval(interval);" +
                        "    Android.processCinemaListError('脚本超时: 15秒内未找到 " + currentCity.getRegionName() + " 的影院列表。')" +
                        "  }" +
                        "},500)" +
                        "})();";
                    view.loadUrl(js);
                }
            }
        });

        try {
            String cityCode = String.valueOf(currentCity.getCityCode());
            String encodedCityName = URLEncoder.encode(currentCity.getRegionName(), "UTF-8");
            String fullUrl = String.format(URL_TEMPLATE, cityCode, cityCode,
                    encodedCityName, encodedCityName, currentFcode);
            webView.loadUrl(fullUrl);
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "URL编码失败", e);
            cancelTimeout();
            currentCityIndex++;
            startScrapingLoop();
        }
    }

    // Unified timeout handling
    private Runnable timeoutRunnable;

    private void setTimeoutAction(Runnable action, int delayMillis) {
        cancelTimeout();
        timeoutRunnable = action;
        mainHandler.postDelayed(timeoutRunnable, delayMillis);
    }

    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    // Unified status update
    private void updateStatus(String message, boolean enableStartButton) {
        runOnUiThread(() -> {
            statusTextView.setText(message);
            if (enableStartButton) {
                startButton.setEnabled(true);
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    public class JavaScriptInterface {
        Context mContext;

        JavaScriptInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void processCityList(String json) {
            cancelTimeout();

            try {
                JSONArray citiesArray = new JSONArray(json);
                final List<City> parsedCities = new ArrayList<>();

                for (int i = 0; i < citiesArray.length(); i++) {
                    JSONObject cityObj = citiesArray.getJSONObject(i);
                    parsedCities.add(new City(
                        cityObj.getString("regionName"),
                        Integer.parseInt(cityObj.getString("cityCode"))
                    ));
                }

                runOnUiThread(() -> {
                    cityList = parsedCities;
                    if (cityList.isEmpty()) {
                        processCityListError("解析到的城市列表为空");
                        return;
                    }

                    Toast.makeText(MainActivity.this,
                            "成功获取 " + cityList.size() + " 个城市！开始抓取影院...",
                            Toast.LENGTH_LONG).show();

                    progressBar.setMax(cityList.size());
                    currentScrapingList = cityList;
                    currentCityIndex = 0;
                    startScrapingLoop();
                });

            } catch (JSONException | NumberFormatException e) {
                Log.e(TAG, "解析城市列表JSON失败", e);
                runOnUiThread(() -> processCityListError("JSON解析失败: " + e.getMessage()));
            }
        }

        @JavascriptInterface
        public void processCityListError(String message) {
            cancelTimeout();
            Log.e(TAG, "获取城市列表失败: " + message);
            updateStatus("错误: 获取城市列表失败 - " + message, true);
        }

        @JavascriptInterface
        public void processCinemaList(String json) {
            cancelTimeout();
            City city = currentScrapingList.get(currentCityIndex);

            try {
                JSONArray cinemasArray = new JSONArray(json);
                List<CinemaInfo> parsedCinemas = new ArrayList<>();

                for (int i = 0; i < cinemasArray.length(); i++) {
                    JSONObject cinemaObj = cinemasArray.getJSONObject(i);
                    parsedCinemas.add(new CinemaInfo(
                        city.getRegionName(),
                        cinemaObj.getString("name"),
                        cinemaObj.getString("address")
                    ));
                }

                Log.d(TAG, "完成: " + city.getRegionName() + ", 找到 " + parsedCinemas.size() + " 家影院");
                allCinemas.addAll(parsedCinemas);

            } catch (JSONException e) {
                Log.e(TAG, "解析 " + city.getRegionName() + " 的影院列表JSON失败", e);
                failedCities.add(city);
                currentCityIndex++;
                runOnUiThread(MainActivity.this::startScrapingLoop);
                return;
            }

            currentCityIndex++;
            runOnUiThread(MainActivity.this::startScrapingLoop);
        }

        @JavascriptInterface
        public void processCinemaListError(String message) {
            cancelTimeout();
            City city = currentScrapingList.get(currentCityIndex);
            Log.e(TAG, "JS错误: " + city.getRegionName() + " - " + message);
            failedCities.add(city);
            currentCityIndex++;
            runOnUiThread(MainActivity.this::startScrapingLoop);
        }
    }

    private void onScrapingFinished() {
        currentState = ScraperState.IDLE;
        statusTextView.setText("所有抓取任务完成！共找到 " + allCinemas.size() + " 家影院。");
        progressBar.setVisibility(View.GONE);

        if (!failedCities.isEmpty()) {
            String warningMsg = "抓取完成，但有 " + failedCities.size() + " 个城市失败。";
            Toast.makeText(this, warningMsg, Toast.LENGTH_LONG).show();
            Log.w(TAG, warningMsg);
        }

        generateFinalExcelFile();
    }

    private void generateFinalExcelFile() {
        updateStatus("正在生成包含所有城市数据的Excel文件...", false);

        backgroundExecutor.execute(() -> {
            try {
                File outputDir = getCacheDir();
                tempExcelFile = new File(outputDir, "all_city_cinemas.xlsx");

                try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                    XSSFSheet sheet = workbook.createSheet("全国影院列表");
                    String[] columns = {"城市", "影院名称", "详细地址"};

                    Row headerRow = sheet.createRow(0);
                    for (int i = 0; i < columns.length; i++) {
                        headerRow.createCell(i).setCellValue(columns[i]);
                    }

                    int rowNum = 1;
                    for (CinemaInfo cinema : allCinemas) {
                        Row row = sheet.createRow(rowNum++);
                        row.createCell(0).setCellValue(cinema.city);
                        row.createCell(1).setCellValue(cinema.name);
                        row.createCell(2).setCellValue(cinema.address);
                    }

                    try (FileOutputStream fileOut = new FileOutputStream(tempExcelFile)) {
                        workbook.write(fileOut);
                    }
                }

                runOnUiThread(this::launchSaveFileDialog);

            } catch (IOException e) {
                Log.e(TAG, "写入Excel时出错", e);
                updateStatus("写入Excel时出错: " + e.getMessage(), true);
            }
        });
    }

    private void launchSaveFileDialog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.putExtra(Intent.EXTRA_TITLE, "all_city_cinemas.xlsx");
        startActivityForResult(intent, SAVE_FILE_REQUEST_CODE);
    }

    private void copyTempFileToUri(Uri targetUri) {
        updateStatus("正在导出文件...", false);

        backgroundExecutor.execute(() -> {
            try (InputStream in = new FileInputStream(tempExcelFile);
                 OutputStream out = getContentResolver().openOutputStream(targetUri)) {

                if (out == null) throw new IOException("无法打开目标文件流");

                byte[] buffer = new byte[8192]; // Increased buffer size for better performance
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                out.flush();

                updateStatus("任务完成！", true);
                runOnUiThread(() -> Toast.makeText(this, "文件已成功保存！", Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                Log.e(TAG, "复制文件失败", e);
                updateStatus("导出失败: " + e.getMessage(), true);
                runOnUiThread(() -> Toast.makeText(this,
                    "保存文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                cleanupTempFile();
            }
        });
    }

    private void cleanupTempFile() {
        if (tempExcelFile != null && tempExcelFile.exists()) {
            if (!tempExcelFile.delete()) {
                Log.w(TAG, "临时Excel文件删除失败");
            }
            tempExcelFile = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_FILE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                copyTempFileToUri(data.getData());
            } else {
                Toast.makeText(this, "取消保存", Toast.LENGTH_SHORT).show();
                updateStatus("文件导出已取消。", true);
                cleanupTempFile();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelTimeout();
        cleanupWebView();
        cleanupTempFile();
    }

    private void cleanupWebView() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }
}

