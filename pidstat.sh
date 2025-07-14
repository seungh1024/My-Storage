#!/bin/bash

OUT_FILE="/app/logs/dstat_java.csv"

# 1. 최초 헤더 + 첫 라인(java 포함 시) 저장
pidstat -cdnm 1 1 | awk 'NR==1 || /java/' > "$OUT_FILE"

# 2. 이후 600초 동안 1초 간격으로 실행하며 java 프로세스만 추출
pidstat -cdnm 1 600 | grep java >> "$OUT_FILE"