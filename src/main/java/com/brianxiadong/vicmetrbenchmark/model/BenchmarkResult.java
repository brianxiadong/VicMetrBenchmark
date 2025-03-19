package com.brianxiadong.vicmetrbenchmark.model;

/**
 * 压测结果模型类
 * 用于存储压测的结果数据
 */
public class BenchmarkResult {

    /**
     * 总请求数
     */
    private long totalRequests;

    /**
     * 成功请求数
     */
    private long successRequests;

    /**
     * 失败请求数
     */
    private long failedRequests;

    /**
     * 总耗时（毫秒）
     */
    private long totalTimeMillis;

    /**
     * 平均响应时间（毫秒）
     */
    private double avgResponseTimeMillis;

    /**
     * 最小响应时间（毫秒）
     */
    private long minResponseTimeMillis;

    /**
     * 最大响应时间（毫秒）
     */
    private long maxResponseTimeMillis;

    /**
     * 写入的数据点数量
     */
    private long dataPointsCount;

    /**
     * CPU使用率（百分比）
     */
    private double cpuUsagePercent;

    /**
     * 内存使用率（百分比）
     */
    private double memoryUsagePercent;

    /**
     * 存储空间使用（MB）
     */
    private double storageUsageMB;

    /**
     * 测试开始时间戳
     */
    private long startTimestamp;

    /**
     * 测试结束时间戳
     */
    private long endTimestamp;

    public long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public long getSuccessRequests() {
        return successRequests;
    }

    public void setSuccessRequests(long successRequests) {
        this.successRequests = successRequests;
    }

    public long getFailedRequests() {
        return failedRequests;
    }

    public void setFailedRequests(long failedRequests) {
        this.failedRequests = failedRequests;
    }

    public long getTotalTimeMillis() {
        return totalTimeMillis;
    }

    public void setTotalTimeMillis(long totalTimeMillis) {
        this.totalTimeMillis = totalTimeMillis;
    }

    public double getAvgResponseTimeMillis() {
        return avgResponseTimeMillis;
    }

    public void setAvgResponseTimeMillis(double avgResponseTimeMillis) {
        this.avgResponseTimeMillis = avgResponseTimeMillis;
    }

    public long getMinResponseTimeMillis() {
        return minResponseTimeMillis;
    }

    public void setMinResponseTimeMillis(long minResponseTimeMillis) {
        this.minResponseTimeMillis = minResponseTimeMillis;
    }

    public long getMaxResponseTimeMillis() {
        return maxResponseTimeMillis;
    }

    public void setMaxResponseTimeMillis(long maxResponseTimeMillis) {
        this.maxResponseTimeMillis = maxResponseTimeMillis;
    }

    public long getDataPointsCount() {
        return dataPointsCount;
    }

    public void setDataPointsCount(long dataPointsCount) {
        this.dataPointsCount = dataPointsCount;
    }

    public double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public void setCpuUsagePercent(double cpuUsagePercent) {
        this.cpuUsagePercent = cpuUsagePercent;
    }

    public double getMemoryUsagePercent() {
        return memoryUsagePercent;
    }

    public void setMemoryUsagePercent(double memoryUsagePercent) {
        this.memoryUsagePercent = memoryUsagePercent;
    }

    public double getStorageUsageMB() {
        return storageUsageMB;
    }

    public void setStorageUsageMB(double storageUsageMB) {
        this.storageUsageMB = storageUsageMB;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public void setStartTimestamp(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    public long getEndTimestamp() {
        return endTimestamp;
    }

    public void setEndTimestamp(long endTimestamp) {
        this.endTimestamp = endTimestamp;
    }
}