package com.treelang.films;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

public class ConverterActivity extends Activity {

    // 定义用于 onActivityResult 的 Request Code
    private static final int REQUEST_CODE_INPUT = 1002;
    private static final int REQUEST_CODE_SAVE = 1003;

    private TextView tvRuleStatus, tvInputStatus, tvLog;
    private Button btnGenerate;

    private String ruleAssetFile = null;
    private Uri inputUri = null;

    // 存储 影院名称 -> 专资编码
    private final Map<String, String> cinemaDict = new HashMap<>();

    // 匹配成功的列表 (修复了原代码 new List<>() 的语法错误)
    private final List<String> matchedNames = new ArrayList<>();
    private final List<String> matchedCodes = new ArrayList<>();
    private final List<String> matchedLines = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_converter);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("Converter");
        }

        tvRuleStatus = findViewById(R.id.tv_rule_status);
        tvInputStatus = findViewById(R.id.tv_input_status);
        tvLog = findViewById(R.id.tv_log);
        Button btnSelectRule = findViewById(R.id.btn_select_rule);
        Button btnSelectInput = findViewById(R.id.btn_select_input);
        btnGenerate = findViewById(R.id.btn_generate);

        btnSelectRule.setOnClickListener(v -> showRuleSelectionDialog());
        btnSelectInput.setOnClickListener(v -> openFilePicker());

        btnGenerate.setOnClickListener(v -> processFiles());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimetypes = { "text/csv", "text/comma-separated-values", "application/csv" };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        startActivityForResult(intent, ConverterActivity.REQUEST_CODE_INPUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQUEST_CODE_INPUT) {
                inputUri = uri;
                tvInputStatus.setText("已选择输入文件");
                checkReady();
            } else if (requestCode == REQUEST_CODE_SAVE) {
                saveDataToFile(uri);
            }
        }
    }

    private void showRuleSelectionDialog() {
        String[] rules = { "芒果规则", "哈哈规则" };
        int checkedItem = -1;
        if ("Rules_Mangguo.csv".equals(ruleAssetFile)) {
            checkedItem = 0;
        } else if ("Rules_Haha.csv".equals(ruleAssetFile)) {
            checkedItem = 1;
        }

        final int[] tempCheckedItem = { checkedItem };

        new android.app.AlertDialog.Builder(this)
                .setTitle("Select Rules")
                .setSingleChoiceItems(rules, checkedItem, (dialog, which) -> tempCheckedItem[0] = which)
                .setPositiveButton("Confirm", (dialog, which) -> {
                    if (tempCheckedItem[0] == 0) {
                        ruleAssetFile = "Rules_Mangguo.csv";
                        tvRuleStatus.setText("已选择: 芒果规则");
                    } else if (tempCheckedItem[0] == 1) {
                        ruleAssetFile = "Rules_Haha.csv";
                        tvRuleStatus.setText("已选择: 哈哈规则");
                    }
                    checkReady();
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void checkReady() {
        btnGenerate.setEnabled(ruleAssetFile != null && inputUri != null);
    }

    private void appendLog(final String msg) {
        runOnUiThread(() -> tvLog.append(msg + "\n"));
    }

    // 核心处理逻辑
    private void processFiles() {
        btnGenerate.setEnabled(false);
        tvLog.setText("开始处理...\n");
        cinemaDict.clear();
        matchedNames.clear();
        matchedCodes.clear();
        matchedLines.clear();

        new Thread(() -> {
            // 1. 读取规则文件建立字典 (使用 try-with-resources 自动释放流)
            try (InputStream ruleIs = getAssets().open(ruleAssetFile);
                    BufferedReader ruleReader = new BufferedReader(
                            new InputStreamReader(ruleIs, StandardCharsets.UTF_8))) {

                String line;
                boolean isFirstLine = true;
                while ((line = ruleReader.readLine()) != null) {
                    if (isFirstLine) {
                        line = line.replace("\uFEFF", "");
                        isFirstLine = false;
                    }
                    String[] parts = line.split(",");
                    if (parts.length >= 2) {
                        String name = parts[0].trim();
                        String code = parts[1].trim();
                        if (!"影院名称".equals(name)) { // 跳过表头，常量放前面防空指针
                            cinemaDict.put(name, code);
                        }
                    }
                }
                appendLog("加载规则库成功，共计: " + cinemaDict.size() + " 条影院数据。");

            } catch (Exception e) {
                appendLog("❌ 加载规则文件发生错误: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> btnGenerate.setEnabled(true));
                return;
            }

            // 2. 读取工作日影院并匹配
            int totalInput = 0;
            int matchCount = 0;

            try (InputStream inputIs = getContentResolver().openInputStream(inputUri);
                    BufferedReader inputReader = new BufferedReader(
                            new InputStreamReader(inputIs, StandardCharsets.UTF_8))) {

                String line;
                boolean isFirstLine = true;

                while ((line = inputReader.readLine()) != null) {
                    if (isFirstLine) {
                        line = line.replace("\uFEFF", "");
                        isFirstLine = false;
                    }
                    String[] parts = line.split(",");
                    if (parts.length >= 2) {
                        String cinemaName = parts[1].trim();
                        if ("Cinema Name".equals(cinemaName))
                            continue; // 跳过表头

                        totalInput++;
                        if ("Rules_Haha.csv".equals(ruleAssetFile)) {
                            matchedLines.add(line);
                            matchCount++;
                        } else if (cinemaDict.containsKey(cinemaName)) {
                            matchedNames.add(cinemaName);
                            matchedCodes.add(cinemaDict.get(cinemaName));
                            matchCount++;
                        } else {
                            appendLog("⚠️ 匹配失败: " + cinemaName);
                        }
                    }
                }

                String resultMsg = "匹配完成！读取: " + totalInput + " 家，成功匹配: " + matchCount + " 家。";
                appendLog(resultMsg);

                if (matchCount > 0) {
                    // 3. 触发系统保存文件操作
                    runOnUiThread(() -> {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        if ("Rules_Haha.csv".equals(ruleAssetFile)) {
                            intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                            intent.putExtra(Intent.EXTRA_TITLE, "哈哈自动报价列表_生成.xlsx");
                        } else {
                            intent.setType("text/csv");
                            intent.putExtra(Intent.EXTRA_TITLE, "芒果自动报价列表_生成.csv");
                        }
                        startActivityForResult(intent, REQUEST_CODE_SAVE);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(ConverterActivity.this, "未能匹配到任何数据，请检查文件内容", Toast.LENGTH_LONG)
                            .show());
                }

            } catch (Exception e) {
                appendLog("❌ 读取输入文件发生错误: " + e.getMessage());
                e.printStackTrace();
            } finally {
                runOnUiThread(() -> btnGenerate.setEnabled(true));
            }
        }).start();
    }

    // 将匹配好的数据写入到系统指定的保存路径中
    private void saveDataToFile(Uri saveUri) {
        // 使用 try-with-resources 自动释放流
        try (OutputStream os = getContentResolver().openOutputStream(saveUri)) {
            if ("Rules_Haha.csv".equals(ruleAssetFile)) {
                Workbook workbook = new Workbook(os, "FilmsApp", "1.0");
                Worksheet sheet = workbook.newWorksheet("Sheet1");

                // 哈哈规则的输出格式：仅使用工作日影院数据并替换表头，保留原先的第1和第3列
                sheet.value(0, 0, "影院分类批量上传模板");
                sheet.range(0, 0, 0, 2).merge();
                
                sheet.value(1, 0, "城市（选填）");
                sheet.value(1, 1, "影院名称");
                sheet.value(1, 2, "详细地址");
                
                for (int i = 0; i < matchedLines.size(); i++) {
                    String[] cols = matchedLines.get(i).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                    if (cols.length > 0) sheet.value(i + 2, 0, cols[0].replace("\"", ""));
                    if (cols.length > 1) sheet.value(i + 2, 1, cols[1].replace("\"", ""));
                    if (cols.length > 2) sheet.value(i + 2, 2, cols[2].replace("\"", ""));
                }
                
                workbook.finish();

                runOnUiThread(() -> {
                    Toast.makeText(ConverterActivity.this, "Excel文件导出成功！", Toast.LENGTH_LONG).show();
                    appendLog("✅ 导出完成！Excel文件已保存。");
                });
            } else {
                // 芒果规则的输出格式：所有影院拼接到行内，保留原生 CSV 快速导入策略
                os.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
                String header = "报价名称,包含院线,排除院线,包含影院,包含影院（专资编码）,排除影院,排除影院（专资编码）,包含省份,包含城市,排除城市,包含影片（精准匹配）,排除影片（精准匹配）,包含影厅,排除影厅,包含座位数（1单张 2两张 3三张 4四张）,\"包含星期几（0,1,2,3,4,5,6）\",W+座位（0不限1不报价）,情侣座位（0可含1不含2只含）,报价类型（0最高价1折扣价2固定价3市场价）,报价金额,匹配最低价,匹配最高价,开启关闭（0禁用1启用）,影片制式（0：全部 1：2D 2: 3D）\n";
                os.write(header.getBytes(StandardCharsets.UTF_8));

                int targetChunkSize = 1500;
                int total = matchedNames.size();
                String timeStr = new java.text.SimpleDateFormat("MMdd", java.util.Locale.getDefault())
                        .format(new java.util.Date());

                int i = 0;
                while (i < total) {
                    int remaining = total - i;
                    int chunkCount = Math.min(targetChunkSize, remaining);
                    int end = i + chunkCount;

                    StringBuilder sbNames = new StringBuilder();
                    StringBuilder sbCodes = new StringBuilder();

                    for (int j = i; j < end; j++) {
                        sbNames.append(matchedNames.get(j));
                        sbCodes.append(matchedCodes.get(j));
                        if (j < end - 1) {
                            sbNames.append(",");
                            sbCodes.append(",");
                        }
                    }

                    // CSV规则：如果单元格内容中包含逗号，必须用双引号将整个单元格内容包裹起来
                    String namesQuoted = "\"" + sbNames + "\"";
                    String codesQuoted = "\"" + sbCodes + "\"";
                    String quoteName = "工作日(" + timeStr + ")" + chunkCount;

                    String dataLine = quoteName + ",,," + namesQuoted + "," + codesQuoted
                            + ",,,,,,,,,,\"1,2,3,4\",\"0,1,2,3,4,5,6\",1,1,3,6.5,8,50,0,0\n";
                    os.write(dataLine.getBytes(StandardCharsets.UTF_8));

                    i = end;
                    targetChunkSize--; // 下一次的分配最大值 -1
                }
                
                os.flush();

                runOnUiThread(() -> {
                    Toast.makeText(ConverterActivity.this, "CSV文件导出成功！", Toast.LENGTH_LONG).show();
                    appendLog("✅ 导出完成！文件已保存。");
                });
            }
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(ConverterActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                appendLog("❌ 导出失败: " + e.getMessage());
            });
            e.printStackTrace();
        }
    }
}