package com.brianxiadong.vicmetrbenchmark.service;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VictoriaMetrics服务类
 * 实现与VictoriaMetrics的交互，包括写入压测、查询数据、删除数据等功能
 * 所有HTTP请求都通过脚本执行，以支持VPN访问
 */
@Service
public class VictoriaMetricsService {

    private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsService.class);
    private final ObjectMapper objectMapper;

    public VictoriaMetricsService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 简单测试连接
     * 仅测试VictoriaMetrics是否可访问，而不返回复杂数据
     * 
     * @param request 包含服务器信息的请求
     * @return 连接是否成功
     */
    public boolean testConnection(BenchmarkRequest request) {
        try {
            String response = executeScript(request, "health");
            return response.contains("ok");
        } catch (Exception e) {
            log.warn("连接测试失败: {}", e.getMessage());
            return false;
        }
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

        // 验证参数
        if (request.getDataCount() == null || request.getDataCount() <= 0) {
            result.setErrorMessage("数据量必须大于0");
            return result;
        }

        if (request.getBatchSize() == null || request.getBatchSize() <= 0) {
            request.setBatchSize(1000); // 默认值
        }

        if (request.getConcurrency() == null || request.getConcurrency() <= 0) {
            request.setConcurrency(1); // 默认值
        }

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
            if (!latch.await(30, TimeUnit.MINUTES)) {
                result.setErrorMessage("压测执行超时");
                executor.shutdownNow();
                return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setErrorMessage("压测被中断");
            executor.shutdownNow();
            return result;
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
            result.setErrorMessage("收集服务器指标失败: " + e.getMessage());
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
        int maxRetries = 3;
        int baseRetryDelay = 1000; // 基础重试延迟1秒
        double backoffMultiplier = 2.0; // 指数退避乘数

        for (int i = 0; i < maxRetries; i++) {
            try {
                String data = generateData(request, batchSize, threadId, batchId);
                String response = executeScript(request, "write", data);

                if (response.contains("success")) {
                    return true;
                }

                log.warn("批次发送失败 - 线程ID: {}, 批次ID: {}, 重试次数: {}, 响应: {}",
                        threadId, batchId, i + 1, response);

                // 如果是502错误，增加重试延迟
                if (response.contains("502")) {
                    int retryDelay = (int) (baseRetryDelay * Math.pow(backoffMultiplier, i));
                    log.info("遇到502错误，等待{}毫秒后重试", retryDelay);
                    Thread.sleep(retryDelay);
                    continue;
                }
            } catch (Exception e) {
                log.warn("第{}次发送批次失败 - 线程ID: {}, 批次ID: {}, 错误: {}",
                        i + 1, threadId, batchId, e.getMessage());
                if (i < maxRetries - 1) {
                    try {
                        int retryDelay = (int) (baseRetryDelay * Math.pow(backoffMultiplier, i));
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return false;
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
        long timestamp = System.currentTimeMillis(); // 使用毫秒级时间戳

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
                            .append(" ").append(timestamp * 1000000) // 转换为纳秒时间戳
                            .append("\n");
                }
                break;

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
        try {
            String response = executeScript(request, "metrics");
            JsonNode root = objectMapper.readTree(response);

            // 从JSON中提取指标值
            result.setCpuUsagePercent(root.get("cpuUsagePercent").asDouble());
            result.setMemoryUsagePercent(root.get("memoryUsagePercent").asDouble());
            result.setStorageUsageMB(root.get("storageUsageMB").asDouble());
            result.setDataPointsCount(root.get("dataPointsCount").asLong());

        } catch (Exception e) {
            log.error("收集服务器指标失败", e);
        }
    }

    /**
     * 使用脚本执行VictoriaMetrics操作
     * 
     * @param request   请求参数
     * @param operation 操作类型
     * @param args      额外参数
     * @return 执行结果
     */
    public String executeScript(BenchmarkRequest request, String operation, String... args) {
        try {
            // 构建命令
            List<String> command = new ArrayList<>();
            command.add("bash");
            command.add("src/main/resources/scripts/vic_metrics_query.sh");
            command.add(request.getHost());
            command.add(String.valueOf(request.getPort()));
            command.add(operation);
            command.addAll(Arrays.asList(args));

            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 读取输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // 等待命令执行完成
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException("命令执行超时");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("命令执行失败，退出码: " + exitCode);
            }

            return output.toString();
        } catch (Exception e) {
            log.error("执行脚本失败: {}", e.getMessage());
            throw new RuntimeException("执行脚本失败: " + e.getMessage());
        }
    }

    /**
     * 使用脚本统计数据量
     * 
     * @param request 请求参数
     * @return 数据量
     */
    public long queryDataCount(BenchmarkRequest request) {
        try {
            String response = executeScript(request, "count", request.getMetricPrefix());
            JsonNode root = objectMapper.readTree(response);

            // 检查响应状态
            if (root.has("status") && "success".equals(root.get("status").asText())) {
                // 检查是否有count字段
                if (root.has("count")) {
                    return root.get("count").asLong();
                }
                // 检查是否有data字段
                if (root.has("data")) {
                    JsonNode data = root.get("data");
                    if (data.isArray() && data.size() > 0) {
                        return data.get(0).asLong();
                    }
                    if (data.isObject() && data.has("count")) {
                        return data.get("count").asLong();
                    }
                }
            }

            // 如果解析失败，记录错误信息
            log.error("无法从响应中解析数据量: {}", response);
            return 0;
        } catch (Exception e) {
            log.error("统计数据量失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 使用脚本删除数据
     * 
     * @param request 请求参数
     * @return 是否成功
     */
    public boolean deleteTestData(BenchmarkRequest request) {
        try {
            String response = executeScript(request, "delete", request.getMetricPrefix());
            return response.contains("删除操作完成");
        } catch (Exception e) {
            log.error("删除数据失败", e);
            return false;
        }
    }

    /**
     * 使用脚本获取服务器指标
     * 
     * @param request 请求参数
     * @return 服务器指标
     */
    public ServerMetrics getServerMetrics(BenchmarkRequest request) {
        ServerMetrics metrics = new ServerMetrics();
        try {
            String response = executeScript(request, "metrics");
            JsonNode root = objectMapper.readTree(response);

            // 从JSON中提取指标值
            metrics.setCpuUsagePercent(root.get("cpuUsagePercent").asDouble());
            metrics.setMemoryUsagePercent(root.get("memoryUsagePercent").asDouble());
            metrics.setStorageUsageMB(root.get("storageUsageMB").asDouble());
            metrics.setTotalStorageMB(root.get("totalStorageMB").asDouble());
            metrics.setVmDataSizeMB(root.get("vmDataSizeMB").asDouble());
            metrics.setVmQueryLatencyMs(root.get("vmQueryLatencyMs").asDouble());

            // 获取数据量
            if (root.has("dataPointsCount")) {
                metrics.setDataPointsCount(root.get("dataPointsCount").asLong());
            }

            if (root.has("errorMessage") && !root.get("errorMessage").isNull()) {
                metrics.setErrorMessage(root.get("errorMessage").asText());
            }
        } catch (Exception e) {
            log.error("获取服务器指标失败", e);
            metrics.setErrorMessage("获取服务器指标失败: " + e.getMessage());
        }
        return metrics;
    }
}