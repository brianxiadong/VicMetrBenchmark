package com.brianxiadong.vicmetrbenchmark.service;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VictoriaMetrics服务类
 * 实现与VictoriaMetrics的交互，包括写入压测、查询数据、删除数据等功能
 */
@Service
public class VictoriaMetricsService {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsService.class);
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public VictoriaMetricsService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 执行写入压测
     * 
     * @param request 压测请求参数
     * @return 压测结果
     */
    public BenchmarkResult runBenchmark(BenchmarkRequest request) {
        BenchmarkResult result = new BenchmarkResult();
        result.setStartTimestamp(System.currentTimeMillis());

        int totalCount = request.getDataCount();
        int batchSize = request.getBatchSize();
        int concurrency = request.getConcurrency();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxTime = new AtomicLong(0);

        // 计算每个线程需要处理的批次数
        int batchesPerThread = (int) Math.ceil((double) totalCount / (batchSize * concurrency));

        // 创建并提交任务
        for (int i = 0; i < concurrency; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < batchesPerThread; j++) {
                        int remaining = totalCount - (threadId * batchesPerThread + j) * batchSize;
                        if (remaining <= 0)
                            break;

                        int currentBatchSize = Math.min(batchSize, remaining);
                        long startTime = System.currentTimeMillis();

                        boolean success = sendBatch(request, currentBatchSize, threadId, j);

                        long endTime = System.currentTimeMillis();
                        long duration = endTime - startTime;

                        totalTime.addAndGet(duration);
                        minTime.updateAndGet(current -> Math.min(current, duration));
                        maxTime.updateAndGet(current -> Math.max(current, duration));

                        if (success) {
                            successCount.addAndGet(currentBatchSize);
                        } else {
                            failCount.addAndGet(currentBatchSize);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();

        // 填充结果
        result.setEndTimestamp(System.currentTimeMillis());
        result.setTotalRequests(successCount.get() + failCount.get());
        result.setSuccessRequests(successCount.get());
        result.setFailedRequests(failCount.get());
        result.setTotalTimeMillis(result.getEndTimestamp() - result.getStartTimestamp());
        result.setDataPointsCount(successCount.get());

        if (successCount.get() > 0) {
            result.setAvgResponseTimeMillis((double) totalTime.get() / (successCount.get() / batchSize));
            result.setMinResponseTimeMillis(minTime.get());
            result.setMaxResponseTimeMillis(maxTime.get());
        }

        // 获取服务器指标
        try {
            collectServerMetrics(request, result);
        } catch (Exception e) {
            log.error("收集服务器指标失败", e);
        }

        return result;
    }

    /**
     * 发送一批数据
     * 
     * @param request   压测请求参数
     * @param batchSize 批次大小
     * @param threadId  线程ID
     * @param batchId   批次ID
     * @return 是否成功
     */
    private boolean sendBatch(BenchmarkRequest request, int batchSize, int threadId, int batchId) {
        try {
            String url = getWriteUrl(request);
            String data = generateData(request, batchSize, threadId, batchId);

            RequestBody requestBody = RequestBody.create(MediaType.parse("text/plain"), data);
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.error("发送批次数据失败", e);
            return false;
        }
    }

    /**
     * 获取写入URL
     * 
     * @param request 压测请求参数
     * @return URL
     */
    private String getWriteUrl(BenchmarkRequest request) {
        String baseUrl = "http://" + request.getHost() + ":" + request.getPort();
        switch (request.getApiType().toLowerCase()) {
            case "prometheus":
                return baseUrl + "/api/v1/import/prometheus";
            case "influx":
                return baseUrl + "/api/v1/import/influx";
            case "opentsdb":
                return baseUrl + "/api/v1/import/opentsdb";
            default:
                return baseUrl + "/api/v1/import/prometheus";
        }
    }

    /**
     * 生成测试数据
     * 
     * @param request   压测请求参数
     * @param batchSize 批次大小
     * @param threadId  线程ID
     * @param batchId   批次ID
     * @return 生成的数据
     */
    private String generateData(BenchmarkRequest request, int batchSize, int threadId, int batchId) {
        StringBuilder data = new StringBuilder();
        long timestamp = System.currentTimeMillis();

        switch (request.getApiType().toLowerCase()) {
            case "prometheus":
                for (int i = 0; i < batchSize; i++) {
                    String metricName = request.getMetricPrefix() + "_" + threadId;
                    data.append(metricName)
                            .append("{thread_id=\"").append(threadId).append("\",")
                            .append("batch_id=\"").append(batchId).append("\",")
                            .append("index=\"").append(i).append("\",")
                            .append("test_id=\"benchmark_test\"} ")
                            .append(Math.random() * 100)
                            .append(" ").append(timestamp + i)
                            .append("\n");
                }
                break;

            case "influx":
                for (int i = 0; i < batchSize; i++) {
                    data.append(request.getMetricPrefix())
                            .append(",thread_id=").append(threadId)
                            .append(",batch_id=").append(batchId)
                            .append(",index=").append(i)
                            .append(",test_id=benchmark_test ")
                            .append("value=").append(Math.random() * 100)
                            .append(" ").append(timestamp + i).append("000000")
                            .append("\n");
                }
                break;

            // 可以添加其他类型的数据格式...

            default:
                // 默认使用Prometheus格式
                for (int i = 0; i < batchSize; i++) {
                    String metricName = request.getMetricPrefix() + "_" + threadId;
                    data.append(metricName)
                            .append("{thread_id=\"").append(threadId).append("\",")
                            .append("batch_id=\"").append(batchId).append("\",")
                            .append("index=\"").append(i).append("\",")
                            .append("test_id=\"benchmark_test\"} ")
                            .append(Math.random() * 100)
                            .append(" ").append(timestamp + i)
                            .append("\n");
                }
        }

        return data.toString();
    }

    /**
     * 收集服务器指标
     * 
     * @param request 压测请求参数
     * @param result  压测结果
     */
    private void collectServerMetrics(BenchmarkRequest request, BenchmarkResult result) {
        // 查询系统指标，这通常需要通过VictoriaMetrics自身的metrics接口或其他方式获取
        // 这里简化实现，可以通过查询特定的系统指标或估算
        String baseUrl = "http://" + request.getHost() + ":" + request.getPort();

        try {
            // 获取VM自身的指标
            Request metricsRequest = new Request.Builder()
                    .url(baseUrl + "/metrics")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(metricsRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String metricsText = response.body().string();

                    // 解析CPU使用率
                    double cpuUsage = extractMetricValue(metricsText, "process_cpu_seconds_total");
                    result.setCpuUsagePercent(cpuUsage * 100); // 转换为百分比

                    // 解析内存使用率
                    double memUsage = extractMetricValue(metricsText, "go_memstats_alloc_bytes");
                    double memTotal = extractMetricValue(metricsText, "go_memstats_sys_bytes");
                    if (memTotal > 0) {
                        result.setMemoryUsagePercent((memUsage / memTotal) * 100);
                    }

                    // 解析存储空间
                    double storageSize = extractMetricValue(metricsText, "vm_storage_data_size_bytes");
                    result.setStorageUsageMB(storageSize / (1024 * 1024)); // 转换为MB
                }
            }

            // 获取已写入的数据量
            long dataCount = queryDataCount(request);
            // 设置到结果中
            result.setDataPointsCount(dataCount);

        } catch (Exception e) {
            log.error("收集服务器指标失败", e);
        }
    }

    /**
     * 从指标文本中提取特定指标的值
     * 
     * @param metricsText 指标文本
     * @param metricName  指标名称
     * @return 指标值
     */
    private double extractMetricValue(String metricsText, String metricName) {
        try {
            String[] lines = metricsText.split("\n");
            for (String line : lines) {
                if (line.startsWith(metricName + " ") || line.startsWith(metricName + "{")) {
                    String[] parts = line.split(" ");
                    if (parts.length >= 2) {
                        return Double.parseDouble(parts[parts.length - 1]);
                    }
                }
            }
        } catch (Exception e) {
            log.error("提取指标值失败: " + metricName, e);
        }
        return 0;
    }

    /**
     * 查询数据总量
     * 
     * @param request 压测请求参数
     * @return 数据总量
     */
    public long queryDataCount(BenchmarkRequest request) {
        String baseUrl = "http://" + request.getHost() + ":" + request.getPort();
        String query = "count(" + request.getMetricPrefix() + "*)";

        try {
            HttpUrl url = HttpUrl.parse(baseUrl + "/api/v1/query")
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonNode root = objectMapper.readTree(responseBody);

                    if (root.has("data") && root.get("data").has("result")
                            && root.get("data").get("result").isArray()) {
                        JsonNode results = root.get("data").get("result");
                        if (results.size() > 0 && results.get(0).has("value")
                                && results.get(0).get("value").isArray()) {
                            JsonNode value = results.get(0).get("value");
                            if (value.size() > 1) {
                                String countStr = value.get(1).asText();
                                return Long.parseLong(countStr);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("查询数据总量失败", e);
        }

        return 0;
    }

    /**
     * 删除测试数据
     * 
     * @param request 包含服务器信息的请求
     * @return 是否成功
     */
    public boolean deleteTestData(BenchmarkRequest request) {
        String baseUrl = "http://" + request.getHost() + ":" + request.getPort();

        try {
            // 构建删除请求 - VictoriaMetrics提供了删除接口
            HttpUrl url = HttpUrl.parse(baseUrl + "/api/v1/admin/tsdb/delete_series")
                    .newBuilder()
                    .addQueryParameter("match[]", request.getMetricPrefix() + "*{test_id=\"benchmark_test\"}")
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(MediaType.parse("text/plain"), ""))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.error("删除测试数据失败", e);
            return false;
        }
    }

    /**
     * 获取服务器实时指标
     * 
     * @param request 包含服务器信息的请求
     * @return 服务器指标
     */
    public ServerMetrics getServerMetrics(BenchmarkRequest request) {
        ServerMetrics metrics = new ServerMetrics();
        String baseUrl = "http://" + request.getHost() + ":" + request.getPort();

        try {
            // 获取VM自身的指标
            Request metricsRequest = new Request.Builder()
                    .url(baseUrl + "/metrics")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(metricsRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String metricsText = response.body().string();

                    // 解析CPU使用率
                    double cpuUsage = extractMetricValue(metricsText, "process_cpu_seconds_total");
                    metrics.setCpuUsagePercent(cpuUsage * 100); // 转换为百分比

                    // 解析内存使用率
                    double memUsage = extractMetricValue(metricsText, "go_memstats_alloc_bytes");
                    double memTotal = extractMetricValue(metricsText, "go_memstats_sys_bytes");
                    if (memTotal > 0) {
                        metrics.setMemoryUsagePercent((memUsage / memTotal) * 100);
                    }

                    // 解析存储空间
                    double storageSize = extractMetricValue(metricsText, "vm_storage_data_size_bytes");
                    metrics.setVmDataSizeMB(storageSize / (1024 * 1024)); // 转换为MB
                }
            }

            // 获取查询延迟
            HttpUrl url = HttpUrl.parse(baseUrl + "/api/v1/query")
                    .newBuilder()
                    .addQueryParameter("query", "avg_over_time(vm_request_duration_seconds[5m])")
                    .build();

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonNode root = objectMapper.readTree(responseBody);

                    if (root.has("data") && root.get("data").has("result")
                            && root.get("data").get("result").isArray()) {
                        JsonNode results = root.get("data").get("result");
                        if (results.size() > 0 && results.get(0).has("value")
                                && results.get(0).get("value").isArray()) {
                            JsonNode value = results.get(0).get("value");
                            if (value.size() > 1) {
                                String latencyStr = value.get(1).asText();
                                metrics.setVmQueryLatencyMs(Double.parseDouble(latencyStr) * 1000); // 转换为毫秒
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("获取服务器指标失败", e);
        }

        return metrics;
    }
}