package com.caesiumstudio.bitstream

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.caesiumstudio.bitstream.data.AppUpdateChecker
import com.caesiumstudio.bitstream.data.SiteEntry
import com.caesiumstudio.bitstream.data.SiteRepository
import com.caesiumstudio.bitstream.ui.home.AddSiteDialogFragment
import com.caesiumstudio.bitstream.ui.home.SiteCardPresenter
import com.caesiumstudio.bitstream.webview.WebViewActivity
import java.util.concurrent.Executors
import java.io.File

class MainFragment : Fragment(), AddSiteDialogFragment.Listener {

    private lateinit var repo: SiteRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsScroll: ScrollView
    private lateinit var navHome: TextView
    private lateinit var navSettings: TextView
    private lateinit var navExit: TextView
    private lateinit var sitesAdapter: SitesAdapter

    private lateinit var tvCursorSpeed: TextView
    private lateinit var tvScrollSpeed: TextView
    private lateinit var tvCheckUpdates: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = SiteRepository(requireContext())

        recyclerView = view.findViewById(R.id.sites_grid)
        settingsScroll = view.findViewById(R.id.settings_scroll)
        navHome = view.findViewById(R.id.nav_home)
        navSettings = view.findViewById(R.id.nav_settings)
        navExit = view.findViewById(R.id.nav_exit)
        tvCursorSpeed = view.findViewById(R.id.setting_cursor_speed)
        tvScrollSpeed = view.findViewById(R.id.setting_scroll_speed)
        tvCheckUpdates = view.findViewById(R.id.setting_check_updates)

