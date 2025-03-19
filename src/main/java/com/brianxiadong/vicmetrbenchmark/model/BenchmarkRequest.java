package com.brianxiadong.vicmetrbenchmark.model;

import lombok.Data;

/**
 * 压测请求参数模型类
 * 用于接收前端传递的压测参数
 */
@Data
public class BenchmarkRequest {

    /**
     * VictoriaMetrics主机IP
     */
    private String host;

    /**
     * VictoriaMetrics端口
     */
    private Integer port;

    /**
     * 写入数据的总数量
     */
    private Integer dataCount;

    /**
     * 每批次写入的数据量
     */
    private Integer batchSize = 1000;

    /**
     * 并发线程数
     */
    private Integer concurrency = 1;

    /**
     * 指标名称前缀
     */
    private String metricPrefix = "benchmark_metric";

    /**
     * 写入接口类型：prometheus, influx, 等
     */
    private String apiType = "prometheus";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Integer getDataCount() {
        return dataCount;
    }

    public void setDataCount(Integer dataCount) {
        this.dataCount = dataCount;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
    }

    public String getMetricPrefix() {
        return metricPrefix;
    }

    public void setMetricPrefix(String metricPrefix) {
        this.metricPrefix = metricPrefix;
    }

    public String getApiType() {
        return apiType;
    }

    public void setApiType(String apiType) {
        this.apiType = apiType;
    }
}