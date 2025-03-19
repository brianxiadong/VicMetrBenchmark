#!/bin/bash

# VictoriaMetrics辅助脚本，用于执行常用操作

# 参数检查
if [ $# -lt 3 ]; then
    echo "用法: $0 <命令> <host> <port> [附加参数]"
    echo "命令:"
    echo "  health_check - 健康检查"
    echo "  count_data <指标名称> - 计数指标数据"
    echo "  delete_data <指标名称> - 删除指标数据"
    echo "  export_data <指标名称> <输出文件> - 导出指标数据"
    echo "  import_data <输入文件> - 导入指标数据"
    exit 1
fi

COMMAND=$1
HOST=$2
PORT=$3
BASE_URL="http://${HOST}:${PORT}"

# 检查指标名称是否提供
check_metric_name() {
    if [ -z "$1" ]; then
        echo "错误: 指标名称必须提供"
        exit 1
    fi
}

# 健康检查
health_check() {
    echo "检查VictoriaMetrics健康状态..."
    response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 3 "${BASE_URL}/health")
    if [ "$response" = "200" ]; then
        echo "健康检查成功: HTTP 200"
        return 0
    else
        echo "健康检查失败: HTTP $response"
        return 1
    fi
}

# 计数数据
count_data() {
    check_metric_name "$1"
    metric_name=$1
    
    echo "计数指标 '${metric_name}' 的数据..."
    # 使用当前时间作为结束时间，1小时前作为开始时间
    current_time=$(date +%s)
    start_time=$((current_time - 3600))
    
    # 使用series API查询匹配的序列数量
    count_output=$(curl -s --connect-timeout 5 --max-time 10 \
        "${BASE_URL}/api/v1/series?match[]=${metric_name}&start=${start_time}&end=${current_time}")
    
    # 检查响应是否成功
    if [[ $count_output == *"\"status\":\"success\""* ]]; then
        # 提取匹配的系列数量
        count=$(echo "$count_output" | grep -o '"__name__"' | wc -l)
        echo "找到 $count 条匹配的数据"
        return 0
    else
        error=$(echo "$count_output" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
        echo "查询失败: $error"
        return 1
    fi
}

# 删除数据
delete_data() {
    check_metric_name "$1"
    metric_name=$1
    
    echo "删除指标 '${metric_name}' 的数据..."
    # 构建删除请求
    delete_output=$(curl -s -X POST --connect-timeout 5 --max-time 10 \
        "${BASE_URL}/api/v1/admin/tsdb/delete_series?match[]=${metric_name}")
    
    # 检查响应是否成功
    if [[ $delete_output == *"\"status\":\"success\""* ]]; then
        echo "删除成功"
        return 0
    else
        error=$(echo "$delete_output" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
        if [ -z "$error" ]; then
            echo "删除操作已完成"
        else
            echo "删除失败: $error"
            return 1
        fi
    fi
}

# 导出数据
export_data() {
    check_metric_name "$1"
    if [ -z "$2" ]; then
        echo "错误: 必须指定输出文件"
        exit 1
    fi
    
    metric_name=$1
    output_file=$2
    
    echo "导出指标 '${metric_name}' 的数据到 '${output_file}'..."
    # 使用当前时间作为结束时间，1天前作为开始时间
    current_time=$(date +%s)
    start_time=$((current_time - 86400))
    
    # 使用export API导出数据
    curl -s --connect-timeout 10 --max-time 60 \
        "${BASE_URL}/api/v1/export?match[]=${metric_name}&start=${start_time}&end=${current_time}" \
        > "${output_file}"
    
    if [ $? -eq 0 ] && [ -s "${output_file}" ]; then
        count=$(grep -c . "${output_file}")
        echo "导出成功，共 $count 行数据"
        return 0
    else
        echo "导出失败或无数据"
        return 1
    fi
}

# 导入数据
import_data() {
    if [ -z "$1" ]; then
        echo "错误: 必须指定输入文件"
        exit 1
    fi
    
    input_file=$1
    
    if [ ! -f "${input_file}" ]; then
        echo "错误: 输入文件 '${input_file}' 不存在"
        exit 1
    fi
    
    echo "从 '${input_file}' 导入数据..."
    # 使用import API导入数据
    import_output=$(curl -s -X POST --connect-timeout 10 --max-time 60 \
        -H "Content-Type: text/plain" \
        --data-binary @"${input_file}" \
        "${BASE_URL}/api/v1/import")
    
    if [ $? -eq 0 ]; then
        echo "导入成功"
        return 0
    else
        echo "导入失败: $import_output"
        return 1
    fi
}

# 执行命令
case "$COMMAND" in
    health_check)
        health_check
        ;;
    count_data)
        count_data "$4"
        ;;
    delete_data)
        delete_data "$4"
        ;;
    export_data)
        export_data "$4" "$5"
        ;;
    import_data)
        import_data "$4"
        ;;
    *)
        echo "错误: 未知命令 '$COMMAND'"
        exit 1
        ;;
esac 