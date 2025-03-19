count)
    # 统计数据量
    if [ $# -lt 1 ]; then
        log "错误: 指标前缀不能为空"
        echo "{\"status\":\"error\",\"message\":\"指标前缀不能为空\"}"
        exit 1
    fi
    
    PREFIX=$1
    echo "DEBUG: 正在统计指标前缀为 $PREFIX 的数据量..." >&2
    log "正在统计指标前缀为 $PREFIX 的数据量..."
    
    # 构建查询语句
    if [ -z "$PREFIX" ] || [ "$PREFIX" = "*" ]; then
        # 如果没有指定前缀或前缀为*，则获取所有数据量
        echo "DEBUG: 获取所有数据量" >&2
        print_request "GET" "${BASE_URL}/api/v1/series/count"
        curl_cmd="curl -s --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT \"${BASE_URL}/api/v1/series/count\" 2>&1"
        echo "DEBUG: 执行命令: $curl_cmd" >&2
        curl_output=$(eval $curl_cmd)
        
        curl_status=$?
        echo "DEBUG: curl状态码: $curl_status" >&2
        echo "DEBUG: curl输出: $curl_output" >&2
        
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status, 输出: $curl_output"
            echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
            exit 1
        fi
        
        # 解析响应，提取数据量
        if [[ $curl_output == *'"status":"success"'* ]]; then
            # 提取数据量
            count=$(echo "$curl_output" | grep -o '"data":{"count":[0-9]*' | grep -o '[0-9]*$')
            echo "DEBUG: 提取的count值: $count" >&2
            
            if [ -n "$count" ]; then
                echo "{\"status\":\"success\",\"count\":$count}"
            else
                # 直接解析JSON，提取count字段
                echo "DEBUG: 尝试其他方式提取count" >&2
                count=$(echo "$curl_output" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')
                echo "DEBUG: 提取的count值(方法2): $count" >&2
                
                if [ -n "$count" ]; then
                    echo "{\"status\":\"success\",\"count\":$count}"
                else
                    log "警告: 无法从响应中提取count字段: $curl_output"
                    echo "DEBUG: 返回原始响应" >&2
                    echo "$curl_output"
                fi
            fi
        else
            log "错误: 响应不包含成功状态: $curl_output"
            echo "DEBUG: 响应不包含成功状态" >&2
            echo "{\"status\":\"error\",\"message\":\"查询失败\",\"details\":$curl_output}"
        fi
    else
        # 如果指定了前缀，则使用query端点查询特定前缀的数据量
        QUERY="count({__name__=~\"^${PREFIX}.+\"})"
        echo "DEBUG: 使用查询: $QUERY" >&2
        log "正在执行查询: $QUERY"
        print_request "GET" "${BASE_URL}/api/v1/query" "query=${QUERY}"
        
        curl_cmd="curl -s -G --connect-timeout $TIMEOUT --max-time $MAX_TIMEOUT \"${BASE_URL}/api/v1/query\" --data-urlencode \"query=${QUERY}\" 2>&1"
        echo "DEBUG: 执行命令: $curl_cmd" >&2
        curl_output=$(eval $curl_cmd)
        
        curl_status=$?
        echo "DEBUG: curl状态码: $curl_status" >&2
        echo "DEBUG: curl输出: $curl_output" >&2
        
        if [ $curl_status -ne 0 ]; then
            log "错误: curl命令执行失败，状态码: $curl_status, 输出: $curl_output"
            echo "{\"status\":\"error\",\"message\":\"统计失败: $curl_output\",\"curl_status\":$curl_status}"
            exit 1
        fi
        
        # 解析响应，提取数据量
        if [[ $curl_output == *'"status":"success"'* ]]; then
            # 提取数据量，格式为 "value":[1234567890,"123"]
            count=$(echo "$curl_output" | grep -o '"value":\[[^,]*,[^]]*\]' | sed 's/"value":\[\([^,]*\),\([^]]*\)\]/\2/g' | sed 's/"//g')
            echo "DEBUG: 提取的count值: $count" >&2
            
            if [ -n "$count" ]; then
                echo "{\"status\":\"success\",\"count\":$count}"
            else
                log "警告: 无法从响应中提取value字段: $curl_output"
                echo "DEBUG: 返回零" >&2
                echo "{\"status\":\"success\",\"count\":0}"
            fi
        else
            log "错误: 响应不包含成功状态: $curl_output"
            echo "DEBUG: 响应不包含成功状态" >&2
            echo "{\"status\":\"error\",\"message\":\"查询失败\",\"details\":$curl_output}"
        fi
    fi
    ;; 