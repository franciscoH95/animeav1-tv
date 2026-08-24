# AnimeAV1 — Android TV app (contexto para Claude)

App de **Android TV (Leanback)** que scrapea `animeav1.com` para ver anime: inicio (continuar viendo),
catálogo, horario de emisión, listas personales y reproductor nativo. Kotlin, MVVM, Coroutines/StateFlow,
Room, media3 (ExoPlayer), Coil. `applicationId = com.animeav1`, `minSdk 21`, `targetSdk/compileSdk 34`.

> Hay un `../scraper.py` (Python) en la carpeta de arriba — componente aparte y **fuera del repo**
> (está en `.gitignore`: lo que se publica es la app). Este doc cubre solo `android/`.

---

## Build y prueba (entorno de este equipo)

- **macOS.** No hay JAVA_HOME global ni `java` en el PATH: usa el JBR de Android Studio.
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. SDK en
  `~/Library/Android/sdk` (es lo que debe decir `sdk.dir` en `local.properties`, que **no** se
  commitea). `platform-tools` para `adb`.
- **Compilar:** `JAVA_HOME=... ./gradlew :app:assembleDebug`. **Sin `--offline`**: las dependencias
  no están cacheadas en este equipo (la primera build necesita red).
- **Tests:** `./gradlew :app:testDebugUnitTest` (JVM, sin emulador). **Lint:** `./gradlew :app:lintDebug`
  — `abortOnError = true`, así que un `NewApi` por encima de `minSdk` rompe la build en vez de crashear
  en el salón de alguien. `assembleRelease` ejecuta `lintVitalRelease`, así que también depende de esto.
- **Emulador:** API 34, imagen Play Store → no root, no se puede fijar el reloj por adb.
- **⚠️ Gotcha de firma:** `adb install -r` suele fallar con `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  (la versión instalada por Android Studio se firma con otra clave). **Solución:** `adb uninstall com.animeav1`
  y luego `adb install ...`. Esto **borra los datos locales** (Room) del emulador — normal en pruebas.
- **⚠️ Vídeo "glitcheado" en el emulador:** los streams suelen ser **AV1**; el decoder AV1 *por software*
  del emulador produce artefactos. **No es un bug** — en una Android TV real con hardware AV1 se ve limpio.
- **Verificar UI:** `adb shell uiautomator dump` + `adb shell screencap`. El dump de foco a veces va
  desfasado por timing; confirma con screenshot. Los items de RecyclerView no tienen id (su raíz),
  así que en el dump aparecen sin `resource-id`.

---

## Arquitectura

```
data/
  AnimeRepository.kt    object singleton; OkHttp + scraping + cachés en memoria (LruCache(128) + TTL)
  SvelteKitDecoder.kt   resuelve el formato __data.json "aplanado" de SvelteKit
  StreamUrlParser.kt    mitad PURA de la resolución de stream (regex + transform Zilla +
                        secFetchSite), sin red ni Android
  EmbedParser.kt        mitad PURA de la lectura de servidores del episodio: resuelve el nodo
                        `embeds` a List<EmbedServer> con AMBAS pistas (SUB+DUB) etiquetadas
  BackupCodec.kt        mitad PURA del formato de copia de seguridad (JSON ⇄ las 3 tablas)
  AiringSchedule.kt     mitad PURA del cálculo del PRÓXIMO episodio (ancla + cadencia); ver abajo
  MediaType.kt          mitad PURA que DEDUCE el tipo (el sitio llama "TV Anime" hasta a las
                        películas); ver "El tipo que publica el sitio está mal"
  AnimeImages.kt        mitad PURA: las URLs del CDN (portada, backdrop y MINIATURA por episodio),
                        que son convenciones y no datos de la API
  UpdateManifest.kt     mitad PURA del OTA: leer update.json y decidir si hay algo que ofrecer
  UpdateRepository.kt   descarga del manifiesto y del APK, con verificación SHA-256
  ApkInstaller.kt       instalación vía PackageInstaller (+ InstallResultReceiver)
  UpdateChecker.kt      cuándo se mira (intervalos) y contra qué URL
  BackupStore.kt        dónde vive el fichero de copia: MediaStore + buzón (ver "No perder los datos")
  LocalRepository.kt    dueño ÚNICO del estado local (listas, vistos, puntos de reanudación)
  ProfileManager.kt     dueño del perfil ACTIVO (SharedPreferences + StateFlow); ver "Perfiles"
  local/ (Room)         Entities.kt, Daos.kt, AppDatabase.kt
  model/Models.kt       Anime, Series, EpisodeRef, AudioTrack, EmbedServer, ScheduleItem, etc.
viewmodel/              BrowseViewModel, ScheduleViewModel, LocalViewModel, SeriesViewModel, PlayerViewModel,
                        HomeViewModel (filas de descubrimiento del Inicio; ver "Navegación")
ui/
  MainActivity.kt       4 tabs (Inicio/Catálogo/Horario/MiLista) + contenedor de fragments
  home/ browse/ schedule/ mylist/ series/ player/   (browse/ incluye SearchActivity a pantalla completa)
  profile/              ProfilesActivity (selector + gestión), ProfileSettingsActivity (ajustes del
                        perfil: perfiles + copia), ProfileAdapter, ProfileAvatars
  backup/               BackupActivity (exportar / importar)
  update/               UpdateActivity ("Hay una versión nueva" → descarga → instala)
AnimeApp.kt             Application; AnimeRepository.init(); appScope (ver más abajo)

app/src/test/            tests JVM puros (JUnit4), 97 en total. Fixtures REALES capturados del
                         sitio en test/resources/: catalogo__data.json, mp4upload-embed.html y
                         episodio__data.json (dandadan ep.1 — el único con SUB *y* DUB). Cubren
                         SvelteKitDecoder, StreamUrlParser, EmbedParser, MediaType, UpdateManifest
                         y BackupCodec (incluido
                         que un backup del FORMAT 1 se siga importando, y que un FORMAT 3 SIN el
                         campo aditivo `prefs` siga entrando entero) — las piezas que más
                         veces se han roto. `org.json:json` está como testImplementation porque
                         el org.json de android.jar es un stub que lanza excepción.
