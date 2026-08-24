package com.animeav1.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.animeav1.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Presentación de arranque: el rótulo **DON FAK / PRESENTS** con su sintonía, y de ahí al selector
 * de perfiles.
 *
 * Es la actividad de entrada (la que lleva el `LEANBACK_LAUNCHER`) y **se cierra a sí misma** al
 * lanzar [MainActivity], así que no queda en la pila: BACK desde el selector de perfiles sigue
 * saliendo de la app y no vuelve aquí.
 *
 * ⚠️ **Se puede saltar con cualquier tecla.** Una cortinilla es aceptable la primera vez y un peaje
 * el resto; en una TV, además, el usuario ya tiene el mando en la mano.
 *
 * ⚠️ El audio **nunca** puede impedir entrar en la app: todo lo del [MediaPlayer] va envuelto en
 * `runCatching` y el paso al selector lo marca el reloj de la animación, no el final del sonido.
 */
class IntroActivity : AppCompatActivity() {

    private var player: MediaPlayer? = null

    /**
     * Volumen de la sintonía, sobre 1.
     *
     * ⚠️ El fichero está masterizado **a tope**: medido, pica a **-1,0 dBFS** (rms -11,5), así que
     * sonaba tan alto como el aparato permitiera para ese flujo — en una TV, con la barra de volumen
     * donde la deje cada uno, un arranque así asusta más que presenta. 0,35 son -9,1 dB, que dejan
     * el pico en -10,2 dBFS y el rms en -20,6: se oye, pero no salta.
     *
     * Se baja aquí y no rebajando el `.m4a` porque una constante se lee y se ajusta, mientras que un
     * fichero regenerado no dice a qué nivel se hizo.
     */
    private val INTRO_VOLUME = 0.35f
    private var show: Job? = null

    /** La animación arranca UNA vez, la dispare quien la dispare (ver [startShow]). */
    private var started = false

    /** Evita entrar dos veces a [MainActivity] (p. ej. saltar justo cuando vence el temporizador). */
    private var leaving = false

    private lateinit var block: View
    private lateinit var name: View
    private lateinit var presents: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        block    = findViewById(R.id.intro_block)
        name     = findViewById(R.id.intro_name)
        presents = findViewById(R.id.intro_presents)

        // Estado inicial explícito: el rótulo espera fuera, invisible, hasta que haya algo que ver.
        name.alpha = 0f
        name.scaleX = START_SCALE
        name.scaleY = START_SCALE
        presents.alpha = 0f
        presents.translationY = PRESENTS_RISE_PX

        // El sonido se PREPARA aquí (abrir y decodificar el recurso cuesta unos milisegundos de
        // disco) pero no suena hasta que empieza la animación: así ese coste cae durante la
        // animación de apertura de la ventana y no justo en el primer fotograma del rótulo.
        prepareSound()

