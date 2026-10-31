#!/usr/bin/env bash
# Reproducible fork build. Toolchains are supplied by the caller; downloads stay local.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK (tested with Temurin 21.0.13)}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an installed Android SDK}"
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/28.0.13004108}"
export ANDROID_NDK_HOME
export GOPATH="${GOPATH:-$PWD/.local-tools/gopath}"
export GOCACHE="${GOCACHE:-$PWD/.local-tools/gocache}"
export GOBIN="${GOBIN:-$PWD/.local-tools/bin}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.local-tools/gradle}"
export PATH="$GOBIN:$PATH"
mkdir -p .local-tools app/libs
if [[ ! -d .local-tools/sing-box-source/.git ]]; then
    git clone --depth 1 --branch v1.14.4 https://github.com/SagerNet/sing-box.git .local-tools/sing-box-source
fi
[[ "$(git -C .local-tools/sing-box-source describe --tags --exact-match)" == "v1.14.4" ]]
[[ -z "$(git -C .local-tools/sing-box-source status --porcelain)" ]]
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12 github.com/sagernet/gomobile/cmd/gobind@v0.1.12
(
    cd .local-tools/sing-box-source
    gomobile bind -target android/arm64,android/amd64 -androidapi 24 \
        -javapkg=io.nekohasekai -libname=box -trimpath \
        -ldflags '-X github.com/sagernet/sing-box/constant.Version=1.14.4 -checklinkname=0' \
        -tags with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0 \
        -o ../libbox.aar ./experimental/libbox
)
cp .local-tools/libbox.aar app/libs/libbox.aar
./gradlew :app:assembleOtherDebug
