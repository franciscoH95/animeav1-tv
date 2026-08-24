# AnimeAV1 TV

App de **Android TV** para ver anime desde `animeav1.com`: inicio con "continuar viendo", catálogo,
horario de emisión, listas personales por perfil y reproductor nativo.

Está pensada para el mando: toda la navegación es D-pad, sin depender de una pantalla táctil.

## Qué trae

- **Perfiles**: cada persona de la casa tiene sus listas, su historial y sus puntos de reanudación.
- **Reproductor nativo** (media3/ExoPlayer) con pistas SUB/DUB, cambio de servidor en caliente,
  reanudación, auto-marcado de vistos y salto automático al siguiente episodio.
- **Copia de seguridad** a fichero, exportable e importable entre aparatos.
- **Actualizaciones OTA**: la app comprueba el último release de este repositorio y se actualiza
  sola, verificando el SHA-256 del APK antes de instalarlo.

## Instalar

Descarga el `.apk` del [último release](../../releases/latest) y pásalo a la TV (por ejemplo con
`adb install animeav1.apk`, o con cualquier app de instalación local).

La primera vez, Android pedirá permiso para instalar aplicaciones de orígenes desconocidos. A partir
de ahí las siguientes versiones se instalan desde la propia app.

> ⚠️ Si venías de una versión anterior firmada con otra clave, Android no dejará instalar encima.
> Exporta antes una copia desde *Ajustes del perfil → Copia de seguridad* (se guarda en Descargas y
> sobrevive a desinstalar), desinstala, instala y restaura.

## Compilar

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # tests JVM
./gradlew :app:lintDebug           # abortOnError = true a propósito
```

Para una build de release firmada hace falta un `keystore.properties` en la raíz del módulo con
`storeFile`, `storePassword`, `keyAlias` y `keyPassword`. No se commitea.

`minSdk 21`, `targetSdk 34`, Kotlin + Coroutines, Room, media3 y Coil.

## Aviso

Proyecto personal, sin relación con `animeav1.com` ni con los servidores de vídeo que ese sitio
enlaza. No aloja ni redistribuye contenido: solo lee la web pública y reproduce lo que ella publica.
