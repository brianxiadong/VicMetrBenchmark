package com.brianxiadong.vicmetrbenchmark.controller;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.QueryTestResult;
import com.brianxiadong.vicmetrbenchmark.service.VictoriaMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询测试控制器
 * 处理查询测试相关的请求
 */
@Slf4j
@RestController
@RequestMapping("/query-test")
public class QueryTestController {

    @Autowired
    private VictoriaMetricsService victoriaMetricsService;

    // 存储测试结果的列表
    private final List<QueryTestResult> testResults = new ArrayList<>();

    /**
     * 执行一轮查询测试
     */
    @PostMapping("/run")
    public QueryTestResult runQueryTest(@RequestBody BenchmarkRequest request) {
        int roundNumber = testResults.size() + 1;
        QueryTestResult result = victoriaMetricsService.runQueryTest(request, roundNumber);
        testResults.add(result);
        return result;
    }

    /**
     * 导出测试结果为CSV
     */
    @GetMapping("/export")
    public ResponseEntity<String> exportTestResults() {
        String csv = victoriaMetricsService.exportTestResultsToCsv(testResults);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setContentDispositionFormData("attachment", "query-test-results.csv");

        return ResponseEntity.ok()
                .headers(headers)
                .body(csv);
    }

    /**
     * 清除测试结果
     */
    @PostMapping("/clear")
    public String clearTestResults() {
        testResults.clear();
        return "测试结果已清除";
    }
}