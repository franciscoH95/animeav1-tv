package com.animeav1.ui.series

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.animeav1.R

class SeriesActivity : FragmentActivity() {

    /**
     * ⚠️ BACK se lo ofrece primero al fragment: si tiene abierto el panel de sinopsis completa, lo
     * cierra en vez de salir de la ficha. Es la regla de esta app —BACK sube UN nivel, no se salta
     * ninguno— y sin esto un modal propio se comería la salida o, peor, BACK cerraría la ficha
     * entera con el panel todavía en pantalla.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            val f = supportFragmentManager.findFragmentById(R.id.series_container)
            if (f is SeriesFragment && f.consumeBack()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        if (savedInstanceState == null) {
            val slug  = intent.getStringExtra("slug")  ?: return
            val title = intent.getStringExtra("title") ?: slug
            supportFragmentManager.beginTransaction()
                .replace(R.id.series_container, SeriesFragment.newInstance(slug, title))
                .commit()
        }
    }
}
