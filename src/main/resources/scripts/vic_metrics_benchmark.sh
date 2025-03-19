#!/bin/bash

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

# 设置通用连接超时（单位：秒）
TIMEOUT=5
BASE_URL="http://${HOST}:${PORT}"

# 测试连接
echo "测试连接..."
response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout $TIMEOUT --max-time $TIMEOUT "${BASE_URL}/health")
if [ "$response" != "200" ]; then
    echo "连接失败! HTTP状态码: $response"
    exit 1
fi
echo "连接成功!"

# 准备结果变量
START_TIME=$(date +%s%3N)  # 毫秒级时间戳
SUCCESS_COUNT=0
FAIL_COUNT=0
TOTAL_TIME=0
MIN_TIME=999999
MAX_TIME=0

# 创建临时工作目录
TEMP_DIR=$(mktemp -d)
echo "使用临时目录: $TEMP_DIR"

# 生成并行任务函数
generate_tasks() {
    local thread_id=$1
    local batches_per_thread=$2
    
    # 创建任务脚本
    cat > ${TEMP_DIR}/task_${thread_id}.sh << EOF
#!/bin/bash
success=0
fail=0
time_sum=0
time_min=999999
time_max=0

for ((j=0; j<${batches_per_thread}; j++)); do
    remaining=$((${DATA_COUNT} - (${thread_id} * ${batches_per_thread} + j) * ${BATCH_SIZE}))
    if [ \$remaining -le 0 ]; then
        break
    fi
    
    current_batch_size=\$([ \$remaining -lt ${BATCH_SIZE} ] && echo \$remaining || echo ${BATCH_SIZE})
    
    # 生成数据
    data=""
    timestamp=\$(date +%s%3N)
    
    for ((i=0; i<\$current_batch_size; i++)); do
        metric_name="${METRIC_PREFIX}_${thread_id}"
        data="\${data}\${metric_name}{thread_id=\"${thread_id}\",batch_id=\"\$j\",index=\"\$i\",test_id=\"benchmark_test\"} \$(awk -v min=0 -v max=100 'BEGIN{srand(); print rand() * (max-min) + min}') \$((timestamp + i))\n"
    done
    
    # 发送数据
    start_time=\$(date +%s%3N)
    
    status=\$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout ${TIMEOUT} --max-time ${TIMEOUT} \
        -X POST -H "Content-Type: text/plain" \
        --data-binary "\$data" \
        "${BASE_URL}/api/v1/import/prometheus")
    
    end_time=\$(date +%s%3N)
    duration=\$((end_time - start_time))
    
    time_sum=\$((time_sum + duration))
    [ \$duration -lt \$time_min ] && time_min=\$duration
    [ \$duration -gt \$time_max ] && time_max=\$duration
    
    if [ "\$status" = "204" ]; then
        success=\$((success + current_batch_size))
    else
        fail=\$((fail + current_batch_size))
    fi
done

# 输出结果到文件
echo "\$success \$fail \$time_sum \$time_min \$time_max" > ${TEMP_DIR}/result_${thread_id}.txt
EOF
    
    chmod +x ${TEMP_DIR}/task_${thread_id}.sh
}

# 计算每个线程需要处理的批次数
BATCHES_PER_THREAD=$(( (DATA_COUNT + BATCH_SIZE - 1) / (BATCH_SIZE * CONCURRENCY) ))

echo "开始压测:"
echo "- 数据量: $DATA_COUNT"
echo "- 批次大小: $BATCH_SIZE"
echo "- 并发数: $CONCURRENCY"
echo "- 每线程批次数: $BATCHES_PER_THREAD"

# 生成所有任务
for ((i=0; i<CONCURRENCY; i++)); do
    generate_tasks $i $BATCHES_PER_THREAD
done

# 并行运行所有任务
for ((i=0; i<CONCURRENCY; i++)); do
    ${TEMP_DIR}/task_${i}.sh &
    echo "启动线程 $i"
done

# 等待所有任务完成
wait
echo "所有线程执行完毕"

# 统计结果
for ((i=0; i<CONCURRENCY; i++)); do
    if [ -f "${TEMP_DIR}/result_${i}.txt" ]; then
        read thread_success thread_fail thread_time thread_min thread_max < "${TEMP_DIR}/result_${i}.txt"
        SUCCESS_COUNT=$((SUCCESS_COUNT + thread_success))
        FAIL_COUNT=$((FAIL_COUNT + thread_fail))
        TOTAL_TIME=$((TOTAL_TIME + thread_time))
        [ $thread_min -lt $MIN_TIME ] && MIN_TIME=$thread_min
        [ $thread_max -gt $MAX_TIME ] && MAX_TIME=$thread_max
    else
        echo "警告: 线程 $i 的结果文件不存在"
    fi
done

END_TIME=$(date +%s%3N)
TOTAL_ELAPSED=$((END_TIME - START_TIME))

# 输出结果
echo ""
echo "压测结果:"
echo "- 总请求数: $((SUCCESS_COUNT + FAIL_COUNT))"
echo "- 成功请求数: $SUCCESS_COUNT"
echo "- 失败请求数: $FAIL_COUNT"
echo "- 总耗时(毫秒): $TOTAL_ELAPSED"
echo "- 平均响应时间(毫秒): $(( SUCCESS_COUNT > 0 ? TOTAL_TIME / (SUCCESS_COUNT / BATCH_SIZE) : 0))"
echo "- 最小响应时间(毫秒): $MIN_TIME"
echo "- 最大响应时间(毫秒): $MAX_TIME"
echo "- QPS: $(( TOTAL_ELAPSED > 0 ? (SUCCESS_COUNT * 1000) / TOTAL_ELAPSED : 0))"

# 清理临时目录
rm -rf $TEMP_DIR
echo "测试完成!" 