        // ⚠️ La animación NO se lanza en onCreate. Ahí la ventana todavía no se está dibujando —el
        // sistema está reproduciendo su propia animación de apertura— así que los primeros
        // fotogramas se pierden y la cortinilla aparecía ya empezada, "cortada". Se arranca cuando
        // el sistema avisa de que su animación ha terminado, con el primer `onPreDraw` como red de
        // seguridad: `onEnterAnimationComplete` no llega si el lanzamiento no trae animación.
        window.decorView.doOnPreDraw { view -> view.postDelayed({ startShow() }, PREDRAW_GRACE_MS) }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        startShow()
    }

    private fun startShow() {
        if (started || leaving) return
        started = true

        startSound()

        // `withLayer()` en las dos: promueve el texto a una capa de hardware mientras dura la
        // animación. Sin eso, escalar un texto de 72sp con `letterSpacing` obliga a re-rasterizar
        // las letras en CADA fotograma, que es de donde venían los tirones.
        name.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(NAME_IN_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withLayer()
            .start()

        presents.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(PRESENTS_DELAY_MS)
            .setDuration(PRESENTS_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .withLayer()
            .start()

        // Deriva lentísima del conjunto: da sensación de plano vivo en vez de una imagen fija.
        block.animate()
            .scaleX(DRIFT_SCALE).scaleY(DRIFT_SCALE)
            .setDuration(HOLD_MS + FADE_MS)
            .setInterpolator(LinearInterpolator())
            .withLayer()
            .start()

        // lifecycleScope: si la Activity se destruye antes de tiempo, la cuenta atrás se cancela
        // sola. Nada de Handlers sueltos que sobrevivan a la vista.
        show = lifecycleScope.launch {
            delay(HOLD_MS)
            block.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                .withLayer()
                .start()
            delay(FADE_MS)
            goToApp()
        }
    }

    /**
     * Cualquier tecla salta la presentación… menos dos casos.
     *
     * ⚠️ **BACK no salta hacia adelante, sale de la app.** Detrás de esta pantalla no hay nada, así
     * que BACK aquí es "no quería abrir esto"; llevar al usuario hacia dentro sería justo lo
     * contrario de lo que ha pedido. No pregunta como el BACK de [MainActivity]: en una cortinilla
     * de dos segundos no ha entrado todavía a ninguna parte.
     *
     * ⚠️ Las teclas de **volumen** se dejan pasar: consumirlas (devolviendo true) impediría subir o
     * bajar el volumen justo cuando suena algo.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_BACK -> { leaveWithoutEntering(); true }
            else -> { goToApp(); true }
        }
    }

    /** BACK en la cortinilla: cerrar sin entrar. */
    private fun leaveWithoutEntering() {
        if (leaving) return
        leaving = true
        stopSound()
        finish()
    }

    private fun goToApp() {
        if (leaving) return
        leaving = true
        show?.cancel()
        stopSound()
        startActivity(Intent(this, MainActivity::class.java))
        // Sin animación de cierre: el selector de perfiles ya entra con la suya y encadenar dos
        // transiciones se ve como un parpadeo.
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * ⚠️ Los atributos de audio se pasan **en la propia llamada a `create`**, no con
     * `setAudioAttributes` después: `create` devuelve el reproductor ya PREPARADO y los atributos
     * hay que fijarlos antes de preparar, así que puesto después lanza `IllegalStateException` —y,
     * dentro de un `apply`, se llevaría por delante el `start()`, dejando la cortinilla muda sin que
     * nadie se entere—. Sin ellos el sistema registra la reproducción como `USAGE_UNKNOWN`
     * (comprobado en `dumpsys audio`), que no es lo que debe ver el mezclador de una TV.
     */
    private fun prepareSound() {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val session = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
            player = MediaPlayer.create(this, R.raw.intro_sting, attrs, session)?.apply {
                // ⚠️ El volumen se baja AQUÍ y no rebajando el fichero: así queda en una constante
                // que se lee y se ajusta, en vez de en un .m4a que hay que volver a sintetizar y del
                // que nadie recuerda a qué nivel se generó. `setVolume` sí se puede llamar después
                // de `create` —al contrario que los atributos de audio, que hay que fijarlos antes
                // de preparar—, y escala sobre el volumen del aparato: baja la cortinilla, no la TV.
                setVolume(INTRO_VOLUME, INTRO_VOLUME)
            }
        }
    }

    private fun startSound() {
        runCatching { player?.start() }
    }

    private fun stopSound() {
        runCatching { player?.release() }
        player = null
    }

    /** Salir por HOME a mitad de la cortinilla no puede dejar el sonido sobre el lanzador. */
    override fun onStop() {
        super.onStop()
        stopSound()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSound()
    }

    private companion object {
        /** De dónde arranca el rótulo. Poco: un salto grande se ve barato, no cinematográfico. */
        const val START_SCALE = 1.10f
        /** Cuánto se acerca el conjunto durante toda la cortinilla. Casi imperceptible, a propósito. */
        const val DRIFT_SCALE = 1.03f
        const val PRESENTS_RISE_PX = 20f

        const val NAME_IN_MS = 900L
        /** "PRESENTS" entra con el SEGUNDO golpe de la sintonía (0,44 s). */
        const val PRESENTS_DELAY_MS = 420L
        const val PRESENTS_IN_MS = 520L

        /** Desde que arranca la animación hasta que empieza a irse. Cubre la sintonía (2,0 s). */
        const val HOLD_MS = 2200L
        const val FADE_MS = 500L

        /** Margen tras el primer dibujado, por si el sistema no avisa del fin de su animación. */
        const val PREDRAW_GRACE_MS = 220L
    }
}
