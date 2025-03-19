package com.brianxiadong.vicmetrbenchmark.model;

import lombok.Data;

/**
 * 压测请求参数类
 * 用于接收前端传来的压测参数
 */
@Data
public class TestRequest {

    /**
     * VictoriaMetrics主机地址
     */
    private String host;

    /**
     * VictoriaMetrics端口号
     */
    private Integer port;

    /**
     * 要测试的数据量
     */
    private Integer dataCount;

    /**
     * 压测的指标名称前缀
     */
    private String metricPrefix = "benchmark_test";

    /**
     * 每批次写入的数据量
     */
    private Integer batchSize = 1000;

    /**
     * 并发线程数
     */
    private Integer concurrency = 1;
}