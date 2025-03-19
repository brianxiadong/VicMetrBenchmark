package com.brianxiadong.vicmetrbenchmark.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * VictoriaMetrics HTTP 客户端工具类
 * 用于向 VictoriaMetrics 发送各种 HTTP 请求进行测试
 */
@Slf4j
@Component
public class VictoriaMetricsClient {
    private static final Logger logger = LoggerFactory.getLogger(VictoriaMetricsClient.class);

    // 默认的 VictoriaMetrics 服务器地址
    private static final String DEFAULT_HOST = "172.36.100.38";
    private static final int DEFAULT_PORT = 8428;

    // HTTP 客户端配置
    private final OkHttpClient client;

    // 基础 URL
    private final String baseUrl;

    /**
     * 使用默认配置创建客户端
     */
    public VictoriaMetricsClient() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    /**
     * 使用自定义配置创建客户端
     *
     * @param host VictoriaMetrics 服务器地址
     * @param port VictoriaMetrics 服务器端口
     */
    public VictoriaMetricsClient(String host, int port) {
        this.baseUrl = String.format("http://%s:%d", host, port);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 检查服务健康状态
     *
     * @return 健康状态响应
     */
    public String checkHealth() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/health")
                .get()
                .build();

        return executeRequest(request);
    }

    /**
     * 删除指定匹配模式的数据
     *
     * @param matchPattern 匹配模式，例如 "benchmark_metric.+"
     * @return 删除操作响应
     */
    public String deleteSeries(String matchPattern) throws IOException {
        // 构建 URL 并添加查询参数
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/api/v1/admin/tsdb/delete_series").newBuilder();
        urlBuilder.addQueryParameter("match[]", matchPattern);

        // 创建请求
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .post(RequestBody.create(null, new byte[0])) // 使用空的请求体
                .build();

        return executeRequest(request);
    }

    /**
     * 执行 PromQL 查询
     *
     * @param query PromQL 查询语句
     * @return 查询结果
     */
    public String query(String query) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/api/v1/query").newBuilder();
        urlBuilder.addQueryParameter("query", query);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build();

        return executeRequest(request);
    }

    /**
     * 获取服务器指标
     *
     * @return 服务器指标数据
     */
    public String getMetrics() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/metrics")
                .get()
                .build();

        String response = executeRequest(request);

        // 添加详细的日志记录
        logger.info("VictoriaMetrics metrics response length: {}", response.length());
        logger.debug("VictoriaMetrics metrics response:\n{}", response);

        // 记录关键指标
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.contains("process_cpu_seconds_total") ||
                    line.contains("process_resident_memory_bytes") ||
                    line.contains("go_memstats_sys_bytes") ||
                    line.contains("vm_data_size_bytes") ||
                    line.contains("vm_rows_inserted_total")) {
                logger.info("Found key metric: {}", line);
            }
        }

        return response;
    }

    /**
     * 写入数据
     * 根据 apiType 选择正确的接口
     *
     * @param data    要写入的数据
     * @param apiType API类型：prometheus 或 influx
     * @return 写入操作响应
     */
    public String writeData(String data, String apiType) throws IOException {
        RequestBody body = RequestBody.create(
                MediaType.parse("text/plain"), data);

        // 根据 apiType 选择正确的接口
        String endpoint = "/api/v1/import/prometheus";
        if ("influx".equalsIgnoreCase(apiType)) {
            endpoint = "/api/v1/import/influx";
        }

        Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .post(body)
                .build();

        return executeRequest(request);
    }

    /**
     * 写入 Prometheus 格式的数据（向后兼容）
     *
     * @param data Prometheus 格式的数据
     * @return 写入操作响应
     */
    public String writeData(String data) throws IOException {
        return writeData(data, "prometheus");
    }

    /**
     * 查询总数据量
     * 使用 /api/v1/series/count 接口
     * 
     * @return 总数据量响应
     */
    public String queryTotalCount() throws IOException {
        Request request = new Request.Builder()
                .url(baseUrl + "/api/v1/series/count")
                .get()
                .build();

        return executeRequest(request);
    }

    /**
     * 执行 HTTP 请求并处理响应
     *
     * @param request HTTP 请求
     * @return 响应内容
     */
    private String executeRequest(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败: " + response.code());
            }
            return response.body().string();
        }
    }

    /**
     * 使用示例
     */
    public static void main(String[] args) {
        try {
            VictoriaMetricsClient client = new VictoriaMetricsClient();

            // 检查健康状态
            System.out.println("健康状态检查:");
            System.out.println(client.checkHealth());
            System.out.println();

            // 写入一些测试数据
            System.out.println("写入测试数据:");
            String testData = "benchmark_test_metric{test=\"delete_test\"} 100 " + System.currentTimeMillis() + "\n";
            System.out.println(client.writeData(testData));
            System.out.println();

            // 等待1秒确保数据写入
            Thread.sleep(1000);

            // 查询数据确认写入成功
            System.out.println("查询测试数据:");
            System.out.println(client.query("benchmark_test_metric"));
            System.out.println();

            // 删除测试数据
            System.out.println("删除测试数据:");
            String deleteResponse = client.deleteSeries("benchmark_test_metric");
            System.out.println("删除响应: " + deleteResponse);
            System.out.println();

            // 等待1秒确保数据删除
            Thread.sleep(1000);

            // 再次查询确认数据已删除
            System.out.println("确认数据已删除:");
            System.out.println(client.query("benchmark_test_metric"));
            System.out.println();

            // 获取服务器指标
            System.out.println("服务器指标:");
            System.out.println(client.getMetrics());

        } catch (Exception e) {
            logger.error("操作失败", e);
        }
    }
}