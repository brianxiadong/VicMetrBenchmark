package com.brianxiadong.vicmetrbenchmark.controller;

import com.brianxiadong.vicmetrbenchmark.model.BenchmarkRequest;
import com.brianxiadong.vicmetrbenchmark.model.BenchmarkResult;
import com.brianxiadong.vicmetrbenchmark.model.ServerMetrics;
import com.brianxiadong.vicmetrbenchmark.service.VictoriaMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 压测控制器
 * 处理前端的压测请求
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkController.class);
    private final VictoriaMetricsService victoriaMetricsService;

    @Autowired
    public BenchmarkController(VictoriaMetricsService victoriaMetricsService) {
        this.victoriaMetricsService = victoriaMetricsService;
    }

    /**
     * 运行压测
     * 
     * @param request 压测请求参数
     * @return 压测结果
     */
    @PostMapping("/run")
    public ResponseEntity<BenchmarkResult> runBenchmark(@RequestBody BenchmarkRequest request) {
        log.info("收到压测请求: {}", request);
        try {
            BenchmarkResult result = victoriaMetricsService.runBenchmark(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("压测执行失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 查询服务器指标
     * 
     * @param request 包含服务器信息的请求
     * @return 服务器指标
     */
    @PostMapping("/metrics")
    public ResponseEntity<ServerMetrics> getServerMetrics(@RequestBody BenchmarkRequest request) {
        log.info("收到查询服务器指标请求: {}", request);
        try {
            ServerMetrics metrics = victoriaMetricsService.getServerMetrics(request);
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("查询服务器指标失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 查询数据总量
     * 
     * @param request 包含服务器信息的请求
     * @return 数据总量
     */
    @PostMapping("/data-count")
    public ResponseEntity<Long> getDataCount(@RequestBody BenchmarkRequest request) {
        log.info("收到查询数据总量请求: {}", request);
        try {
            long count = victoriaMetricsService.queryDataCount(request);
            return ResponseEntity.ok(count);
        } catch (Exception e) {
            log.error("查询数据总量失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除测试数据
     * 
     * @param request 包含服务器信息的请求
     * @return 操作结果
     */
    @PostMapping("/delete-data")
    public ResponseEntity<Boolean> deleteTestData(@RequestBody BenchmarkRequest request) {
        log.info("收到删除测试数据请求: {}", request);
        try {
            boolean success = victoriaMetricsService.deleteTestData(request);
            return ResponseEntity.ok(success);
        } catch (Exception e) {
            log.error("删除测试数据失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}