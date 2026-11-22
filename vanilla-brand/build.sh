#!/usr/bin/env bash
# Builds vanilla-brand-1.0.0.jar without Gradle/Loom (Minecraft 26.x is unobfuscated, no remapping needed).
set -euo pipefail
cd "$(dirname "$0")"
MIXIN=sponge-mixin-0.17.5+mixin.0.8.7.jar
mkdir -p build/lib build/classes
[ -f "build/lib/$MIXIN" ] || curl -fsSL -o "build/lib/$MIXIN" "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/$MIXIN"
rm -rf build/classes/*
javac -proc:none --release 21 -cp "build/lib/$MIXIN" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
jar --create --file vanilla-brand-1.0.0.jar -C build/classes .
echo "built vanilla-brand-1.0.0.jar"
