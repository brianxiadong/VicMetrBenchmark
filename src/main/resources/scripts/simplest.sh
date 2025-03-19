#!/bin/bash
HOST=$1
PORT=$2
OPERATION=$3
PREFIX=$4
if [ "$OPERATION" = "count" ]; then
  if [ "$PREFIX" = "*" ]; then
    echo "{\"success\":true,\"data\":{\"count\":456}}" >&1
  else
    echo "{\"success\":true,\"data\":{\"count\":123}}" >&1
  fi
else
  echo "{\"success\":true,\"data\":{\"message\":\"Operation not supported\"}}" >&1
fi
