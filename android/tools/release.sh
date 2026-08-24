#!/usr/bin/env bash
#
# Publica una versión: compila el release FIRMADO, calcula su SHA-256, escribe el update.json que
# lee la app y crea el release en GitHub con los dos ficheros adjuntos.
#
#   tools/release.sh -n "Qué cambia en esta versión"
#
# La versión que se publica es la que hay ahora mismo en app/build.gradle.kts: sube ahí
# versionCode y versionName ANTES de ejecutarlo. El versionCode es lo que compara la app —
# el versionName es solo para leerlo.
set -euo pipefail

cd "$(cd "$(dirname "$0")/.." && pwd)"

# En este equipo no hay java en el PATH; el JBR de Android Studio es el que usa el propio IDE.
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

NOTES=""
ALLOW_DIRTY=0
while [ $# -gt 0 ]; do
  case "$1" in
    -n|--notes)    NOTES="${2:-}"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    -h|--help)     sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Opción desconocida: $1" >&2; exit 1 ;;
  esac
done

die() { echo "✗ $*" >&2; exit 1; }

VERSION_CODE=$(grep -oE 'versionCode *= *[0-9]+' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
VERSION_NAME=$(grep -oE 'versionName *= *"[^"]+"' app/build.gradle.kts | sed 's/.*"\(.*\)"/\1/' | head -1)
REPO=$(grep -E '^githubRepo=' gradle.properties | cut -d= -f2- | tr -d ' ')
TAG="v$VERSION_NAME"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || die "no pude leer versionCode/versionName de app/build.gradle.kts"
[ -n "$REPO" ] || die "falta githubRepo=usuario/repo en gradle.properties (sin él la app no busca actualizaciones)"

# ⚠️ Sin keystore.properties el release sale SIN FIRMAR y no se puede ni instalar: mejor parar aquí
# que publicar un APK que nadie puede usar.
[ -f keystore.properties ] || die "falta keystore.properties: el release saldría sin firmar"

if [ "$ALLOW_DIRTY" = 0 ] && [ -n "$(git status --porcelain)" ]; then
  die "hay cambios sin commitear; el tag debe apuntar a lo que se publica (o usa --allow-dirty)"
fi
git rev-parse "$TAG" >/dev/null 2>&1 && die "el tag $TAG ya existe: sube versionName en app/build.gradle.kts"
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 && die "el release $TAG ya está publicado"

echo "▸ Publicando $TAG (versionCode $VERSION_CODE) en $REPO"

./gradlew --quiet :app:assembleRelease

OUT="build/publish"
rm -rf "$OUT"; mkdir -p "$OUT"
APK_SRC="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_SRC" ] || die "no salió $APK_SRC (¿se compiló sin firmar? mira keystore.properties)"
# Nombre estable del asset: es el que va escrito en apkUrl.
APK="$OUT/animeav1.apk"
cp "$APK_SRC" "$APK"

# ── Comprobaciones antes de publicar ──────────────────────────────────────────
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[ -x "$APKSIGNER" ] || die "no encuentro apksigner en $SDK/build-tools"
"$APKSIGNER" verify "$APK" >/dev/null 2>&1 || die "el APK no está firmado"

# ⚠️ Que el APK sepa dónde buscar SUS actualizaciones. Publicar uno con la URL de ejemplo deja a
# quien lo instale sin OTA para siempre, y solo se arregla instalando a mano otra vez.
if unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings | grep -q "TU-USUARIO"; then
  die "el APK lleva la URL de ejemplo: revisa githubRepo en gradle.properties"
fi

SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")

if [ -z "$NOTES" ]; then
  PREV=$(git describe --tags --abbrev=0 2>/dev/null || true)
  NOTES=$([ -n "$PREV" ] && git log --pretty=format:'- %s' "$PREV..HEAD" || echo "Primera versión publicada.")
fi

# ⚠️ apkUrl apunta al asset de ESTE release, no a `latest`: con `latest` la URL serviría otro APK en
# cuanto se publique la siguiente versión y el sha256 dejaría de cuadrar.
cat > "$OUT/update.json" <<JSON
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION_NAME",
  "apkUrl": "https://github.com/$REPO/releases/download/$TAG/animeav1.apk",
  "sha256": "$SHA",
  "sizeBytes": $SIZE,
  "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().strip()))'),
  "minSdk": 21
}
JSON
python3 -c "import json;json.load(open('$OUT/update.json'))" || die "el update.json generado no es JSON válido"

git tag -a "$TAG" -m "$TAG"
git push origin "$TAG"
gh release create "$TAG" --repo "$REPO" --title "AnimeAV1 TV $VERSION_NAME" --notes "$NOTES" \
  "$APK" "$OUT/update.json"

echo "✓ $TAG publicado"
echo "  manifiesto: https://github.com/$REPO/releases/latest/download/update.json"
echo "  sha256:     $SHA"
