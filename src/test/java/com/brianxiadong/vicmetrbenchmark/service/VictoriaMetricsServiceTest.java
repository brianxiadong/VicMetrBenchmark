package com.brianxiadong.vicmetrbenchmark.service;

import com.brianxiadong.vicmetrbenchmark.VicMetrBenchmarkApplication;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.brianxiadong.vicmetrbenchmark.utils.VictoriaMetricsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * VictoriaMetricsService 的单元测试类
 * 
 * @author Brian Xia
 */
@SpringBootTest(classes = VicMetrBenchmarkApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class VictoriaMetricsServiceTest {

    @Autowired
    private VictoriaMetricsService victoriaMetricsService;

    @MockBean
    private VictoriaMetricsClient victoriaMetricsClient;

    private BenchmarkRequest benchmarkRequest;

    @BeforeEach
    void setUp() throws IOException {
        benchmarkRequest = new BenchmarkRequest();
        benchmarkRequest.setHost("localhost");
        benchmarkRequest.setPort(8428);
        benchmarkRequest.setDataCount(1000);
        benchmarkRequest.setBatchSize(100);
        benchmarkRequest.setConcurrency(10);
        benchmarkRequest.setMetricPrefix("test_metric");

        // Mock VictoriaMetricsClient responses
        when(victoriaMetricsClient.getMetrics()).thenReturn(
                "vm_app_version{version=\"v1.91.3\"} 1\n" +
                        "vm_rows{type=\"indexdb\"} 1000\n" +
                        "vm_data_size_bytes{type=\"storage/big\"} 1024\n" +
                        "vm_rows_inserted_total{type=\"prometheus\"} 2000");

        when(victoriaMetricsClient.query(any())).thenReturn("[]");
        when(victoriaMetricsClient.deleteSeries(any())).thenReturn("{}");
    }

    @Test
    void testCollectServerMetrics() {
        BenchmarkResult result = new BenchmarkResult();
        ServerMetrics metrics = victoriaMetricsService.collectServerMetrics(benchmarkRequest, result);

        assertNotNull(metrics);
        assertTrue(metrics.getCpuUsagePercent() >= 0);
        assertTrue(metrics.getMemoryUsagePercent() >= 0);
        assertTrue(metrics.getStorageUsageMB() >= 0);
        assertTrue(metrics.getDataPointsCount() >= 0);
    }

    @Test
    void testGetServerMetrics() {
        ServerMetrics metrics = victoriaMetricsService.getServerMetrics(benchmarkRequest);

        assertNotNull(metrics);
        assertTrue(metrics.getCpuUsagePercent() >= 0);
        assertTrue(metrics.getMemoryUsagePercent() >= 0);
        assertTrue(metrics.getStorageUsageMB() >= 0);
        assertTrue(metrics.getDataPointsCount() >= 0);
    }

    @Test
    void testQueryDataCount() {
        long count = victoriaMetricsService.queryDataCount(benchmarkRequest);
        assertTrue(count >= 0);
    }

    @Test
    void testDeleteTestData() {
        boolean success = victoriaMetricsService.deleteTestData(benchmarkRequest);
        assertTrue(success);
    }
}