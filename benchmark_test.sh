#!/bin/bash

# VictoriaMetrics压测脚本

# 参数检查
if [ $# -lt 6 ]; then
    echo "用法: $0 <host> <port> <指标前缀> <数据量> <批次大小> <并发数>"
    exit 1
fi

HOST=$1
PORT=$2
METRIC_PREFIX=$3
DATA_COUNT=$4
BATCH_SIZE=$5
CONCURRENCY=$6

# 设置通用连接超时
TIMEOUT=5
BASE_URL="http://${HOST}:${PORT}"

# 测试连接
echo "测试连接..."
response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 3 "${BASE_URL}/health")
if [ "$response" != "200" ]; then
    echo "连接失败! HTTP状态码: $response"
    exit 1
fi
echo "连接成功!"

# 准备结果变量
START_TIME=$(date +%s)
SUCCESS_COUNT=0
FAIL_COUNT=0
TOTAL_TIME=0
MIN_TIME=999999
MAX_TIME=0

# 创建临时工作目录
TEMP_DIR=$(mktemp -d)
echo "使用临时目录: $TEMP_DIR"

# 执行单个批次测试
run_batch() {
    local batch_id=$1
    local size=$2
    
    # 生成测试数据
    data=""
    timestamp=$(date +%s)
    
    for ((i=0; i<size; i++)); do
        # 确保每个指标名称唯一
        data="${data}${METRIC_PREFIX}{batch_id=\"${batch_id}\",index=\"${i}\",test_id=\"benchmark_test\"} $(( RANDOM % 100 )) $((timestamp + i))\n"
    done
    
    # 保存请求数据到临时文件（用于调试）
    echo -e "$data" > "${TEMP_DIR}/batch_${batch_id}_data.txt"
    
    # 使用time命令获取精确的执行时间（单位为秒）
    {
        time_output=$(TIMEFORMAT="%R"; { time \
            echo -e "$data" | curl -s -o "${TEMP_DIR}/batch_${batch_id}_response.txt" \
            --connect-timeout $TIMEOUT --max-time $TIMEOUT \
            -X POST -H "Content-Type: text/plain" \
            --data-binary @- \
            "${BASE_URL}/api/v1/import/prometheus"; } 2>&1)
        status=$?
        
        # 将时间转换为毫秒
        duration_ms=$(echo "$time_output" | awk '{print int($1 * 1000)}')
        
        # 输出请求状态码（用于调试）
        response_code=$(cat "${TEMP_DIR}/batch_${batch_id}_response.txt" | wc -c)
        echo "批次 ${batch_id} 状态: ${status}, 返回内容大小: ${response_code}" >> "${TEMP_DIR}/debug.log"
        
        # 记录结果
        if [ $status -eq 0 ]; then
            echo "$size $duration_ms" > "${TEMP_DIR}/result_${batch_id}.txt"
        else
            echo "0 $duration_ms" > "${TEMP_DIR}/result_${batch_id}.txt"
        fi
    } &> /dev/null
}

# 创建并行任务
for ((i=0; i<$DATA_COUNT; i+=$BATCH_SIZE)); do
    if [ $((i / $BATCH_SIZE % $CONCURRENCY)) -eq 0 ]; then
        # 等待前一轮并发任务完成
        wait
    fi
    
    # 处理最后一批可能不足BATCH_SIZE的情况
    current_batch_size=$BATCH_SIZE
    if [ $((i + $BATCH_SIZE)) -gt $DATA_COUNT ]; then
        current_batch_size=$(($DATA_COUNT - $i))
    fi
    
    echo "启动批次 $((i / $BATCH_SIZE)), 大小: $current_batch_size"
    run_batch $((i / $BATCH_SIZE)) $current_batch_size &
done

# 等待所有任务完成
wait
echo "所有批次执行完毕"

# 输出调试信息
if [ -f "${TEMP_DIR}/debug.log" ]; then
    echo "调试信息:"
    cat "${TEMP_DIR}/debug.log"
fi

# 统计结果
for f in ${TEMP_DIR}/result_*.txt; do
    if [ -f "$f" ]; then
        read success_count duration < "$f"
        SUCCESS_COUNT=$((SUCCESS_COUNT + success_count))
        FAIL_COUNT=$((FAIL_COUNT + $BATCH_SIZE - success_count))
        TOTAL_TIME=$((TOTAL_TIME + duration))
        
        if [ $success_count -gt 0 ]; then
            [ $duration -lt $MIN_TIME ] && MIN_TIME=$duration
            [ $duration -gt $MAX_TIME ] && MAX_TIME=$duration
        fi
    fi
done

END_TIME=$(date +%s)
ELAPSED_SECONDS=$((END_TIME - START_TIME))
if [ $ELAPSED_SECONDS -lt 1 ]; then
    ELAPSED_SECONDS=1
fi
TOTAL_ELAPSED=$((ELAPSED_SECONDS * 1000))

# 输出结果
echo ""
echo "压测结果:"
echo "- 总请求数: $((SUCCESS_COUNT + FAIL_COUNT))"
echo "- 成功请求数: $SUCCESS_COUNT"
echo "- 失败请求数: $FAIL_COUNT"
echo "- 总耗时(毫秒): $TOTAL_ELAPSED"

# 安全地计算平均响应时间
AVG_RESPONSE_TIME=0
if [ $SUCCESS_COUNT -gt 0 ]; then
    BATCH_COUNT=$((SUCCESS_COUNT / BATCH_SIZE))
    if [ $BATCH_COUNT -gt 0 ]; then
        AVG_RESPONSE_TIME=$((TOTAL_TIME / BATCH_COUNT))
    fi
fi
echo "- 批次平均响应时间(毫秒): $AVG_RESPONSE_TIME"

echo "- 最小批次响应时间(毫秒): $MIN_TIME"
echo "- 最大批次响应时间(毫秒): $MAX_TIME"

# 安全地计算QPS
QPS=0
if [ $TOTAL_ELAPSED -gt 0 ]; then
    QPS=$(( (SUCCESS_COUNT * 1000) / TOTAL_ELAPSED ))
fi
echo "- QPS: $QPS"

# 查询实际写入的数据量
echo ""
echo "查询写入的数据量..."
count=$(curl -s --connect-timeout 3 --max-time 5 "${BASE_URL}/api/v1/series?match[]=${METRIC_PREFIX}&start=$(($(date +%s) - 3600))&end=$(date +%s)" | grep -o '"__name__"' | wc -l)
echo "查询到 $count 条数据"

# 显示第一批请求的详细信息（用于调试）
echo ""
echo "第一批请求数据示例:"
if [ -f "${TEMP_DIR}/batch_0_data.txt" ]; then
    head -n 3 "${TEMP_DIR}/batch_0_data.txt"
fi

# 清理临时目录
rm -rf $TEMP_DIR
echo "测试完成!" 