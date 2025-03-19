#!/bin/bash

# 设置错误处理
set -e
set -o pipefail

# 检查参数数量
if [ "$#" -lt 3 ]; then
    echo "用法: $0 <host> <port> <operation> [args...]" >&2
    echo "操作类型:" >&2
    echo "  health      - 检查服务健康状态" >&2
    echo "  write       - 写入数据" >&2
    echo "  metrics     - 获取服务器指标" >&2
    echo "  count       - 统计数据量" >&2
    echo "  delete      - 删除数据" >&2
    echo "  query       - 查询数据" >&2
    exit 1
fi

HOST=$1
PORT=$2
OPERATION=$3
shift 3

# 设置通用连接超时（单位：秒）
TIMEOUT=5
MAX_TIMEOUT=30
BASE_URL="http://${HOST}:${PORT}"

# 通用错误处理函数
handle_error() {
    local response=$1
    local operation=$2
    if [[ $response == *"error"* ]] || [[ $response == *"failed"* ]]; then
        echo "{\"status\":\"error\",\"message\":\"$operation 操作失败\",\"details\":\"$response\"}"
        exit 1
    fi
}

# 检查curl是否安装
if ! command -v curl &> /dev/null; then
    echo "{\"status\":\"error\",\"message\":\"curl 未安装\"}"
    exit 1
fi

# 检查jq是否安装
if ! command -v jq &> /dev/null; then
    echo "{\"status\":\"error\",\"message\":\"jq 未安装\"}"
    exit 1
fi