        setupNav()
        setupGrid()
        setupSettings()
        showHome()
        syncRemoteSites()
        checkForUpdates()
    }

    // --- Remote config sync ---

    private fun syncRemoteSites() {
        Executors.newSingleThreadExecutor().execute {
            // Step 1: fetch JSON and show all sites immediately
            val remoteSites = repo.fetchRemote(REMOTE_CONFIG_URL) ?: return@execute
            view?.post {
                if (isAdded) sitesAdapter.submitList(remoteSites)
            }

            // Step 2: check availability and remove dead sites silently
            val available = repo.filterAvailable(remoteSites)
            if (available.size != remoteSites.size) {
                view?.post {
                    if (isAdded) sitesAdapter.submitList(available)
                }
            }
        }
    }

    // --- In-app updates ---

    private fun checkForUpdates() {
        if (!AppUpdateChecker.shouldCheck(requireContext())) return
        Executors.newSingleThreadExecutor().execute {
            AppUpdateChecker.recordChecked(requireContext())
            val info = AppUpdateChecker.fetchUpdateInfo(AppUpdateChecker.UPDATE_JSON_URL)
                ?: return@execute
            if (!AppUpdateChecker.isUpdateAvailable(requireContext(), info.versionCode)) return@execute
            view?.post {
                if (isAdded) showUpdateDialog(info)
            }
        }
    }

    private fun checkForUpdatesManually() {
        tvCheckUpdates.isEnabled = false
        tvCheckUpdates.text = "Checking…"
        Executors.newSingleThreadExecutor().execute {
            val info = AppUpdateChecker.fetchUpdateInfo(AppUpdateChecker.UPDATE_JSON_URL)
            view?.post {
                if (!isAdded) return@post
                tvCheckUpdates.isEnabled = true
                tvCheckUpdates.text = "Check for Updates"
                if (info == null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Check for Updates")
                        .setMessage("Could not reach the update server. Please try again later.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else if (!AppUpdateChecker.isUpdateAvailable(requireContext(), info.versionCode)) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Up to Date")
                        .setMessage("You're running the latest version.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    showUpdateDialog(info)
                }
            }
        }
    }

    private fun showUpdateDialog(info: AppUpdateChecker.UpdateInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.update_title, info.versionName))
            .setMessage(info.changelog.ifEmpty { getString(R.string.update_default_changelog) })
            .setPositiveButton(R.string.update_download_install) { dialog, _ ->
                dialog.dismiss()
                startDownload(info)
            }
            .setNegativeButton(R.string.update_not_now, null)
            .show()
    }

    private fun startDownload(info: AppUpdateChecker.UpdateInfo) {
        val ctx = requireContext()
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val statusText = TextView(ctx).apply {
            setText(R.string.update_downloading)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 32)
            addView(progressBar)
            addView(statusText)
        }
        val progressDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.update_downloading_title)
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        Executors.newSingleThreadExecutor().execute {
            val apkFile = AppUpdateChecker.downloadApk(ctx, info.apkUrl) { percent ->
                view?.post {
                    if (isAdded && progressDialog.isShowing) {
                        if (percent >= 0) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = percent
                            statusText.text = getString(R.string.update_downloading_progress, percent)
                        }
                    }
                }
            }
            view?.post {
                if (!isAdded) return@post
                progressDialog.dismiss()
                if (apkFile != null) {
                    handleInstall(apkFile)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.update_failed_title)
                        .setMessage(R.string.update_failed_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun handleInstall(apkFile: File) {
        val ctx = requireContext()
        if (AppUpdateChecker.canInstallUnknownSources(ctx)) {
            AppUpdateChecker.installApk(ctx, apkFile)
        } else {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    AppUpdateChecker.openInstallPermissionSettings(ctx)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // --- Navigation ---

    private fun setupNav() {
        navHome.setOnClickListener { showHome() }
        navSettings.setOnClickListener { showSettings() }
        navExit.setOnClickListener { requireActivity().finishAffinity() }
    }

    private fun showHome() {
        recyclerView.visibility = View.VISIBLE
        settingsScroll.visibility = View.GONE
        setActiveTab(navHome)
        recyclerView.requestFocus()
    }

    private fun showSettings() {
        recyclerView.visibility = View.GONE
        settingsScroll.visibility = View.VISIBLE
        setActiveTab(navSettings)
        refreshSettingsLabels()
        tvCursorSpeed.requestFocus()
    }

    private fun setActiveTab(active: TextView) {
        navHome.isSelected = (active == navHome)
        navSettings.isSelected = (active == navSettings)
        navExit.isSelected = false
    }

    // --- Home grid ---

    private fun setupGrid() {
        val dm = resources.displayMetrics
        val spacingPx = (16 * dm.density).toInt()
        val minWidthPx = (160 * dm.density).toInt()
        val cols = (dm.widthPixels / (minWidthPx + spacingPx)).coerceAtLeast(1)

        sitesAdapter = SitesAdapter(
            onSiteClick = { site ->
                startActivity(
                    Intent(requireContext(), WebViewActivity::class.java)
                        .putExtra(WebViewActivity.EXTRA_URL, site.url)
                )
            },
            onAddClick = {
                AddSiteDialogFragment.newInstance()
                    .also { it.setListener(this) }
                    .show(requireActivity().supportFragmentManager, "add_site")
            },
            onEditSite = { site ->
                AddSiteDialogFragment.newInstance(site)
                    .also { it.setListener(this) }
                    .show(requireActivity().supportFragmentManager, "edit_site")
            },
            onDeleteSite = { site ->
                repo.deleteSite(site.id)
                refreshSites()
            },
            onToggleFavorite = { site ->
                repo.toggleFavorite(site.id)
                refreshSites()
            }
        )

        recyclerView.layoutManager = GridLayoutManager(requireContext(), cols)
        recyclerView.adapter = sitesAdapter
        refreshSites()
    }

    private fun refreshSites() {
        sitesAdapter.submitList(repo.loadSites().sortedByDescending { it.isFavorite })
    }

    override fun onSiteConfirmed(url: String, existingId: Long?) {
        if (existingId != null) {
            val existing = repo.loadSites().firstOrNull { it.id == existingId } ?: return
            val normalized =
                if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            val host = try {
                val h = android.net.Uri.parse(normalized).host?.removePrefix("www.") ?: url
                h.substringBefore(".").replaceFirstChar { it.uppercaseChar() }
            } catch (_: Exception) {
                url
            }
            repo.updateSite(existing.copy(url = normalized, displayName = host))
        } else {
            repo.addSite(url)
        }
        refreshSites()
    }

    // --- Settings panel ---

    private fun setupSettings() {
        tvCursorSpeed.setOnClickListener {
            showSpeedPicker(
                "Pointer Speed",
                KEY_CURSOR_SPEED,
                tvCursorSpeed
            )
        }
        tvScrollSpeed.setOnClickListener {
            showSpeedPicker(
                "Scroll Speed",
                KEY_SCROLL_SPEED,
                tvScrollSpeed
            )
        }
        tvCheckUpdates.setOnClickListener {
            checkForUpdatesManually()
        }
    }

    private fun refreshSettingsLabels() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tvCursorSpeed.text = "Pointer Speed: ${labelFor(prefs.getFloat(KEY_CURSOR_SPEED, 1.0f))}"
        tvScrollSpeed.text = "Scroll Speed: ${labelFor(prefs.getFloat(KEY_SCROLL_SPEED, 1.0f))}"
    }

    private fun showSpeedPicker(title: String, prefKey: String, label: TextView) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getFloat(prefKey, 1.0f)
        val currentIdx = SPEED_VALUES.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(SPEED_LABELS, currentIdx) { dialog, idx ->
                prefs.edit().putFloat(prefKey, SPEED_VALUES[idx]).apply()
                label.text = "$title: ${SPEED_LABELS[idx]}"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun labelFor(value: Float): String =
        SPEED_LABELS.getOrElse(SPEED_VALUES.indexOfFirst { it == value }.takeIf { it >= 0 }
            ?: 1) { "Normal (1×)" }

    // --- Key handling ---

    fun handleKeyDown(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_F1) {
            navHome.requestFocus()
            return true
        }
        return false
    }

    fun simulateLongPress() {
        recyclerView.findFocus()?.performLongClick()
    }

    // --- Inner RecyclerView adapter ---

    private class SitesAdapter(
        private val onSiteClick: (SiteEntry) -> Unit,
        private val onAddClick: () -> Unit,
        private val onEditSite: (SiteEntry) -> Unit,
        private val onDeleteSite: (SiteEntry) -> Unit,
        private val onToggleFavorite: (SiteEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var sites: List<SiteEntry> = emptyList()

        fun submitList(newSites: List<SiteEntry>) {
            sites = newSites
            notifyDataSetChanged()
        }

        override fun getItemCount() = sites.size + 1
        override fun getItemViewType(position: Int) =
            if (position < sites.size) TYPE_SITE else TYPE_ADD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cardWidth = SiteCardPresenter.computeCardWidth(parent)
            val cardHeight = (cardWidth * 0.56f).toInt()
            val density = parent.context.resources.displayMetrics.density
            val pad = (16 * density).toInt()

            val bgDrawable = if (viewType == TYPE_ADD)
                R.drawable.add_card_background
            else
                R.drawable.card_background

            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(cardWidth, cardHeight).also {
                    it.setMargins(8, 8, 8, 8)
                }
                isFocusable = true
                isFocusableInTouchMode = true
                background = ContextCompat.getDrawable(parent.context, bgDrawable)
                stateListAnimator = null
            }

            if (viewType == TYPE_ADD) {
                // "Add Site" tile: centered label only, no favicon
                val tv = TextView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.02f
                    setPadding(pad, pad, pad, pad)
                }
                frame.addView(tv)
            } else {
                // Site tile: favicon + label stacked vertically, centered as a unit
                val faviconSize = (40 * density).toInt()

                val inner = LinearLayout(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).also { it.gravity = Gravity.CENTER }
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(pad, pad, pad, pad)
                }

                val faviconView = ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(faviconSize, faviconSize).also {
                        it.gravity = Gravity.CENTER_HORIZONTAL
                        it.bottomMargin = (6 * density).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val tv = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.02f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                inner.addView(faviconView)
                inner.addView(tv)
                frame.addView(inner)

                // Star badge: top-right, unchanged
                val starSize = (18 * density).toInt()
                val starMargin = (10 * density).toInt()
                val star = ImageView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(starSize, starSize).also {
                        it.gravity = Gravity.TOP or Gravity.END
                        it.setMargins(0, starMargin, starMargin, 0)
                    }
                    setImageResource(R.drawable.ic_star)
                    visibility = View.GONE
                }
                frame.addView(star)
            }

            return object : RecyclerView.ViewHolder(frame) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val frame = holder.itemView as FrameLayout

            if (position < sites.size) {
                val site = sites[position]
                // Site tile: frame[0]=inner(favicon+tv), frame[1]=star
                val inner = frame.getChildAt(0) as LinearLayout
                val faviconView = inner.getChildAt(0) as ImageView
                val tv = inner.getChildAt(1) as TextView
                val star = frame.getChildAt(1) as ImageView

                tv.text = cleanDisplayName(site.displayName)
                star.visibility = if (site.isFavorite) View.VISIBLE else View.GONE

                val domain = Uri.parse(site.url).host?.removePrefix("www.") ?: ""
                val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
                Glide.with(faviconView)
                    .load(faviconUrl)
                    .placeholder(R.drawable.ic_globe)
                    .error(R.drawable.ic_globe)
                    .into(faviconView)

                frame.setOnClickListener { onSiteClick(site) }
                frame.setOnLongClickListener {
                    val favLabel = if (site.isFavorite) "Unfavorite" else "Favorite"
                    AlertDialog.Builder(frame.context)
                        .setTitle(site.displayName)
                        .setItems(arrayOf(favLabel, "Edit", "Delete")) { _, which ->
                            when (which) {
                                0 -> onToggleFavorite(site)
                                1 -> onEditSite(site)
                                2 -> onDeleteSite(site)
                            }
                        }
                        .show()
                    true
                }
            } else {
                // "Add Site" tile: single TextView child
                val tv = frame.getChildAt(0) as TextView
                tv.text = "+ Add Site"
                frame.setOnClickListener { onAddClick() }
                frame.setOnLongClickListener(null)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "bitstream_settings"
        const val KEY_CURSOR_SPEED = "cursor_speed"
        const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val TYPE_SITE = 0
        private const val TYPE_ADD = 1
        private val SPEED_LABELS =
            arrayOf("Slow (0.5×)", "Normal (1×)", "Fast (1.5×)", "Very Fast (2×)")
        private val SPEED_VALUES = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)

        const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/caesiumstudio/bitstream/main/sites.json"

        fun cleanDisplayName(raw: String): String {
            // Strip www. prefix, then take only the part before the first dot, capitalize
            val noWww = raw.removePrefix("www.")
            val name = noWww.substringBefore(".")
            return name.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
