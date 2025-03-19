package com.brianxiadong.vicmetrbenchmark.service;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.brianxiadong.vicmetrbenchmark.utils.VictoriaMetricsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 使用VictoriaMetricsClient进行HTTP请求
 */
@Slf4j
@Service
public class VictoriaMetricsService {

    @Autowired
    private VictoriaMetricsClient victoriaMetricsClient;

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
            String response = victoriaMetricsClient.checkHealth();
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
        long startTime = System.currentTimeMillis();
        result.setStartTimestamp(startTime);

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
                        boolean success = sendBatch(request, currentBatchSize, threadId, j);

                        // 更新统计信息
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

        // 计算总耗时
        long endTime = System.currentTimeMillis();
        long totalTimeMillis = endTime - startTime;

        // 填充结果
        result.setEndTimestamp(endTime);
        result.setTotalRequests(successCount.get() + failCount.get());
        result.setSuccessRequests(successCount.get());
        result.setFailedRequests(failCount.get());
        result.setTotalTimeMillis(totalTimeMillis);
        result.setDataPointsCount(successCount.get());

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
                // 生成数据
                String data = generateData(request, batchSize, threadId, batchId);
                log.debug("生成的数据: {}", data);

                // 使用新的 writeData 方法，传入 apiType
                String response = victoriaMetricsClient.writeData(data, request.getApiType());
                log.debug("写入响应: {}", response);

                // 检查响应是否成功
                // VictoriaMetrics 写入成功时返回空字符串
                if (response != null && response.trim().isEmpty()) {
                    log.info("批次发送成功 - 线程ID: {}, 批次ID: {}, 数据量: {}",
                            threadId, batchId, batchSize);
                    return true;
                }

                log.warn("批次发送失败 - 线程ID: {}, 批次ID: {}, 重试次数: {}, 响应: {}",
                        threadId, batchId, i + 1, response);

