package com.animeav1.ui.mylist

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.local.ListType
import com.animeav1.ui.series.GridSpacingDecoration
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.LocalViewModel
import com.animeav1.ui.GRID_COLUMNS
import com.animeav1.ui.rescuingFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
class MyListFragment : Fragment() {

    private lateinit var localVm: LocalViewModel
    private var activeList = ListType.POR_VER
    private var listJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localVm = ViewModelProvider(requireActivity())[LocalViewModel::class.java]
    }

    /** Botón de cada sub-lista, para poder devolverle el foco (ver el rescate del colector). */
    private var listButtons: Map<ListType, Button> = emptyMap()

    private fun activeListButton(): View? = listButtons[activeList]?.takeIf { it.isShown }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_mylist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler  = view.findViewById<RecyclerView>(R.id.mylist_recycler)
        val emptyText = view.findViewById<TextView>(R.id.mylist_empty)
        // Same grid as the catalog: 6 fixed columns with even spacing.
        recycler.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        recycler.setHasFixedSize(true)
        recycler.addItemDecoration(GridSpacingDecoration(6))

        val adapter = MyListAdapter { series ->
            // Open in SeriesActivity (like the catalog/schedule) so the tab fragments stay alive.
            startActivity(Intent(requireContext(), SeriesActivity::class.java).apply {
                putExtra("slug", series.slug)
                putExtra("title", series.title)
            })
        }
        recycler.adapter = adapter

        // Tab buttons
        val btnPorVer     = view.findViewById<Button>(R.id.btn_list_por_ver)
        val btnViendo     = view.findViewById<Button>(R.id.btn_list_viendo)
        val btnCompletadas = view.findViewById<Button>(R.id.btn_list_completadas)
        val btnFavoritos  = view.findViewById<Button>(R.id.btn_list_favoritos)

        fun selectList(type: ListType) {
            activeList = type
            btnPorVer.isSelected     = (type == ListType.POR_VER)
            btnViendo.isSelected     = (type == ListType.VIENDO)
            btnCompletadas.isSelected = (type == ListType.COMPLETADAS)
            btnFavoritos.isSelected  = (type == ListType.FAVORITOS)

            listJob?.cancel()
            listJob = viewLifecycleOwner.lifecycleScope.launch {
                val flow = if (type == ListType.FAVORITOS)
                    localVm.repo.getFavoritesList()
                else
                    localVm.repo.getByList(type)
                // Paused below STARTED: no Room diffs while MainActivity sits behind the player.
                flow.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                    .collectLatest { list ->
                        adapter.submitList(list)
                        // Vaciar la sub-lista esconde la rejilla, y si el foco estaba en una tarjeta
                        // Android lo manda a la pestaña INICIO (con la barra marcando todavía Mi
                        // Lista): un CENTRO por reflejo cambiaba de sección. Se devuelve al botón de
                        // la sub-lista activa, que es el nivel de arriba de esta pantalla.
                        rescuingFocus(view, { activeListButton() }) {
                            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                            recycler.visibility  = if (list.isEmpty()) View.GONE   else View.VISIBLE
                            // Sin tarjetas la rejilla no debe poder recibir el foco.
                            recycler.isFocusable = list.isNotEmpty()
                        }
                        // Proactively refresh airing series' episode counts so the "NUEVO" badge is current.
                        localVm.refreshAiringTotals(list)
                    }
            }
        }

        listButtons = mapOf(
            ListType.POR_VER to btnPorVer,
            ListType.VIENDO to btnViendo,
            ListType.COMPLETADAS to btnCompletadas,
            ListType.FAVORITOS to btnFavoritos
        )

        btnPorVer.setOnClickListener     { selectList(ListType.POR_VER) }
        btnViendo.setOnClickListener     { selectList(ListType.VIENDO) }
        btnCompletadas.setOnClickListener { selectList(ListType.COMPLETADAS) }
        btnFavoritos.setOnClickListener  { selectList(ListType.FAVORITOS) }

        // Collect watched counts reactively and push to adapter
        viewLifecycleOwner.lifecycleScope.launch {
            localVm.repo.getWatchedCountsPerSeries()
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collectLatest { counts ->
                    adapter.updateWatchedCounts(counts.associate { it.animeSlug to it.watchedCount })
                }
        }

        selectList(activeList)
    }
}
