#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/android-sdk
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build
mkdir -p build/classes
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 \
  -o build/base.apk
find src -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -classpath "$JAR" -d build/classes @build/sources.txt
"$BT/d8" --min-api 24 --output build build/classes/tcl/remote/*.class
python3 - << 'PY'
import zipfile
with zipfile.ZipFile("build/base.apk", "a") as apk:
    apk.write("build/classes.dex", "classes.dex")
PY
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Controle TCL"
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out ControleTCL.apk build/aligned.apk
echo "APK: $(pwd)/ControleTCL.apk"
