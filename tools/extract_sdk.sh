#!/bin/bash
# Extrae de la app Kami Home (o Yi Home) instalada en TU teléfono (o de sus APK) lo que el proyecto necesita y no está en git:
#   app/libs/yisdk.jar                      SDK P2P de Yi (com.tnp, com.tutk, com.p2p, com.xiaoyi.camera + lo que usan)
#   app/src/main/jniLibs/arm64-v8a/*.so     librerías nativas TNP/TUTK, VoAACEncoder para "Hablar"
#   yi.properties                           clave HMAC del login de la API de Yi y tabla XOR del QR
#
# Uso:
#   tools/extract_sdk.sh                                        # desde el teléfono conectado por adb
#   tools/extract_sdk.sh base.apk split_config.arm64_v8a.apk    # desde APK ya descargados
#
# Requiere: adb (solo modo teléfono), d2j-dex2jar (brew install dex2jar), JDK 17+ (jar, jdeps), unzip.
# Probado con Kami Home 4.9.9 (com.yitechnology.kamihome) y Yi Home 6.9.9 (com.ants360.yicamera.international):
# mismo SDK, misma nube (gw-*.xiaoyi.com) y misma clave de login. Si están las dos se usa Kami (SDK más nuevo).
set -euo pipefail

PKGS="com.yitechnology.kamihome com.ants360.yicamera.international"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

need() { command -v "$1" >/dev/null || { echo "Falta '$1'. $2" >&2; exit 1; }; }
need d2j-dex2jar "Instálalo con: brew install dex2jar"
need jar "Instala un JDK 17+"
need jdeps "Instala un JDK 17+"
need unzip ""