# 根据操作类型执行不同的命令
case $OPERATION in
    "health")
        # 检查服务健康状态
        curl -s "${BASE_URL}/health"
        ;;
        
    "write")
        # 写入数据
        if [ -z "$1" ]; then
            echo "错误: 写入操作需要数据参数" >&2
            exit 1
        fi
        echo "$1" | curl -s -w "%{http_code}" --data-binary @- "${BASE_URL}/api/v1/import/prometheus"
        ;;
        
    "metrics")
        # 获取服务器指标
        metrics_data=$(curl -s "${BASE_URL}/metrics")
        if [ $? -ne 0 ]; then
            echo "{\"status\":\"error\",\"message\":\"获取metrics数据失败\"}"
            exit 1
        fi
        
        series_count_json=$(curl -s "${BASE_URL}/api/v1/series/count")
        if [ $? -ne 0 ]; then
            echo "{\"status\":\"error\",\"message\":\"获取series count失败\"}"
            exit 1
        fi
        
        # CPU使用率计算 (process_cpu_seconds_total)
        cpu_seconds=$(echo "$metrics_data" | grep '^process_cpu_seconds_total' | head -n1 | awk '{print $2}')
        if [ -z "$cpu_seconds" ]; then
            cpu_seconds="0"
        fi
        
        # 计算CPU使用率：(cpu_seconds / uptime) * 100 表示平均CPU使用率
        uptime=$(echo "$metrics_data" | grep '^process_start_time_seconds' | head -n1 | awk '{print $2}')
        if [ -z "$uptime" ]; then
            uptime=$(date +%s)
        fi
        
        current_time=$(date +%s)
        total_uptime=$((current_time - uptime))
        if [ $total_uptime -gt 0 ]; then
            cpu_usage=$(echo "scale=2; ($cpu_seconds / $total_uptime) * 100" | bc 2>/dev/null || echo "0")
        else
            cpu_usage="0"
        fi
        
        # 内存使用率计算（以MB为单位）
        mem_used=$(echo "$metrics_data" | grep '^process_resident_memory_bytes' | head -n1 | awk '{print $2}')
        if [ -z "$mem_used" ]; then
            mem_used="0"
        fi
        
        mem_total=$(echo "$metrics_data" | grep '^go_memstats_sys_bytes' | head -n1 | awk '{print $2}')
        if [ -z "$mem_total" ]; then
            mem_total="0"
        fi
        
        if [ -n "$mem_total" ] && [ "$mem_total" != "0" ]; then
            memory_usage_percent=$(echo "scale=2; $mem_used * 100 / $mem_total" | bc 2>/dev/null || echo "0")
        else
            memory_usage_percent="0"
        fi
        mem_used_mb=$(echo "scale=2; $mem_used / 1024 / 1024" | bc 2>/dev/null || echo "0")
        
        # 存储使用计算 (vm_data_size_bytes)
        storage_used=$(echo "$metrics_data" | grep '^vm_data_size_bytes' | awk '{sum += $2} END {print sum}')
        if [ -z "$storage_used" ]; then
            storage_used="0"
        fi
        storage_used_mb=$(echo "scale=2; $storage_used / 1024 / 1024" | bc 2>/dev/null || echo "0")
        
        # 总存储空间 (go_memstats_sys_bytes)
        total_storage=$(echo "$metrics_data" | grep '^go_memstats_sys_bytes' | head -n1 | awk '{print $2}')
        if [ -z "$total_storage" ]; then
            total_storage="0"
        fi
        total_storage_mb=$(echo "scale=2; $total_storage / 1024 / 1024" | bc 2>/dev/null || echo "0")
        
        # 数据点数量（使用 vm_rows_inserted_total 指标）
        data_points=$(echo "$metrics_data" | grep '^vm_rows_inserted_total' | awk '{sum += $2} END {print sum}')
        if [ -z "$data_points" ]; then
            data_points="0"
        fi
        
        # 查询延迟计算
        query_duration=$(echo "$metrics_data" | grep 'vm_request_duration_seconds_sum{path="/api/v1/query"}' | head -n1 | awk '{print $2}')
        query_count=$(echo "$metrics_data" | grep 'vm_request_duration_seconds_count{path="/api/v1/query"}' | head -n1 | awk '{print $2}')
        if [ -n "$query_count" ] && [ "$query_count" != "0" ]; then
            query_latency=$(echo "scale=2; ($query_duration / $query_count) * 1000" | bc 2>/dev/null || echo "0")
        else
            query_latency="0"
        fi
        
        # 输出JSON格式的结果
        cat << EOF
{
    "cpuUsagePercent": ${cpu_usage:-0},
    "memoryUsagePercent": ${memory_usage_percent:-0},
    "memoryUsedMB": ${mem_used_mb:-0},
    "storageUsageMB": ${storage_used_mb:-0},
    "totalStorageMB": ${total_storage_mb:-0},
    "vmDataSizeMB": ${storage_used_mb:-0},
    "vmQueryLatencyMs": ${query_latency:-0},
    "dataPointsCount": ${data_points:-0}
}
EOF
        ;;
        
    "count")
        # 统计数据量
        if [ $# -lt 1 ]; then
            echo "{\"status\":\"error\",\"message\":\"指标前缀不能为空\"}"
            exit 1
        fi
        
        PREFIX=$1
        
        # 构建查询语句
        if [ -z "$PREFIX" ] || [ "$PREFIX" = "*" ]; then
            # 如果没有指定前缀或前缀为*，则获取所有数据量
            curl_output=$(curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/series/count" 2>&1)
            
            curl_status=$?
            if [ $curl_status -ne 0 ]; then
                echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
                exit 1
            fi
            
            # 解析响应，提取数据量
            if [[ $curl_output == *'"status":"success"'* ]]; then
                # 提取数据量
                count=$(echo "$curl_output" | grep -o '"data":[0-9]*' | grep -o '[0-9]*$')
                if [ -n "$count" ]; then
                    echo "{\"status\":\"success\",\"count\":$count}"
                else
                    echo "$curl_output"
                fi
            else
                echo "$curl_output"
            fi
        else
            # 使用正确的PromQL语法查询特定前缀的数据量
            query="count({__name__=~\"^${PREFIX}.*\"})"
            curl_output=$(curl -s -G --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/query" --data-urlencode "query=$query" 2>&1)
            
            curl_status=$?
            if [ $curl_status -ne 0 ]; then
                echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
                exit 1
            fi
            
            # 解析响应，提取数据量
            if [[ $curl_output == *'"status":"success"'* ]]; then
                # 提取数据量
                count=$(echo "$curl_output" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
                if [ -n "$count" ]; then
                    echo "{\"status\":\"success\",\"count\":$count}"
                else
                    echo "$curl_output"
                fi
            else
                echo "$curl_output"
            fi
        fi
        ;;
        
    "delete")
        # 删除数据
        if [ -z "$1" ]; then
            echo "错误: delete操作需要metric前缀参数" >&2
            exit 1
        fi
        
        # 构建删除请求
        MATCH_PATTERN="^${1}.*"
        response=$(curl -s -X POST "${BASE_URL}/api/v1/admin/tsdb/delete_series" \
            --data-urlencode "match[]={__name__=~\"${MATCH_PATTERN}\"}")
        
        # 检查删除操作是否成功
        # VictoriaMetrics 的删除 API 即使成功也会返回非零状态码
        # 所以我们主要检查响应内容是否包含错误信息
        if [[ $response == *"error"* ]]; then
            echo "{\"status\":\"error\",\"message\":\"删除操作失败\",\"details\":\"$response\"}"
            exit 1
        else
            echo "{\"status\":\"success\",\"message\":\"成功删除匹配模式 ${MATCH_PATTERN} 的数据\"}"
        fi
        ;;
        
    "query")
        # 查询数据
        if [ -z "$1" ]; then
            echo "错误: query操作需要查询语句参数" >&2
            exit 1
        fi
        curl -s -G "${BASE_URL}/api/v1/query" --data-urlencode "query=$1"
        ;;
        
    *)
        echo "错误: 未知的操作类型 '$OPERATION'" >&2
        exit 1
        ;;
esac 