```

Nota: la UI usa **RecyclerView + adapters** (`*Adapter.kt`). Los restos de la versión Leanback anterior
(varios `*Presenter.kt`, `ScheduleActivity`, `LoginActivity`, `SearchDialogFragment` y sus layouts) se
**eliminaron**, junto con la dependencia `androidx.leanback`. El tema base pasó a `Theme.AppCompat.NoActionBar`
(antes `Theme.AppCompat.Leanback`). Se conservan `<uses-feature android.software.leanback>` y
`LEANBACK_LAUNCHER` en el manifest para que la TV liste la app.

---

## El sitio: formato de datos (clave)

`animeav1.com` es un sitio SvelteKit. Los datos se sacan de los endpoints `__data.json`:

- Catálogo:  `/catalogo/__data.json?page=N&search=&category=&genre=&status=&order=`
  (`order=score` = mejor valorados; verificado contra el sitio. `CatalogFilter.order` queda **fuera**
  de `isEmpty` a propósito: ordenar no es filtrar, así que no debe encender el chip "Quitar filtros")
- Serie:     `/media/<slug>/__data.json`
- Episodio:  `/media/<slug>/<number>/__data.json`  → de aquí salen los `embeds` (servidores)
- Horario:   (lo maneja `ScheduleViewModel`/repo)

El JSON es un **array aplanado con índices**: cada objeto referencia otros nodos por su posición en el
array. `SvelteKitDecoder.decode(json, rootKey)` lo resuelve a un `JSONObject` usable. User-Agent fijo
(Firefox) en todas las peticiones; `usesCleartextTraffic=true` por si algún stream va por HTTP.

**El catálogo NO trae año ni estado** — solo `id, title, slug, synopsis, category{name}`. El `category`
es el **tipo** ("TV Anime"/"Película"/"OVA"/"Especial"). Año/estado solo están en el detalle de la serie.

### ⚠️ El tipo que publica el sitio está mal, y el tipo se DEDUCE (`data/MediaType.kt`)

`category` dice "TV Anime" para **casi todo**: de las 1000 fichas del catálogo, exactamente **una**
está marcada como "Película", dos como OVA y una como Especial (medido con
`/catalogo/__data.json?category=pelicula|ova|especial`). Así que *One Piece Film: Red* o *Digimon
Adventure: Last Evolution Kizuna* —un solo episodio de 94 minutos— salían como serie. **No es un
fallo de la app**: la web del sitio también las enseña como "TV Anime" y su JSON trae
`category:{name:"TV Anime"}` junto a `runtime:94`.

`MediaType.refine` corrige el tipo, y **solo cuando la evidencia es inequívoca** — hoy el error de
origen es inofensivo (todo dice lo mismo), mientras que pasarse de listo afirmaría algo falso de una
obra concreta:

1. **Lo dice el título** (romaji o el `aka` español): "Movie", "Film", "Película". Verificado sobre
   los 1000 títulos del catálogo: casan 32 y las 32 son películas de verdad (*Chainsaw Man Movie:
   Reze-hen*, *Spy x Family Movie: Code: White*, *Versailles no Bara (Movie)*…), **cero falsos
   positivos**.
2. **Un ÚNICO episodio de ≥70 min** que no esté en emisión. Rescata a las que no lo dicen en el
   nombre (Kizuna 94', *Dragon Ball Super: Broly* 100', *Kingsglaive* 115').

⚠️ El umbral es 70 y no 60: por debajo empiezan los **especiales y OVAs** de un capítulo largo, que
no son películas (*Ensa no Edinburgh* 52', *Grisaia: Caprice no Mayu 0* 47', *Cocoon* 60'). Las
películas cortas de ese rango —las de Dragon Ball Z, 46'-51'— ya las caza la regla del título. Y se
exige no estar **en emisión** porque una serie recién estrenada tiene `episodesCount == 1` y puede
abrir con un episodio doble.

⚠️ **Si el sitio dice algo distinto de "TV Anime", se le cree** y no se toca: es su único dato
deliberado. Y ⚠️ **el catálogo solo puede aplicar la regla del título**, porque no trae `runtime` ni
`episodesCount` (la misma limitación que con el año y el estado): Kizuna sale como "TV Anime" en la
rejilla y como "Película" en su ficha. La ficha es la que manda, y es la que se guarda en
`favorite_series`, así que Mi Lista enseña el tipo bueno.

---

## Resolución de servidores/streams (lo más frágil)

`AnimeRepository.getEmbeds(slug, n)` devuelve `List<EmbedServer>` (server, url, **audio**).
`extractStreamUrl(embedUrl)` convierte la URL del embed en una URL **directa** `.mp4`/`.m3u8`
reproducible por ExoPlayer:

- **HLS (Zilla)** → transform directo: `player.zilla-networks.com/play/<id>` ⇒ `/m3u8/<id>` (id de 32 chars).
  Sin JS, sin token. Es el más fiable, pero ⚠️ **no hay ninguna priorización en código**: el reproductor
  toma el **índice 0** de lo que devuelve el sitio (o el `preferredServer`/`preferredAudio` heredados del
  episodio anterior). Hoy Zilla sale primero porque el sitio lo lista primero; si eso cambiara, cambiaría
  el servidor por defecto.
  ⚠️⚠️ **La playlist NO basta: los segmentos exigen `Sec-Fetch-Site: same-origin`.** El Cloudflare que hay
  delante del CDN devuelve **403** a cada `/segs/<id>/NNN.html` cuyo request no traiga esa cabecera con ese
  valor **literal** (`same-site`, `cross-site`, `none` o cualquier otro → 403), mientras que `/m3u8/<id>` sí
  pasa sin ella. El síntoma NO es un error de red: media3 carga la playlist, no recibe ni un segmento y se
  queda en `STATE_BUFFERING` para siempre hasta que el watchdog lo mata con "HLS no responde". Se manda en
  `initPlayer()` vía `setDefaultRequestProperties`, con el valor calculado por
  `StreamUrlParser.secFetchSite(url, referer)` (se calcula de verdad en lugar de mentir: para Zilla los
  segmentos van al mismo host que el embed). Los segmentos son **fMP4 (AV1) con extensión `.html`** —
  disfrazados a propósito; media3 los reconoce por sniffing, no por extensión ni Content-Type.
- **MP4Upload** → el `.mp4` está en texto plano en el HTML del embed. **Requiere `Referer: mp4upload.com`**
  (con otro referer el CDN da 403 HTML). ⚠️ El regex DEBE anclar la extensión al final
  (`...\.mp4(?=["'\s<>]|$)`); si no, `.mp4` casa con el **dominio** `mp4upload.com` y extrae el `.js` del player.
  Es indiferente a las cabeceras `Sec-Fetch-*` (verificado: 206 con y sin ellas).
- **YourUpload** → el `.mp4` está en texto plano en el HTML del embed, en `vidcache.net:8161`, y
  **redirige** (302) a `s410.vidcache.net:8166` — funciona porque `setAllowCrossProtocolRedirects(true)`.
  Requiere `Referer: yourupload.com` (sin él, HTTP 500). Es el único servidor en **H.264**: los demás
  son AV1, así que es el que se ve bien en el emulador y en hardware sin decoder AV1. ⚠️ Su URL lleva
  token de un solo uso — ver "Limitaciones conocidas".
- **Servidores filtrados** (nunca llegan a la UI, lista en `EmbedParser.UNSUPPORTED_SERVERS`):
  - `Mega`, `UPNShare` (uns.bio) → cifran el stream y lo descifran con su JS de cliente.
  - `TeraBox`, `StreamTape`, `VidHide` → el HTML del embed no trae ninguna URL directa (0 coincidencias
    de `directUrlFrom` en todos los embeds probados del sitio real).
  - `DoodStream` → responde el reto JS de Cloudflare ("Just a moment…", HTTP 403); un GET plano no lo pasa.
  - `Netu` (hqq.ac) → sí deja un `.m3u8` en el HTML, pero es un **señuelo**: ruta de 2018, timestamp de
    2020 y la IP `94.25.170.26` incrustada en la propia URL. Ni siquiera conecta.

  ⚠️ Es una lista negra **a propósito**, no una lista blanca: un servidor nuevo que el sitio empiece a
  ofrecer sigue apareciendo y se le da la oportunidad de resolver. Y se compara por **nombre exacto** de
  servidor (+ un respaldo por host), no por subcadena: `"netu" in url` casaría por accidente con
  cualquier URL que contenga esa secuencia.
- El `Referer` para ExoPlayer se deriva del **host del embed** (en `PlayerViewModel.refererOf`), no de animeav1.

### Pistas de audio (SUB / DUB)

El sitio publica cada episodio en dos bloques independientes, `embeds.SUB` y `embeds.DUB`, **cada uno con su
propia lista de servidores y sus propias URLs**. `EmbedParser.parse` devuelve las **dos** en una sola lista
(SUB primero, orden del sitio dentro de cada pista), etiquetadas con `AudioTrack`, y filtra los no
reproducibles de ambas. Solo si **ninguna** pista deja un servidor soportado la lista sale vacía
("No hay servidores") — es honesto.

> Antes `getEmbeds` se quedaba con la **primera pista que tuviera algo reproducible**, así que el doblaje
> era literalmente inalcanzable desde la app aunque el sitio lo ofreciera.

⚠️ **El nombre del servidor ya no identifica un embed**: "HLS" existe en las dos pistas. `PlayerActivity`
rastrea `selectedEmbed`/`playingEmbed` **por identidad de `EmbedServer`**, no por `String`, y `StreamState`
lleva el `EmbedServer` entero. Si añades comparaciones por nombre, vuelves a mezclar subtitulado y doblaje.

---

## Reproductor (`ui/player/PlayerActivity.kt`)

- **ExoPlayer nativo** (se eliminó el WebView). `DefaultHttpDataSource` con UA + `Referer` (mapa de headers).
- **Pantalla y audio:** el `PlayerView` usa `surface_type="surface_view"` (overlay por HW/HDR en TV).
  Se mantiene `FLAG_KEEP_SCREEN_ON` **solo mientras reproduce** (no en pausa/`STATE_ENDED`), vía
  `updateKeepScreenOn()`. El `ExoPlayer.Builder` pide audio focus (`setAudioAttributes(.., handleAudioFocus=true)`)
  y `setHandleAudioBecomingNoisy(true)`.
- **⚠️ MIME para HLS sin extensión:** la URL de Zilla es `/m3u8/<id>` (sin `.m3u8`), así que media3 la trataría
  como progresiva y falla (`UnrecognizedInputFormatException`). **Se fuerza** `MimeTypes.APPLICATION_M3U8`
  cuando `"m3u8" in url`. El módulo `media3-exoplayer-hls` ya está en el build.
- **Controles propios (no los de media3):** el foco D-pad del controlador de media3 en TV con layout
  personalizado **no es fiable**. Por eso `use_controller="false"` y un `controls_overlay` propio en
  `activity_player.xml` con `ImageButton`s (iconos vectoriales `ic_*` + `icon_button_bg` circular).
  El **transporte se maneja por teclas** en `dispatchKeyEvent`. La `DefaultTimeBar` (`ctrl_timebar`) es
  **focusable = el scrubber**: al mostrar controles el foco aterriza ahí. Con el scrubber enfocado
  **IZQ/DER = seek ±10s** (`seekFromTimebar` refleja la posición al instante), CENTRO = play/pausa,
  ABAJO baja a la fila de botones; los botones tienen `nextFocusUp=@id/ctrl_timebar` para volver al scrubber.
  Con los controles **ocultos**, IZQ/DER también hacen seek (y muestran controles), **ARRIBA abre el panel
  de servidores** directamente; cualquier otra tecla solo muestra. Auto-oculta a los 5s; la barra se
  refresca además por un loop de 500ms. Las teclas **multimedia físicas** (`KEYCODE_MEDIA_*`) se manejan
  en una rama común ANTES de bifurcar por estado, así funcionan también con controles visibles y modales.
  Mientras los controles están visibles, el rótulo "EP N" del loading overlay se pone INVISIBLE
  (`ep_info_block`) para no solaparse con la timebar.
  ⚠️ Los botones de episodio anterior/siguiente en el límite (primer/último) **no se deshabilitan** (eso los
  haría no-focusables y rompería la cadena `nextFocus` hacia Servidores/Visto): se atenúan con `alpha` y el
  clic es no-op por el guard de rango de `goToEpisode` (que además consulta el **visto real** en Room antes
  de lanzar el intent del episodio destino).
- **Fuente preferida POR SERIE (no por episodio).** Al elegir servidor, `onServerSelected` guarda
  `(pista, servidor)` en `series_prefs` para ESE perfil (fire-and-forget en `appScope`), y al abrir
  el reproductor esa preferencia **manda sobre los extras del intent**: los extras solo saben del
  episodio anterior, la tabla sabe de todas las veces que se ha visto la serie. Así, volver a una
  serie que se veía doblada no la reabre en subtitulado.
  ⚠️ Y la preferencia **se lee incondicionalmente**: pisa a `preferredServer`/`preferredAudio` del
  intent, no al revés. Leerla solo "si el extra viene vacío" dejaba la otra mitad del agujero abierta:
  `goToEpisode` arrastra `selectedEmbed`, que tras un fallback NO es lo que el usuario eligió, así que
  un stall de 25 s en YourUpload dejaba el resto del maratón en HLS (AV1) aunque la tabla dijera otra
  cosa.
  ⚠️ **Solo se guarda con `fromUser = true`**, y por ahí pasa únicamente el clic del panel. Por
  `onServerSelected` entran también el pick automático del arranque y el fallback por fallo de CDN:
  persistir esos convertía un accidente en la preferencia permanente de la serie. Un episodio recién
  emitido que el sitio publica solo en SUB dejaba fijada en subtitulado una serie que se veía
  DOBLADA (y no se autorreparaba: al reabrir volvía a escribir SUB), y un stall de 25 s en YourUpload
  —el único H.264— la dejaba fijada en HLS.
  ⚠️ `pendingServerSwitch` lo decide **quien llama** (`switchInPlace`), no `player != null`: el
  fallback libera el player ANTES de llegar aquí, así que calcularlo dentro daba false y se perdía la
  posición — el episodio arrancaba de cero, o salía "¿Continuar viendo?" a mitad de reproducción. En
  el fallback en cadena el valor se lee de `pendingServerSwitch` **antes** de resetearlo.
  ⚠️ El pick por defecto **espera a `prefsReady`**: al recrearse la Activity el StateFlow retenido de
  embeds reemite al instante y corría con `preferredServer`/`preferredAudio` todavía a null.
- **Fallback automático de fuente.** `triedEmbeds` recuerda lo ya intentado y
  `nextUntriedSource(failed)` devuelve la siguiente fuente **de la misma pista de audio** (nunca
  salta de doblado a subtitulado por su cuenta). Cuando un stream falla —o el watchdog lo mata— se
  prueba sola la siguiente y solo se abre el panel cuando **se agotan todas**.
  ⚠️ Antes cualquier fallo eran 25 s de spinner y luego un panel de "servidores": una decisión
  técnica que un usuario final no puede tomar. Cubre los tres orígenes de fallo: resolución
  (`StreamState.Failed`), watchdog y **`PlaybackException` de media3** (`onPlaybackError`) — un error
  de reproducción es igual de poco accionable para el usuario que un CDN callado.
- **La pantalla de error es un modal y el D-pad tiene que llegar a sus botones.** `dispatchKeyEvent`
  la trata como tal (rama propia antes de la de "controles ocultos"). ⚠️ Sin esa rama, la de controles
  ocultos consumía TODAS las teclas devolviendo `true` y sus dos acciones (`showControls`/`scrub`)
  hacen return inmediato con `player == null` — que es exactamente el estado del error: el mando
  quedaba **muerto** y no se podía pulsar ni "Reintentar" ni "Salir". Va después de ⏭/⏮ a propósito:
  cambiar de episodio es una salida legítima de un error. Y "Reintentar" cuando lo que falló fue la
  **lista** de servidores (no hay ningún embed que reintentar) vuelve a pedirla con `loadEmbeds`, no
  es un no-op; antes de eso hace `vm.clearError()` **y `vm.clearEmbeds()`**, porque los dos son
  StateFlow y conflatan valores iguales: sin `clearError` un segundo fallo con el mismo mensaje no
  reemitiría, y sin `clearEmbeds` —el caso que se escapó— repedir una lista que había llegado **vacía**
  vuelve a poner `emptyList()`, el mismo valor, así que el colector no se enteraba y `showLoading`
  dejaba la pantalla con el spinner para siempre, sin mensaje y **sin ningún botón**: un cuelgue del
  que solo se salía con BACK.
  ⚠️ Reintentar a mano limpia `triedEmbeds` (en la rama que SÍ tiene embed, no en la que no lo tiene):
  es "vuelve a intentarlo todo". Sin eso, tras agotar la cadena por un corte de red pasajero cada
  pulsación reintentaba SOLO la última fuente que falló, 25 s de watchdog por pulsación.
- **Scrub acelerado (mantener IZQ/DER).** El paso crece con las repeticiones: **10 s** las 3
  primeras, **30 s** hasta la 10ª, **60 s** después. Mientras hay scrub en curso la timebar y los
  rótulos muestran el **objetivo** (`scrubTargetMs`), no la posición real, y el `seekTo` se aplica
  **una sola vez al soltar** la tecla (`commitScrub` en el `ACTION_UP`): así se rebufferiza una vez
  en lugar de una por pulsación. El loop de 500 ms que refresca la barra se inhibe mientras
  `scrubTargetMs >= 0`, si no pisaría el objetivo con la posición vieja. ⚠️ **Todos** los caminos de seek llaman a
  `cancelNextIfAwayFromEnd()`, que mata la cuenta atrás del siguiente episodio si la posición queda a
  más de `NEXT_CARD_REMAINING_MS` del final: el card lo dispara `endMonitorJob` mirando la posición
  REAL, así que podía aparecer —y saltar de episodio— justo mientras el usuario rebobinaba. No basta
  ponerlo en `commitScrub`: con la tarjeta en pantalla `hideControls()` ya se ha llevado los botones de
  ±10 s y IZQ/DER navegan entre "Ver ahora"/"Cancelar", así que las teclas físicas ⏪/⏩ son el ÚNICO
  seek que queda en ese estado.
- **Watchdog de stream atascado:** algunos CDN aceptan la conexión y nunca envían segmentos (media3 queda
  en `STATE_BUFFERING` para siempre, sin error). `startStallWatchdog` (loop 1s) vigila `bufferedPosition`:
  si no avanza en **25s** de buffering → `onStallTimeout()` = release + error "X no responde" + panel de
  servidores. Se cancela en `releasePlayer`.
- **Cambio de servidor fallido:** si `StreamState.Failed` llega con `player != null` (el stream viejo sigue
  reproduciéndose), NO se tapa con el overlay de error: se restaura la selección (`playingEmbed`),
  Toast y se reabre el panel. BACK desde el panel con player activo también hace `hideLoading()` (nunca
  dejar el overlay pegado sobre vídeo en reproducción).
- **No crear el player en background:** los collectors de stream no son lifecycle-aware a propósito, pero
  `initPlayer()` tiene guard `lifecycle.currentState.isAtLeast(STARTED)` — si la resolución termina con la
  Activity parada (HOME a mitad de carga), no suena audio sobre el launcher; `onStart` lo crea al volver.
- **STATE_ENDED:** `togglePlay` hace `seekTo(0)+play` si el estado es ENDED, y el icono play/pausa
  considera ENDED como "no reproduciendo" (antes quedaba un callejón sin salida al acabar el contenido).
- **Un solo loader:** `show_buffering="never"` (el spinner de media3 traspasaba el scrim y se veían dos);
  `loading_overlay` (carga inicial) y `buffering_spinner` (re-buffering) nunca coexisten.
- **⚠️ El reproductor NO toca los DAOs.** Todo el estado local pasa por `LocalRepository` (`private val
  local by lazy { LocalRepository(AppDatabase.get(applicationContext)) }`). Antes reimplementaba aquí la
  regla de promoción de listas y las dos copias ya podían divergir. Si añades una regla nueva, va al
  repositorio, no a la Activity.
- **Punto de reanudación:** tabla Room `episode_progress`; guarda cada 5s + al liberar el player. La
  **política** vive en `LocalRepository` (`RESUME_MIN_MS` 10s, `RESUME_END_FRACTION` 0.97, `isFinished()`):
  por debajo del mínimo o pasado el 97% no se guarda punto, se borra. `saveProgress` además **no reescribe
  el progreso de un episodio ya marcado visto** (si no, el saver de 5s resucitaba la fila que el
  auto-marcado acababa de borrar). Al reentrar muestra overlay "¿Continuar viendo?" (Reanudar / Desde el inicio).
  **Cambiar de servidor a mitad de reproducción reanuda en el sitio** (flag `pendingServerSwitch`), sin
  re-mostrar el overlay. La fila *Continuar viendo* del Inicio pasa el extra **`autoResume=true`**: el
  player reanuda directo en el punto guardado sin re-preguntar (el clic en esa tarjeta YA es la elección).
  `saveProgress` además hace `insertIfAbsent` de la serie en `favorite_series` con `listType="none"`
  (no aparece en ninguna sub-lista de Mi Lista) para que el JOIN de `getContinueWatching` tenga
  título/portada aunque la serie nunca se haya agregado a una lista.
- **Auto-marcar visto:** un `endMonitorJob` (loop de 1s, solo mientras reproduce) marca visto cuando quedan
  **≤2 min** (`WATCHED_REMAINING_MS`), además del `STATE_ENDED`. Guard: solo en la segunda mitad
  (`position >= duration/2`) para no marcar clips cortos al inicio. `autoMarkWatched` es idempotente
  (`isWatched`). Delega en `LocalRepository.markWatchedThrough`, que en **una sola transacción** marca el
  episodio + anteriores, **borra el `episode_progress` del rango** (un `clearRange`, no N deletes) y mueve
  la serie a Viendo/Completadas. Además `getContinueWatching` excluye con `NOT EXISTS` los
  episodios presentes en `watched_episodes` — un episodio visto nunca se ofrece para "continuar".
  ⚠️ **El ✓ manual marca el mismo rango**, no solo el episodio en curso: las tres entradas de "marcar
  visto" (auto del final, ✓ del reproductor y la ficha) arrastran los anteriores sin marcar. Dejar
  huecos detrás hace mentir al badge "vistos/total" de Mi Lista, al "NUEVO" y al botón *Continuar* de
  la ficha, que apuntaría al primer hueco en vez de a donde va el usuario. **Desmarcar sigue quitando
  solo uno** a propósito: marcar afirma algo que ya pasó, desmarcar en bloque borraría historial que
  nadie ha pedido borrar — a cambio, ✓ y ✓ otra vez no deja las cosas como estaban si había huecos.
- **Al acabar el último episodio disponible se vuelve a la ficha** (`returnToSeriesIfLastEpisode`, desde
  `STATE_ENDED`). Antes el reproductor se quedaba en negro sobre el frame final, ya marcado visto y sin
  nada que hacer salvo BACK. ⚠️ Se navega a `SeriesActivity` con `CLEAR_TOP | SINGLE_TOP` y no con un
  `finish()` pelado: el reproductor no siempre se abre desde la ficha —la fila *Continuar viendo* de
  Inicio lo lanza directo— y un finish devolvería al Inicio; con esos flags, si la ficha ya está en la
  pila se trae al frente **sin recrearla** (conserva scroll y bloque de episodios) y si no, se crea.
  ⚠️ La guarda es `maxEpisode > 0`: sin ese extra `maxEpisode` se deriva de `totalEpisodes`, que puede
  ser 0 en una fila cacheada de `favorite_series`, y `number >= 0` cerraría el reproductor al acabar
  **cualquier** episodio. Sin dato fiable, no se cierra.
- **Auto-reproducción del siguiente (estilo Netflix):** el mismo `endMonitorJob`, cuando quedan **≤30s** y
  hay episodio siguiente (`number < maxEpisode`), muestra `next_episode_card` (esquina inf-der) con miniatura,
  "Episodio N+1" y cuenta atrás de **10s** (`startNextCountdown`) → `goToNextEpisode`. Botones **Ver ahora**
  / **Cancelar** (BACK = cancelar). `nextCardHandled` evita re-mostrarla; `releasePlayer` cancela la cuenta
  atrás (no auto-avanza estando en background). En `dispatchKeyEvent` hay una rama de modal para el card.
- La **tarjeta de siguiente episodio** enseña el fotograma de ESE episodio
  (`AnimeImages.episodeThumbFromCover`, derivado de la portada porque el reproductor recibe la URL y
  no el id de la serie). Antes ponía el backdrop de la serie, o sea la misma imagen para los 1172
  episodios de One Piece. Si el CDN no lo tiene (403) se cae a la imagen de la serie, que es lo que
  había antes.
- **Panel de servidores** (DPAD-ARRIBA o botón ☰): lista lateral **agrupada por pista de audio**, con
  cabeceras `SUBTITULADO` / `DOBLADO` (`item_server_header.xml`); al elegir, re-resuelve y reproduce desde
  la misma posición. `ServerAdapter` tiene por eso **dos view types**: las cabeceras son
  `focusable="false"` para que el D-pad salte de servidor a servidor sin pararse en los rótulos, y la
  selección se guarda **por `EmbedServer`**, no por índice (los índices del adapter dejaron de coincidir
  con los de la lista de embeds en cuanto se intercalaron cabeceras).
  ⚠️ **Abrir el panel son dos cosas separadas y las dos importan:** el *scroll* va a
  `groupStartOf(seleccionado)` (no a `positionOf`, que pegaría el servidor al borde y empujaría su propio
  rótulo fuera de pantalla) y el *foco* se pide **explícitamente** al ViewHolder de
  `positionOf(seleccionado)`. Un `serverList.requestFocus()` pelado NO vale: aterriza en el primer hijo
  focusable adjunto, que con cabeceras ya no es el servidor en uso — y para el grupo DOBLADO (el último)
  el `LinearLayoutManager` ni siquiera puede subir su cabecera del todo, así que el foco caía en una
  tarjeta de SUBTITULADO. Con un mando eso es grave: ARRIBA + CENTRO por reflejo cambiaba de servidor
  **y de idioma** sin pedirlo.
  Cuando el episodio trae las dos pistas, los avisos nombran la pista ("No se pudo cargar HLS (Doblado)")
  vía `PlayerActivity.labelOf`; con una sola pista se omite para no meter ruido.
- **`maxEpisode` se refresca con el conteo vivo** (`refreshMaxEpisode`, `getSeries` cacheada). Los
  extras pueden traerlo de una fila cacheada de `favorite_series` —la fila *Continuar viendo* de
  Inicio no refresca totales, eso solo pasa al abrir la ficha—, y en una serie en emisión ese conteo
  va por detrás: cuando coincide con el episodio que se ve, el reproductor lo tomaba por el último
  (ni ofrecía el siguiente, ni dejaba avanzar con ⏭, y al marcar visto lo trataba como fin de serie).
- Extras del intent que necesita: `slug, number, title, coverUrl, backdropUrl, totalEpisodes, minEpisode,
  maxEpisode, seriesStatus, startDate, category, preferredServer, preferredAudio, isWatched`.
  `preferredAudio` (`"SUB"`/`"DUB"`) se arrastra junto a `preferredServer` al pasar de episodio: si venías
  viendo el doblaje, el siguiente episodio no debe saltar al subtitulado solo porque SUB va primero.
- Ciclo de vida: crea el player en `onStart` (API>23) / `onResume` (≤23), lo libera en `onStop`/`onPause`,
  preservando posición.

`AnimeApp.appScope` = `CoroutineScope(SupervisorJob + Executors.newSingleThreadExecutor().asCoroutineDispatcher())`:
scope de app **de un solo hilo** para escrituras fire-and-forget de progreso/visto que deben sobrevivir al
cierre de la Activity y **serializarse** (evita reordenar clear/upsert).

---

## Room (persistencia local)

**`AppDatabase` versión 10**, `exportSchema=false`, con migraciones reales (no destructivas) +
`fallbackToDestructiveMigration()` **solo en debug** (ver "No perder los datos del usuario"):

- `Profile` (PK id autogenerado, + `uuid` con índice ÚNICO): name, colorIndex, createdAt. Ver "Perfiles".
- `FavoriteSeries` (PK **profileId+slug**): title, coverUrl, listType, totalEpisodes, isFavorite, addedAt,
  **year, status, category** (con `@ColumnInfo(defaultValue=...)`). Getter `statusName` mapea status→texto.
- `WatchedEpisode` (PK **profileId**+slug+number)
- `EpisodeProgress` (PK **profileId**+slug+number): positionMs, durationMs, updatedAt
- `SeriesPrefs` (PK **profileId**+slug): audio (`"SUB"`/`"DUB"`), server, updatedAt. Cómo quiere el
  usuario ver ESA serie; ver "Fuente preferida POR SERIE" en el reproductor.

**Migraciones:** 4→5 añadió `episode_progress`; 5→6 añadió `year`/`status` a favorite_series; 6→7 añadió
`category`; **7→8 metió `profileId` en las claves primarias** (recreando las tablas — ver "Perfiles");
9→10 creó `series_prefs`
(tabla nueva: no toca ninguna de las tres de estado, así que no hay nada que recrear ni copiar);
8→9 añadió `profiles.uuid` (aquí sí basta un ADD COLUMN: no se toca ninguna PK, así que las tres tablas
de estado **no** se recrean). ⚠️ En la 8→9 el orden importa: el ADD COLUMN deja todas las filas con `''`,
así que el índice único va **después** del relleno. Los uuid se generan en SQL con `randomblob`, que
SQLite evalúa por fila, para que el relleno vaya dentro de la misma transacción que la migración.
**Patrón para añadir una columna:** `MIGRATION_x_y` con `ALTER TABLE ... ADD COLUMN ... DEFAULT ...` y el
mismo `@ColumnInfo(defaultValue=...)` en la entidad (Room valida que coincidan). **Para cambiar una clave
primaria no vale `ALTER`**: hay que recrear la tabla y copiar las filas, como hace la 7→8.

**⚠️ Promoción de listas — UNA sola implementación.** Marcar visto mueve la serie a Viendo (si no
estaba en ninguna lista o estaba en Por Ver) y a Completadas (último episodio + `status == 0`). Esa regla
vive SOLO en `LocalRepository.applyPromotion`, y la usan las dos entradas: `markWatchedThrough` (el
reproductor, marca el rango `minEpisode..ep`) y `setWatched` (el diálogo de la ficha, un episodio).
Antes había tres copias divergentes — la de la ficha nunca llegaba a Completadas y creaba la fila con
`totalEpisodes = 0`, lo que dejaba Mi Lista mostrando "▶ 3 ep" en vez de "▶ 3/12". Si tocas la regla,
tócala ahí.

**⚠️ Regla de acceso:** los DAOs son privados de `LocalRepository`; **nadie fuera de `data/` los usa**
(solo se construye el repositorio). Las operaciones multi-paso (`addToList`, `markWatched`,
`markWatchedThrough`, borrar una serie) van en `db.withTransaction { }`. No hay `@ForeignKey`/CASCADE:
al borrar la fila de una serie, `removeSeriesRow` borra también su `episode_progress` (si no, los puntos
de reanudación sobreviven de por vida y reaparecen como un "¿Continuar viendo?" rancio al re-agregarla).
`watched_episodes` **se conserva a propósito**: salir de una lista no es pedir borrar lo ya visto.
`setFavorite` tampoco borra una fila `listType="none"` si aún tiene progreso guardado — esa fila es lo
que da título/portada a la tarjeta de Inicio.

**Propagación de year/status/category:** se guardan al agregar a lista / marcar favorito / marcar visto,
desde `SeriesFragment` (tiene el `Series` completo: `startDate.take(4)`=año, `status`, `category`) y desde
el reproductor (vía extras del intent). `LocalRepository.updateMeta` hace **backfill** de year/status/category
**y refresca `totalEpisodes`** (conteo vivo `series.episodes.size`) al abrir el detalle de una serie ya
listada (no-op si no está en lista) — así el badge "vistos/total" de Mi Lista sigue a las series en emisión.
El catálogo no guarda nada (es solo lectura).

**Inicio / "Continuar viendo":** `FavoriteSeriesDao.getContinueWatching()` une `episode_progress` (solo
episodios sin terminar; el reproductor borra al ~97%) con `favorite_series`, agrupa por slug y toma el más
reciente (`MAX(updatedAt)`). **Badge "NUEVO" (Mi Lista):** `LocalViewModel.refreshAiringTotals` refresca en
segundo plano el conteo de las series en emisión (status==2) al abrir Mi Lista (una vez por sesión, `getSeries`
cacheado); el badge sale si `status==2 && 1 ≤ vistos < total`.

---

## Perfiles

Varias personas comparten la TV: cada perfil tiene sus **propias** listas, episodios vistos y puntos
de reanudación. `profiles` (id autogenerado, name, colorIndex, createdAt) y `profileId` **dentro de la
clave primaria** de las tres tablas de estado local: `favorite_series(profileId, slug)`,
`watched_episodes(profileId, animeSlug, episodeNumber)`, `episode_progress(idem)`.

⚠️ **TODA consulta de estado local filtra por `profileId`.** Una que se olvide mezcla las listas de dos
personas, y el síntoma que ve el usuario es "en mi lista hay series que yo no puse". En
`getContinueWatching` va en las **tres** partes (el progreso, el `JOIN` de la serie y el `NOT EXISTS`
de vistos): sin filtrar el JOIN, el progreso de uno se emparejaría con la ficha del otro; sin filtrar
el NOT EXISTS, lo que uno ha visto ocultaría el "continuar" del otro.

**`ProfileManager`** (object, SharedPreferences) es el dueño del perfil activo y lo expone como
`activeId: StateFlow`. ⚠️ El **nombre y el color** también son StateFlow y van **emparejados**
(`updateActive(name, colorIndex)`, y los consumidores los `combine`): tenerlos en dos formas distintas
—el nombre observable y el color en un `var` que solo escribía `setActive`— dejaba el avatar con el
color anterior al editar el perfil activo, y sin repintar nada si solo cambiaba el color (el StateFlow
del nombre conflata el valor igual). Era el mismo perfil de dos colores distintos según la pantalla,
justo lo que `ProfileAvatars` existe para evitar. Vive fuera de Room porque es una preferencia del aparato, no un dato del
usuario, y hay que poder leerlo antes de la primera consulta. `AnimeApp.onCreate` llama a su `init()`
**antes** de `AppDatabase.get()`.

En `LocalRepository`:
- `private val pid get() = ProfileManager.requireActiveId()` — se lee **en cada llamada**, nunca se
  cachea en un campo. El repositorio se construye una vez (LocalViewModel, reproductor, pantalla de
  copias) y sobrevive al cambio de perfil; un id cacheado dejaría al reproductor escribiendo en el
  perfil anterior.
- Los `Flow` se encadenan con `ProfileManager.activeId.flatMapLatest { dao.getX(pid) }`. Eso es lo que
  hace que cambiar de perfil repueble Inicio y Mi Lista **sin reiniciar la app**; con el id resuelto
  una sola vez, el colector seguía mostrando las listas del perfil anterior.
- `deleteProfile` limpia las tres tablas de ese perfil **y** su fila, en una transacción (no hay
  `@ForeignKey`/CASCADE), y **no deja borrar el último**: quedarse sin ninguno deja la app sin dónde
  escribir.
- `ensureActiveProfile()` se llama al arrancar y cubre los dos huecos: una **instalación limpia** no
  pasa por la migración 7→8, así que nadie habría creado el perfil por defecto; y el id guardado puede
  apuntar a un perfil ya borrado.

**Migración 7→8 (la primera que mueve datos, no solo añade columnas).** SQLite **no puede cambiar una
clave primaria**, así que recrea las tres tablas: `CREATE ..._new` → `INSERT INTO ..._new SELECT` →
`DROP` → `RENAME`. Todo lo que ya existía se adopta en el perfil `Profile.DEFAULT_ID` (1, "Principal"),
así que quien venía usando la app encuentra sus listas donde estaban. Las columnas se enumeran
**explícitamente** en el `INSERT ... SELECT`: un `SELECT *` dependería del orden físico de las columnas,
que tras tres migraciones ya no es el del `data class`.

⚠️ **El foco del selector se pide desde el adapter** (`ProfileAdapter.pendingFocusId`), no con un
`post {}` desde la Activity. Los perfiles llegan de un Flow y `notifyDataSetChanged` solo *programa*
el layout: en ese momento el RecyclerView aún no tiene hijos, así que
`findViewHolderForAdapterPosition` devuelve null y `requestFocus()` falla en silencio — el selector se
quedaba **sin foco en ninguna tarjeta** y con un mando no había forma de elegir perfil. Se consume en
`onBindViewHolder`, que corre cuando la vista ya existe. Mismo problema en el foco inicial de Inicio,
resuelto ahí con un `OnGlobalLayoutListener` de un solo uso (`HomeFragment.focusWhenReady`).

**UI** (`ui/profile/`): ⚠️ las **tres** pantallas de perfil resuelven el perfil por su cuenta
(`ensureActiveProfile`): `ProfileManager.init()` solo restaura el *id* de SharedPreferences, así que
tras una muerte de proceso el nombre arranca vacío y el color en 0 — y cualquiera de las tres puede ser
la primera en recrearse. El **avatar de la barra global** abre `ProfileSettingsActivity` —los ajustes
del perfil activo: su nombre, "Perfiles" y "Copia de seguridad"—, y desde ahí se llega a
`ProfilesActivity`, que sirve de selector y de gestión según el extra `EXTRA_PICKER`.

> El nombre del perfil y el botón "Copia" vivían en la cabecera de Mi Lista. Eran acciones de la
> **app** metidas en una fila cuyo trabajo es elegir sub-lista, y con un mando había que cruzarla
> entera para llegar a ellas; además dejaban esa cabecera distinta de las otras tres. Ahora el nombre
> se ve en la pantalla que habla del perfil, y el avatar (que ya decía "estás en Ana") es la puerta. Avatares = inicial sobre color (`ProfileAvatars`, que concentra la paleta **y** el
pintado, incluido el "+" de la tarjeta de añadir); sin imágenes, que en una TV se leen peor y habría
que meterlas en la copia de seguridad.

⚠️ **El rótulo "ACTIVO" de la tarjeta va `invisible`, nunca `gone`.** Con `gone` la tarjeta del perfil
activo medía 54 px más que las demás (medido en captura: 554 frente a 500) y la fila salía escalonada.
El hueco se reserva siempre y el adapter alterna VISIBLE/INVISIBLE. Lo mismo en la tarjeta "Añadir
perfil", que sin reservarlo volvía a descuadrar la fila.

**Cuándo sale el selector.** SIEMPRE al abrir la app, también con un solo perfil: es la puerta de
entrada.

- Se lanza desde `MainActivity.onCreate` de forma **síncrona**, no dentro de `resolveProfile()`: esa
  función espera a Room, y con el selector saliendo en cada arranque ese hueco se veía como un
  parpadeo de Inicio detrás. `ProfilesActivity` se encarga ella misma de que exista al menos un
  perfil (`ensureActiveProfile`), que hace falta en una instalación limpia — si no, la primera vez
  saldría un selector con solo "Añadir perfil".
- La condición es `savedInstanceState == null`, o sea **arranque de verdad**. Con estado guardado
  venimos de una rotación o de que el sistema mató el proceso y restauró la sesión donde estaba:
  preguntar ahí no es "entrar en la app", es interrumpir. Volver del reproductor o de la ficha
  tampoco pasa por aquí — `MainActivity` no se recrea, solo se reanuda.
- ⚠️ Como selector, **BACK sale de la app** — preguntando primero (ver "Confirmación de salida").
  Antes se tragaba el BACK para que nadie llegara a la app sin haber elegido, pero eso solo era
  aceptable cuando el selector aparecía en contadas ocasiones; saliendo en cada arranque dejaba al
  usuario **atrapado** en la puerta, sin más salida que HOME. Dejarlo pasar tampoco valdría: caería
  en la app con un perfil que no ha elegido, que es justo lo que se quiere evitar. Al confirmar usa
  `finishAffinity()`, no `finish()`: cerrar solo el selector devolvería al usuario a la app sin haber
  elegido perfil.
- ⚠️ La parte de *resolver* el perfil (`resolveProfile`) corre siempre, incluso al recrearse la
  Activity y aunque el selector vaya a preguntar por encima: el id está en preferencias pero el
  **nombre** solo se conoce tras un `setActive`, y sin eso el botón de perfil de Mi Lista salía vacío.

**Cambiar de perfil devuelve a Inicio.** Lo que se acaba de hacer es *entrar* como otra persona, y
las demás pestañas siguen enseñando lo de la anterior hasta que Room reemite — Mi Lista es
literalmente "la lista de otro". Son tres piezas y hacen falta las tres:

- En modo **gestión** ya no se sigue administrando: se entra en la app, y vale para las **dos**
  formas de cambiar de perfil desde ahí —elegirlo y **crear uno nuevo**, que también se activa—,
  las dos por el mismo `goToHome()`: navega a `MainActivity` con `CLEAR_TOP | SINGLE_TOP`.
  ⚠️ Un `finish()` pelado no vale: entre medias está
  `ProfileSettingsActivity`, así que devolvería al usuario a los ajustes del perfil que **acaba de
  abandonar**. Con esos flags se cierra todo lo que hay por encima y `MainActivity` se reutiliza sin
  recrearse (verificado: la pila queda solo con `MainActivity`), el mismo patrón que la vuelta a la
  ficha desde el reproductor. Si se pulsa el perfil que YA estaba activo no se navega: no ha
  cambiado nada. En modo **selector** el destino es el mismo, solo que ahí basta con cerrar:
  `MainActivity` está justo debajo.
- `MainActivity` observa `ProfileManager.activeId` y se pone en Inicio cuando cambia. Así queda
  cubierto **todo** camino, incluidos los que no navegan: **borrar el perfil activo**
  reapunta a otro (verificado: al volver, Inicio). ⚠️ Va con `repeatOnLifecycle(STARTED)` y no con
  un `collect` pelado: el cambio
  ocurre mientras `MainActivity` está PARADA (el usuario está en la pantalla de perfiles) y hacer
  ahí el `commit()` de la transacción de fragments revienta con `IllegalStateException` por llegar
  después de `onSaveInstanceState`.
- `HomeFragment.rearmInitialFocus()` recoloca el foco como en una entrada nueva. ⚠️ **Fuerza** la
  colocación aunque el foco ya esté dentro, que es justo lo que la colocación normal se prohíbe: al
  cambiar de perfil aparecen secciones ARRIBA (*Continuar viendo*, *Mi lista*) que empujan la
  tarjeta enfocada **fuera de la pantalla**. Sigue enfocada —el foco no se pierde— pero no se ve:
  medido en el emulador, el volcado de `uiautomator` no traía ni una vista con `focused="true"`
  porque no lista lo que no cabe en pantalla, y para el usuario Inicio aparecía sin ningún aro y sin
  saber dónde tenía el mando. Sale 1 de cada 3 veces según qué fila gane la carrera de carga, así
  que hay que probarlo varias veces seguidas. Respeta `keyTicks`: si el usuario pulsa cualquier
  tecla antes de que las filas estén listas, manda él.

⚠️ Y un agujero que esto destapó, general a toda la app: **el diff de `submitList` es asíncrono, así
que `rescuingFocus` puede no ver la expulsión**. Mira el foco alrededor del cambio de visibilidad,
pero la tarjeta enfocada no se retira ahí sino cuando el diff se aplica, un momento después: da el
rescate por innecesario y ya no vuelve a mirar nadie. Esconder la sección entera sí lo caza (ese
cambio es síncrono); lo que se escapa es la sección que sigue VISIBLE y pierde solo la tarjeta
enfocada —tres series en *Continuar viendo* y se termina una—. Por eso las tres filas locales de
Inicio pasan un `commitCallback` a `submitList` (`rescueAfterDiff`), que solo actúa si el foco
estaba DENTRO antes del cambio: si el usuario lo había subido a la barra de pestañas, una reemisión
de Room no tiene por qué bajárselo otra vez.

Un perfil nuevo arranca **vacío** a propósito, y se activa al crearlo.

⚠️ **El editor de perfil bloquea la rejilla mientras está abierto** (`setGridEnabled(false)`). Un
overlay VISIBLE encima no aísla nada: `FocusFinder` no sabe de oclusión, así que las tarjetas tapadas
seguían entrando en la búsqueda de foco y una pulsación IZQUIERDA desde el campo de texto saltaba a una
tarjeta invisible bajo el scrim — donde CENTRO cambiaba de perfil sin que se viera nada. Mismo motivo por
el que el panel de servidores del reproductor enfoca su fila explícitamente.

⚠️ **El "OK" del teclado cierra el teclado** (`setOnEditorActionListener` en `editor_name`, con
`imeOptions="actionDone"`). En una TV el teclado ocupa media pantalla y **se come las pulsaciones
del D-pad**: mientras está puesto, ABAJO recorre sus teclas y no baja ni a los colores ni a Guardar,
así que la única salida era BACK, que no se anuncia en ninguna parte. El foco se queda en el campo a
propósito y no salta a Guardar: lo siguiente puede ser elegir color, que está entre medias.

⚠️ **Al borrar el perfil activo se reapunta el activo ANTES de borrarlo.** Al revés queda una ventana en
la que `requireActiveId()` devuelve un perfil que ya no existe; si la corrutina muere ahí (salir de la
pantalla cancela `lifecycleScope`), la app pasa el resto de la sesión escribiendo progreso y listas en un
perfil fantasma. `ensureActiveProfile()` lo repara, pero solo en el arranque siguiente.

**Dos identificadores, y cada uno tiene su sitio.** `Profile` lleva los dos:

- **`id: Long`** (`AUTOINCREMENT`, la PK) — clave **local**. Es lo que llevan las tres tablas de estado
  en su clave primaria: un entero es más barato de indexar y dentro del aparato basta.
- **`uuid: String`** (índice ÚNICO) — identidad **estable entre aparatos**, y lo único que viaja en la
  copia de seguridad. Se genera al crear el perfil y no se reasigna nunca.

⚠️ **El fichero de copia NO referencia perfiles por `id`.** El `id` es un ordinal del aparato que
escribió el fichero: el "perfil 2" de una TV no es el "perfil 2" de otra, así que un backup keyed por
`id` **fusionaba personas distintas** al importarlo en otro aparato. Con el uuid, `importAll` resuelve
cada referencia contra `profiles.uuid`: si está, es el mismo perfil; si no, crea uno nuevo. Y por eso
importar dos veces el mismo fichero es idempotente en vez de duplicar perfiles.

---

## Confirmación de salida

BACK cierra la app en **dos** pantallas —`MainActivity` y el selector de perfiles— y en las dos
pregunta antes. La implementación es **una sola**: `view_exit_confirm.xml` (incluido con `<include>`
en los dos layouts) + `ui/ExitConfirm.kt`. Dos overlays iguales acabarían divergiendo, que es la clase
de duplicación que este proyecto ya ha pagado antes.

- Overlay propio y no un `AlertDialog`, como el resto de modales de la app: el foco por D-pad es
  predecible. Debe ser el **último hijo de un FrameLayout**; por eso `activity_main.xml` lleva un
  FrameLayout exterior envolviendo su LinearLayout (no cambia nada de la disposición).
- ⚠️ `show()` saca el contenido de debajo de la búsqueda de foco
  (`descendantFocusability = FOCUS_BLOCK_DESCENDANTS`). Un overlay VISIBLE no aísla nada: FocusFinder
  no sabe de oclusión, así que las pestañas y las tarjetas seguirían siendo alcanzables con el D-pad
  por detrás del scrim — el mismo fallo que tuvo el editor de perfil.
- **El foco arranca en "Salir"**: quien pulsa BACK quiere irse, así que la salida deliberada son dos
  pulsaciones (BACK + CENTRO).
- ⚠️ **BACK con la pregunta abierta la cancela**, no confirma. Es lo que hacen todos los modales de
  esta app, y aquí además es lo que hace que machacar BACK —el accidente que esta pantalla existe
  para evitar— no acabe cerrando nada. Si BACK confirmara, machacar BACK cerraría la app igual y la
  pregunta no serviría para nada.
- Al cancelar se devuelve el foco a donde estaba (`focusBefore`), no al primer focusable.

---

## No perder los datos del usuario

Tres mecanismos distintos, y hacen falta los tres porque cada uno cubre un fallo diferente.

### 1. Auto Backup del sistema (automático)

`allowBackup=true` + `backup_rules.xml` (API ≤30) + `data_extraction_rules.xml` (API 31+). **Verificado
de punta a punta** en un Android TV (API 36): `bmgr backupnow` → `adb uninstall` → `adb install` y los
tres ficheros de Room volvieron solos, con listas, vistos y punto de reanudación intactos.

⚠️ **Las reglas usan SOLO `<exclude>`, nunca `<include>`.** En cuanto hay un `<include>` el backup se
reduce a lo listado, y eso dejaría fuera `animeav1.db-wal`. No es teórico: en una captura real justo
después de importar, el `.db` eran **4 KB sin ninguna tabla legible** y todo estaba en un `-wal` de
78 KB. Un backup que copie solo el `.db` puede llevarse una base de datos vacía. Solo se excluye el
`-shm`, que SQLite reconstruye al abrir.

⚠️ **No se puede dar por hecho.** En la imagen de Android TV el **Backup Manager viene desactivado**
(`bmgr enabled` → "currently disabled") y el único transport disponible es `LocalTransport`, el de
pruebas: el de Google (`com.google.android.gms/.backup.BackupTransportService`) está en la whitelist
pero no disponible sin cuenta. Para probar en emulador: `adb shell bmgr enable true` +
`adb shell bmgr transport com.android.localtransport/.LocalTransport`.

### 2. Exportar / importar a fichero (`ui/backup/BackupActivity`, en Ajustes del perfil)

Serializa las tres tablas **y los perfiles** a JSON. Tres formatos escritos hasta hoy, y `decode` lee
los tres — se escribieron backups con todos ellos, y dejar de leer uno convertiría un respaldo válido y
ya guardado por el usuario en basura:

| v | perfiles | referencia de cada fila | portable entre aparatos |
|---|----------|-------------------------|--------------------------|
| 1 | ninguno | (implícita) | trivialmente: todo a un perfil |
| 2 | con `id` local | `profileId` numérico | **no** — ver "Perfiles" |
| 3 | con `uuid` | `profileUuid` | sí |

Solo se **escribe** el formato 3. Cada fila del fichero llega con un `ProfileRef` (`Active` para el
formato 1, `LegacyId` para el 2, `Uuid` para el 3) y quien lo traduce a ids locales —creando los
perfiles que falten— es `LocalRepository.importAll`. ⚠️ Por eso `BackupData` **no** son las `@Entity`
de Room: cuando lo eran, las filas llegaban ya con un `profileId` que solo tenía sentido en el aparato
que las escribió. El formato vive en `BackupCodec` (mitad **pura**, con tests) y sus
claves están **congeladas y son distintas de los nombres de columna de Room** a propósito: renombrar un
campo de una `@Entity` no debe invalidar los backups ya escritos. `BackupCodec.FORMAT` se versiona
aparte de la versión de Room — y no cambia por añadir un campo: `prefs` (las preferencias de
pista/fuente) entró **sin subir FORMAT** porque es puramente aditivo, así que un lector viejo lo
ignora y uno nuevo lo da por vacío si el fichero no lo trae; un backup con `format` mayor se rechaza entero en vez de importarse a
medias, y a uno más viejo le faltan campos que caen en sus valores por defecto.

⚠️ Al resolver un perfil del **formato 2** se prueba `getById` → `getByName` → crear. El paso por
nombre es lo que hace que importar dos veces el mismo fichero no duplique: el perfil que crea
`createFromBackup` recibe un id nuevo de `AUTOINCREMENT`, nunca el que traía el fichero, así que el
segundo import volvía a fallar el `getById` y creaba otro perfil —y otro juego completo de listas,
vistos y progreso— cada vez. Casar por nombre puede fusionar dos homónimos, pero el formato 2 ya es
inherentemente no portable y duplicar sin límite es peor.

La copia incluye **todos** los perfiles, no solo el activo: dejar los demás fuera perdería los
perfiles de casa justo cuando el usuario cree estar a salvo. Los perfiles se resuelven **antes** que las
filas (una fila de un perfil que no exista en `profiles` quedaría invisible) y nunca se borran, ni con
`replace`: borrar el perfil activo dejaría la app apuntando a uno que ya no está. ⚠️ **"Sustituir" con
una copia sin elementos se rechaza** (`BackupData.isEmpty`): vaciaría las tres tablas de todos los
perfiles sin restaurar nada, y se anunciaría como "Restaurados 0 elementos". `LocalRepository.exportAll/importAll` hacen todo en `withTransaction`. Importar ofrece **Fusionar**
(por defecto, no destruye) o **Sustituir**, y ⚠️ **no pasa por `applyPromotion`**: un backup es un
estado ya decidido, reaplicar las reglas movería series de lista al restaurarlas.

**Las restricciones de almacenamiento de Android TV mandan en este diseño** (todo comprobado, API 36):

- **No hay DocumentsUI.** `ACTION_CREATE_DOCUMENT`/`OPEN_DOCUMENT` solo los resuelve
  `com.android.tv.frameworkpackagestubs.Stubs$DocumentsStub`, que únicamente avisa de que no se
  soporta. **Nada de selector de ficheros**: la app escribe en un sitio fijo y ofrece ella la lista.
- **La app no puede leer `/sdcard/Download` ajeno** (`Permission denied`) ni escribir ahí por ruta
  directa. La copia pública se escribe por **MediaStore**, que sí funciona sin permisos.
- **MediaStore pierde la propiedad al desinstalar.** Comprobado: tras `uninstall`+`install`, la
  consulta por `owner_package_name` devuelve las exportaciones de ESTA instalación pero **no** la que
  se hizo antes de desinstalar, aunque el fichero siga en Descargas. Por eso hay dos destinos.

Por eso se escribe en **dos** sitios y se listan **dos** fuentes:

| Destino | Sobrevive a desinstalar | La app lo puede leer |
|---|---|---|
| `Download/AnimeAV1/` (MediaStore) | **Sí** — es el respaldo de verdad | Solo dentro de la misma instalación |
| `Android/data/com.animeav1/files/backups/` (buzón) | No | **Siempre** |

⚠️ **El buzón lo tiene que crear la APP.** `BackupStore.list()` hace `mkdirs()` justo por esto: si el
directorio lo crea `adb push` u otro proceso, queda a nombre de ese usuario y la app se come un
`Permission denied` al leer dentro. Con el directorio ya creado por la app, un `adb push` dentro **sí**
se lee (el fichero queda a nombre de shell, pero eso no importa). Ojo al depurar: `adb shell run-as`
da un **falso negativo** aquí — su vista de `/storage` no es la del proceso real de la app.

**Borrar una copia: mantener pulsado** sobre su fila (mismo gesto que la rejilla de episodios y las
tarjetas de *Continuar viendo*; la pulsación corta sigue siendo restaurar). Ninguno de los dos gestos
toca el disco por su cuenta: los dos abren el mismo overlay, con distinto título y **distinto número
de botones**, para que el reflejo "CENTRO otra vez" no caiga donde caía en el otro.

⚠️ **Se borra por NOMBRE, nunca por una `Source` suelta**, y por eso `Entry` lleva `sources` (una
lista) en vez de una sola procedencia. `write` escribe el MISMO nombre en los dos destinos; cuando
`list` deduplicaba con `distinctBy`, la fila que el usuario veía era siempre la del buzón y **la Uri
de su gemela en Descargas no llegaba a existir**. Borrando solo esa Entry se iba media copia: en el
refresco siguiente la otra mitad reaparecía —con una fecha ligeramente distinta, porque
`DATE_MODIFIED` va en segundos y `File.lastModified()` en milisegundos— y parecía que había que
borrar dos veces. Al revés era peor: borrar solo la de Descargas destruye el único respaldo que
sobrevive a desinstalar mientras la lista sigue enseñando una copia perfectamente restaurable.

⚠️ **El desenlace no es un booleano** (`DeleteResult(inbox, downloads)`): los dos destinos son
independientes y el caso interesante es el del medio. Con un booleano la pantalla diría "Copia
borrada" y la fila reaparecería acto seguido; ahora dice cuál de las dos quedó viva. Y cada destino
se decide **volviendo a mirar** (`mediaEntries().none { … }`, `!file.exists()`), no por el valor que
devolvió `delete()`: es lo único que puede afirmar "ahí ya no queda nada con ese nombre".

⚠️ **En API ≤ 28 la copia pública se lista por ruta directa** (`Entry.Source.Public`), y eso no es un
adorno: `mediaEntries` devuelve vacío por debajo de Q, así que antes **toda** copia salía etiquetada
"solo en la app · se pierde al desinstalar" y el modal prometía no tocar Descargas… mientras el
borrado sí la borraba. Etiqueta, aviso y efecto decían tres cosas distintas y se cumplía la
destructiva. Va detrás de `WRITE_EXTERNAL_STORAGE`, igual que exportar: **lo que no se puede listar
no se borra**, y sin permiso se devuelve `false` en vez de deducir el desenlace de un `exists()` que
tampoco puede ver el fichero. (Comprobado en API 36; la rama ≤28 está razonada pero no probada en
aparato.)

⚠️ Un fichero de **otra instalación** en Descargas no se lista (scoped storage lo esconde) y por
tanto tampoco se borra: no hay forma de llegar a él desde esta pantalla, y pedir consentimiento con
`MediaStore.createDeleteRequest` para algo que el usuario no ve no tendría sentido.

La fila dice **dónde vive** cada copia ("En Descargas y en la app" / "Solo en la app · se pierde al
desinstalar"). Es la diferencia entre un respaldo de verdad y uno que se evapora al desinstalar, y
antes las dos se veían igual. La decisión la toma `Entry.location`; la UI solo elige el texto.

⚠️ El overlay de esta pantalla **saca el contenido de la búsqueda de foco**
(`FOCUS_BLOCK_DESCENDANTS`, como `ExitConfirm`). No lo hacía, y con la confirmación de importar
abierta el D-pad alcanzaba las filas **por detrás del scrim**. Y `hideConfirm` devuelve el foco a
donde estaba, no con un `list.requestFocus()` pelado: eso aterrizaba en la PRIMERA fila, y con la
lista vacía —ruta que el borrado sí hace alcanzable— fallaba en silencio sobre una vista invisible y
la pantalla se quedaba sin ningún aro. Al borrar, `focusBefore` se anula **antes** de cerrar el
modal: `RecyclerView` recicla esa misma `View` para otra entrada, así que restaurarla habría puesto
el foco sobre un fichero distinto del que el usuario tenía.

Flujo real para restaurar tras reinstalar: abrir la pantalla Copia una vez (crea el buzón) → dejar el
JSON ahí (`adb push`) → volver a la pantalla → aparece en la lista → Fusionar.

⚠️ **Restaurar tras reinstalar deja un perfil DUPLICADO, y hay que saberlo.** Ensayado entero en el
emulador con el APK publicado (exportar → desinstalar → instalar → restaurar): los datos vuelven
completos —5 series, 10 vistos y 2 puntos de reanudación, con su uuid original— pero **no** al perfil
con el que arranca la app. Una instalación limpia crea su propio "Principal" con un uuid nuevo, así
que el "Principal" de la copia no casa con él y `importAll` lo crea al lado: quedan dos perfiles con
el mismo nombre, el vacío activo y el restaurado con todo. Hay que **elegir el restaurado en el
selector** (y borrar el vacío). No es pérdida de datos, pero visto desde el sofá parece exactamente
eso. Si algún día molesta, el arreglo con menos riesgo es adoptar en el import un perfil existente
del mismo nombre **solo si está completamente vacío**: sin filas no hay nada que se pueda fusionar
mal, que es lo que el uuid existe para evitar.

### 3. Que la reinstalación no haga falta

- **`fallbackToDestructiveMigration()` solo en debug** (`isDebuggable(context)`). En release, si falta
  una `MIGRATION_x_y`, Room **lanza**: el fallo se nota pero los datos siguen en disco y se recuperan
  publicando la migración. Borrar en silencio convertía un despiste en pérdida definitiva.
- **`android:hasFragileUserData="true"`**: al desinstalar desde la UI (API 29+), el sistema ofrece
  conservar los datos.
- ⚠️ **La firma es lo que más datos puede costar, y sigue sin resolver.** No hay `keystore.properties`,
  así que release se firma con el `~/.android/debug.keystore` de la máquina. El día que se genere una
  clave de release de verdad, la firma cambia y **todo usuario tendrá que desinstalar** para
  actualizar → adiós listas e historial. Perder ese keystore da el mismo problema para siempre. Es
  también la causa del gotcha `INSTALL_FAILED_UPDATE_INCOMPATIBLE` de arriba. Generar la clave **antes**
  de que haya usuarios es gratis; después cuesta los datos de todos.

---

## Presentación de arranque (`ui/IntroActivity`)

Cortinilla de bienvenida: el rótulo **DON FAK / PRESENTS** con su locución, y de ahí al selector de
perfiles. Es la **actividad de entrada** (lleva el `LEANBACK_LAUNCHER`, que se le quitó a
`MainActivity`) y **se cierra a sí misma** al lanzar `MainActivity`, así que no queda en la pila:
BACK desde el selector sigue saliendo de la app y no vuelve a la cortinilla (verificado: la pila
queda `MainActivity → ProfilesActivity`). Al volver de recientes tampoco se ve, porque ya no existe.

- ⚠️ **Cualquier tecla la salta**, menos dos casos. **BACK sale de la app** en vez de saltar hacia
  adelante: detrás de esta pantalla no hay nada, así que BACK aquí es "no quería abrir esto", y
  llevar al usuario hacia dentro sería lo contrario de lo que ha pedido (no pregunta como el BACK de
  `MainActivity`: en dos segundos no ha entrado a ninguna parte). Y las teclas de **volumen** se
  dejan pasar: consumirlas impediría subir o bajar el volumen justo cuando suena algo.
- ⚠️ **El audio nunca puede impedir entrar en la app**: todo el `MediaPlayer` va en `runCatching` y
  quien decide el paso al selector es el reloj de la animación, no el final del sonido.
- ⚠️ Los atributos de audio se pasan **en la propia llamada a `MediaPlayer.create`**, no con
  `setAudioAttributes` después: `create` devuelve el reproductor ya PREPARADO y los atributos hay que
  fijarlos antes de preparar, así que puesto después lanza `IllegalStateException` y —dentro del
  mismo `apply`— se lleva por delante el `start()`, dejando la cortinilla muda sin que nadie se
  entere. Sin ellos el sistema la registra como `USAGE_UNKNOWN` (visto en `dumpsys audio`), que no es
  lo que debe ver el mezclador de una TV.
- El sonido es una **sintonía de dos golpes** (`res/raw/intro_sting.m4a`, 15 KB, 2,0 s), no una voz:
  sintetizada con un script de numpy —bombo con barrido de frecuencia de 190→62 Hz para el primero
  y 240→92 Hz para el segundo, más fuerte y con cola larga— y convertida con `afconvert`. La
  envolvente medida es la que se busca: −12,8 dB en el primer golpe, −5,6 dB en el segundo a los
  0,44 s y caída limpia hasta el silencio. Para cambiarlo basta dejar otro fichero con ese nombre;
  si dura distinto, ajustar `HOLD_MS`.
- ⚠️ **La animación NO se lanza en `onCreate`**: ahí la ventana todavía no se dibuja —el sistema está
  reproduciendo su propia animación de apertura— así que los primeros fotogramas se perdían y la
  cortinilla aparecía ya empezada, "cortada". Arranca en `onEnterAnimationComplete`, con el primer
  `onPreDraw` (+220 ms) como red de seguridad, porque ese aviso **no llega** si el lanzamiento no
  trae animación. Medido con capturas seguidas: el brillo de la banda del rótulo sube 24 → 32 → 44 →
  46, o sea que el fundido ocurre EN pantalla.
- ⚠️ **El contenedor del rótulo ocupa la pantalla entera, no `wrap_content`.** Envolviendo justo al
  texto, sus límites eran los del rótulo, y como un ViewGroup recorta a cada hijo a los suyos, la
  entrada —que empieza al 1,10 de escala— se cortaba por los lados: la "D" y la "K" salían con un
  corte recto hasta que la escala llegaba a 1. Medido a media animación: **1482 px de ancho cuando
  tocaban ~1546**, y el borde izquierdo saltando de 18 a 71 de brillo en un píxel en vez de
  degradar; después del arreglo se capta un fotograma de **1528 px** (escala 1,033) con el borde ya
  suavizado. Es el mismo error que la fila de relacionadas y la lista de copias, en su tercera
  variante: aquí ni siquiera hacía falta tocar `clipChildren`, bastaba con que el contenedor tuviera
  sitio.
- ⚠️ Todas las animaciones llevan **`withLayer()`**: promueve el texto a una capa de hardware
  mientras dura la animación. Sin eso, escalar 72sp con `letterSpacing` obliga a re-rasterizar las
  letras en cada fotograma, que es de donde venían los tirones.
- El sonido se **prepara** en `onCreate` pero no suena hasta que empieza la animación, para que el
  coste de abrir y decodificar el recurso caiga durante la animación de apertura de la ventana y no
  en el primer fotograma del rótulo. La animación no depende del sonido: rótulo entrando 900 ms,
  "PRESENTS" a los 420 ms (cae con el segundo golpe), deriva lentísima del conjunto, y a los 2,2 s
  se desvanece en 500 ms. Los tiempos van con `lifecycleScope`, que se cancela solo si la Activity
  muere antes — nada de `Handler` sueltos.

## Navegación entre secciones (`MainActivity`)

**4 tabs arriba** (Inicio/Catálogo/Horario/MiLista; **Inicio es el aterrizaje**, `activeTab=0`) +
`R.id.fragment_container`. Catálogo/Horario/MiLista son **coherentes** (menú + grilla); el **Inicio**
(`ui/home/HomeFragment`) son **filas horizontales**, en este orden: *Continuar viendo* (reanuda directo
en el reproductor con los extras derivados de `favorite_series` + `autoResume=true`), *Mi lista · Viendo*,
*Mi lista · Por ver*, y tres filas de **descubrimiento** del catálogo (*En emisión*, *Mejor valorados*,
*Películas*). Las locales salen de Room; las de descubrimiento de `HomeViewModel`.

- **Quitar de *Continuar viendo*: mantener pulsado** sobre la tarjeta (mismo gesto que la rejilla de
  episodios de la ficha, que es donde la app ya esconde la acción secundaria de una tarjeta).
  Pregunta antes, porque **borra el punto de reanudación** y el gesto es hermano del que abre la
  ficha: un mantenido accidental no puede tirar media hora de episodio sin avisar.
  ⚠️ `LocalRepository.removeFromContinue` borra el progreso de la serie **entera**, no solo el del
  episodio de la tarjeta: la fila muestra un episodio por serie (el más reciente sin terminar), así
  que quitando solo ese la serie reaparecería al instante con el anterior. Y si la fila de
  `favorite_series` existía SOLO para dar título y portada a la tarjeta (`listType="none"` sin
  favorito, que es como la crea `saveProgress`), se va con ella — la misma regla que aplica
  `setFavorite` por el otro lado. Lo visto **no** se toca.
  ⚠️ El foco del diálogo se pide **a mano** (`getButton(BUTTON_POSITIVE).requestFocus()` en un
  `setOnShowListener`): un `AlertDialog` con botones aterriza el foco en el `buttonPanel` que los
  contiene, no en un botón, así que no se ve ningún indicador y CENTRO no hace nada — con un mando
  el diálogo parece colgado (comprobado en el emulador). El de la ficha no lo necesita porque usa
  `setItems`, y ahí la lista sí selecciona su primer elemento.
  Quitar la última tarjeta esconde la sección entera, y de que el foco no se pierda ya se encarga
  `rescuingFocus` (verificado: salta a *Mi lista · Viendo*).
- ⚠️ **`HomeViewModel` es propio y no se reutiliza `BrowseViewModel`**: ese acumula páginas para el
  scroll infinito del catálogo, y aquí cada fila es una única página que no crece — compartirlo haría
  que Inicio y Catálogo se pisaran la paginación. Carga **una vez por sesión** (`started`).
- Cada fila **carga y falla por separado** (`Row(items, loading, failed)`): su propio skeleton
  (`row_skeleton`, con el mismo pulso de alpha que el catálogo) y su propio botón Reintentar
  (`vm.retry(kind)`). Una fila que no responde no puede dejar Inicio en blanco. Una fila que acaba
  vacía y sin error se esconde entera: un rótulo sin tarjetas debajo parece un fallo de la app.
- **El foco de las filas está alineado, no "lo mínimo".** Dos piezas propias, las dos solo de Inicio:
  `ui/SectionScrollView` ancla **arriba** la sección que recibe el foco (lo comparte la ficha de serie) (su rótulo queda justo bajo la barra
  de pestañas) y `ui/RowLayoutManager` ancla la tarjeta enfocada al **inicio** de su fila (lo comparte
  la fila de *Series relacionadas* de la ficha). El
  desplazamiento mínimo de serie dejaba la fila recién enfocada pegada al borde inferior —con el
  título de sus tarjetas cortado por el borde de la pantalla y sin asomar la fila siguiente— y la
  tarjeta pegada al borde derecho, sin ver nada de lo que venía después.
  ⚠️ Alinear la tarjeta **también arregla el movimiento vertical**, que era de donde venía la
  sensación de foco caprichoso: `FocusFinder` elige por geometría, así que bajar de una fila ya
  desplazada a otra sin desplazar aterrizaba en una tarjeta distinta cada vez. Con todas las filas
  alineando en la misma X, ARRIBA/ABAJO cae siempre en la tarjeta que la fila destino tenía
  seleccionada — y volver a una fila recupera la que dejaste, sin guardar ningún índice.
  ⚠️ **Solo se ancla la sección que CABE en la pantalla** (`anchorable`). Una más alta —la rejilla de
  episodios de una serie larga— se queda con el desplazamiento normal: anclada, el scroll se quedaría
  clavado en su borde superior y al bajar dentro de ella el foco se saldría por debajo del borde y
  desaparecería. Y a ese desplazamiento normal, que es el MÍNIMO para que quepa lo enfocado, se le
  "engorda" el rectángulo (`FOCUS_MARGIN_DP`) para que el tile no quede pegado al borde de abajo y se
  vea que hay otra fila detrás.
  ⚠️ `SectionScrollView` **no deduce** las secciones de la jerarquía: se las registra `HomeFragment`
  (`registerRow`). Las locales cuelgan del contenido y las de descubrimiento de `discovery_rows`, así
  que cualquier regla estructural trataría *todas* las de descubrimiento como una sola fila. Y anula
  su propio desplazamiento (devolviendo 0 en `computeScrollDeltaToGetChildRectOnScreen`) **solo**
  cuando el foco está dentro de una fila registrada: el botón del estado vacío sigue con el
  comportamiento normal.
- ⚠️ **En el extremo de una fila, IZQ/DER no salen de ella** (`RowLayoutManager.onInterceptFocusSearch`).
  Cuando el `RecyclerView` no encuentra a dónde ir delega en el padre, y `FocusFinder` se lleva el foco
  a la tarjeta más cercana de OTRA fila: en *Películas*, con una sola tarjeta, DERECHA saltaba a *Mejor
  valorados* y arrastraba el scroll. Va en `onInterceptFocusSearch` y **no** en `onFocusSearchFailed`,
  que es donde parecía tocar: lo que devuelve esa otra pasa por `isPreferredNextFocus`, que descarta
  explícitamente `next == focused` y cae en el `super.focusSearch` del padre — o sea, el salto seguía
  ocurriendo (comprobado en el emulador). Solo se interceptan LEFT/RIGHT **y solo en el extremo**:
  interceptar ARRIBA/ABAJO dejaría Inicio con una sola fila alcanzable.
- ⚠️ **Ninguna rejilla vacía debe ser focusable**, y vale para las cuatro: con `afterDescendants` y
  cero hijos el propio RecyclerView acepta el foco y no dibuja nada, así que el foco "desaparece" de la
  pantalla. Catálogo (`animeGrid.isFocusable = prev.isNotEmpty()`, y false en el error) y la rejilla de
  episodios (`recycler.isFocusable = shown.isNotEmpty()`) lo hacen en código; Mi Lista y Horario
  esconden el recycler. En el Catálogo se volvió urgente al quitar la barra de búsqueda: ABAJO desde un
  chip pasó a ser la pulsación que cae en el hueco.
- ⚠️ Sin tarjetas, la fila **no debe ser focusable**: con `descendantFocusability=afterDescendants` y
  ningún hijo focusable el foco se para en el RecyclerView vacío y el D-pad se queda ahí sin nada que
  hacer. (Las filas locales resuelven lo mismo escondiendo la sección.)
- ⚠️ **El foco inicial se recalcula en cada intento, no se fija al primer destino.** Prioridad:
  reanudar > mis listas > descubrir. Las filas se llenan en el orden en que responden Room y la red,
  no en el orden en que se ven: fijando el destino la primera vez que una fila no estaba vacía, el
  foco aterrizaba en *Mi lista · Viendo* cuando su consulta ganaba la carrera a *Continuar viendo*
  (medido en el emulador). La comprobación buena es `childCount > 0`, no "la lista no está vacía":
  una fila recién poblada tarda un layout en tener hijos y un `requestFocus()` sin hijos falla en
  silencio — por eso hay un único `OnGlobalLayoutListener` en la raíz que reintenta y se quita solo.
  Ese listener se **desregistra al ocultarse el tab** (el ViewTreeObserver es el de la ventana, así que
  seguía disparando desde Catálogo y al volver a Inicio se llevaba el foco de la pestaña a una fila) y
  también **en cuanto el usuario pulsa una tecla**, vía `MainActivity.keyTicks`: colocar el foco inicial
  vale solo mientras NADIE lo ha puesto. Sin eso, el usuario que subía a la barra y caminaba hasta otra
  pestaña se encontraba con que la fila recién cargada le arrancaba el foco, y el CENTRO que ya tenía en
  el dedo abría una ficha en vez de cambiar de sección.
- ⚠️ **Esconder una vista que tiene el foco lo expulsa de la sección**, y por eso existe
  `ui/FocusRescue.kt` (`rescuingFocus`), la ÚNICA implementación de la regla — la usan Inicio y Mi
  Lista, cada una con su política de destino:
  Android no deja el foco en null, hace `clearFocus()` y lo reasigna al primer focusable de la ventana
  —la pestaña INICIO—, y `focusPlaced` impedía recolocarlo. Pasa al pulsar "Reintentar" (el propio
  botón se esconde al pasar a cargando) y cuando un colector de Room vacía una sección teniendo el
  foco dentro (marcar visto el último pendiente, quitar la serie de la lista, cambiar de perfil).
  ⚠️ El destino tiene que estar **mostrado** (`isShown`), no solo tener hijos: una sección que acaba
  de pasar a GONE conserva sus hijos (el diff de `submitList` es asíncrono y un contenedor GONE ya no
  se dispone, así que el RecyclerView no recicla nada) y `requestFocus()` NO mira la visibilidad de
  los ancestros — el rescate metía el foco justo en la fila que lo había provocado, invisible y sobre
  una tarjeta que ya no existe (o, al cambiar de perfil, sobre los pósters del perfil anterior).
  ⚠️ Los botones "Reintentar" son destinos de primera: cuando las filas de descubrimiento fallan son
  lo único focusable de Inicio, y además el que se pulsó recupera el foco al reaparecer — si no, había
  que volver a bajar toda la página en CADA intento.
- El **estado vacío accionable** (botón "Explorar el catálogo") sale solo si están vacías las tres
  filas locales **y** las tres de catálogo; mientras una carga, no. Antes alguien que solo hubiera
  guardado series en *Por ver* leía "Aún no has empezado a ver nada" con su lista llena. ⚠️ Al añadir/mover un
tab hay que actualizar **todos** los arrays/índices: `tabTags`, `lastFocus[]`, `isTabButton`,
`activeTabButton`, `newFragment`, `updateTabStyle`.

**Acciones globales de la barra** (a la derecha del hueco flexible, fuera de `tabTags`/`lastFocus[]`/
`isTabButton`): **BUSCAR**, el **avatar** del perfil activo y el **reloj**. El reloj es un `TextClock`
—una TV no tiene barra de estado, así que la hora no estaba en ninguna parte del sistema—: se
suscribe él solo a `ACTION_TIME_TICK`/`TIME_CHANGED`/`TIMEZONE_CHANGED` y solo mientras está pegado a
la ventana, así que no hay ningún loop que arrancar ni cancelar. Lleva `format12Hour` **y**
`format24Hour`: `TextClock` elige según el ajuste del aparato, y sin segundos a propósito (con
segundos el tick pasa de un minuto a 1 s). ⚠️ **No focusable**: no hace nada al pulsarlo y sería una
parada muerta al final de la cadena BUSCAR → avatar.
⚠️ **El avatar es un `FrameLayout` con el círculo dentro, no un `TextView` suelto**, y el aro de foco
va en el CONTENEDOR: el círculo se tiñe con `setColorFilter` (SRC_IN) para tomar el color del perfil,
así que un trazo dentro del mismo drawable saldría del mismo color y no se vería — el avatar era lo
único de la barra que no se resaltaba al enfocarlo. El aro (`avatar_focus_ring`) sigue el criterio de
`tab_background`: 2dp de `@color/focus_stroke` y nada el resto del tiempo. El contenedor mide 40dp,
como las pestañas y BUSCAR, así que el alto de la barra no cambia. ⚠️ `MainActivity` guarda las **dos**
vistas: `actionProfile` (el contenedor, que lleva el clic y el foco) y `actionProfileAvatar` (el
círculo, que es lo que pinta `ProfileAvatars`).
⚠️ El avatar es el último focusable de la barra, así que lleva `nextFocusRight` **a sí mismo**: sin
eso, DERECHA no encontraba nada a la derecha —el reloj no es focusable— y `FocusFinder` se llevaba el
foco ABAJO, a una tarjeta del contenido; una pulsación hacia la derecha acababa fuera de la barra.
La búsqueda es una pantalla aparte:
`ui/browse/SearchActivity` (campo grande + búsqueda en vivo con debounce, reutiliza
`CatalogAnimeAdapter`/`BrowseViewModel`), y se llega **solo desde la barra global** (botón BUSCAR,
junto al avatar). El Catálogo ya **no** tiene barra de búsqueda propia: era un lanzador duplicado de
lo que ya se puede hacer desde cualquier pestaña, y su cabecera ahora es como la del resto de
secciones (título + controles). En `SearchActivity` la grilla tiene **espaciado** (decoración
local 8dp), **skeleton** de carga (reutiliza `SkeletonAdapter`, pulso alpha), cabecera **"N resultados"**
(`CatalogPage.total`), **scroll infinito** (mismo patrón de paginación que BrowseFragment, con
`pendingPage`/`lastQuery`) y una hairline bajo la barra. Al borrar la query bajo 2 caracteres se llama
`vm.cancelSearch()` y el colector descarta páginas obsoletas (`lastQuery.length < 2`).
⚠️ **Las respuestas del catálogo van numeradas (`BrowseViewModel.CatalogResult`), y no es
decoración.** `CatalogPage` es un `data class` y `_catalog` un StateFlow, que **conflata valores
iguales**: dos consultas DISTINTAS devuelven muy a menudo exactamente la misma página —comprobado
contra el sitio: `digim`, `digimo` y `digimon` dan los mismos 15 resultados y el mismo `total`—, así
que la segunda no llegaba nunca al colector. Reproducido en el emulador: al escribir el nombre letra
a letra, la pantalla mostraba los 15 resultados con `digim` y al añadir la **o** se quedaba con el
esqueleto pulsando **para siempre**, sin resultados y sin mensaje. Con el contador cada respuesta es
un valor distinto y siempre se emite. Cubre también dos búsquedas seguidas **sin resultados** (las dos
son `CatalogPage(emptyList(), 0)`) y repetir la MISMA consulta tras borrarla. Es el mismo fallo que
`_error` ya tenía documentado —y que allí se tapa poniendo `null` antes de cada petición—, pero con el
contador no depende de que el colector llegue a ver el valor intermedio.
⚠️ **Un fallo de red se distingue de un fallo de paginación por la PÁGINA pedida** (`pendingPage > 1`),
no por que el adaptador tenga tarjetas: al empezar una búsqueda nueva se enseña el esqueleto pero la
lista sigue guardando los resultados de la búsqueda anterior, así que un fallo de la primera página
salía como un Toast de "no se pudieron cargar más" —que se va solo— y dejaba otra vez el esqueleto
puesto para siempre.
⚠️ **Teclado por
foco:** `windowSoftInputMode=stateUnchanged` + listener de foco del input (`showIme` al enfocar, `hideIme`
al perder foco/bajar a resultados) **+ reintento en `onWindowFocusChanged(true)`** (el `showSoftInput` de
onCreate se ignora porque la ventana aún no tiene foco), para que el teclado salga al entrar y BACK no se
"coma" un toque.

- **Estado y foco persistentes:** los fragments **no se destruyen** — se usa `add()/show()/hide()` (no
  `replace()`). Así se conservan filtros, búsqueda, sub-lista, páginas cargadas y scroll. Un
  `OnGlobalFocusChangeListener` recuerda el último foco por tab (`lastFocus[]` guarda `FocusMemory`:
  la View **y su posición de adapter** si era item de RecyclerView; al restaurar se prefiere
  `findViewHolderForAdapterPosition` porque la View puede haberse re-bindeado a otro ítem).
- **BACK sube un nivel** (`MainActivity.dispatchKeyEvent`): rejilla → menú de la sección → pestañas →
  y solo entonces pregunta si cerrar. El "menú" es la fila de controles de la cabecera, marcada en
  cada layout con **`@id/section_menu`** (filtros del Catálogo, días del Horario, sub-listas de Mi
  Lista); Inicio no la tiene, así que va derecho a las pestañas.
  ⚠️ Marcado explícito y no heurística. Antes era "el primer focusable que no vive dentro de un
  RecyclerView", que funcionaba **solo** porque el Catálogo tenía un `EditText` de búsqueda: al
  quitarlo, su primer focusable pasó a ser un chip de filtro —que sí vive en un RecyclerView— y BACK
  se saltaba el nivel del menú de golpe. Por lo mismo, la condición de "subir al menú" es *no estar
  ya dentro de él* (`section_menu` entre los ancestros) y no "estar dentro de un RecyclerView": con
  el menú siendo una fila de chips, esa condición lo re-enfocaba a sí mismo y BACK dejaba de subir.
  ⚠️ `sectionMenu()` filtra igual que `firstFocusable` (`canHostFocus`): un `HorizontalScrollView` es
  focusable por su constructor y un RecyclerView vacío también acepta el foco, así que en el Horario
  sin red —sin botones de día todavía— BACK dejaba el foco en el scroll de la cabecera, sin ningún
  indicador en pantalla y sin nada que hacer con CENTRO.
  ⚠️ `sectionMenu()` parte de `addFocusables` y **no** de `container.findViewById`: los fragments de
  los tabs se ocultan con `hide()`, no se destruyen, así que un findViewById encontraría el menú de
  otra sección. ⚠️ Salta de golpe: NO recorre el scroll (antes preguntaba cerrar desde
  cualquier punto, y desde la fila 15 del catálogo la única salida era pulsar ARRIBA una vez por fila).
- **Navegación D-pad simétrica:** ABAJO desde un tab → primer ítem del menú (o el foco recordado);
  ABAJO desde el menú → grilla; ARRIBA desde la grilla → menú (no salta el menú); ARRIBA desde el menú → tab.
  Implementado con `focusSearch(FOCUS_UP)` + `addFocusables`, no con `replace`. ⚠️ Si la sección no tiene
  nada enfocable (Inicio vacío, sección cargando), ABAJO **se queda en el tab** (`firstFocusable` ignora
  scroll-containers pelados y RecyclerViews vacíos; `fragment_container` ya NO es focusable).
- **⚠️ Abrir detalle de serie:** SIEMPRE con `startActivity(SeriesActivity)`. **Nunca** `replace(R.id.fragment_container, ...)`
  desde un fragment de tab — reemplazaría el contenedor de los fragments vivos y rompería el show/hide.
  (Catálogo, Horario y Mi Lista ya lo hacen con SeriesActivity.) El contenedor de `SeriesActivity` se llama
  **`series_container`** (id propio) para que sea estructuralmente imposible apuntar al de los tabs.
- **Paginación del catálogo:** `currentPage` solo avanza cuando la página **llega** (`pendingPage` →
  éxito); un fallo de paginación muestra Toast y el siguiente scroll reintenta la MISMA página (antes
  saltaba páginas en silencio).
- **Horario:** carga con el mismo skeleton que el catálogo; si la carga falla hay botón **Reintentar** y
  además se reintenta solo al re-mostrar el tab (`onHiddenChanged` + `vm.schedule.value == null`). UP desde
  la fila superior de la grilla aterriza en el **día seleccionado** (los items de la primera fila llevan
  `nextFocusUpId` = id del botón del día activo, vía `ScheduleListAdapter.upFocusId`).
- **Lifecycle de colectores:** Home y Mi Lista coleccionan Room con `viewLifecycleOwner` +
  `repeatOnLifecycle(STARTED)` / `flowWithLifecycle` — sin diffs en background durante la reproducción.
  ⚠️ BrowseFragment NO puede migrar tal cual: su colector **acumula** páginas (`prev.addAll`), y la
  re-emisión sticky al volver a STARTED duplicaría la última página.
- **SeriesFragment:** `bindSeries` corre **una vez por instancia de Series** (`lastBound`, identidad) —
  el StateFlow sticky re-emite al volver del player y un re-bind completo robaría el foco a "Continuar"
  y resetearía el scroll de la grilla de episodios.

---

## Convenciones de UI (las 3 grillas son iguales)

Catálogo, Horario, Mi Lista y Búsqueda comparten la **grilla de pósters verticales, `GRID_COLUMNS`
(6) columnas** — la constante vive en `ui/Grids.kt` y los skeletons usan la misma (si no, se ve un
salto al cargar). ⚠️ **`GRID_COLUMNS` y `@dimen/poster_card_height` van de la mano**: la altura está
en dp, así que cambiar de columnas rompe la proporción del arte. Las portadas son **2:3**; con 5
columnas y 165dp la celda salía casi cuadrada y `centerCrop` se comía ~21 % del alto (medido), justo
la franja del logotipo. Con 6 columnas y 186dp el recorte queda en 2,7 %.
⚠️ **Los títulos de tarjeta llevan `maxLines=2` + `minLines=2`**: sin el mínimo, un título largo hacía
5 líneas y el de al lado 1, y la fila salía escalonada (mismo criterio que el rótulo ACTIVO de las
tarjetas de perfil — el hueco se reserva siempre).

Cada tarjeta: `card_focus_bg`, portada `@dimen/poster_card_height` (186dp) con esquinas redondeadas
(Coil `RoundedCornersTransformation(8f)` — ⚠️ ese valor está en **píxeles**, no dp, así que redondea
mucho menos de lo que parece), título a 2 líneas y línea(s) meta en `text_secondary`.
Márgenes de zona segura TV: `@dimen/tv_safe_h` (48dp) / `tv_safe_v` (24dp) en grillas y cabeceras (también
en los bordes exteriores del detalle de serie). Tipografía de tarjeta a tamaños TV (título 14sp, meta 12sp,
cabeceras de sección 20sp). Carga: **skeleton** con pulso (`item_skeleton_card`) — también en el Horario;
vacíos con icono `ic_empty` centrados en un FrameLayout. **La ficha va en TRES secciones apiladas** (`fragment_series`): portada+info, episodios y
relacionadas, dentro de un `SectionScrollView` — el mismo scroll de Inicio, que **ancla arriba la
sección enfocada** en vez de desplazar lo mínimo. Sustituye al diseño de dos columnas (panel
izquierdo + rejilla derecha).
- **Sección 1**: banner a lo ancho con degradado hacia el fondo, la **portada montada sobre su borde
  inferior** (`series_poster_overlap` es un margen NEGATIVO, emparejado con `series_banner_height` y
  `series_info_top`), y al lado título, puntuación+estado+géneros **en una sola fila**, próximo
  episodio, las acciones y la sinopsis.
  ⚠️ Las acciones (Reproducir / lista / favorito) van **antes** de la sinopsis, no al final del
  bloque: puestas detrás de un texto de varias líneas se caían por debajo del borde de la pantalla
  (medido en la primera versión: no se veían sin hacer scroll). "← Volver" va suelto sobre el banner
  para no competir con "Reproducir", que es lo que el usuario viene a pulsar.
  ⚠️ La sección se queda **por debajo de la altura de pantalla a propósito**, para que asome la
  cabecera "Episodios N": si la primera pantalla se llena entera, en una TV nadie sabe que ABAJO
  lleva a alguna parte.
  ⚠️ Si la serie no tiene backdrop, el banner cae a la **portada recortada** y solo después a un
  degradado neutro: el `placeholder_backdrop` con su "play" gigante, a lo ancho de la cabecera, se
  leía como un fallo de carga.
- **Sección 2**: la rejilla de episodios, **5 columnas fijas** (`EPISODE_COLUMNS`). 5 × 10 filas son
  los 50 de un bloque exactos, así que un bloque es una cuadrícula completa y no una última fila
  coja. Hubo una etapa de columnas responsivas (a partir de un ancho objetivo por tile) que en una
  TV 4K daba 8: con el fotograma del capítulo dentro, tiles de ese tamaño se quedaban pequeños para
  reconocer la escena de un vistazo. ⚠️ El precio es real: a 5 columnas el tile mide ~690 px en 4K y
  la miniatura del CDN tiene 220, así que se estira unas 3 veces y se ve suave — y no hay versión
  mayor de esas imágenes.
  ⚠️ **Se ven TODOS los episodios**: la rejilla es `wrap_content` y quien scrollea es la página, no
  ella (`isNestedScrollingEnabled = false`, o se pelean por el desplazamiento). Hubo una versión con
  tope de 4 filas para tener *Series relacionadas* cerca, pero eso dejaba una serie de 64 episodios
  **cortada**. El coste está acotado por los **bloques**: por encima de `EPISODES_PER_BLOCK` (50) se
  enseña un bloque cada vez, así que el máximo son 10 filas aunque la serie tenga 1175 episodios.
  ⚠️ Ya **no** se hace `scrollToPosition` al primer episodio sin ver: la rejilla no scrollea por su
  cuenta y la ficha debe abrirse por arriba, en la portada. El episodio que toca sigue señalado y
  "Continuar" lleva a él.
  ⚠️ **Sin `setHasFixedSize(true)`**: el alto de la rejilla depende de cuántos episodios haya, que es
  justo lo que esa bandera promete que no pasa. El lint `InvalidSetHasFixedSize` lo caza y rompe la
  build, y hace bien.
- **Sección 3**: relacionadas, escondida entera (`relations_section`) cuando no las hay — no sus
  piezas sueltas, o queda un divisor y un rótulo colgando al final de la página.
- ⚠️ **Los chips de bloque están DENTRO de la cadena de foco**, y eso hay que decirlo a mano. Lo
  primero que hay debajo de la portada no siempre es la rejilla: en las series largas hay antes una
  fila de chips, y apuntando la portada directamente a `episodes_recycler` los chips quedaban
  **inalcanzables por arriba y por abajo** — o sea, no había forma de llegar al episodio 101 en
  adelante (reproducido con One Piece, 1175 episodios). Se resuelve en tiempo de ejecución
  (`isVisible` de `episode_blocks`) porque los chips solo existen por encima de `EPISODES_PER_BLOCK`.
  ⚠️ La fila de chips usa `RowLayoutManager` y no un `LinearLayoutManager` pelado: DERECHA en el
  último chip se iba a una tarjeta de *Series relacionadas*, al otro extremo de la página, y de allí
  ya no se volvía.
- ⚠️ **La cadena de foco es vertical y va cableada**: ARRIBA desde la primera fila de tiles va a
  "Reproducir" (`EpisodeGridAdapter.upFocusId`, como el `upFocusId` del Horario) porque entre medias
  hay una sección entera y `FocusFinder` decide por distancia; ABAJO desde la fila de acciones va al
  botón de sinopsis si está y a la rejilla si no. Al cambiar de dos columnas a tres secciones hubo
  que rehacerla entera: heredada del diseño viejo, ARRIBA desde "Reproducir" bajaba a "Leer más",
  que ahora está debajo.

**Sinopsis completa.** El panel la recorta a 2 líneas —sin límite, una sinopsis larga empuja
las secciones de abajo fuera de la pantalla, y como el texto no es enfocable tampoco se puede recorrer con el
mando—, y debajo aparece **"Leer sinopsis completa"**, que abre un panel propio con el texto entero.
⚠️ El botón sale **solo si el texto se ha cortado de verdad**, y eso se le pregunta al `Layout` del
TextView (`getEllipsisCount` de la última línea) en un `post`, no contando caracteres: lo que cabe
depende del ancho, del cuerpo de letra y de dónde parta cada palabra.
⚠️ Por eso el `nextFocus` de `btn_back` y `btn_continue` se **recablea en código** según esté o no:
dejarlo fijo en el XML apuntando a una vista `gone` deja la tecla muerta (`requestFocus` falla en lo
invisible), el mismo error que ya costó caro en la rejilla.
⚠️ El panel es un overlay propio y **saca la ficha de la búsqueda de foco**
(`FOCUS_BLOCK_DESCENDANTS`, igual que `ExitConfirm`): un overlay visible no aísla nada, y sin eso
ABAJO desde el texto se iba a "Reproducir" **por detrás del panel** (comprobado en el emulador).
⚠️ El tope de altura lo pone el fragment, **no el XML**: `android:maxHeight` es un atributo que solo
implementan algunas vistas y un `NestedScrollView` **lo ignora** —comprobado: el panel crecía con el
texto hasta salirse de la pantalla, que es justo lo que viene a arreglar—. Si el texto cabe, el foco
va a "Cerrar" (un ScrollView enfocado no dibuja ningún indicador y el panel parecería sin foco); si
no cabe, va al texto, y ARRIBA/ABAJO lo recorren.

**La rejilla ocupa lo que necesita, no todo lo que hay** (`fitGridHeight`). Con `layout_weight="1"`
reservaba **siempre** la misma altura, tuviera 6 episodios o 1172: en una serie corta los tiles
llenaban una o dos filas y debajo quedaba un socavón hasta *Series relacionadas*, anclada al fondo.
Ahora se le da la altura de su contenido cuando cabe —y las relacionadas suben justo debajo de los
tiles, que además es lo que las hace visibles— y se le devuelve el peso cuando no cabe. Medido:
Bleach (4 ep) 864 → **352 px** y su rótulo de relacionadas de 1386 a **650**; Nige (6 ep) 864 →
**704**; Frieren (28 ep) se queda en 864, igual que antes.
⚠️ Se restaura el peso **antes** de medir y se mide en un `post`: leyendo la altura cuando ya tiene
una altura fija de un bloque anterior se estaría midiendo contra sí misma, y la rejilla se iría
encogiendo bloque a bloque.
⚠️ La altura de fila se saca de `getDecoratedBoundsWithMargins`, no de `child.height`: ese no incluye
márgenes ni lo que añaden las `ItemDecoration`, y quedarse corto por esos pocos píxeles deja un pelín
de scroll — suficiente para que el `scrollToPosition` del primer episodio sin ver desplace la rejilla
y **corte la primera fila** (medido: 66 px comidos de la fila de arriba).

**Próximo episodio (ficha, solo series en emisión).** La línea "Nuevo episodio el viernes 28" sale de
`AiringSchedule.nextAirDate`, que lo **calcula**; no hay ningún campo que lo diga.
⚠️ El sitio publica `nextDate`, pero **no es la fecha del próximo episodio**: cruzado con
`/horario/__data.json`, en las tres series en emisión que se miraron valía exactamente lo mismo que
`startDate` mientras el último episodio publicado era de esa misma semana. Sirve solo como **ancla**
de la cadencia (`waitDays`, 7 en la práctica) y se cuenta desde ahí.
⚠️ **Si la serie va atrasada más de un episodio respecto de su propia cadencia, no se promete
nada** y la línea se esconde: es lo que distingue una serie viva de una parada, y sin eso la ficha de
una serie abandonada anunciaría un episodio nuevo cada semana para siempre. Se tolera UNO de retraso
(una semana de descanso o un recap es normal). Tampoco se muestra si `status != 2`: en una serie
terminada la cadencia seguiría dando fechas futuras alegremente.
⚠️ La mitad pura no lee el reloj —"hoy" entra por parámetro—, que es lo que permite probarla; los
tests incluyen los tres casos reales capturados del sitio con su respuesta cruzada contra el horario.
El formato de día usa `Locale("es")` fijo, no el del aparato: el resto de la UI está en español a
pelo, y un "Friday 28" dentro de "Nuevo episodio el…" quedaría a medias.
El detalle de serie tiene botón **"Continuar"**
(foco por defecto) que salta al primer episodio no visto. El diálogo de un episodio ofrece **"Marcar
como visto (y anteriores)"** —`markWatchedThrough`, la opción por defecto— y **"Marcar solo este
episodio"** como escape para quien quiera dejar un hueco a propósito (un especial suelto).
La **rejilla de episodios** se parte en bloques de `EPISODES_PER_BLOCK` (**50**) con una fila de chips
(`EpisodeBlockAdapter`) que solo aparece si hay más de uno — One Piece son 1172 tiles, ~235
pulsaciones de ABAJO para llegar al final. Al abrir la ficha se muestra el bloque que contiene el
**primer episodio no visto**, no el primero. Cada tile lleva una barra de progreso
(`episode_progress` de ese perfil, vía `LocalRepository.progressForSeries`) y el siguiente a ver va
marcado.
⚠️ **Cada tile enseña el fotograma de SU episodio**, y esa imagen no la publica la API: se arma por
convención con el id de la serie y el **número** del episodio (`AnimeImages.episodeThumb`), igual que
las portadas. Va por número y **no por el id del episodio**, que es el error fácil porque el resto
del CDN va por id: para la película de Digimon (serie 1280, episodio id 20309, número 1),
`/screenshots/1280/1.jpg` devuelve la imagen y `/screenshots/1280/20309.jpg` devuelve **403**.
Lo medido antes de fiarse: son **220×124** y pesan 3-8 KB (no hay versión mayor: `?w=640`, `_large`,
`@2x` y `.webp` no existen), la cobertura fue de 36/36 en 6 series —incluidos los episodios 1, 500 y
1175 de One Piece— y un ~8% son fotogramas **casi negros**, que cargan bien y se ven como un
rectángulo oscuro. ⚠️ Cuando falta, el CDN responde **403 con HTML**, no un 404 de imagen, así que el
tile se limpia (`dispose()` + `setImageDrawable(null)`) ANTES de pedir la nueva: sin eso el tile
reciclado se quedaba enseñando el fotograma del episodio anterior, que no se lee como "falta una
imagen" sino como "la rejilla miente". El número va sobre un degradado y con sombra porque tiene que
leerse igual en un fotograma blanco y en uno negro.

**Cabecera de la sección: buscador de episodios y orden.** En una serie de 1175 capítulos, llegar al
847 a saltos de bloque son muchas pulsaciones; escribirlo son tres. Y quien abre One Piece para ver
lo último no quiere empezar por el episodio 1.

- ⚠️ **Los bloques son trozos de la lista YA ORDENADA, no rangos fijos de números.** En descendente
  el primer chip es `1175-1126`, que es justo a lo que va quien pide ese orden; con rangos fijos,
  pedir "descendente" te dejaba igualmente en el 1-50, solo que del revés. Por eso
  `EpisodeBlockAdapter` recibe **pares** `(primero, último)` y no `IntRange`: un `IntRange` con
  `first > last` está **vacío** en Kotlin, así que ni se etiquetaba ni casaba con ningún episodio.
- ⚠️ `allNumbers` del `EpisodeGridAdapter` va **siempre ascendente**, aunque la rejilla se enseñe del
  revés: de ahí sale "el siguiente a ver", que es el primer episodio SIN VER de la serie. Pasándolo
  en el orden de pantalla, en descendente la marca caía en el último episodio.
- **Buscar manda sobre los bloques**: se filtra por prefijo sobre TODA la serie (escribir `84` saca
  849, 848, 847…), no dentro del bloque visible, que es lo que espera quien escribe un número. Por
  eso los chips se esconden mientras hay filtro: seguirían marcando un bloque que no es lo que hay
  debajo. Sin coincidencias sale un rótulo propio: una rejilla vacía y muda parece la app rota.
- ⚠️ El **OK del teclado lo cierra** (misma lección que el editor de perfil): mientras está puesto se
  come el D-pad y no se llega ni a los chips ni a la rejilla.
- ⚠️ **La cadena de foco cambió**: lo primero que hay debajo de la portada ya no es la rejilla ni los
  chips, es el buscador (`firstBelow = R.id.ep_search`), y de ahí se baja a chips o a rejilla según
  haya filtro — se recablea en `renderEpisodes`. Apuntando la portada directamente a los tiles, el
  buscador y el botón de orden serían inalcanzables bajando, que es exactamente lo que ya pasó una
  vez con los chips.
- Un único `renderEpisodes` decide qué se ve a partir del estado (orden, filtro, bloque), en vez de
  tres caminos que se pisan.

⚠️ **El tile enseña el fotograma ENTERO**: su alto sale del ancho a 16:9 (`ui/RatioImageView`), que
es la proporción de las miniaturas del CDN, así que no hay nada que recortar. No vale
`adjustViewBounds`, que sería lo obvio: ese calcula el alto a partir del drawable **ya cargado**, así
que mientras las imágenes viajan los tiles miden 0 y la rejilla pega un salto cuando llegan las
respuestas — y los episodios sin miniatura (403) se quedarían planos para siempre.

⚠️ Tres cosas que el bloque **no** puede decidir por su cuenta, porque son de la SERIE: el "siguiente
a ver" (`EpisodeGridAdapter.allNumbers`, si no cada bloque pintaba su propio primer-no-visto como si
fuera el siguiente), el denominador de "✓ vistos/total" (`episodesTotal`, si no One Piece con 700
vistos mostraba "✓ 700 / 100") y el índice al que se hace scroll (el del bloque mostrado; con el
índice global el scroll caía fuera de rango en los bloques 2..N y se descartaba en silencio).
⚠️ El **progreso vive en el fragment** (`progressMap`), no solo dentro del adapter: cambiar de bloque
crea un adapter nuevo y el Flow de Room no reemite mientras nadie escriba, así que las barras
desaparecían para el resto de la sesión al tocar un chip. Y seleccionar el chip del bloque inicial no
lo trae a la vista: hace falta `scrollToPosition`, o el único chip resaltado queda fuera de pantalla.
⚠️ La fila de chips tiene altura **fija** (38 dp, la del chip): con `wrap_content` el lint
`InvalidSetHasFixedSize` rompe la build (`abortOnError=true`) porque no sabe que scrollea en
horizontal.
**Series relacionadas** (`item_relation_card`): sigue la misma convención que el resto de tarjetas
—póster arriba, distintivos SOBRE el póster, título debajo sobre el fondo de la tarjeta,
`card_focus_bg`—. Antes tenía un lenguaje propio: el texto en una caja oscura pegada al póster y a
10sp/9sp, ilegible desde el sofá. ⚠️ `relation_card_width` y `relation_poster_height` **van juntas**
(el arte es 2:3, así que la altura es el ancho × 1,5) y el póster tiene altura FIJA: cuando se
llevaba "lo que sobrara" de una altura fija de tarjeta (`layout_weight="1"`) salía casi cuadrado
—`centerCrop` se comía un tercio del alto, justo la franja del logotipo— y además cambiaba de tamaño
con la longitud del título, así que las tarjetas de una fila no coincidían. El año va de distintivo
sobre el póster y no en una línea propia: la fila compite por alto con la rejilla de episodios.
⚠️ **A esta fila no se llegaba con el mando.** La rejilla de episodios tiene `layout_weight="1"`, así
que en una serie corta los tiles se quedan arriba y queda un palmo de vacío hasta las relacionadas.
`FocusFinder` prefiere el candidato alineado con el foco **pero solo hasta cierta distancia**: si el
que está en el haz queda más lejos que el borde lejano de otro candidato, decide por distancia
ponderada — y ganaba el botón *Continuar* del panel izquierdo (medido con 4 episodios: 954 px contra
714 px). Por eso el foco de esta zona va cableado a mano: `EpisodeGridAdapter.downFocusId` en la
ÚLTIMA fila de tiles, `nextFocusUp` de la tarjeta a la rejilla, y `nextFocusDown` de la tarjeta **a sí
misma** para que ABAJO —donde no hay nada— no se cuele en el panel izquierdo.
⚠️ Cuando la serie **no tiene relacionadas**, `downFocusId` apunta al **propio tile** (`episode_tile`)
en vez de a `View.NO_ID`: sin destino explícito, ABAJO desde la última fila cruzaba la pantalla hasta
"Agregar a lista" del panel izquierdo, por la misma regla de distancia de `FocusFinder`. Apuntar al
RecyclerView de relacionadas cuando está `gone` tampoco vale: `requestFocus` falla en una vista
invisible y la tecla se quedaría muerta de otra manera.
⚠️ La fila usa `RowLayoutManager` con **`blockStartEdge = false`**: DERECHA en la última tarjeta se
queda dentro (si no, saltaba hacia arriba a un tile de la rejilla), pero IZQUIERDA sí sale, porque a
la izquierda está el panel de la serie y ese paso es legítimo. Es la misma clase que usan las filas
de Inicio; vive en `ui/` por eso.
**Animación de foco unificada en los 7 adapters de tarjeta:** `animate().scaleX/Y(1.10).translationZ(8f)` a 120ms
(nunca `elevation` en seco, y siempre con lift de Z — sin él la tarjeta escalada se dibuja DEBAJO de sus
vecinas). ⚠️ `ServerAdapter` y `FilterChipAdapter` **quedan fuera** de esa convención (usan su propio
resaltado); `SkeletonAdapter` no es focusable. Clicks de adapter SIEMPRE con guard:
`val pos = bindingAdapterPosition; if (pos != NO_POSITION)`.
Espaciados de grilla SIEMPRE en dp convertidos con `displayMetrics.density` (nunca px crudos).
El ancho de las tarjetas de las filas horizontales del Inicio vive en `@dimen/home_card_width`, que
comparten `item_home_poster` y el skeleton de `view_home_row` (si divergen, los huecos no caen donde
caerán las tarjetas). ⚠️ Su título lleva **`minLines="2"`** además de `maxLines="2"`: sin reservar la
segunda línea, las tarjetas de título corto medían menos y la fila salía escalonada — el mismo
problema que el rótulo "ACTIVO" de los perfiles.
Las grillas usan `setHasFixedSize(true)`; los adapters son `ListAdapter`/DiffUtil y el badge de vistos en
Mi Lista se actualiza con **bind parcial por payload** (no recarga la portada). ⚠️ **No pongas
`clipChildren="false"` en estos RecyclerView** (ni en su root): la animación de foco escala 1.10 +
`translationZ`, y sin clip la tarjeta de la fila superior se dibuja **sobre la cabecera/pestañas**. Con
`clipToPadding="false"` + el padding del RV basta para que la escala se vea bien recortada en el borde
(las filas horizontales del Inicio llevan `paddingVertical="12dp"` para absorber la escala).
⚠️ **La fila de *Series relacionadas* necesitaba ese hueco y no podía tenerlo con padding a secas**:
sus tarjetas tienen que empezar exactamente donde empieza el rótulo de la sección, así que un padding
horizontal descuadraba la fila entera, y sin hueco la PRIMERA tarjeta se cortaba por la izquierda al
escalarse (medido: 19 px). Se resuelve con **padding horizontal + márgenes NEGATIVOS del mismo
valor** (8dp): la fila se ensancha hacia el margen vacío de la columna, pero sus tarjetas siguen
alineadas con el rótulo. La columna lleva `clipToPadding="false"` para no recortar esa fila más
ancha.
⚠️ **La regla, en una línea:** todo contenedor con tarjetas que escalan necesita **hueco propio**
(padding con `clipToPadding="false"`, y márgenes negativos del mismo valor si las tarjetas tienen que
seguir alineadas con algo de fuera), nunca quitar el recorte de un contenedor que además guarda otra
cosa. Revisado contenedor a contenedor midiendo en el emulador (la pista es que
`uiautomator` da los límites YA recortados: si la tarjeta enfocada no mide exactamente 1,10 × la de
al lado, se está recortando). Catálogo, Búsqueda, Mi Lista, Horario, Perfiles, las filas de Inicio y
la rejilla de episodios pasan; la **lista de ficheros de Copia de seguridad NO pasaba** —la fila
enfocada se quedaba sin los lados del aro de foco, solo con el trazo de arriba y el de abajo— y se
arregló con la misma pareja padding/margen negativo, puesta en el **contenedor** de la lista (un hijo
lo recorta su padre, así que ensanchar solo la lista dentro de un FrameLayout normal no servía de
nada). ⚠️ Las filas de Inicio pasan **por 1 px** (47 px de escala contra 12dp de padding): si crece
la tarjeta —otra línea de título, un póster más alto— hay que subir ese padding a la vez.
⚠️ **Lo que NO vale es `clipChildren="false"` en la columna**, aunque parezca el camino corto (fue el
primer intento y hubo que deshacerlo). `clipChildren` de un padre recorta a cada hijo **a los límites
del hijo**, así que es justo lo que mantiene los tiles de la rejilla de episodios dentro de la
rejilla: al quitarlo, los tiles a medio scroll se dibujaban **sobre la cabecera "Episodios"**
(comprobado en el emulador: las cajas del 11 al 15 tapaban el rótulo). Es también la razón de que un
`clipChildren="false"` en el propio RecyclerView de relacionadas no bastara: la columna lo volvía a
recortar por el borde del RecyclerView.

- **Catálogo** (`item_catalog_anime`): título + **tipo**.
- **Mi Lista** (`item_mylist_card`): título + **año · estado · tipo** + badge "▶ vistos/total" en la portada.
- **Horario** (`item_schedule_card`): título + **tipo** + fecha + badge "▶ Ep N" (último episodio) en la portada.

Iconos: vectoriales Material en `res/drawable/ic_*.xml` — reproductor (play/pause/skip/replay_10/forward_10/
dns/check_circle) sobre `icon_button_bg`, tabs y cabeceras (`ic_catalog`/`ic_schedule`/`ic_list`) y el corazón
de favorito (`ic_favorite`/`ic_favorite_border`). El indicador de **foco** está unificado en `@color/accent`
vía `@color/focus_stroke` (grillas, tabs, chips y barra de búsqueda; antes mezclaba rojo/oro/morado).
Tamaños compartidos en `res/values/dimens.xml`; strings de UI en `strings.xml`. ⚠️ La regla de "sin
literales" **no se cumple del todo**: quedan ~10 `android:text` literales (placeholders y símbolos como
`"0:00"`, `"EP 1"`, `"▶"`, `"✓"`, más `"Tipo:"` y `"Todos"`) y 9 `contentDescription` en castellano
directamente en los layouts del reproductor y la búsqueda. Paleta: `accent #7B6CF6`, `accent_light #B09CF8`, `bg_dark #121212` (fondo
único de todas las pantallas), más variantes translúcidas derivadas en colors.xml (`accent_line #CC...`,
`accent_badge_bg #55...`) — al cambiar el acento hay que actualizarlas juntas.

---

## Actualizaciones OTA

La app se actualiza sola: mira un `update.json` publicado junto al APK, y si hay versión nueva la
ofrece, la descarga, **comprueba su SHA-256** e instala. Verificado de punta a punta en el emulador
(8/1.4.1 → 9/1.5.0, con perfiles, listas y vistos intactos).

⚠️ **Falta lo único que no puede hacer el código: la clave de firma de release.** Hoy no hay
`keystore.properties`, así que `assembleRelease` sale **sin firmar** y cada build de debug lleva la
clave de la máquina que la hizo. Sin una clave ESTABLE no hay OTA posible: el APK nuevo no se puede
instalar encima (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) y la única salida es desinstalar, que es
justo lo que el OTA viene a evitar. Y el paso de "firmado con debug" a "firmado de verdad" cuesta
UNA desinstalación —conviene exportar antes una copia desde Ajustes del perfil → Copia de
seguridad—. Generar la clave antes de que haya usuarios es gratis; después cuesta los datos de todos.

**De dónde se lee.** `BuildConfig.UPDATE_MANIFEST_URL`, que `app/build.gradle.kts` compone con la
propiedad `githubRepo` de `gradle.properties`:
`https://github.com/<repo>/releases/latest/download/update.json`. ⚠️ Esa URL **no cambia al publicar
una versión nueva**: GitHub siempre resuelve `latest` al release más reciente, así que la app no
necesita saber qué versión existe para preguntar por ella. Mientras `githubRepo` siga sin rellenar,
la URL lleva `TU-USUARIO` y `UpdateChecker` no comprueba nada.

**El manifiesto** es pequeño y aditivo (`versionCode`, `versionName`, `apkUrl`, `sha256`,
`sizeBytes`, `notes`, `minSdk`). ⚠️ `UpdateManifest.parse` descarta el fichero ENTERO si algo no
cuadra —`versionCode` a 0, `apkUrl` que no sea http(s), un `sha256` que no sean 64 hex— en vez de
rellenar con valores por defecto: el resultado de creerse un dato a medias aquí es instalar un APK,
la operación menos reversible de la app. Se compara por `versionCode` y **nunca** por `versionName`
("1.10.0" es posterior a "1.9.0" pero menor alfabéticamente), y `minSdk` se mira ANTES de descargar
para no bajarse 9 MB que el aparato no puede instalar.

**Trampas que costaron una sesión, y que están resueltas:**

- ⚠️ **Los sellos de "ya he mirado" se escriben DESPUÉS de la petición, nunca antes.** Al arrancar,
  `MainActivity` se resume un instante antes de que el selector de perfiles se ponga encima, así que
  la primera comprobación se cancela a media petición. Sellando antes, esa comprobación fantasma se
  apuntaba el día entero y la de verdad —la de justo después de elegir perfil— se encontraba con que
  "ya se había mirado hoy": medido en el emulador, **el aviso no salía nunca**. Hay dos ventanas: 24 h
  tras leer el manifiesto y 30 min tras un fallo (la TV puede estar sin red justo entonces).
- ⚠️ Por lo mismo, `UpdateRepository` **relanza la `CancellationException`** en vez de convertirla en
  "no hay manifiesto": quien llama tiene que poder distinguir "he mirado y no hay nada" de "ni he
  llegado a mirar".
- ⚠️ La comprobación va en `repeatOnLifecycle(RESUMED)` y no en `onCreate`: resumido = el usuario
  está de verdad en la app, no detrás del selector de perfiles.
- ⚠️ **Cliente HTTP propio**, no el de `AnimeRepository`: aquel fuerza `max-age=300` sobre toda
  respuesta correcta, o sea que serviría un manifiesto rancio durante cinco minutos —justo el fichero
  cuyo trabajo es decir la verdad ahora— y guardaría cada APK de 9 MB en su caché de disco.
- ⚠️ Desde API 26 el permiso `REQUEST_INSTALL_PACKAGES` **no basta**: el usuario tiene que autorizar
  la app en "orígenes desconocidos" (`canRequestPackageInstalls`). Se comprueba ANTES de descargar,
  porque si no la sesión se rechaza al final, tras bajarse el APK entero y sin decir por qué. En una
  TV se abre `ACTION_MANAGE_UNKNOWN_APP_SOURCES` (comprobado: sale la pantalla del sistema con la app
  en la lista).
- ⚠️ `STATUS_PENDING_USER_ACTION` **no es un error**, es el caso normal: `InstallResultReceiver`
  tiene que lanzar el intent de confirmación que devuelve el sistema. Sin eso la instalación se queda
  esperando y desde fuera parece que "Actualizar" no hizo nada.
- ⚠️ El `PendingIntent` de la sesión va con `FLAG_MUTABLE` desde API 31: el sistema RELLENA sus
  extras, e inmutable Android 12+ lanza al crearlo.
- Los botones de la pantalla se **esconden** mientras descarga, no se deshabilitan: deshabilitados
  dejan de ser focusables y el foco se escaparía (mismo motivo por el que el reproductor atenúa sus
  botones de episodio en vez de deshabilitarlos).

**Probarlo en local sin publicar nada** (es como se verificó):

```
# 1. APK "nuevo": sube versionCode/versionName, compila, y guárdalo con su sha256
#    (debe ir firmado con la MISMA clave que el instalado; en debug ya coincide)
# 2. update.json apuntando a http://10.0.2.2:8000/animeav1.apk  (10.0.2.2 = el host, desde el emulador)
cd /tmp/ota && python3 -m http.server 8000 &
./gradlew :app:assembleDebug -PupdateUrl=http://10.0.2.2:8000/update.json
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.animeav1 rm -f shared_prefs/updates.xml   # olvida el "ya miré hoy"
```

**Lo que falta para publicar de verdad:** crear el repo (hoy el proyecto no tiene ni un commit),
rellenar `githubRepo`, y un script que suba `versionCode`, compile el release firmado, calcule el
sha256 y escriba el `update.json` como asset del release junto al APK.

---

## Limitaciones conocidas / posibles mejoras

- **Año/estado en el catálogo:** no es posible sin una petición de detalle por cada tarjeta (la API del
  catálogo no los trae). Solo se muestra el tipo. Una mejora sería carga perezosa por tarjeta visible.
  (El detalle de serie SÍ muestra "Estado · Año · Tipo" en el badge.)
- **Servidores con JS (Mega/UPNShare):** no reproducibles nativamente; reintroducir un WebView solo como
  fallback sería una opción.
- **URL de YourUpload de un solo uso + `streamCache`.** El `.mp4` que extrae YourUpload lleva un token
  que **cambia en cada scrape** (`vidcache.net:8161/a20260806<token>/video.mp4`) y redirige a
  `s410.vidcache.net:8166`. `extractStreamUrl` cachea la URL resuelta **10 min** (`STREAM_TTL`), así que
  volver a elegir YourUpload dentro de esa ventana puede entregarle a ExoPlayer un token ya muerto: no
  da error, simplemente no llegan bytes y a los 25 s salta el watchdog con "YourUpload no responde".
  Observado una vez en emulador; reproducirlo de nuevo en un episodio limpio funcionó a la primera.
  El arreglo sería no cachear los servidores con token de un solo uso (o cachear con un TTL mucho más
  corto que el del resto).
- **Estado en config-change (rotación):** los fragments no guardan `selectedDay`/scroll en
  `onSaveInstanceState`. Irrelevante en TV (no rota), pero presente.
- **Sin logging de red** (ningún `HttpLoggingInterceptor`). Los ViewModels propagan errores con
  `it.message ?: "Error de red"` — el fallback es OBLIGATORIO: un `message == null` dejaría skeleton/spinner
  infinito porque los colectores hacen `err ?: return@collectLatest`.
- **Parsing JSON manual** con `org.json` (no kotlinx.serialization).
- **kapt (no KSP):** la migración a KSP requiere bajar el plugin gradle (no está cacheado offline).
- **Release sin R8 ni firma propia:** `isMinifyEnabled=false`; hay `signingConfigs.release` **opcional**
  que se activa si existe `keystore.properties` en la raíz. ⚠️ Si NO existe, release no queda firmado
  con debug como decía aquí: sale **sin firmar** (`app-release-unsigned.apk`, comprobado ejecutando
  `assembleRelease`), o sea que hoy una build de release **no se puede ni instalar**.
- `versionCode`/`versionName` en 8 / 1.4.1 — subir en cada publicación.
- **Cobertura de tests mínima:** solo hay tests JVM de `SvelteKitDecoder`, `StreamUrlParser`,
  `EmbedParser`, `BackupCodec`, `AiringSchedule`, `MediaType` y `UpdateManifest`. Nada de Room (haría falta `androidTest` o Robolectric), ni de ViewModels, ni de UI —
  en particular, la selección SUB/DUB de `PlayerActivity` y el agrupado de `ServerAdapter` solo están
  verificados a mano en el emulador.
- **Stack ~2 años sin actualizar:** media3 1.3.1 (vs 1.10.x), Room 2.6.1, Kotlin 1.9.24, kapt en vez de
  KSP, `enableJetifier` activo sin ninguna dependencia de support-library que lo justifique. `targetSdk 34`
  ya no cumple el mínimo de Play. Subirlo es una cascada (compileSdk, Kotlin 2.x, KSP).

## Flujos de prueba útiles (adb)

```
# Catálogo→serie→agregar a lista:  tap poster → tap "Agregar a lista" → CENTER → tap "Por Ver"
# Reproducir:  serie → tap episodio → CENTER (auto-reproduce 1er servidor; HLS por defecto)
# Inspeccionar Room:  adb exec-out run-as com.animeav1 cat databases/animeav1.db > /tmp/x.db
#                     (sin sqlite3 en el device; abrir con python sqlite3 en el host; tirar también -wal)
```
