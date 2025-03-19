/**
 * VictoriaMetrics压测系统前端JS
 * 实现前端交互逻辑、数据处理和API调用
 */
$(document).ready(function () {
    // 全局对象，存储当前的配置信息
    const config = {
        host: '172.36.100.38',
        port: 8428,
        dataCount: 10000,
        batchSize: 1000,
        concurrency: 4,
        metricPrefix: 'benchmark_metric',
        apiType: 'prometheus'
    };

    // 全局变量，存储压测状态
    let benchmarkInProgress = false;
    let progressInterval = null;
    let progressUpdateInterval = 2000; // 每2秒更新一次进度

    // API路径
    const API = {
        RUN_BENCHMARK: '/api/benchmark/run',
        GET_METRICS: '/api/benchmark/metrics',
        GET_DATA_COUNT: '/api/benchmark/data-count',
        DELETE_DATA: '/api/benchmark/delete-data',
        TEST_CONNECTION: '/api/benchmark/test-connection',
        EXECUTE_QUERY: '/api/benchmark/query',
        CHECK_HEALTH: '/api/benchmark/health'
    };

    // 初始化事件绑定
    function init() {
        // 测试连接按钮
        $('#testConnection').on('click', testConnection);

        // 健康检查按钮
        $('#checkHealth').on('click', checkHealth);

        // 执行查询按钮
        $('#executeQuery').on('click', executeQuery);

        // 开始压测按钮
        $('#startBenchmark').on('click', startBenchmark);

        // 刷新指标按钮
        $('#refreshMetrics').on('click', function () {
            refreshMetrics();
        });

        // 删除测试数据按钮
        $('#deleteData').on('click', deleteTestData);

        // 查询测试数据总量按钮
        $('#queryTestDataCount').on('click', function () {
            const $btn = $(this);
            $btn.prop('disabled', true).text('查询中...');

            // 显示加载状态
            $('#testDataCount').text('加载中...');

            // 获取当前配置的指标前缀
            const metricPrefix = $('#metricPrefix').val();
            if (!metricPrefix) {
                alert('请先设置指标名称前缀');
                $btn.prop('disabled', false).text('查询测试数据总量');
                return;
            }

            // 获取测试数据总量
            $.ajax({
                url: API.GET_DATA_COUNT,
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    ...config,
                    metricPrefix: metricPrefix
                }),
                success: function (response) {
                    if (response.success === true) {
                        const count = response.count || 0;
                        $('#testDataCount').text(count.toLocaleString());

                        // 在数据查询结果区域显示详细信息
                        $('#dataCount').html(`
                            <div class="alert alert-success">
                                <h4>数据总量查询结果</h4>
                                <p>当前指标前缀 "${metricPrefix}" 下的数据总量：<strong>${count.toLocaleString()}</strong> 条</p>
                                <p>查询时间：${new Date().toLocaleString()}</p>
                            </div>
                        `);
                    } else {
                        $('#testDataCount').text('查询失败');
                        $('#dataCount').html(`
                            <div class="alert alert-danger">
                                <h4>查询失败</h4>
                                <p>${response.error || '未知错误'}</p>
                            </div>
                        `);
                    }
                },
                error: function () {
                    $('#testDataCount').text('查询失败');
                    $('#dataCount').html(`
                        <div class="alert alert-danger">
                            <h4>查询失败</h4>
                            <p>请检查服务器连接状态</p>
                        </div>
                    `);
                },
                complete: function () {
                    $btn.prop('disabled', false).text('查询测试数据总量');
                }
            });
        });

        // 读取表单的默认值
        $('#host').val() && (config.host = $('#host').val());
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

        // 使用新的轻量级测试连接接口
        $.ajax({
            url: API.TEST_CONNECTION,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                host: host,
                port: parseInt(port)
            }),
            success: function (response) {
                if (response.success === true) {
                    showConnectionStatus('连接成功！', true);
                    updateConfig();
                    // 更新数据量显示
                    getDataCount();
                } else {
                    let errorMsg = '连接失败，请检查IP和端口';
                    if (response.message) {
                        errorMsg = response.message;
                    } else if (response.error) {
                        errorMsg = response.error;
                    }
                    showConnectionStatus(errorMsg, false);
                }
            },
            error: function () {
                showConnectionStatus('连接失败，请检查IP和端口', false);
            }
        });
    }

    // 健康检查
    function checkHealth() {
        updateConfig();

        if (!config.host || !config.port) {
            alert('请先配置服务器信息');
            return;
        }

        // 显示按钮状态
        $('#checkHealth').prop('disabled', true).text('检查中...');

        $.ajax({
            url: API.CHECK_HEALTH,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (response) {
                // 还原按钮状态
                $('#checkHealth').prop('disabled', false).text('健康检查');

                if (response.success === true) {
                    showConnectionStatus('服务器健康状态: ' + response.status, true);
                } else {
                    let errorMsg = '健康检查失败';
                    if (response.error) {
                        errorMsg = response.error;
                    }
                    showConnectionStatus(errorMsg, false);
                }
            },
            error: function () {
                // 还原按钮状态
                $('#checkHealth').prop('disabled', false).text('健康检查');
                showConnectionStatus('健康检查失败，请检查配置或服务器状态', false);
            }
        });
    }

    // 执行查询
    function executeQuery() {
        updateConfig();

        const queryStatement = $('#queryStatement').val();
        if (!queryStatement) {
            alert('请输入查询语句');
            return;
        }

        // 显示按钮状态
        $('#executeQuery').prop('disabled', true).text('查询中...');

        $.ajax({
            url: API.EXECUTE_QUERY,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                ...config,
                metricPrefix: queryStatement
            }),
            success: function (response) {
                // 还原按钮状态
                $('#executeQuery').prop('disabled', false).text('执行查询');

                if (response.success === true) {
                    // 格式化并显示查询结果
                    const result = response.result;
                    let formattedResult = '';
                    try {
                        const jsonResult = JSON.parse(result);
                        formattedResult = JSON.stringify(jsonResult, null, 2);
                    } catch (e) {
                        formattedResult = result;
                    }

                    $('#queryResult').html(`
                        <div class="alert alert-success">
                            <h4>查询结果</h4>
                            <pre class="mt-3">${formattedResult}</pre>
                        </div>
                    `);
                } else {
                    let errorMsg = '查询执行失败';
                    if (response.error) {
                        errorMsg = response.error;
                    }
                    $('#queryResult').html(`
                        <div class="alert alert-danger">
                            <h4>错误</h4>
                            <p>${errorMsg}</p>
                        </div>
                    `);
                }
            },
            error: function () {
                // 还原按钮状态
                $('#executeQuery').prop('disabled', false).text('执行查询');
                $('#queryResult').html(`
                    <div class="alert alert-danger">
                        <h4>错误</h4>
                        <p>查询执行失败，请检查配置或服务器状态</p>
                    </div>
                `);
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

        // 显示进度条并设置初始值
        $('#benchmarkProgress').show();
        $('#benchmarkProgressBar').css('width', '0%').attr('aria-valuenow', 0).text('0%');

        // 禁用开始按钮
        $('#startBenchmark').prop('disabled', true).text('压测进行中...');

        // 清空之前的结果
        clearResults();

        // 设置压测状态为进行中
        benchmarkInProgress = true;

        // 启动定时器，定期更新进度条和数据量
        startProgressUpdates();

        // 发送压测请求
        $.ajax({
            url: API.RUN_BENCHMARK,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (result) {
                // 停止进度更新
                stopProgressUpdates();

                // 完成进度条
                updateProgressBar(100);

                // 隐藏进度条（延迟一会，让用户看到100%）
                setTimeout(function () {
                    $('#benchmarkProgress').hide();
                }, 1000);

                // 还原按钮状态
                $('#startBenchmark').prop('disabled', false).text('开始压测');

                // 压测状态设为完成
                benchmarkInProgress = false;

                // 显示结果
                displayResults(result);

                // 刷新指标
                refreshMetrics();
            },
            error: function (xhr) {
                // 停止进度更新
                stopProgressUpdates();

                // 隐藏进度条
                $('#benchmarkProgress').hide();

                // 还原按钮状态
                $('#startBenchmark').prop('disabled', false).text('开始压测');

                // 压测状态设为完成
                benchmarkInProgress = false;

                // 显示错误
                alert('压测执行失败，请检查配置或服务器状态');
                console.error('压测失败', xhr);
            }
        });
    }

    // 开始定时更新进度
    function startProgressUpdates() {
        let startTime = new Date().getTime();
        let estimatedDuration = estimateBenchmarkDuration();

        // 清除可能存在的旧计时器
        stopProgressUpdates();

        // 设置新的计时器
        progressInterval = setInterval(function () {
            // 更新进度条
            let currentTime = new Date().getTime();
            let elapsedTime = currentTime - startTime;
            let percentComplete = Math.min(95, (elapsedTime / estimatedDuration) * 100);

            updateProgressBar(percentComplete);

            // 查询当前数据量
            getDataCount();
        }, progressUpdateInterval);
    }

    // 停止进度更新
    function stopProgressUpdates() {
        if (progressInterval) {
            clearInterval(progressInterval);
            progressInterval = null;
        }
    }

    // 更新进度条
    function updateProgressBar(percent) {
        percent = Math.round(percent);
        $('#benchmarkProgressBar')
            .css('width', percent + '%')
            .attr('aria-valuenow', percent)
            .text(percent + '%');
    }

    // 估算压测时间（毫秒）
    function estimateBenchmarkDuration() {
        // 根据数据量和并发数估算
        // 这只是一个简单的估算，可以根据实际情况调整
        const baseTime = 2000; // 基础时间2秒
        const dataFactor = config.dataCount / 1000; // 数据量因子
        const concurrencyFactor = Math.max(1, config.concurrency) / 2; // 并发因子

        return baseTime + (dataFactor * 500 / concurrencyFactor);
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

        // 显示加载状态
        $('#cpuUsage').text('加载中...');
        $('#memoryUsage').text('加载中...');
        $('#storageUsage').text('加载中...');
        $('#currentDataCount').text('加载中...');
        $('#refreshMetrics').prop('disabled', true).text('刷新中...');

        // 获取服务器指标
        $.ajax({
            url: API.GET_METRICS,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function (metrics) {
                $('#cpuUsage').text(metrics.cpuUsagePercent.toFixed(2) + '%');
                $('#memoryUsage').text(metrics.memoryUsagePercent.toFixed(2) + '%');
                $('#storageUsage').text(metrics.storageUsageMB.toFixed(2) + ' MB');

                // 同时获取所有数据量
                $.ajax({
                    url: API.GET_DATA_COUNT,
                    type: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify({
                        ...config,
                        metricPrefix: '*'  // 使用通配符获取所有数据量
                    }),
                    success: function (response) {
                        if (response.success === true) {
                            const count = response.count || 0;
                            $('#currentDataCount').text(count.toLocaleString());
                        } else {
                            $('#currentDataCount').text('查询失败');
                        }
                    },
                    error: function () {
                        $('#currentDataCount').text('查询失败');
                    },
                    complete: function () {
                        $('#refreshMetrics').prop('disabled', false).text('刷新指标和数据量');
                    }
                });
            },
            error: function () {
                $('#cpuUsage').text('获取失败');
                $('#memoryUsage').text('获取失败');
                $('#storageUsage').text('获取失败');
                $('#currentDataCount').text('获取失败');
                $('#refreshMetrics').prop('disabled', false).text('刷新指标和数据量');
            }
        });
    }

    // 获取当前数据总量
    function getDataCount(showLoading = false) {
        updateConfig();

        if (!config.host || !config.port) {
            return;
        }

        // 如果是手动查询，显示加载状态
        if (showLoading) {
            $('#currentDataCount').text('查询中...');
            $('#queryTestDataCount').prop('disabled', true);
        }

        // 获取当前配置的指标前缀
        const metricPrefix = $('#metricPrefix').val();
        if (!metricPrefix) {
            alert('请先设置指标名称前缀');
            if (showLoading) {
                $('#queryTestDataCount').prop('disabled', false);
            }
            return;
        }

        $.ajax({
            url: API.GET_DATA_COUNT,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                ...config,
                metricPrefix: metricPrefix
            }),
            success: function (response) {
                // 处理新的响应格式
                if (response.success === true) {
                    const count = response.count || 0;
                    $('#currentDataCount').text(count.toLocaleString());

                    // 如果是手动查询，恢复按钮状态
                    if (showLoading) {
                        $('#queryTestDataCount').prop('disabled', false);
                    }

                    // 如果正在压测，更新数据进度
                    if (benchmarkInProgress && config.dataCount > 0) {
                        let dataPercent = Math.min(100, (count / config.dataCount) * 100);
                        if (dataPercent > 0) {
                            updateProgressBar(dataPercent);
                        }
                    }

                    // 在数据查询结果区域显示详细信息
                    $('#dataCount').html(`
                        <div class="alert alert-success">
                            <h4>数据总量查询结果</h4>
                            <p>当前指标前缀 "${metricPrefix}" 下的数据总量：<strong>${count.toLocaleString()}</strong> 条</p>
                            <p>查询时间：${new Date().toLocaleString()}</p>
                        </div>
                    `);
                } else {
                    // 查询失败
                    $('#currentDataCount').text('查询失败');

                    if (showLoading) {
                        $('#queryTestDataCount').prop('disabled', false);
                        let errorMsg = '查询数据量失败';
                        if (response.error) {
                            errorMsg = response.error;
                        }
                        alert(errorMsg);
                    }

                    // 在数据查询结果区域显示错误信息
                    $('#dataCount').html(`
                        <div class="alert alert-danger">
                            <h4>查询失败</h4>
                            <p>${response.error || '未知错误'}</p>
                        </div>
                    `);
                }
            },
            error: function () {
                console.error('获取数据总量失败');

                // 如果是手动查询，恢复按钮状态并显示错误
                if (showLoading) {
                    $('#currentDataCount').text('查询失败');
                    $('#queryTestDataCount').prop('disabled', false);
                }

                // 在数据查询结果区域显示错误信息
                $('#dataCount').html(`
                    <div class="alert alert-danger">
                        <h4>查询失败</h4>
                        <p>请检查服务器连接状态</p>
                    </div>
                `);
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
            success: function (response) {
                // 还原按钮状态
                $('#deleteData').prop('disabled', false).text('删除测试数据');

                if (response.success === true) {
                    alert('测试数据已成功删除');
                    // 刷新数据量显示
                    getDataCount();
                } else {
                    let errorMsg = '删除测试数据失败';
                    if (response.error) {
                        errorMsg = response.error;
                    } else if (response.message) {
                        errorMsg = response.message;
                    }
                    alert(errorMsg);
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