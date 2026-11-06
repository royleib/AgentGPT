#!/usr/bin/env bash
# Compiles and runs the proxy engine test on a regular JVM (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
javac -d "$OUT" app/src/main/java/com/royleib/fasthotspot/proxy/ProxyServer.java proxy-test/ProxyServerTest.java
java -cp "$OUT" ProxyServerTest
