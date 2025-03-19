#!/bin/bash

# 设置错误处理
set -e
set -o pipefail

# 日志函数
log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1"
}

# 测试函数
test_command() {
    local cmd="$1"
    local description="$2"
    local expected_exit="$3"
    
    log "测试: $description"
    log "执行命令: $cmd"
    
    if [ -z "$expected_exit" ]; then
        expected_exit=0
    fi
    
    if eval "$cmd"; then
        if [ $? -eq $expected_exit ]; then
            log "✓ 测试通过"
            return 0
        else
            log "✗ 测试失败 - 退出码不匹配"
            return 1
        fi
    else
        if [ $? -eq $expected_exit ]; then
            log "✓ 测试通过（预期的错误）"
            return 0
        else
            log "✗ 测试失败 - 意外的错误"
            return 1
        fi
    fi
}

# 检查脚本是否存在
SCRIPT_PATH="vic_metrics_query.sh"
if [ ! -f "$SCRIPT_PATH" ]; then
    log "错误: 脚本文件 $SCRIPT_PATH 不存在"
    exit 1
fi

# 确保脚本有执行权限
chmod +x "$SCRIPT_PATH"

# 测试参数
HOST="172.36.100.38"
PORT="8428"
METRIC_PREFIX="test_metric"
QUERY="up"

# 检查服务器是否可访问
log "检查 VictoriaMetrics 服务器状态..."
if curl -s --connect-timeout 5 "http://$HOST:$PORT/health" > /dev/null; then
    log "✓ VictoriaMetrics 服务器可访问"
else
    log "✗ VictoriaMetrics 服务器不可访问，请检查服务器状态"
    exit 1
fi

# 测试用例
log "开始测试 VictoriaMetrics 查询脚本..."

# 1. 测试参数检查（预期失败）
test_command "./$SCRIPT_PATH" "参数检查（无参数）" 1

# 2. 测试健康检查
test_command "./$SCRIPT_PATH $HOST $PORT health" "健康检查"

# 3. 测试连接
test_command "./$SCRIPT_PATH $HOST $PORT test_connection" "连接测试"

# 4. 测试查询
test_command "./$SCRIPT_PATH $HOST $PORT query '$QUERY'" "执行查询"

# 5. 测试数据量统计
test_command "./$SCRIPT_PATH $HOST $PORT count $METRIC_PREFIX" "统计数据量"

# 6. 测试获取指标
test_command "./$SCRIPT_PATH $HOST $PORT metrics" "获取服务器指标"

# 7. 测试删除数据
test_command "./$SCRIPT_PATH $HOST $PORT delete $METRIC_PREFIX" "删除数据"

# 8. 测试无效操作
test_command "./$SCRIPT_PATH $HOST $PORT invalid_operation" "无效操作" 1

# 9. 测试无效主机
test_command "./$SCRIPT_PATH invalid_host $PORT health" "无效主机" 1

# 10. 测试无效端口
test_command "./$SCRIPT_PATH $HOST invalid_port health" "无效端口" 1

log "测试完成" 