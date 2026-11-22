#!/usr/bin/env bash
# Builds vanilla-brand-<version>.jar without Gradle/Loom (Minecraft 26.x is unobfuscated, no remapping needed).
# Needs JDK 25+ (Minecraft 26.x classes are Java 25); set JAVA_HOME if the default javac is older.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=1.1.0
MC=26.3
FAPI=0.166.2+26.3
MIXIN=sponge-mixin-0.17.5+mixin.0.8.7.jar
L=build/lib
mkdir -p $L build/classes
[ -f "$L/$MIXIN" ] || curl -fsSL -o "$L/$MIXIN" "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/$MIXIN"
if [ ! -f $L/client.jar ]; then
  url=$(curl -fsSL https://piston-meta.mojang.com/mc/game/version_manifest_v2.json | python3 -c "import json,sys;print([v['url'] for v in json.load(sys.stdin)['versions'] if v['id']=='$MC'][0])")
  curl -fsSL -o $L/client.jar "$(curl -fsSL "$url" | python3 -c "import json,sys;print(json.load(sys.stdin)['downloads']['client']['url'])")"
fi
if [ ! -f $L/fabric-networking.jar ]; then
  curl -fsSL -o $L/fabric-api.jar "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FAPI/fabric-api-$FAPI.jar"
  unzip -p $L/fabric-api.jar "$(unzip -Z1 $L/fabric-api.jar | grep 'META-INF/jars/fabric-networking-api-v1')" > $L/fabric-networking.jar
fi
rm -rf build/classes/*
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -proc:none --release 21 -cp "$L/$MIXIN:$L/client.jar:$L/fabric-networking.jar" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
rm -f vanilla-brand-*.jar
"${JAVA_HOME:+$JAVA_HOME/bin/}jar" --create --file vanilla-brand-$VERSION.jar -C build/classes .
echo "built vanilla-brand-$VERSION.jar"
