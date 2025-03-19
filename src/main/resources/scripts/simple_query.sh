#!/bin/bash
HOST="172.36.100.38"
PORT="8428"
BASE_URL="http://${HOST}:${PORT}"
# 查询所有数据量
echo "查询所有数据量..."
curl -s "${BASE_URL}/api/v1/series/count"
# 查询特定前缀数据量
echo -e "

查询特定前缀数据量..."
curl -s -G "${BASE_URL}/api/v1/query" --data-urlencode "query=count({__name__=~\"^benchmark_metric.*\"})"
