#!/usr/bin/env bash
# Compiles GatewayClient.kt plus this harness with kotlinc and runs it against the real gateway.
# Needs: a Python 3.14 with the upstream deps installed, KOTLINC (or kotlin-compiler jar in $JARS).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
py="${PYTHON:-$root/.venv-host/bin/python}"
jars="${JARS:?set JARS to a dir with kotlin-stdlib, kotlinx-coroutines-core-jvm and json jars}"
out="$(mktemp -d)"
cp=$(ls "$jars"/*.jar | tr '\n' ':')
java -cp "$jars/kotlin-compiler-2.0.21.jar:$jars/kotlin-script-runtime-2.0.21.jar:$jars/kotlin-reflect-1.6.10.jar:$jars/kotlin-stdlib-2.0.21.jar:$jars/kotlinx-coroutines-core-jvm-1.9.0.jar:$jars/trove4j.jar:$jars/kdaemon.jar:$jars/annotations.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$cp" -d "$out" -jvm-target 17 \
  "$root/app/src/main/java/dev/hermes/android/gateway/GatewayClient.kt" "$here/GatewayHarness.kt"
java -cp "$out:$cp" GatewayHarnessKt "$py" "$root/upstream/hermes-agent" "$(mktemp -d)"
