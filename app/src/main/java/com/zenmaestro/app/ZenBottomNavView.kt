package com.zenmaestro.app

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat

class ZenBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var listener: ((Int) -> Unit)? = null

    private val items: List<NavItem>

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_zen_bottom_nav, this, true)

        items = listOf(
            NavItem(R.id.nav_home, R.id.homeIcon, R.id.homeLabel, R.id.homeIndicator),
            NavItem(R.id.nav_plan, R.id.planIcon, R.id.planLabel, R.id.planIndicator),
            NavItem(R.id.nav_coach, null, R.id.coachLabel, R.id.coachIndicator),
            NavItem(R.id.nav_progress, R.id.progressIcon, R.id.progressLabel, R.id.progressIndicator),
            NavItem(R.id.nav_profile, R.id.profileIcon, R.id.profileLabel, R.id.profileIndicator)
        )

        items.forEach { item ->
            findViewById<View>(item.containerId).setOnClickListener {
                listener?.invoke(item.containerId)
            }
        }
        findViewById<View>(R.id.coachFloatingButton).setOnClickListener {
            listener?.invoke(R.id.nav_coach)
        }
    }

    fun setOnItemSelectedListener(listener: (Int) -> Unit) {
        this.listener = listener
    }

    fun setSelectedItem(itemId: Int) {
        val active = ContextCompat.getColor(context, R.color.chalkboard)
        val inactive = ContextCompat.getColor(context, R.color.sage)

        items.forEach { item ->
            val selected = item.containerId == itemId
            item.iconId?.let { iconId ->
                ImageViewCompat.setImageTintList(
                    findViewById<ImageView>(iconId),
                    ColorStateList.valueOf(if (selected) active else inactive)
                )
            }
            findViewById<TextView>(item.labelId).setTextColor(if (selected) active else inactive)
            findViewById<View>(item.indicatorId).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            findViewById<View>(item.containerId).isSelected = selected
        }
    }

    private data class NavItem(
        val containerId: Int,
        val iconId: Int?,
        val labelId: Int,
        val indicatorId: Int
    )
}
