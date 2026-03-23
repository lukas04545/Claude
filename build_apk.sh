#!/bin/bash
# ============================================================
#  Manual Android APK build script  (v3 — R-first)
# ============================================================
set -e

KOTLINC=/opt/kotlinc/bin/kotlinc
ANDROID_SDK=/usr/lib/android-sdk
ANDROID_JAR=$ANDROID_SDK/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_SDK/build-tools/debian
AAPT=$BUILD_TOOLS/aapt
ZIPALIGN=$BUILD_TOOLS/zipalign

PROJECT=/home/user/Claude/app
SRC=$PROJECT/src/main/java
RES=$PROJECT/src/main/res
MANIFEST=$PROJECT/src/main/AndroidManifest.xml
BUILD=/home/user/Claude/build_output
KEYSTORE=$BUILD/debug.keystore
MVN=https://repo1.maven.org/maven2

rm -rf $BUILD && mkdir -p $BUILD/{classes,gen,obj,dex,libs,aar_extract}

echo "=== Step 1: Download dependencies from Maven Central ==="

download() {  # group artifact version ext
  local group=$(echo $1 | tr '.' '/') art=$2 ver=$3 ext=${4:-jar}
  local file=$BUILD/libs/${art}-${ver}.${ext}
  [ -f "$file" ] && return 0
  echo "  Fetching $art-$ver.$ext"
  curl -sL --max-time 90 "$MVN/$group/$art/$ver/$art-$ver.$ext" -o "$file" || true
}

# Plain JARs
download org.jetbrains.kotlin  kotlin-stdlib        1.9.22
download org.jetbrains.kotlin  kotlin-stdlib-jdk8   1.9.22
download org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.7.3
download org.jetbrains.kotlinx kotlinx-coroutines-android  1.7.3

echo "=== Step 2: Build classpath ==="
CLASSPATH=$ANDROID_JAR
for jar in $BUILD/libs/*.jar; do
  [ -f "$jar" ] && CLASSPATH="$CLASSPATH:$jar"
done
echo "  Classpath entries: $(echo $CLASSPATH | tr ':' '\n' | wc -l)"

echo "=== Step 3: Generate R.java with aapt (BEFORE Kotlin compile) ==="
mkdir -p $BUILD/gen

$AAPT package -f -m \
  -J $BUILD/gen \
  -M $MANIFEST \
  -S $RES \
  -I $ANDROID_JAR \
  2>&1 | grep -v "^$" | head -20 || true

RJAVA=$(find $BUILD/gen -name "R.java" 2>/dev/null | head -1)
if [ -n "$RJAVA" ]; then
  echo "  Found R.java: $RJAVA"
  echo "  Compiling R.java..."
  javac -cp "$CLASSPATH" -d $BUILD/classes \
    -source 8 -target 8 -nowarn "$RJAVA" 2>&1 | head -10 || true
  echo "  R class files: $(find $BUILD/classes -name 'R*.class' | wc -l)"
else
  echo "  ERROR: R.java not generated!"
  exit 1
fi

# Add compiled R classes to classpath
CLASSPATH="$CLASSPATH:$BUILD/classes"

echo "=== Step 4: Compile Kotlin sources ==="
KOTLIN_SOURCES=$(find $SRC -name "*.kt" | sort)
echo "  Compiling $(echo $KOTLIN_SOURCES | wc -w) Kotlin files..."

$KOTLINC \
  -cp "$CLASSPATH" \
  -d $BUILD/classes \
  -jvm-target 1.8 \
  -nowarn \
  $KOTLIN_SOURCES 2>&1 | grep -v "^w:" | grep -v "JAVA_TOOL_OPTIONS" || true

CLASS_COUNT=$(find $BUILD/classes -name '*.class' 2>/dev/null | wc -l)
echo "  Class files: $CLASS_COUNT"

if [ "$CLASS_COUNT" -lt 5 ]; then
  echo "  ERROR: Too few class files — compilation likely failed"
  exit 1
fi

echo "=== Step 5: Convert classes -> DEX ==="
# dalvik-exchange installs dx at this path
DX_BIN=$BUILD_TOOLS/dx

if [ -x "$DX_BIN" ]; then
  echo "  Using dx: $DX_BIN"
  $DX_BIN --dex \
    --min-sdk-version=26 \
    --output=$BUILD/dex/classes.dex \
    $BUILD/classes/ \
    2>&1 | head -20 || true
else
  DX_JAR=$(find $ANDROID_SDK -name "dx.jar" 2>/dev/null | head -1)
  if [ -n "$DX_JAR" ]; then
    echo "  Using dx.jar: $DX_JAR"
    java -jar "$DX_JAR" --dex --min-sdk-version=26 --output=$BUILD/dex/classes.dex $BUILD/classes/ \
      2>&1 | head -20 || true
  else
    echo "  WARN: No DEX tool found"
    exit 1
  fi
fi

DEX_FILE=$(find $BUILD/dex -name "*.dex" 2>/dev/null | head -1)
if [ -z "$DEX_FILE" ]; then
  echo "  ERROR: DEX file not produced"
  exit 1
fi
echo "  DEX file: $DEX_FILE ($(ls -lh $DEX_FILE | awk '{print $5}'))"

echo "=== Step 6: Package APK ==="
APK_UNALIGNED=$BUILD/app-unaligned.apk

$AAPT package -f \
  -M $MANIFEST \
  -S $RES \
  -I $ANDROID_JAR \
  -F $APK_UNALIGNED \
  2>&1 | head -10 || true

# Add DEX(es)
for dex in $BUILD/dex/*.dex; do
  (cd $(dirname $dex) && zip -qu $APK_UNALIGNED $(basename $dex) 2>&1) || true
  echo "  Added $(basename $dex) to APK"
done

echo "  APK size (unaligned): $(ls -lh $APK_UNALIGNED | awk '{print $5}')"

echo "=== Step 7: Zipalign ==="
APK_ALIGNED=$BUILD/app-aligned.apk
$ZIPALIGN -f 4 $APK_UNALIGNED $APK_ALIGNED 2>&1 || cp $APK_UNALIGNED $APK_ALIGNED

echo "=== Step 8: Generate debug keystore & sign ==="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore $KEYSTORE -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1
fi

APK_FINAL=/home/user/Claude/app-debug.apk
java -jar $BUILD_TOOLS/apksigner.jar sign \
  --ks $KEYSTORE \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --min-sdk-version 26 \
  --out $APK_FINAL \
  $APK_ALIGNED 2>&1

echo ""
if [ -f "$APK_FINAL" ]; then
  echo "=== BUILD COMPLETE ==="
  ls -lh $APK_FINAL
  echo "APK ready: $APK_FINAL"
  echo "APK verification:"
  java -jar $BUILD_TOOLS/apksigner.jar verify --verbose $APK_FINAL 2>&1 | head -5
else
  echo "=== BUILD FAILED - APK not produced ==="
  exit 1
fi
