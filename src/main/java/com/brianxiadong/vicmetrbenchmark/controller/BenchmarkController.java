package com.brianxiadong.vicmetrbenchmark.controller;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.brianxiadong.vicmetrbenchmark.service.VictoriaMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 压测控制器
 * 处理前端的压测请求
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkController.class);

    @Autowired
    private VictoriaMetricsService victoriaMetricsService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 运行压测
     * 
     * @param request 压测请求参数
     * @return 压测结果
     */
    @PostMapping("/run")
    public BenchmarkResult runBenchmark(@RequestBody BenchmarkRequest request) {
        log.info("收到压测请求: {}", request);

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            BenchmarkResult errorResult = new BenchmarkResult();
            errorResult.setErrorMessage("主机地址不能为空");
            return errorResult;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            BenchmarkResult errorResult = new BenchmarkResult();
            errorResult.setErrorMessage("端口号必须大于0");
            return errorResult;
        }

        if (request.getDataCount() == null || request.getDataCount() <= 0) {
            BenchmarkResult errorResult = new BenchmarkResult();
            errorResult.setErrorMessage("数据量必须大于0");
            return errorResult;
        }

        // 设置默认值
        if (request.getBatchSize() == null || request.getBatchSize() <= 0) {
            request.setBatchSize(1000);
        }

        if (request.getConcurrency() == null || request.getConcurrency() <= 0) {
            request.setConcurrency(1);
        }

        if (request.getMetricPrefix() == null || request.getMetricPrefix().trim().isEmpty()) {
            request.setMetricPrefix("benchmark_metric");
        }

        if (request.getApiType() == null || request.getApiType().trim().isEmpty()) {
            request.setApiType("prometheus");
        }

        return victoriaMetricsService.runBenchmark(request);
    }

    /**
     * 获取服务器指标
     * 
     * @param request 包含服务器信息的请求
     * @return 服务器指标
     */
    @PostMapping("/metrics")
    public ServerMetrics getServerMetrics(@RequestBody BenchmarkRequest request) {
        log.info("收到获取服务器指标请求: host={}, port={}", request.getHost(), request.getPort());

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            ServerMetrics errorMetrics = new ServerMetrics();
            errorMetrics.setErrorMessage("主机地址不能为空");
            return errorMetrics;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            ServerMetrics errorMetrics = new ServerMetrics();
            errorMetrics.setErrorMessage("端口号必须大于0");
            return errorMetrics;
        }

        return victoriaMetricsService.getServerMetrics(request);
    }

    /**
     * 查询数据总量
     * 
     * @param request 包含服务器信息的请求
     * @return 数据总量
     */
    @PostMapping("/data-count")
    public Map<String, Object> getDataCount(@RequestBody BenchmarkRequest request) {
        log.info("收到查询数据总量请求: host={}, port={}", request.getHost(), request.getPort());
        Map<String, Object> response = new HashMap<>();

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "主机地址不能为空");
            response.put("count", 0);
            return response;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            response.put("success", false);
            response.put("error", "端口号必须大于0");
            response.put("count", 0);
            return response;
        }

        try {
            long count = victoriaMetricsService.queryDataCount(request);
            response.put("success", true);
            response.put("count", count);
        } catch (Exception e) {
            log.error("查询数据总量失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("count", 0);
        }

        return response;
    }

    /**
     * 删除测试数据
     * 
     * @param request 包含服务器信息的请求
     * @return 是否成功
     */
    @PostMapping("/delete-data")
    public Map<String, Object> deleteTestData(@RequestBody BenchmarkRequest request) {
        log.info("收到删除测试数据请求: host={}, port={}", request.getHost(), request.getPort());
        Map<String, Object> response = new HashMap<>();

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "主机地址不能为空");
            return response;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            response.put("success", false);
            response.put("error", "端口号必须大于0");
            return response;
        }

        if (request.getMetricPrefix() == null || request.getMetricPrefix().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "指标前缀不能为空");
            return response;
        }


        try {
            boolean success = victoriaMetricsService.deleteTestData(request);
            response.put("success", success);
            if (!success) {
                response.put("error", "删除数据失败");
            }
        } catch (Exception e) {
            log.error("删除测试数据失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }

    /**
     * 测试连接
     * 提供轻量级的连接测试，不获取复杂指标
     * 
     * @param request 包含服务器信息的请求
     * @return 连接是否成功
     */
    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@RequestBody BenchmarkRequest request) {
        log.info("收到测试连接请求: host={}, port={}", request.getHost(), request.getPort());
        Map<String, Object> response = new HashMap<>();

        try {
            // 使用curl直接请求健康检查接口
            String url = "http://" + request.getHost() + ":" + request.getPort() + "/health";
            ProcessBuilder processBuilder = new ProcessBuilder("curl", "-s", "-o", "/dev/null",
                    "-w", "%{http_code}", "--connect-timeout", "3", "--max-time", "3", url);
            processBuilder.redirectErrorStream(true);

            // 执行命令
            Process process = processBuilder.start();

            // 读取输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            // 等待命令执行完成
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                response.put("success", false);
                response.put("error", "连接超时");
                return response;
            }

            String statusCode = output.toString().trim();
            boolean success = "200".equals(statusCode);

            response.put("success", success);
            if (!success) {
                response.put("message", "无法连接到服务器, HTTP状态码: " + statusCode);
            }
        } catch (Exception e) {
            log.error("测试连接失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }

    /**
     * 执行VictoriaMetrics查询
     * 
     * @param request 请求参数
     * @return 查询结果
     */
    @PostMapping("/query")
    public Map<String, Object> executeQuery(@RequestBody BenchmarkRequest request) {
        log.info("收到查询请求: host={}, port={}", request.getHost(), request.getPort());
        Map<String, Object> response = new HashMap<>();

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "主机地址不能为空");
            return response;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            response.put("success", false);
            response.put("error", "端口号必须大于0");
            return response;
        }

        if (request.getMetricPrefix() == null || request.getMetricPrefix().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "查询语句不能为空");
            return response;
        }

        try {
            String result = victoriaMetricsService.executeScript(request, "query", request.getMetricPrefix());
            response.put("success", true);
            response.put("result", result);
        } catch (Exception e) {
            log.error("执行查询失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }

    /**
     * 检查服务器健康状态
     * 
     * @param request 请求参数
     * @return 健康状态
     */
    @PostMapping("/health")
    public Map<String, Object> checkHealth(@RequestBody BenchmarkRequest request) {
        log.info("收到健康检查请求: host={}, port={}", request.getHost(), request.getPort());
        Map<String, Object> response = new HashMap<>();

        // 参数验证
        if (request.getHost() == null || request.getHost().trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "主机地址不能为空");
            return response;
        }

        if (request.getPort() == null || request.getPort() <= 0) {
            response.put("success", false);
            response.put("error", "端口号必须大于0");
            return response;
        }

        try {
            String result = victoriaMetricsService.executeScript(request, "health");
            response.put("success", true);
            response.put("status", result);
        } catch (Exception e) {
            log.error("健康检查失败", e);
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        return response;
    }
}