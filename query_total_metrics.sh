#!/bin/bash

# VictoriaMetrics总数据量查询脚本
# 查询数据库中所有时间范围的数据总量

# 参数检查
if [ $# -lt 2 ]; then
    echo "用法: $0 <host> <port> [详细模式(y/n)]"
    echo "示例: $0 172.36.100.38 8428"
    echo "详细模式下会输出更多指标信息和示例数据"
    exit 1
fi

HOST=$1
PORT=$2
VERBOSE=${3:-"n"}

# 构建基本URL
BASE_URL="http://${HOST}:${PORT}"
API_URL="${BASE_URL}/api/v1/query"
SERIES_URL="${BASE_URL}/api/v1/series"

# 计算时间范围 (超大范围，实际上相当于所有数据)
current_time=$(date +%s)
start_time=0  # 从Unix时间戳的起始开始
end_time=$((current_time + 3600*24*365*10))  # 十年后结束，确保包含所有数据

# 输出查询参数
echo "VictoriaMetrics 数据总量查询工具"
echo "========================================"
echo "主机: ${HOST}"
echo "端口: ${PORT}"
echo "----------------------------------------"

# 检查服务器健康状态
echo -n "检查服务器状态..."
health_status=$(curl -s --connect-timeout 3 --max-time 5 "${BASE_URL}/health")
if [ "$health_status" = "OK" ]; then
    echo -e "\r服务器状态: 正常               "
else
    echo -e "\r服务器状态: 异常，无法连接或服务器未响应"
    echo "无法继续查询，请检查主机和端口是否正确"
    exit 1
fi

# 查询所有不同指标名称的数量
echo -n "正在查询指标名称数量..."
metrics_url="${BASE_URL}/api/v1/label/__name__/values"
metrics_response=$(curl -s --connect-timeout 5 --max-time 20 "$metrics_url")

if [[ $metrics_response == *'"status":"success"'* ]]; then
    # 提取指标名称列表并计算数量
    metrics_count=$(echo "$metrics_response" | grep -o '"data":\[[^]]*\]' | grep -o '"[^"]*"' | wc -l)
    echo -e "\r指标名称总数: $metrics_count                "
    
    # 如果是详细模式，保存指标名称列表以便后续使用
    if [ "$VERBOSE" = "y" ]; then
        metrics_list=$(echo "$metrics_response" | grep -o '"data":\[[^]]*\]' | grep -o '"[^"]*"' | sed 's/"//g')
        echo "$metrics_list" > /tmp/vm_metrics_list.txt
    fi
else
    echo -e "\r查询指标名称失败                   "
fi

# 查询全部时间序列数量
echo -n "正在查询时间序列数量..."
series_count_query='count(up) or count(vm_app_version)'
series_count_response=$(curl -s --connect-timeout 5 --max-time 20 \
    "${API_URL}?query=${series_count_query}")

if [[ $series_count_response == *'"status":"success"'* ]]; then
    series_count=$(echo "$series_count_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
    echo -e "\r时间序列总数: 查询中... (此查询可能不准确)    "
    
    # 尝试更精确的查询方法
    echo -n "正在使用系列匹配计算总量..."
    match_query="${SERIES_URL}?match[]={__name__!=\"\"}&start=${start_time}&end=${end_time}"
    match_response=$(curl -s --connect-timeout 5 --max-time 30 "$match_query")
    
    if [[ $match_response == *'"status":"success"'* ]]; then
        # 计算返回的系列数量
        match_count=$(echo "$match_response" | grep -o '"__name__"' | wc -l)
        echo -e "\r时间序列总数: $match_count (匹配方法)       "
    else
        match_error=$(echo "$match_response" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
        echo -e "\r时间序列查询错误: $match_error"
    fi
else
    echo -e "\r查询时间序列数量失败                   "
fi

# 使用内部指标直接获取指标总数
echo -n "正在查询内部存储指标..."
storage_metrics_query='sum(vm_metrics_with_labels_total)'
storage_metrics_response=$(curl -s --connect-timeout 5 --max-time 20 \
    "${API_URL}?query=${storage_metrics_query}")

if [[ $storage_metrics_response == *'"status":"success"'* ]]; then
    storage_metrics_count=$(echo "$storage_metrics_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
    if [ -n "$storage_metrics_count" ] && [ "$storage_metrics_count" != "null" ]; then
        echo -e "\r内部统计的指标总数: $storage_metrics_count            "
    else
        echo -e "\r内部统计的指标总数: 无数据            "
    fi
else
    echo -e "\r查询内部存储指标失败                   "
fi

# 查询数据点总数 - 使用 vm 内部指标
echo -n "正在查询数据点总数 (行计数)..."
rows_query='sum(vm_rows)'
rows_response=$(curl -s --connect-timeout 5 --max-time 20 \
    "${API_URL}?query=${rows_query}")

if [[ $rows_response == *'"status":"success"'* ]]; then
    rows_count=$(echo "$rows_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
    if [ -n "$rows_count" ] && [ "$rows_count" != "null" ]; then
        # 转换成可读格式
        rows_count_formatted=$(printf "%'.0f" $rows_count 2>/dev/null || echo "$rows_count")
        echo -e "\r数据点总数 (行): $rows_count_formatted           "
    else
        echo -e "\r数据点总数查询返回空值                   "
    fi
else
    # 尝试备用指标查询
    echo -n "正在尝试备用数据点查询..."
    rows_alt_query='sum(vm_cache_entries{type="storage/metricIDs"})'
    rows_alt_response=$(curl -s --connect-timeout 5 --max-time 20 \
        "${API_URL}?query=${rows_alt_query}")
        
    if [[ $rows_alt_response == *'"status":"success"'* ]]; then
        rows_alt_count=$(echo "$rows_alt_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
        if [ -n "$rows_alt_count" ] && [ "$rows_alt_count" != "null" ]; then
            rows_alt_formatted=$(printf "%'.0f" $rows_alt_count 2>/dev/null || echo "$rows_alt_count")
            echo -e "\r数据点总数 (备用查询): $rows_alt_formatted           "
        else
            echo -e "\r备用数据点查询返回空值                   "
        fi
    else
        echo -e "\r查询数据点总数失败                   "
    fi
fi

# 查询压测数据 - 使用特定标签匹配
echo -n "正在查询压测数据..."
benchmark_query="${SERIES_URL}?match[]={test_id=~\".*test.*\"}&start=${start_time}&end=${end_time}"
benchmark_response=$(curl -s --connect-timeout 5 --max-time 30 "$benchmark_query")

if [[ $benchmark_response == *'"status":"success"'* ]]; then
    benchmark_count=$(echo "$benchmark_response" | grep -o '"__name__"' | wc -l)
    echo -e "\r压测数据数量: $benchmark_count           "
    
    # 获取压测数据的标签信息
    if [ "$VERBOSE" = "y" ] && [ $benchmark_count -gt 0 ]; then
        echo "压测数据标签示例 (前3个):"
        echo "$benchmark_response" | grep -o '{[^}]*}' | head -3
    fi
else
    echo -e "\r查询压测数据失败                   "
    
    # 尝试API查询方法
    echo -n "尝试备用方法查询压测数据..."
    benchmark_api_query='count({test_id=~".*test.*"}) or count({__name__=~".*bench.*"})'
    benchmark_api_response=$(curl -s --connect-timeout 5 --max-time 20 \
        "${API_URL}?query=${benchmark_api_query}")
        
    if [[ $benchmark_api_response == *'"status":"success"'* ]]; then
        benchmark_api_count=$(echo "$benchmark_api_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
        if [ -n "$benchmark_api_count" ] && [ "$benchmark_api_count" != "null" ]; then
            echo -e "\r压测数据数量 (API查询): $benchmark_api_count           "
        else
            echo -e "\r备用压测数据查询返回空值                   "
        fi
    else
        echo -e "\r备用压测数据查询失败                   "
    fi
fi

# 查询存储大小
echo -n "正在查询存储大小..."
storage_query='vm_data_size_bytes{type="storage/total"}'
storage_response=$(curl -s --connect-timeout 5 --max-time 20 \
    "${API_URL}?query=${storage_query}")

if [[ $storage_response == *'"status":"success"'* ]]; then
    storage_size=$(echo "$storage_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
    if [ -n "$storage_size" ] && [ "$storage_size" != "null" ]; then
        # 转换为人类可读格式
        if command -v bc &> /dev/null; then
            size_mb=$(echo "scale=2; $storage_size / 1024 / 1024" | bc)
            size_gb=$(echo "scale=2; $size_mb / 1024" | bc)
            echo -e "\r存储大小: ${size_mb} MB (${size_gb} GB)           "
        else
            # 如果bc不可用，用简单计算
            size_mb=$((storage_size / 1024 / 1024))
            size_gb=$((size_mb / 1024))
            echo -e "\r存储大小: ${size_mb} MB (${size_gb} GB) (近似值)           "
        fi
    else
        echo -e "\r存储大小查询返回空值                   "
    fi
else
    echo -e "\r查询存储大小失败                   "
fi

# 如果是详细模式，输出更多信息
if [ "$VERBOSE" = "y" ]; then
    echo ""
    echo "----------------------------------------"
    echo "详细信息:"
    
    # 列出前10个最大的指标
    echo "数据量最大的前10个指标:"
    top_metrics_query='topk(10, count by (__name__) ({__name__!=""}))'
    top_metrics=$(curl -s --connect-timeout 5 --max-time 30 \
        "${API_URL}?query=${top_metrics_query}")
    
    if [[ $top_metrics == *'"status":"success"'* ]]; then
        echo "$top_metrics" | grep -o '"metric":{"__name__":"[^"]*"},"value":\[[^,]*,[^]]*\]' | \
        sed 's/"metric":{"__name__":"$$$$","value":\[\([^,]*\),\([^]]*\)\]/\2|\1/g' | \
        awk -F"|" '{printf "%-30s %s 行\n", $2, $1}' | \
        sed 's/"metric":{"__name__":"//g' | sed 's/"},"value":\[//g' | nl
    else
        echo "无法获取指标排名"
    fi
    
    # 查询系统资源使用情况
    echo "系统资源使用情况:"
    
    # CPU使用率
    cpu_query='process_cpu_seconds_total'
    cpu_response=$(curl -s --connect-timeout 5 --max-time 20 \
        "${API_URL}?query=${cpu_query}")
        
    if [[ $cpu_response == *'"status":"success"'* ]]; then
        cpu_usage=$(echo "$cpu_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
        if [ -n "$cpu_usage" ] && [ "$cpu_usage" != "null" ]; then
            echo "- CPU使用总时间: ${cpu_usage} 秒"
        fi
    fi
    
    # 内存使用
    mem_query='process_resident_memory_bytes'
    mem_response=$(curl -s --connect-timeout 5 --max-time 20 \
        "${API_URL}?query=${mem_query}")
        
    if [[ $mem_response == *'"status":"success"'* ]]; then
        mem_usage=$(echo "$mem_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
        if [ -n "$mem_usage" ] && [ "$mem_usage" != "null" ]; then
            mem_mb=$(echo "scale=2; $mem_usage / 1024 / 1024" | bc 2>/dev/null || echo "$((mem_usage / 1024 / 1024))")
            echo "- 内存使用: ${mem_mb} MB"
        fi
    fi
    
    # 运行时间
    uptime_query='vm_app_uptime_seconds'
    uptime_response=$(curl -s --connect-timeout 5 --max-time 20 \
        "${API_URL}?query=${uptime_query}")
        
    if [[ $uptime_response == *'"status":"success"'* ]]; then
        uptime=$(echo "$uptime_response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g')
        if [ -n "$uptime" ] && [ "$uptime" != "null" ]; then
            uptime_days=$(echo "scale=2; $uptime / 86400" | bc 2>/dev/null || echo "$((uptime / 86400))")
            echo "- 运行时间: ${uptime_days} 天"
        fi
    fi
fi

echo "========================================"
echo "查询完成"
echo ""
echo "提示: 要查询特定时间范围的数据，请使用 query_data_count.sh 脚本" 