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
    app/src/main/java/io/nekohasekai/sfa/surge/FailoverDecision.kt \
    app/src/main/java/io/nekohasekai/sfa/surge/AndroidRuntimeFeatures.kt \
    app/src/main/java/io/nekohasekai/sfa/surge/AndroidRoutingOverrides.kt \
    test/surge/SurgeConverterTest.kt test/surge/AndroidRoutingOverridesTest.kt test/surge/AndroidRuntimeFeaturesTest.kt -cp "$JSON_JAR" -include-runtime -d .local-tools/converter-tests.jar
"$JAVA_HOME/bin/java" -cp ".local-tools/converter-tests.jar:$JSON_JAR" SurgeConverterTestKt
"$SING_BOX" check -c .local-tools/test-converted.json

"$JAVA_HOME/bin/java" -cp ".local-tools/converter-tests.jar:$JSON_JAR" AndroidRoutingOverridesTestKt
"$SING_BOX" check -c .local-tools/overrides-test.json

"$JAVA_HOME/bin/java" -cp ".local-tools/converter-tests.jar:$JSON_JAR" AndroidRuntimeFeaturesTestKt
"$SING_BOX" check -c .local-tools/runtime-features-test.json

"$SING_BOX" check -c .local-tools/migrated-runtime-test.json

SING_BOX="$SING_BOX" python3 tools/test-ruleset-download.py