                // 如果是502错误，增加重试延迟
                if (response != null && response.contains("502")) {
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
     * 查询所有数据量
     * 使用 /api/v1/series/count 接口
     * 
     * @return 所有数据量
     */
    public long queryTotalDataCount() {
        try {
            // 使用新的 queryTotalCount 方法
            String response = victoriaMetricsClient.queryTotalCount();
            log.info("查询总数据量响应: {}", response);

            // 解析 JSON 响应
            JsonNode root = objectMapper.readTree(response);
            if (root.has("status") && "success".equals(root.get("status").asText())
                    && root.has("data") && root.get("data").isArray() && root.get("data").size() > 0) {
                return root.get("data").get(0).asLong();
            } else {
                log.error("总数据量响应格式不正确: {}", response);
                return 0;
            }
        } catch (Exception e) {
            log.error("查询总数据量失败", e);
            return 0;
        }
    }

    /**
     * 使用VictoriaMetricsClient统计数据量
     * 支持按前缀查询数据量
     * 
     * @param request 请求参数
     * @return 数据量
     */
    public long queryDataCount(BenchmarkRequest request) {
        try {
            // 验证输入参数
            if (request == null || request.getMetricPrefix() == null || request.getMetricPrefix().trim().isEmpty()) {
                log.error("指标前缀不能为空");
                return 0;
            }

            // 构建查询语句，使用正确的 PromQL 格式
            String query = String.format("count({__name__=~\"%s.+\"})", request.getMetricPrefix());
            log.debug("构建的查询语句: {}", query);

            // 使用 query 方法发送请求
            String response = victoriaMetricsClient.query(query);
            log.debug("查询响应: {}", response);

            // 解析 JSON 响应
            JsonNode root = objectMapper.readTree(response);
            if (root.has("status") && "success".equals(root.get("status").asText())
                    && root.has("data") && root.get("data").has("result")) {
                JsonNode result = root.get("data").get("result");
                if (result.isArray() && result.size() > 0) {
                    JsonNode firstResult = result.get(0);
                    if (firstResult.has("value") && firstResult.get("value").isArray()) {
                        JsonNode value = firstResult.get("value");
                        if (value.size() > 1) {
                            return value.get(1).asLong();
                        }
                    }
                }
            }

            log.warn("未找到匹配的数据点");
            return 0;
        } catch (Exception e) {
            log.error("查询前缀数据量失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 收集服务器指标
     * 
     * @param request 压测请求参数
     * @param result  压测结果
     */
    public ServerMetrics collectServerMetrics(BenchmarkRequest request, BenchmarkResult result) {
        ServerMetrics metrics = new ServerMetrics();
        try {
            String metricsResponse = victoriaMetricsClient.getMetrics();
            if (metricsResponse == null || metricsResponse.trim().isEmpty()) {
                throw new RuntimeException("获取服务器指标响应为空");
            }

            String[] lines = metricsResponse.split("\n");
            if (lines.length == 0) {
                throw new RuntimeException("服务器指标数据为空");
            }

            double cpuSeconds = 0;
            double processStartTime = 0;
            double memoryUsed = 0;
            double totalMemory = 0;
            double storageUsed = 0;
            long dataPoints = 0;

            for (String line : lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }

                String metricName = parts[0];
                String value = parts[1];

                try {
                    switch (metricName) {
                        case "process_cpu_seconds_total":
                            cpuSeconds = Double.parseDouble(value);
                            break;
                        case "process_start_time_seconds":
                            processStartTime = Double.parseDouble(value);
                            break;
                        case "process_resident_memory_bytes":
                            memoryUsed = Double.parseDouble(value);
                            break;
                        case "go_memstats_sys_bytes":
                            totalMemory = Double.parseDouble(value);
                            break;
                        case "vm_data_size_bytes{type=\"storage/inmemory\"}":
                        case "vm_data_size_bytes{type=\"storage/small\"}":
                        case "vm_data_size_bytes{type=\"storage/big\"}":
                        case "vm_data_size_bytes{type=\"indexdb/inmemory\"}":
                        case "vm_data_size_bytes{type=\"indexdb/file\"}":
                            storageUsed += Double.parseDouble(value);
                            break;
                        case "vm_rows_inserted_total{type=\"prometheus\"}":
                        case "vm_rows_inserted_total{type=\"promremotewrite\"}":
                            dataPoints += Long.parseLong(value);
                            break;
                    }
                } catch (NumberFormatException e) {
                    log.warn("解析指标值失败: {} 对应指标: {}", value, metricName);
                }
            }

            // Calculate CPU usage percentage
            if (processStartTime > 0) {
                double uptime = System.currentTimeMillis() / 1000.0 - processStartTime;
                metrics.setCpuUsagePercent((cpuSeconds / uptime) * 100);
            } else {
                log.warn("无法计算CPU使用率：进程启动时间未知");
                metrics.setCpuUsagePercent(0);
            }

            // Calculate memory usage percentage
            if (totalMemory > 0) {
                metrics.setMemoryUsagePercent((memoryUsed / totalMemory) * 100);
            } else {
                log.warn("无法计算内存使用率：总内存为0");
                metrics.setMemoryUsagePercent(0);
            }

            // Convert storage to MB
            metrics.setStorageUsageMB(storageUsed / (1024 * 1024));

            // 获取总数据量和前缀数据量
            long totalDataCount = queryTotalDataCount();
            long prefixDataCount = queryDataCount(request);
            metrics.setTotalDataPointsCount(totalDataCount);
            metrics.setDataPointsCount(prefixDataCount);

            log.info(
                    "收集服务器指标成功 - CPU: {}%, 内存: {}%, 存储: {}MB, 总数据点: {}, 前缀数据点: {}",
                    metrics.getCpuUsagePercent(), metrics.getMemoryUsagePercent(), metrics.getStorageUsageMB(),
                    metrics.getTotalDataPointsCount(), metrics.getDataPointsCount());

        } catch (Exception e) {
            String errorMsg = "收集服务器指标失败: " + e.getMessage();
            log.error(errorMsg, e);
            metrics.setErrorMessage(errorMsg);
            // 设置默认值
            metrics.setCpuUsagePercent(0);
            metrics.setMemoryUsagePercent(0);
            metrics.setStorageUsageMB(0);
            metrics.setTotalDataPointsCount(0);
            metrics.setDataPointsCount(0);
        }

        return metrics;
    }

    /**
     * 使用VictoriaMetricsClient执行操作
     * 
     * @param request   请求参数
     * @param operation 操作类型
     * @param args      额外参数
     * @return 执行结果
     */
    public String executeScript(BenchmarkRequest request, String operation, String... args) {
        try {
            switch (operation) {
                case "health":
                    return victoriaMetricsClient.checkHealth();
                case "write":
                    if (args.length > 0) {
                        return victoriaMetricsClient.writeData(args[0]);
                    }
                    throw new IllegalArgumentException("写入操作需要数据参数");
                case "metrics":
                    return victoriaMetricsClient.getMetrics();
                case "count":
                    if (args.length > 0) {
                        String query = String.format("count(%s)", args[0]);
                        return victoriaMetricsClient.query(query);
                    }
                    throw new IllegalArgumentException("计数操作需要指标名称参数");
                case "delete":
                    if (args.length > 0) {
                        return victoriaMetricsClient.deleteSeries(args[0]);
                    }
                    throw new IllegalArgumentException("删除操作需要匹配模式参数");
                default:
                    throw new IllegalArgumentException("不支持的操作类型: " + operation);
            }
        } catch (Exception e) {
            log.error("执行操作失败: {}", e.getMessage());
            throw new RuntimeException("执行操作失败: " + e.getMessage());
        }
    }

    /**
     * 使用VictoriaMetricsClient删除数据
     * 
     * @param request 请求参数
     * @return 是否成功
     */
    public boolean deleteTestData(BenchmarkRequest request) {
        try {
            // 构建匹配模式，使用正确的格式
            String matchPattern = "{__name__=~\"" + request.getMetricPrefix() + ".+\"}";
            log.info("正在删除测试数据，匹配模式: {}", matchPattern);

            // 调用 VictoriaMetrics 客户端删除数据
            String response = victoriaMetricsClient.deleteSeries(matchPattern);
            log.info("删除测试数据响应: {}", response);

            // 等待1秒确保数据删除
            Thread.sleep(1000);

            // 验证数据是否已删除
            String queryResponse = victoriaMetricsClient.query(request.getMetricPrefix());
            log.info("删除后查询响应: {}", queryResponse);

            return true;
        } catch (Exception e) {
            log.error("删除测试数据失败", e);
            return false;
        }
    }

    /**
     * 使用VictoriaMetricsClient获取服务器指标
     * 
     * @param request 请求参数
     * @return 服务器指标
     */
    public ServerMetrics getServerMetrics(BenchmarkRequest request) {
        BenchmarkResult result = new BenchmarkResult();
        return collectServerMetrics(request, result);
    }
}