# ---------- 1. conseguir los APK ----------
if [ $# -ge 2 ]; then
  cp "$1" "$WORK/base.apk"; cp "$2" "$WORK/arm64.apk"
else
  ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
  [ -x "$ADB" ] || { echo "No encuentro adb. Pasa los APK como argumentos o define ADB=/ruta/adb" >&2; exit 1; }
  # --user 0: en teléfonos con espacio clonado (user 999) pm sin usuario falla
  paths=""
  for PKG in $PKGS; do
    paths="$("$ADB" shell pm path --user 0 "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://' || true)"
    [ -n "$paths" ] && break
  done
  [ -n "$paths" ] || { echo "Ni Kami Home ni Yi Home están instaladas en el teléfono conectado" >&2; exit 1; }
  ver="$("$ADB" shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName | sed 's/.*=//' || true)"
  echo "$PKG $ver encontrada en el teléfono"
  "$ADB" pull "$(grep '/base.apk$' <<<"$paths")" "$WORK/base.apk" >/dev/null
  "$ADB" pull "$(grep 'split_config.arm64_v8a.apk$' <<<"$paths")" "$WORK/arm64.apk" >/dev/null \
    || { echo "No hay split arm64 (¿teléfono de 32 bits?)" >&2; exit 1; }
  # imágenes de modelos (opcional): están en el split de densidad
  dpi="$(grep -E 'split_config\.[a-z]*dpi\.apk$' <<<"$paths" | head -1 || true)"
  [ -n "$dpi" ] && "$ADB" pull "$dpi" "$WORK/dpi.apk" >/dev/null || true
fi

# ---------- 2. dex → jar ----------
echo "Convirtiendo dex → jar (tarda ~1 min)…"
mkdir -p "$WORK/jars" "$WORK/sdk"
unzip -qo "$WORK/base.apk" 'classes*.dex' -d "$WORK/dex"
for d in "$WORK"/dex/classes*.dex; do
  d2j-dex2jar -f -o "$WORK/jars/$(basename "$d" .dex).jar" "$d" >/dev/null 2>&1 &
done
wait
take() { # take <prefijo>… : copia esos paquetes (sin subpaquetes) desde todos los jars
  for j in "$WORK"/jars/*.jar; do
    for p in "$@"; do unzip -qo "$j" "$p/*" -d "$WORK/sdk" 2>/dev/null || true; done
  done
}

# ---------- 3. SDK + clausura de dependencias con jdeps ----------
take com/tnp com/p2p com/tutk com/xiaoyi/camera com/xiaoyi/log com/xiaoyi/p2pservertest \
  com/aac com/audio/handle com/decoder com/encoder/util com/freq com/langtao com/sinaapp \
  com/xiaomi/fastvideo glnk
# fuera: plataforma, apache-http legacy, firebase-perf (instrumentación) y los players GL/ijk que no usamos
SKIP='^(java|javax|android|androidx|kotlin|org\.json|org\.w3c|org\.xml|dalvik|org\.apache\.http|com\.google\.firebase|tv\.danmaku|com\.video\.draw)(\.|$)|^not$'
for i in $(seq 1 10); do
  (cd "$WORK/sdk" && jar cf "$WORK/yisdk.jar" .)
  missing=$(jdeps -verbose:package --ignore-missing-deps "$WORK/yisdk.jar" 2>/dev/null \
    | awk '/not found/ {print $3}' | sort -u | grep -vE "$SKIP" || true)
  new=()
  for p in $missing; do d="${p//.//}"; [ -d "$WORK/sdk/$d" ] || new+=("$d"); done
  [ ${#new[@]} -eq 0 ] && break
  echo "  dependencias ronda $i: ${new[*]}"
  take "${new[@]}"
done
mkdir -p "$ROOT/app/libs"
cp "$WORK/yisdk.jar" "$ROOT/app/libs/yisdk.jar"

# ---------- 4. librerías nativas ----------
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI"
# webrtc_apm (+ audio_preprocessing, c++_shared) = MobileAEC: cancelación de eco del intercomunicador full-dúplex
for l in PPPP_API IOTCAPIs AVAPIs sCHL VoAACEncoder webrtc_apm webrtc_audio_preprocessing c++_shared; do
  unzip -qjo "$WORK/arm64.apk" "lib/arm64-v8a/lib$l.so" -d "$JNI"
done

# ---------- 5. clave HMAC del login (ae.d2 / Sha256Util en 6.9.9) ----------
key=$(for j in "$WORK"/jars/*.jar; do unzip -p "$j" ae/d2.class 2>/dev/null; done | strings -n 32 | grep -m1 -oE '[A-Za-z0-9$]{32}$' || true)
[ -n "$key" ] || { echo "No encontré la clave HMAC del login (¿otra versión de Yi Home?)" >&2; exit 1; }
echo "YI_LOGIN_KEY=$key" > "$ROOT/yi.properties"

# ---------- 6. tabla XOR de la clave Wi-Fi en el QR de emparejamiento (70 caracteres alfanuméricos, no hex) ----------
xor=$(strings -n 70 "$WORK"/dex/classes*.dex | grep -E '^.?[A-Za-z0-9]{70}$' | grep -oE '[A-Za-z0-9]{70}$' | grep -E '[g-zG-Z]' | sort -u || true)
[ "$(wc -l <<<"$xor" | tr -d ' ')" = 1 ] && [ -n "$xor" ] || { echo "No encontré la tabla XOR del QR (¿otra versión de Yi Home?)" >&2; exit 1; }
echo "YI_PAIR_XOR=$xor" >> "$ROOT/yi.properties"

# ---------- 7. imágenes del catálogo de modelos (assets/catalog.json → assets/models/*.png) ----------
MODELS="$ROOT/app/src/debug/assets/models"  # solo debug: no van en el APK redistribuible
mkdir -p "$MODELS"
for img in $(grep -oE '"image": "[^"]+"' "$ROOT/app/src/main/assets/catalog.json" | cut -d'"' -f4); do
  for a in "$WORK/dpi.apk" "$WORK/base.apk"; do
    [ -f "$a" ] || continue
    f=$(unzip -Z1 "$a" 2>/dev/null | grep -E "^res/drawable[^/]*/$img\.(png|webp)$" | sort -r | head -1 || true)
    [ -n "$f" ] && { unzip -p "$a" "$f" > "$MODELS/$img.${f##*.}"; break; }
  done
done

echo "Listo: app/libs/yisdk.jar ($(du -h "$ROOT/app/libs/yisdk.jar" | cut -f1)), $(ls "$JNI" | wc -l | tr -d ' ') .so, $(ls "$MODELS" | wc -l | tr -d ' ') imágenes, yi.properties"
