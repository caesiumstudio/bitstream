package com.caesiumstudio.bitstream.ui.home

import android.graphics.Color
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.caesiumstudio.bitstream.R
import com.caesiumstudio.bitstream.data.SiteEntry

class SiteCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardWidth = computeCardWidth(parent)
        val cardHeight = (cardWidth * 0.56f).toInt() // 16:9-ish aspect ratio

        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(cardWidth, cardHeight)
            isFocusable = true
            isFocusableInTouchMode = true
            background = ContextCompat.getDrawable(parent.context, R.drawable.card_background)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val entry = item as SiteEntry
        (viewHolder.view as TextView).text = entry.displayName
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}

    companion object {
        // Target number of columns; cards will shrink to fit
        private const val TARGET_COLUMNS = 4
        private const val MIN_CARD_WIDTH_DP = 160
        private const val CARD_SPACING_DP = 16

        fun computeCardWidth(parent: ViewGroup): Int {
            val dm = parent.context.resources.displayMetrics
            val screenWidthPx = dm.widthPixels
            val spacingPx = (CARD_SPACING_DP * dm.density).toInt()
            val minWidthPx = (MIN_CARD_WIDTH_DP * dm.density).toInt()

            // How many columns fit at minimum card width?
            val maxCols = screenWidthPx / (minWidthPx + spacingPx)
            val cols = maxCols.coerceAtLeast(1)

            // Distribute remaining space evenly
            return (screenWidthPx - spacingPx * (cols + 1)) / cols
        }

        // Kept for AddTilePresenter compatibility
        val CARD_WIDTH get() = 400
        val CARD_HEIGHT get() = 225
    }
}
