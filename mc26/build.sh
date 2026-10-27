#!/usr/bin/env bash
# MC 26.3 版 trit 纹理优化 mod —— 直接 javac 编译打包。
# 26.1+ 的游戏已不再混淆，Fabric 也弃用 Intermediary/Yarn，
# 所以**不需要 Loom 的 remapJar**：javac 产出的 class 就是最终产物。
set -e
cd "$(dirname "$0")"

JDK="E:/MC/MC/meta/java_versions/mojang-java-runtime-epsilon-windows-x64-b374544c680d"
GJ="E:/MC/MC/meta/versions/26.3-0.20.1/26.3-0.20.1.jar"
LOADER="E:/MC/MC/meta/libraries/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"
DEPS="D:/KF/.yanagent/mc-texture/mc26/deps"

CP=$(cat cp.txt)

rm -rf build/classes build/jar
mkdir -p build/classes build/jar

echo "== javac =="
"$JDK/bin/javac.exe" -encoding UTF-8 -nowarn -cp "$CP" -d build/classes \
  $(find src -name '*.java')

echo "== 拷贝资源 =="
cp -r resources/. build/classes/

echo "== 打 jar =="
"$JDK/bin/jar.exe" --create --file build/jar/trit-texture-0.2.0.jar -C build/classes .

echo "OK -> $(pwd)/build/jar/trit-texture-0.2.0.jar"
