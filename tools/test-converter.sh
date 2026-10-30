#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
KOTLINC="${KOTLINC:-$PWD/.local-tools/kotlinc/bin/kotlinc}"
JSON_JAR="${JSON_JAR:-$PWD/.local-tools/json.jar}"
SING_BOX="${SING_BOX:-$PWD/.local-tools/sing-box-1.14.4-darwin-arm64/sing-box}"
mkdir -p .local-tools
"$KOTLINC" app/src/main/java/io/nekohasekai/sfa/surge/SurgeSyntax.kt \
    app/src/main/java/io/nekohasekai/sfa/surge/SurgeConverter.kt \
    test/surge/SurgeConverterTest.kt -cp "$JSON_JAR" -include-runtime -d .local-tools/converter-tests.jar
"$JAVA_HOME/bin/java" -cp ".local-tools/converter-tests.jar:$JSON_JAR" SurgeConverterTestKt
"$SING_BOX" check -c .local-tools/test-converted.json
