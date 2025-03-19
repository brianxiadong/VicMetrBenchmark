/**
 * VictoriaMetrics压测系统前端JS
 * 实现前端交互逻辑、数据处理和API调用
 */
$(document).ready(function () {
    // 全局对象，存储当前的配置信息
    const config = {
        host: '',
        port: 8428,
        dataCount: 10000,
        batchSize: 1000,
        concurrency: 4,
        metricPrefix: 'benchmark_metric',
        apiType: 'prometheus'
    };

    // API路径
    const API = {
        RUN_BENCHMARK: '/api/benchmark/run',
        GET_METRICS: '/api/benchmark/metrics',
        GET_DATA_COUNT: '/api/benchmark/data-count',
        DELETE_DATA: '/api/benchmark/delete-data'
    };

    // 初始化事件绑定
    function init() {
        // 测试连接按钮
        $('#testConnection').on('click', testConnection);

        // 开始压测按钮
        $('#startBenchmark').on('click', startBenchmark);

        // 刷新指标按钮
        $('#refreshMetrics').on('click', refreshMetrics);

        // 删除测试数据按钮
        $('#deleteData').on('click', deleteTestData);

        // 读取表单的默认值
        $('#port').val() && (config.port = parseInt($('#port').val()));
        $('#dataCount').val() && (config.dataCount = parseInt($('#dataCount').val()));
        $('#batchSize').val() && (config.batchSize = parseInt($('#batchSize').val()));
        $('#concurrency').val() && (config.concurrency = parseInt($('#concurrency').val()));
        $('#metricPrefix').val() && (config.metricPrefix = $('#metricPrefix').val());
        $('#apiType').val() && (config.apiType = $('#apiType').val());
    }

    // 更新配置信息
    function updateConfig() {
        config.host = $('#host').val();
        config.port = parseInt($('#port').val());
        config.dataCount = parseInt($('#dataCount').val());
        config.batchSize = parseInt($('#batchSize').val());
        config.concurrency = parseInt($('#concurrency').val());
        config.metricPrefix = $('#metricPrefix').val();
        config.apiType = $('#apiType').val();
    }

    // 测试与VictoriaMetrics的连接
    function testConnection() {
        const host = $('#host').val();
        const port = $('#port').val();

        if (!host || !port) {
            showConnectionStatus('请输入主机IP和端口', false);
            return;
        }

        showConnectionStatus('测试连接中...', null);

        // 构建测试URL，通常VictoriaMetrics的健康检查接口是/health
        const url = `http://${host}:${port}/health`;

        // 使用JSONP方式进行跨域请求，或使用代理
        // 这里使用了后端代理方式，将请求发送给我们的服务
        $.ajax({
            url: API.GET_METRICS,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                host: host,
                port: parseInt(port)
            }),
            success: function (data) {
                showConnectionStatus('连接成功！', true);
                updateConfig();
                // 更新数据量显示
                getDataCount();
            },
            error: function () {
                showConnectionStatus('连接失败，请检查IP和端口', false);
            }
        });
    }

    // 显示连接状态
    function showConnectionStatus(message, isSuccess) {
        const statusEl = $('#connectionStatus');
        statusEl.removeClass('success error');

        if (isSuccess === true) {
            statusEl.addClass('success');
        } else if (isSuccess === false) {
            statusEl.addClass('error');
        }

        statusEl.text(message);
    }

    // 开始压测
    function startBenchmark() {
        updateConfig();

        if (!validateConfig()) {
            return;
        }

        // 显示进度条
        $('#benchmarkProgress').show();

        // 禁用开始按钮
        $('#startBenchmark').prop('disabled', true).text('压测进行中...');

        // 清空之前的结果
        clearResults();

        // 发送压测请求
        $.ajax({
            url: API.RUN_BENCHMARK,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (result) {
                // 隐藏进度条
                $('#benchmarkProgress').hide();

                // 还原按钮状态
                $('#startBenchmark').prop('disabled', false).text('开始压测');

                // 显示结果
                displayResults(result);

                // 刷新指标
                refreshMetrics();
            },
            error: function (xhr) {
                // 隐藏进度条
                $('#benchmarkProgress').hide();

                // 还原按钮状态
                $('#startBenchmark').prop('disabled', false).text('开始压测');

                // 显示错误
                alert('压测执行失败，请检查配置或服务器状态');
                console.error('压测失败', xhr);
            }
        });
    }

    // 验证配置
    function validateConfig() {
        if (!config.host) {
            alert('请输入主机IP');
            return false;
        }

        if (!config.port || config.port <= 0) {
            alert('请输入有效的端口号');
            return false;
        }

        if (!config.dataCount || config.dataCount <= 0) {
            alert('请输入有效的数据总量');
            return false;
        }

        if (!config.batchSize || config.batchSize <= 0) {
            alert('请输入有效的批次大小');
            return false;
        }

        if (!config.concurrency || config.concurrency <= 0) {
            alert('请输入有效的并发线程数');
            return false;
        }

        return true;
    }

    // 清空结果显示
    function clearResults() {
        $('#totalRequests').text('-');
        $('#successRequests').text('-');
        $('#failedRequests').text('-');
        $('#totalTime').text('-');
        $('#avgResponseTime').text('-');
        $('#minResponseTime').text('-');
        $('#maxResponseTime').text('-');
        $('#dataPointsCount').text('-');
        $('#cpuUsage').text('-');
        $('#memoryUsage').text('-');
        $('#storageUsage').text('-');
    }

    // 显示压测结果
    function displayResults(result) {
        $('#totalRequests').text(result.totalRequests);
        $('#successRequests').text(result.successRequests);
        $('#failedRequests').text(result.failedRequests);
        $('#totalTime').text(formatTime(result.totalTimeMillis));
        $('#avgResponseTime').text(formatTime(result.avgResponseTimeMillis));
        $('#minResponseTime').text(formatTime(result.minResponseTimeMillis));
        $('#maxResponseTime').text(formatTime(result.maxResponseTimeMillis));
        $('#dataPointsCount').text(result.dataPointsCount);
        $('#cpuUsage').text(result.cpuUsagePercent.toFixed(2) + '%');
        $('#memoryUsage').text(result.memoryUsagePercent.toFixed(2) + '%');
        $('#storageUsage').text(result.storageUsageMB.toFixed(2) + ' MB');
    }

    // 格式化时间显示
    function formatTime(millis) {
        if (millis < 1000) {
            return millis.toFixed(2) + ' ms';
        } else {
            return (millis / 1000).toFixed(2) + ' s';
        }
    }

    // 刷新服务器指标
    function refreshMetrics() {
        updateConfig();

        if (!config.host || !config.port) {
            return;
        }

        // 获取服务器指标
        $.ajax({
            url: API.GET_METRICS,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (metrics) {
                $('#cpuUsage').text(metrics.cpuUsagePercent.toFixed(2) + '%');
                $('#memoryUsage').text(metrics.memoryUsagePercent.toFixed(2) + '%');
                $('#storageUsage').text(metrics.vmDataSizeMB.toFixed(2) + ' MB');

                // 同时更新数据量
                getDataCount();
            },
            error: function () {
                console.error('获取服务器指标失败');
            }
        });
    }

    // 获取当前数据总量
    function getDataCount() {
        updateConfig();

        if (!config.host || !config.port) {
            return;
        }

        $.ajax({
            url: API.GET_DATA_COUNT,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (count) {
                $('#currentDataCount').text(count);
            },
            error: function () {
                console.error('获取数据总量失败');
            }
        });
    }

    // 删除测试数据
    function deleteTestData() {
        updateConfig();

        if (!config.host || !config.port) {
            alert('请先配置服务器信息');
            return;
        }

        if (!confirm('确定要删除所有测试数据吗？')) {
            return;
        }

        // 显示按钮状态
        $('#deleteData').prop('disabled', true).text('删除中...');

        $.ajax({
            url: API.DELETE_DATA,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (result) {
                // 还原按钮状态
                $('#deleteData').prop('disabled', false).text('删除测试数据');

                if (result === true) {
                    alert('测试数据已成功删除');
                    // 刷新数据量显示
                    getDataCount();
                } else {
                    alert('删除测试数据失败');
                }
            },
            error: function () {
                // 还原按钮状态
                $('#deleteData').prop('disabled', false).text('删除测试数据');
                alert('删除测试数据失败，请检查配置或服务器状态');
            }
        });
    }

    // 初始化
    init();
}); 