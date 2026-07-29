#!/bin/bash
set -e

JAVA_LIBRARY_PATH="${JAVA_LIBRARY_PATH:-/usr/lib/jni}"
export LD_LIBRARY_PATH="${JAVA_LIBRARY_PATH}:${LD_LIBRARY_PATH}"

java ${JAVA_OPTS} -Djava.library.path="${JAVA_LIBRARY_PATH}" -jar /app/lib/*.jar