package com.brianxiadong.vicmetrbenchmark.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 查询测试结果模型类
 * 用于存储每轮查询测试的详细信息
 */
@Data
public class QueryTestResult {
    // 测试ID
    private String testId;

    // 测试时间
    private LocalDateTime testTime;

    // 测试轮次
    private int roundNumber;

    // 总数据量
    private long totalDataPoints;

    // 查询耗时（毫秒）
    private long queryTimeMillis;

    // 服务器CPU使用率
    private double cpuUsagePercent;

    // 服务器内存使用率
    private double memoryUsagePercent;

    // 服务器存储使用量（MB）
    private double storageUsageMB;

    // 测试描述（可选）
    private String description;

    // 测试状态（成功/失败）
    private String status;

    // 错误信息（如果有）
    private String errorMessage;
}