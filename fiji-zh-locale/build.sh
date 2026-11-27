#!/usr/bin/env bash
# 构建 Fiji 中文汉化插件（macOS / Linux）
#
# 用法:
#   ./build.sh                            # 自动查找 Fiji 与 JDK
#   FIJI_HOME=/Applications/Fiji.app ./build.sh
#
# 产物输出到 dist/，目录结构与 Fiji 安装目录一致，直接复制即可。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIJI_HOME="${FIJI_HOME:-$(dirname "$ROOT")/Fiji.app}"
OUT_DIR="${OUT_DIR:-$ROOT/dist}"
BUILD_DIR="$ROOT/build"

echo "==> Fiji 目录: $FIJI_HOME"

# ---------- 1. 定位 javac / jar ----------
JAVAC=""
if [ -d "$FIJI_HOME/java" ]; then
  JAVAC="$(find "$FIJI_HOME/java" -type f -name javac 2>/dev/null | head -n 1 || true)"
fi
if [ -z "$JAVAC" ] && command -v javac >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
fi
if [ -z "$JAVAC" ]; then
  echo "错误: 找不到 javac。请安装 JDK 8+，或设置 FIJI_HOME 指向内含 JDK 的 Fiji 目录。" >&2
  exit 1
fi
JAR="$(dirname "$JAVAC")/jar"
echo "==> javac: $JAVAC"

# ---------- 2. 准备 ImageJ1 依赖 ----------
IJ_JAR=""
if [ -d "$FIJI_HOME/jars" ]; then
  IJ_JAR="$(find "$FIJI_HOME/jars" -maxdepth 1 -name 'ij-*.jar' | head -n 1 || true)"
fi
if [ -z "$IJ_JAR" ]; then
  mkdir -p "$ROOT/lib"
  IJ_JAR="$ROOT/lib/ij-1.54p.jar"
  if [ ! -f "$IJ_JAR" ]; then
    URL="https://repo1.maven.org/maven2/net/imagej/ij/1.54p/ij-1.54p.jar"
    echo "==> 下载 ImageJ1 依赖: $URL"
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL -o "$IJ_JAR" "$URL"
    else
      wget -q -O "$IJ_JAR" "$URL"
    fi
  fi
fi
echo "==> ij.jar: $IJ_JAR"

# ---------- 3. 编译 ----------
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

"$JAVAC" -encoding UTF-8 -cp "$IJ_JAR" -d "$BUILD_DIR" "$ROOT/src/Zh_Locale.java"

if [ ! -f "$BUILD_DIR/Zh_Locale.class" ]; then
  echo "错误: 编译产物缺少 Zh_Locale.class" >&2
  exit 1
fi
if ! ls "$BUILD_DIR" | grep -q '^Zh_Locale\$'; then
  echo "错误: 缺少匿名内部类（Zh_Locale\$1.class），打包后会 NoClassDefFoundError。" >&2
  exit 1
fi
echo "==> 编译完成: $(cd "$BUILD_DIR" && ls *.class | tr '\n' ' ')"

# ---------- 4. 打包并整理 dist ----------
mkdir -p "$OUT_DIR/plugins" "$OUT_DIR/macros/AutoRun"
rm -f "$OUT_DIR/plugins/Zh_Locale.jar"
( cd "$BUILD_DIR" && "$JAR" cf "$OUT_DIR/plugins/Zh_Locale.jar" ./*.class )

cp "$ROOT/dict/zh_dict.txt" "$OUT_DIR/plugins/zh_dict.txt"
cp "$ROOT/macros/ChineseLocalization.ijm" "$OUT_DIR/macros/AutoRun/ChineseLocalization.ijm"

echo ""
echo "==> 构建完成: $OUT_DIR/plugins/Zh_Locale.jar"
echo "    把 dist/ 下的内容按相同结构复制到 Fiji 安装目录即可。"
