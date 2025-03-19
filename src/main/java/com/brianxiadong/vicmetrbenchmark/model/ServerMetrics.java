package com.brianxiadong.vicmetrbenchmark.model;

import lombok.Data;

/**
 * 服务器指标模型类
 * 用于存储服务器的CPU、内存、磁盘等资源使用情况
 */
@Data
public class ServerMetrics {

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
     * 存储空间总量（MB）
     */
    private double totalStorageMB;

    /**
     * VictoriaMetrics数据所占空间（MB）
     */
    private double vmDataSizeMB;

    /**
     * VictoriaMetrics平均查询延迟（毫秒）
     */
    private double vmQueryLatencyMs;

    /**
     * 数据点总数（按前缀）
     */
    private long dataPointsCount;

    /**
     * 总数据点数量（所有指标）
     */
    private long totalDataPointsCount;

    /**
     * 错误信息
     */
    private String errorMessage;

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

    public double getTotalStorageMB() {
        return totalStorageMB;
    }

    public void setTotalStorageMB(double totalStorageMB) {
        this.totalStorageMB = totalStorageMB;
    }

    public double getVmDataSizeMB() {
        return vmDataSizeMB;
    }

    public void setVmDataSizeMB(double vmDataSizeMB) {
        this.vmDataSizeMB = vmDataSizeMB;
    }

    public double getVmQueryLatencyMs() {
        return vmQueryLatencyMs;
    }

    public void setVmQueryLatencyMs(double vmQueryLatencyMs) {
        this.vmQueryLatencyMs = vmQueryLatencyMs;
    }

    public long getDataPointsCount() {
        return dataPointsCount;
    }

    public void setDataPointsCount(long dataPointsCount) {
        this.dataPointsCount = dataPointsCount;
    }

    public long getTotalDataPointsCount() {
        return totalDataPointsCount;
    }

    public void setTotalDataPointsCount(long totalDataPointsCount) {
        this.totalDataPointsCount = totalDataPointsCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}