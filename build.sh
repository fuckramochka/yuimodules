#!/bin/bash
# Сборка .hmod для одного модуля.
# Использование: ./build.sh demo 1.0.0 stable
# Нужны: JDK 17+, ANDROID_HOME (platforms/android-36/android.jar, build-tools с d8).
# Для модулей с экранами/движками на классах телеграма:
#   export APP_API_JAR=/шлях/до/amegram-app.jar   (зібрати в amenew: ./gradlew :TMessagesProj:exportModuleApi)
# Додаткові compileOnly-банки (gson/okhttp/...) — поклади в ext-libs/ (в рантаймі їх дає застосунок).
set -e
MOD=${1:-demo}
VER=${2:-1.0.0}
BRANCH=${3:-stable}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/out"
rm -rf "$OUT" && mkdir -p "$OUT/classes"

if [ -z "$ANDROID_HOME" ] && [ -d "/home/user/android-sdk" ]; then
  export ANDROID_HOME="/home/user/android-sdk"
fi
if ! command -v javac >/dev/null 2>&1; then
  JDK_BIN="$(ls -d /home/user/.gradle/jdks/*/bin 2>/dev/null | head -1)"
  if [ -n "$JDK_BIN" ]; then export PATH="$JDK_BIN:$PATH"; fi
fi

ANDROID_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
D8=$(ls -d "$ANDROID_HOME/build-tools/"*/d8 2>/dev/null | sort -V | tail -1)

APP_API_DIR="/home/user/amenew/TMessagesProj/src/main/java"
CP="$HERE/hot-api:$ANDROID_JAR"
LIBS="--lib $ANDROID_JAR"
if [ -d "$APP_API_DIR" ]; then
  CP="$CP:$APP_API_DIR"
  LIBS="$LIBS --lib $APP_API_DIR"
elif [ -n "$APP_API_JAR" ] && [ -f "$APP_API_JAR" ]; then
  CP="$CP:$APP_API_JAR"
  LIBS="$LIBS --lib $APP_API_JAR"
fi
for j in "$HERE/ext-libs/"*.jar; do
  [ -f "$j" ] || continue
  CP="$CP:$j"
  LIBS="$LIBS --lib $j"
done

javac -source 8 -target 8 -nowarn \
  -cp "$CP" \
  -d "$OUT/classes" \
  "$HERE/modules/$MOD/"*.java

mkdir -p "$OUT/dex"
CLASS_FILES=$(find "$OUT/classes" -name "*.class")
# shellcheck disable=SC2086
"$D8" --min-api 27 $LIBS --output "$OUT/dex" $CLASS_FILES

python3 - "$HERE/modules/$MOD/manifest.json" "$VER" "$BRANCH" <<'PY'
import json, sys
src, ver, branch = sys.argv[1], sys.argv[2], sys.argv[3]
m = json.load(open(src))
m["version"] = ver
m["branch"] = branch
json.dump(m, open("/tmp/hmod_manifest.json", "w"), ensure_ascii=False)
PY

mkdir -p "$OUT/pkg" && cp "$OUT/dex/classes.dex" "$OUT/pkg/" && cp /tmp/hmod_manifest.json "$OUT/pkg/manifest.json"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT/pkg" && zip -q -X "$OUT/$MOD-$VER.hmod" classes.dex manifest.json)
else
  (cd "$OUT/pkg" && python3 -c "import zipfile, sys; z = zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED); z.write('classes.dex'); z.write('manifest.json'); z.close()" "$OUT/$MOD-$VER.hmod")
fi
sha256sum "$OUT/$MOD-$VER.hmod"
echo "Готово: $OUT/$MOD-$VER.hmod — залей в релиз и обнови modules.json (url + sha256 + version)."
