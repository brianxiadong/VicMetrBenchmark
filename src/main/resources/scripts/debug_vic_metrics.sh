#!/bin/bash

# 设置错误处理
set -e
set -o pipefail

# 日志函数
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1" >/dev/null
}

# 打印HTTP请求信息
print_request() {
    local method=$1
    local url=$2
    local data=$3
    echo "----------------------------------------" >/dev/null
    echo "HTTP请求信息:" >/dev/null
    echo "方法: $method" >/dev/null
    echo "URL: $url" >/dev/null
    if [ ! -z "$data" ]; then
        echo "数据: $data" >/dev/null
    fi
    echo "----------------------------------------" >/dev/null
}

# 参数检查
if [ $# -lt 3 ]; then
    echo "{\"status\":\"error\",\"message\":\"用法: $0 <host> <port> <操作> [参数...]\"}" >&1
    exit 1
fi

HOST=$1
PORT=$2
OPERATION=$3
shift 3  # 移除前三个参数，剩余的都是操作参数

# 设置通用连接超时（单位：秒）
TIMEOUT=5
MAX_TIMEOUT=30
BASE_URL="http://${HOST}:${PORT}"

# 通用错误处理函数
handle_error() {
    local response=$1
    local operation=$2
    if [[ $response == *"error"* ]] || [[ $response == *"failed"* ]]; then
        log "错误: $operation 操作失败 - $response"
        echo "{\"status\":\"error\",\"message\":\"$operation 操作失败\",\"details\":\"$response\"}" >&1
        exit 1
    fi
}

# 检查curl是否安装
if ! command -v curl &> /dev/null; then
    log "错误: curl 未安装"
    exit 1
fi

# 检查jq是否安装
if ! command -v jq &> /dev/null; then
    log "错误: jq 未安装"
    exit 1
fi

# 根据操作类型执行不同的命令
case $OPERATION in
    health)
        # 检查健康状态
        log "正在检查健康状态..."
        print_request "GET" "${BASE_URL}/health"
        # 将curl的详细输出重定向到/dev/null，响应内容保存到stdout
        curl_output=$(curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/health" 2>/dev/null)
        curl_status=$?
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status"
            echo "{\"status\":\"error\",\"message\":\"健康检查失败\",\"curl_status\":$curl_status}"
            exit 1
        fi
        log "健康状态: $curl_output"
        echo "{\"status\":\"ok\",\"message\":\"$curl_output\"}"
        ;;
    
    test_connection)
        # 测试连接
        log "正在测试连接..."
        print_request "GET" "${BASE_URL}/health"
        http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/health" 2>/dev/null)
        curl_status=$?
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status"
            echo "{\"status\":\"error\",\"message\":\"连接测试失败\",\"curl_status\":$curl_status}"
            exit 1
        fi
        if [ "$http_code" = "200" ]; then
            log "连接测试成功"
            echo "{\"status\":\"ok\",\"connected\":true}"
        else
            log "连接测试失败，HTTP状态码: $http_code"
            echo "{\"status\":\"error\",\"connected\":false,\"http_code\":\"$http_code\"}"
        fi
        ;;
    
    query)
        # 执行查询
        if [ $# -lt 1 ]; then
            log "错误: 查询语句不能为空"
            echo "{\"status\":\"error\",\"message\":\"查询语句不能为空\"}"
            exit 1
        fi
        
        QUERY=$(echo "$1" | sed 's/ /%20/g')  # URL编码查询语句
        log "正在执行查询: $QUERY"
        print_request "GET" "${BASE_URL}/api/v1/query?query=${QUERY}"
        curl_output=$(curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/query?query=${QUERY}" 2>/dev/null)
        curl_status=$?
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status"
            echo "{\"status\":\"error\",\"message\":\"查询失败\",\"curl_status\":$curl_status}"
            exit 1
        fi
        echo "$curl_output"
        ;;
    
    count)
        # 统计数据量
        if [ $# -lt 1 ]; then
            log "错误: 指标前缀不能为空"
            echo "{\"status\":\"error\",\"message\":\"指标前缀不能为空\"}"
            exit 1
        fi
        
        PREFIX=$1
        log "正在统计指标前缀为 $PREFIX 的数据量..."
        
        # 构建查询语句
        if [ -z "$PREFIX" ] || [ "$PREFIX" = "*" ]; then
            # 如果没有指定前缀或前缀为*，则获取所有数据量
            print_request "GET" "${BASE_URL}/api/v1/series/count"
            curl_output=$(curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/series/count" 2>&1)
            
            curl_status=$?
            if [ $curl_status -ne 0 ]; then
                log "错误: curl命令执行失败，状态码: $curl_status, 输出: $curl_output"
                echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
                exit 1
            fi
            
            # 解析响应，提取数据量
            if [[ $curl_output == *'"status":"success"'* ]]; then
                # 提取数据量
                count=$(echo "$curl_output" | grep -o '"data":{"count":[0-9]*' | grep -o '[0-9]*$')
                if [ -n "$count" ]; then
                    echo "{\"status\":\"success\",\"count\":$count}"
                else
                    # 直接解析JSON，提取count字段
                    count=$(echo "$curl_output" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')
                    if [ -n "$count" ]; then
                        echo "{\"status\":\"success\",\"count\":$count}"
                    else
                        log "警告: 无法从响应中提取count字段: $curl_output"
                        echo "$curl_output"
                    fi
                fi
            else
                log "错误: 响应不包含成功状态: $curl_output"
                echo "{\"status\":\"error\",\"message\":\"查询失败\",\"details\":$curl_output}"
            fi
        else
            # 如果指定了前缀，则使用query端点查询特定前缀的数据量
            QUERY="count({__name__=~\"^${PREFIX}.+\"})"
            log "正在执行查询: $QUERY"
            print_request "GET" "${BASE_URL}/api/v1/query" "query=${QUERY}"
            curl_output=$(curl -s -G --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/query" --data-urlencode "query=${QUERY}" 2>&1)
            
            curl_status=$?
            if [ $curl_status -ne 0 ]; then
                log "错误: curl命令执行失败，状态码: $curl_status, 输出: $curl_output"
                echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
                exit 1
            fi
            
            # 解析响应，提取数据量
            if [[ $curl_output == *'"status":"success"'* ]]; then
                # 提取数据量，格式为 "value":[1234567890,"123"]
                count=$(echo "$curl_output" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g' | sed 's/"//g')
                if [ -n "$count" ]; then
                    echo "{\"status\":\"success\",\"count\":$count}"
                else
                    log "警告: 无法从响应中提取value字段: $curl_output"
                    echo "{\"status\":\"success\",\"count\":0}"
                fi
            else
                log "错误: 响应不包含成功状态: $curl_output"
                echo "{\"status\":\"error\",\"message\":\"查询失败\",\"details\":$curl_output}"
            fi
        fi
        ;;
    
    delete)
        # 删除数据
        if [ $# -lt 1 ]; then
            log "错误: 指标前缀不能为空"
            echo "{\"status\":\"error\",\"message\":\"指标前缀不能为空\"}"
            exit 1
        fi
        
        PREFIX=$1
        log "正在删除指标前缀为 $PREFIX 的数据..."
        # 使用正确的PromQL语法
        MATCH="{__name__=~\"${PREFIX}.*\"}"
        ENCODED_MATCH=$(echo "$MATCH" | sed 's/\//\\\//g' | sed 's/ /%20/g' | sed 's/"/%22/g' | sed 's/{/%7B/g' | sed 's/}/%7D/g' | sed 's/=/%3D/g' | sed 's/\*/%2A/g')
        print_request "POST" "${BASE_URL}/api/v1/admin/tsdb/delete_series" "match[]=${ENCODED_MATCH}"
        curl_output=$(curl -s -X POST --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/api/v1/admin/tsdb/delete_series?match[]=${ENCODED_MATCH}" 2>/dev/null)
        curl_status=$?
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status"
            echo "{\"status\":\"error\",\"message\":\"删除失败\",\"curl_status\":$curl_status}"
            exit 1
        fi
        log "删除操作完成"
        echo "{\"status\":\"ok\",\"message\":\"删除操作完成\"}"
        ;;
    
    metrics)
        # 获取服务器指标
        log "正在获取服务器指标..."
        print_request "GET" "${BASE_URL}/metrics"
        curl_output=$(curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT "${BASE_URL}/metrics" 2>/dev/null)
        curl_status=$?
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status"
            echo "{\"status\":\"error\",\"message\":\"获取指标失败\",\"curl_status\":$curl_status}"
            exit 1
        fi

        # 提取需要的指标
        vm_data_size=$(echo "$curl_output" | grep "vm_data_size_bytes" | awk '{sum += $2} END {print sum}')
        go_memstats_alloc=$(echo "$curl_output" | grep "go_memstats_alloc_bytes" | head -n1 | awk '{print $2}')
        go_memstats_sys=$(echo "$curl_output" | grep "go_memstats_sys_bytes" | head -n1 | awk '{print $2}')
        process_cpu=$(echo "$curl_output" | grep "process_cpu_seconds_total" | head -n1 | awk '{print $2}')
        process_resident_memory_bytes=$(echo "$curl_output" | grep "process_resident_memory_bytes" | head -n1 | awk '{print $2}')
        
        # 计算百分比
        memory_usage_percent=$(echo "scale=2; $process_resident_memory_bytes * 100 / $go_memstats_sys" | bc)
        cpu_usage_percent=$(echo "scale=2; $process_cpu * 100 / 3600" | bc)  # 将CPU时间转换为每小时使用率
        storage_usage_mb=$(echo "scale=2; $vm_data_size / 1024 / 1024" | bc)
        total_storage_mb=$(echo "scale=2; $go_memstats_sys / 1024 / 1024" | bc)
        vm_data_size_mb=$(echo "scale=2; $vm_data_size / 1024 / 1024" | bc)
        
        # 构建返回的JSON
        echo "{
            \"cpuUsagePercent\": $cpu_usage_percent,
            \"memoryUsagePercent\": $memory_usage_percent,
            \"storageUsageMB\": $storage_usage_mb,
            \"totalStorageMB\": $total_storage_mb,
            \"vmDataSizeMB\": $vm_data_size_mb,
            \"vmQueryLatencyMs\": 0.0,
            \"errorMessage\": null
        }"
        ;;
    
    *)
        log "错误: 未知操作 '$OPERATION'"
        echo "{\"status\":\"error\",\"message\":\"未知操作 '$OPERATION'\"}"
        exit 1
        ;;
esac 