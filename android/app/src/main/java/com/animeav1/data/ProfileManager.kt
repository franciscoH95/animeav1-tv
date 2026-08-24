package com.animeav1.data

import android.content.Context
import android.content.SharedPreferences
import com.animeav1.data.local.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quién es el perfil activo. Vive **fuera** de Room a propósito: es una preferencia del aparato, no
 * un dato del usuario, y tiene que poder leerse de forma síncrona antes de la primera consulta.
 *
 * Se expone como [activeId] `StateFlow` para que [LocalRepository] pueda encadenar sus Flows con
 * `flatMapLatest`: al cambiar de perfil, Inicio y Mi Lista se repueblan sin reiniciar la app.
 *
 * `object` (y no una instancia inyectada) por coherencia con [AnimeRepository]: la app no tiene
 * inyección de dependencias, y un segundo "perfil activo" en memoria sería exactamente el tipo de
 * estado duplicado que ya se ha ido de las manos antes en este proyecto.
 */
object ProfileManager {

    private const val PREFS = "profile_prefs"
    private const val KEY_ACTIVE = "active_profile_id"

    /** Marca de "todavía no se ha elegido": el arranque decide si hay que mostrar el selector. */
    const val NONE = 0L

    private lateinit var prefs: SharedPreferences

    private val _activeId = MutableStateFlow(NONE)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    /** Nombre del perfil activo, solo para pintarlo en la UI. Lo refresca [setActive]. */
    private val _activeName = MutableStateFlow("")
    val activeName: StateFlow<String> = _activeName.asStateFlow()

    /**
     * Color del avatar del perfil activo.
     *
     * ⚠️ Es un `StateFlow` y no un `var`, y va **emparejado** con [activeName]: eran dos fuentes
     * incoherentes. El nombre se observaba pero el color se leía de un campo que solo escribía
     * [setActive], así que cambiar el color del perfil activo en el editor no repintaba nada (y si
     * además cambiaba el nombre, el avatar salía con la inicial nueva y el color viejo). El resultado
     * era el mismo perfil de dos colores distintos según la pantalla, justo lo que `ProfileAvatars`
     * existe para evitar.
     */
    private val _activeColorIndex = MutableStateFlow(0)
    val activeColorIndex: StateFlow<Int> = _activeColorIndex.asStateFlow()

    /** Debe llamarse una vez desde Application.onCreate() antes de tocar la base de datos. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _activeId.value = prefs.getLong(KEY_ACTIVE, Profile.DEFAULT_ID)
    }

    /**
     * Id que deben usar las consultas. Nunca [NONE]: si aún no se ha elegido nada, cae en el perfil
     * por defecto, que es el que la migración 7→8 creó con los datos que ya existían. Devolver 0
     * aquí dejaría a la app leyendo un perfil vacío y pareciendo que se han perdido los datos.
     */
    fun requireActiveId(): Long = _activeId.value.takeIf { it != NONE } ?: Profile.DEFAULT_ID

    fun setActive(profile: Profile) {
        _activeId.value = profile.id
        _activeColorIndex.value = profile.colorIndex
        _activeName.value = profile.name
        prefs.edit().putLong(KEY_ACTIVE, profile.id).apply()
    }

    /**
     * Nombre y color, cuando se edita el perfil que ya está activo.
     *
     * ⚠️ Los dos juntos: el editor guarda ambos en Room de una vez, y avisar solo del nombre dejaba el
     * avatar con el color anterior el resto de la sesión (y sin avisar de nada si el nombre no había
     * cambiado, porque el StateFlow conflata el valor igual).
     */
    fun updateActive(name: String, colorIndex: Int) {
        _activeName.value = name
        _activeColorIndex.value = colorIndex
    }
}
