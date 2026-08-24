package com.animeav1.ui.schedule

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.model.ScheduleItem
import com.animeav1.ui.browse.SkeletonAdapter
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.ScheduleViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduleFragment : Fragment() {

    private lateinit var vm: ScheduleViewModel

    private lateinit var dayTabsContainer: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var skeleton: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var btnRetry: Button
    private var skeletonAnim: android.animation.ObjectAnimator? = null

    private var scheduleData: Map<String, List<ScheduleItem>> = emptyMap()
    private var adapter: ScheduleListAdapter? = null
    private var selectedDay: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(requireActivity())[ScheduleViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_schedule, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dayTabsContainer = view.findViewById(R.id.day_tabs)
        recycler         = view.findViewById(R.id.sched_recycler)
        skeleton         = view.findViewById(R.id.sched_skeleton)
        emptyText        = view.findViewById(R.id.sched_empty)
        btnRetry         = view.findViewById(R.id.sched_retry)

        adapter = ScheduleListAdapter { item ->
            startActivity(Intent(requireContext(), SeriesActivity::class.java).apply {
                putExtra("slug",  item.slug)
                putExtra("title", item.title)
            })
        }

        // Same poster grid as the catalog / My List.
        recycler.layoutManager = GridLayoutManager(requireContext(), ScheduleListAdapter.COLUMNS)
        recycler.setHasFixedSize(true)
        recycler.addItemDecoration(GridSpacing(6))
        recycler.adapter = adapter

        skeleton.layoutManager = GridLayoutManager(requireContext(), ScheduleListAdapter.COLUMNS)
        skeleton.setHasFixedSize(true)
        skeleton.adapter = SkeletonAdapter()

        btnRetry.setOnClickListener { retryLoad() }

        observeViewModel()
        showSkeleton()
    }

    /** A network blip at app start must not leave the tab dead forever. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && vm.schedule.value == null) retryLoad()
    }

    private fun retryLoad() {
        emptyText.visibility = View.GONE
        btnRetry.visibility = View.GONE
        showSkeleton()
        vm.load()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.schedule.collectLatest { data ->
                data ?: return@collectLatest
                scheduleData = data
                hideSkeleton()
                btnRetry.visibility = View.GONE
                buildDayTabs(data.keys.toList())
                // Select today by default (or keep the day the user had selected)
                val today = todayInSpanish()
                val initial = when {
                    data.containsKey(selectedDay) -> selectedDay
                    data.containsKey(today)       -> today
                    else                          -> data.keys.firstOrNull() ?: ""
                }
                showDay(initial)
            }
        }
        lifecycleScope.launch {
            vm.error.collectLatest { err ->
                err ?: return@collectLatest
                if (vm.schedule.value != null) return@collectLatest   // stale error after a retry won
                hideSkeleton()
                emptyText.text = "Error al cargar: $err"
                emptyText.visibility = View.VISIBLE
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun showSkeleton() {
        skeleton.visibility = View.VISIBLE
        skeletonAnim?.cancel()
        skeletonAnim = android.animation.ObjectAnimator.ofFloat(skeleton, View.ALPHA, 0.4f, 0.9f).apply {
            duration = 750
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideSkeleton() {
        skeletonAnim?.cancel(); skeletonAnim = null
        skeleton.alpha = 1f
        skeleton.visibility = View.GONE
    }

    private fun buildDayTabs(days: List<String>) {
        dayTabsContainer.removeAllViews()
        val today = todayInSpanish()
        days.forEach { day ->
            val btn = Button(requireContext()).apply {
                val isToday = (day == today)
                id = View.generateViewId()   // referenced by the grid's nextFocusUp
                text = if (isToday) "⬤ $day" else day
                textSize = 13f
                // ContextCompat, not resources.getColor(int, Theme): that overload is API 23
                // and minSdk is 21 — it crashes with NoSuchMethodError on Lollipop TV boxes.
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                setBackgroundResource(R.drawable.tab_background)
                setPadding(40, 0, 40, 0)
                isFocusable = true
                tag = day
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                lp.marginEnd = 6
                layoutParams = lp
                setOnClickListener { showDay(day) }
            }
            dayTabsContainer.addView(btn)
        }
    }

    private fun showDay(day: String) {
        selectedDay = day
        // Update tab selection state; UP from the grid's top row must land on the selected day.
        for (i in 0 until dayTabsContainer.childCount) {
            val btn = dayTabsContainer.getChildAt(i) as? Button ?: continue
            btn.isSelected = (btn.tag == day)
            if (btn.isSelected) adapter?.upFocusId = btn.id
        }
        val items = scheduleData[day] ?: emptyList()
        adapter?.submitList(items)
        if (items.isEmpty()) {
            recycler.visibility  = View.GONE
            emptyText.visibility = View.VISIBLE
        } else {
            recycler.visibility  = View.VISIBLE
            emptyText.visibility = View.GONE
        }
    }

    private fun todayInSpanish(): String = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY    -> "Lunes"
        Calendar.TUESDAY   -> "Martes"
        Calendar.WEDNESDAY -> "Miércoles"
        Calendar.THURSDAY  -> "Jueves"
        Calendar.FRIDAY    -> "Viernes"
        Calendar.SATURDAY  -> "Sábado"
        Calendar.SUNDAY    -> "Domingo"
        else -> ""
    }

    class GridSpacing(private val dp: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val px = (dp * view.resources.displayMetrics.density).toInt()
            out.set(px, px, px, px)
        }
    }
}
