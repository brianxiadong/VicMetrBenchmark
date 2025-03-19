#!/bin/bash

# VictoriaMetrics数据系列总量查询脚本
# 使用series API和分页处理统计总数据量

# 参数检查
if [ $# -lt 2 ]; then
    echo "用法: $0 <host> <port> [测试模式(y/n)]"
    echo "此脚本统计VictoriaMetrics数据库中所有时间系列(series)的总数"
    echo "如果启用测试模式，只处理前10个指标用于验证"
    exit 1
fi

HOST=$1
PORT=$2
TEST_MODE=${3:-"n"}

# 构建基本URL
BASE_URL="http://${HOST}:${PORT}"
SERIES_URL="${BASE_URL}/api/v1/series"

# 计算时间范围 (选择较大范围以涵盖更多数据)
current_time=$(date +%s)
start_time=$((current_time - 3600 * 24 * 30))  # 30天前
end_time=$current_time  # 当前时间

# 显示查询参数
echo "VictoriaMetrics 数据系列总量查询工具"
echo "========================================"
echo "主机: ${HOST}"
echo "端口: ${PORT}"
echo "测试模式: ${TEST_MODE}"
echo "----------------------------------------"

# 测试连接
echo -n "测试连接中... "
health_status=$(curl -s --connect-timeout 3 --max-time 5 "${BASE_URL}/health")
if [ "$health_status" = "OK" ]; then
    echo "连接成功"
else
    echo "连接失败，无法继续"
    exit 1
fi

# 临时文件和结果文件
timestamp=$(date +%Y%m%d_%H%M%S)
temp_dir="/tmp/vm_query_${timestamp}"
mkdir -p $temp_dir

metrics_file="${temp_dir}/metrics_list.txt"
temp_file="${temp_dir}/series_data.txt"
result_file="${temp_dir}/vm_series_result.txt"
server_result_file="/var/www/html/vm_metrics_reports/vm_series_report_${HOST}_${timestamp}.txt"

# 查询指标名称列表
get_metric_names() {
    echo -n "查询指标名称列表... "
    metrics_url="${BASE_URL}/api/v1/label/__name__/values"
    metrics_response=$(curl -s --connect-timeout 5 --max-time 20 "$metrics_url")
    
    if [[ $metrics_response == *'"status":"success"'* ]]; then
        # 提取指标名称并保存
        echo "$metrics_response" | grep -o '"data":\[[^]]*\]' | grep -o '"[^"]*"' | sed 's/"//g' > "$metrics_file"
        metrics_count=$(cat "$metrics_file" | wc -l)
        echo "找到 $metrics_count 个指标名称"
        echo "指标名称总数: $metrics_count" >> "$result_file"
        return 0
    else
        echo "查询指标名称失败: $metrics_response"
        return 1
    fi
}

# 使用直接方法查询某个指标的系列数
query_series_for_metric() {
    local metric=$1
    local url="${SERIES_URL}?match%5B%5D=%7B__name__%3D%22${metric}%22%7D&start=${start_time}&end=${end_time}"
    
    response=$(curl -s --connect-timeout 5 --max-time 30 "$url")
    
    if [[ $response == *'"status":"success"'* ]]; then
        # 计算系列数量
        count=$(echo "$response" | grep -o '"__name__":"' | wc -l)
        echo "$count"
    else
        echo "0"
    fi
}

# 使用分页方式查询所有系列 - 完整实现
query_all_series_with_pagination() {
    echo "尝试使用分页方式查询所有系列..."
    
    local limit=1000
    local series_count=0
    local page=0
    local continuation_token=""
    local has_more=true
    
    while $has_more; do
        page=$((page + 1))
        
        # 构建查询URL
        if [ -z "$continuation_token" ]; then
            # 第一页查询
            query_url="${SERIES_URL}?match%5B%5D=%7B__name__%21%3D%22%22%7D&start=${start_time}&end=${end_time}&limit=${limit}"
        else
            # 后续页查询 (带分页标记)
            query_url="${SERIES_URL}?match%5B%5D=%7B__name__%21%3D%22%22%7D&start=${start_time}&end=${end_time}&limit=${limit}&continue_from=${continuation_token}"
        fi
        
        echo -n "正在查询第 $page 页... "
        response=$(curl -s --connect-timeout 5 --max-time 60 "$query_url")
        
        if [[ $response == *'"status":"success"'* ]]; then
            # 提取本页系列数量
            echo "$response" > "${temp_dir}/page_${page}.json"
            current_count=$(grep -o '"__name__":"' "${temp_dir}/page_${page}.json" | wc -l)
            series_count=$((series_count + current_count))
            
            echo "找到 $current_count 个系列，累计: $series_count"
            
            # 检查是否是测试模式
            if [ "$TEST_MODE" = "y" ] && [ $page -ge 2 ]; then
                echo "测试模式: 仅查询前2页数据"
                has_more=false
                break
            fi
            
            # 提取继续标记
            continuation_token=$(grep -o '"continuationToken":"[^"]*"' "${temp_dir}/page_${page}.json" | sed 's/"continuationToken":"//g' | sed 's/"//g')
            
            if [ -z "$continuation_token" ] || [ "$current_count" -lt "$limit" ]; then
                echo "没有更多数据或达到了最后一页"
                has_more=false
            else
                echo "获取到继续标记: ${continuation_token:0:10}..."
                # 暂停一下以避免过快请求
                sleep 0.5
            fi
        else
            error=$(echo "$response" | grep -o '"error":"[^"]*"' | cut -d'"' -f4)
            echo "查询失败: $error"
            has_more=false
        fi
    done
    
    echo "分页查询完成，总系列数: $series_count"
    echo "分页查询系列总数: $series_count" >> "$result_file"
    
    return 0
}

# 分批查询所有指标的系列数
count_series() {
    # 首先获取指标名称列表
    if [ ! -f "$metrics_file" ]; then
        echo "需要先获取指标名称列表"
        get_metric_names
        if [ $? -ne 0 ]; then
            echo "无法获取指标名称列表，退出"
            return 1
        fi
    fi
    
    local total_count=0
    local processed=0
    local total_metrics=$(cat "$metrics_file" | wc -l)
    
    echo "开始查询数据，共 $total_metrics 个指标"
    echo "过程可能较慢，请耐心等待..."
    
    # 测试模式下只处理前10个指标
    if [ "$TEST_MODE" = "y" ]; then
        limit=10
        echo "测试模式: 只处理前 $limit 个指标"
        head -n $limit "$metrics_file" > "${temp_dir}/metrics_list_test.txt"
        mv "${temp_dir}/metrics_list_test.txt" "$metrics_file"
        total_metrics=$limit
    fi
    
    # 创建一个指标的结果文件，记录详细信息
    echo "指标名称,系列数" > "${temp_dir}/metric_series_counts.csv"
    
    # 逐个查询每个指标的系列数
    while read metric; do
        processed=$((processed + 1))
        echo -n "[$processed/$total_metrics] 查询指标 '${metric}'... "
        
        # 查询该指标的系列数
        current_count=$(query_series_for_metric "$metric")
        
        # 更新总数
        total_count=$((total_count + current_count))
        echo "找到 $current_count 条系列"
        
        # 记录到CSV文件
        echo "$metric,$current_count" >> "${temp_dir}/metric_series_counts.csv"
        
        # 显示进度条
        percent=$((processed * 100 / total_metrics))
        echo -n -e "\r进度: $percent% ($processed/$total_metrics) - 当前总计: $total_count 系列"
        
        # 休息一下以避免API速率限制
        sleep 0.1
    done < "$metrics_file"
    echo -e "\n"
    
    echo "----------------------------------------"
    echo "查询完成"
    echo "总数据系列数量: $total_count"
    
    # 保存结果
    echo "单指标查询系列总数: $total_count" >> "$result_file"
    
    return 0
}

# 查询总数据点数量
query_total_points() {
    echo -n "查询系统数据点总量... "
    query_url="${BASE_URL}/api/v1/query?query=sum(vm_rows)"
    
    response=$(curl -s --connect-timeout 5 --max-time 20 "$query_url")
    
    if [[ $response == *'"status":"success"'* ]]; then
        # 提取值
        total_points=$(echo "$response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g' | sed 's/"//g')
        if [ -n "$total_points" ] && [ "$total_points" != "null" ]; then
            # 格式化大数字
            formatted_points=$(echo "$total_points" | sed ':a;s/\B[0-9]\{3\}\>/,&/;ta')
            echo "总数据点数量: $formatted_points"
            echo "总数据点数量: $formatted_points" >> "$result_file"
        else
            echo "查询返回空值"
        fi
    else
        echo "查询失败: $response"
    fi
}

# 查询系统存储使用情况
query_storage_usage() {
    echo -n "查询存储使用情况... "
    query_url="${BASE_URL}/api/v1/query?query=vm_storage_size_bytes"
    
    response=$(curl -s --connect-timeout 5 --max-time 20 "$query_url")
    
    if [[ $response == *'"status":"success"'* ]]; then
        # 提取值 (字节)
        storage_bytes=$(echo "$response" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g' | sed 's/"//g')
        if [ -n "$storage_bytes" ] && [ "$storage_bytes" != "null" ]; then
            # 转换为易读格式 (GB)
            storage_gb=$(echo "scale=2; $storage_bytes / 1024 / 1024 / 1024" | bc)
            echo "存储使用: ${storage_gb}GB"
            echo "存储使用: ${storage_gb}GB" >> "$result_file"
        else
            echo "查询返回空值"
        fi
    else
        echo "查询失败: $response"
    fi
}

# 上传结果到服务器
upload_results() {
    echo -n "准备上传结果到服务器... "
    
    # 检查服务器目录是否存在，不存在则创建
    if [ ! -d "/var/www/html/vm_metrics_reports" ]; then
        sudo mkdir -p /var/www/html/vm_metrics_reports
        sudo chmod 755 /var/www/html/vm_metrics_reports
    fi
    
    # 复制结果文件到服务器位置
    if cp "$result_file" "$server_result_file" 2>/dev/null; then
        echo "上传成功: $server_result_file"
        echo "结果可以通过以下URL访问:"
        echo "http://$(hostname -I | awk '{print $1}')/vm_metrics_reports/$(basename $server_result_file)"
    else
        echo "无法复制到服务器目录，尝试使用sudo"
        if sudo cp "$result_file" "$server_result_file"; then
            sudo chmod 644 "$server_result_file"
            echo "上传成功: $server_result_file"
        else
            echo "上传失败"
        fi
    fi
}

# 主函数
run_query() {
    echo "开始数据采集... $(date)"
    echo "----------------------------------------" > "$result_file"
    echo "VictoriaMetrics 统计报告" >> "$result_file"
    echo "主机: ${HOST}:${PORT}" >> "$result_file"
    echo "生成时间: $(date)" >> "$result_file"
    echo "查询范围: $(date -r $start_time) 至 $(date -r $end_time)" >> "$result_file"
    echo "----------------------------------------" >> "$result_file"
    
    get_metric_names
    
    # 两种方式查询系列数
    if [ "$TEST_MODE" = "y" ]; then
        count_series
        query_all_series_with_pagination
    else
        # 非测试模式，优先使用分页查询，速度更快
        query_all_series_with_pagination
        if grep -q "分页查询系列总数: 0" "$result_file"; then
            echo "分页查询未返回数据，尝试单指标查询方式..."
            count_series
        fi
    fi
    
    query_total_points
    query_storage_usage
    
    # 添加总结
    echo "----------------------------------------" >> "$result_file"
    echo "查询完成时间: $(date)" >> "$result_file"
    echo "查询方式:" >> "$result_file"
    echo "- 分页API查询" >> "$result_file"
    echo "- 单指标逐个查询" >> "$result_file"
    echo "- 系统指标查询" >> "$result_file"
    
    echo "========================================" 
    echo "查询完成，结果已保存至: $result_file"
    
    # 尝试上传到服务器
    if [ "$TEST_MODE" = "n" ]; then
        upload_results
    fi
    
    # 显示结果文件内容
    echo "----------------------------------------"
    echo "结果概要:"
    cat "$result_file"
}

# 运行查询
run_query 