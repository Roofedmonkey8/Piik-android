package io.github.piikandroid

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Home: watch an invite link, or host from this phone. */
class MainActivity : Activity() {
    private lateinit var linkInput: EditText
    private lateinit var recentBox: LinearLayout
    private lateinit var hostStatus: TextView
    private lateinit var hostPrimary: Button
    private lateinit var hostStop: Button
    private var openHostWhenReady = false

    private val prefs by lazy { getSharedPreferences("piik", MODE_PRIVATE) }
    private val hostListener: () -> Unit = { renderHost() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleShare(intent)
        HostState.addListener(hostListener)
        renderHost()
        renderRecents()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        HostState.removeListener(hostListener)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets the focused app read the clipboard.
        if (hasFocus && linkInput.text.isEmpty()) {
            val clip = service<ClipboardManager>().primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            extractLink(text)?.let { if (it !in recents()) linkInput.setText(it) }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extractLink(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { watch(it) }
        }
    }

    // ---- watch ----

    private fun watch(raw: String) {
        val url = normalize(raw) ?: run {
            linkInput.error = "That doesn't look like a link"
            return
        }
        val list = (listOf(url) + recents().filter { it != url }).take(8)
        prefs.edit().putString("recents", list.joinToString("\n")).apply()
        renderRecents()
        startActivity(
            Intent(this, WebActivity::class.java)
                .putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_WATCH)
                .putExtra(WebActivity.EXTRA_URL, url),
        )
    }

    private fun recents(): List<String> =
        prefs.getString("recents", "")!!.split("\n").filter { it.isNotBlank() }

    // ---- host ----

    private fun startHosting() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.RECORD_AUDIO) // phone audio + microphone in shares
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), REQUEST_HOST_PERMISSIONS)
            return
        }
        launchHost()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Hosting works without them; shares just won't include audio.
        if (requestCode == REQUEST_HOST_PERMISSIONS) launchHost()
    }

    private fun launchHost() {
        openHostWhenReady = true
        if (HostState.phase == HostState.Phase.RUNNING) {
            openHostPage()
        } else {
            PiikService.start(this)
        }
        renderHost()
    }

    private fun openHostPage() {
        openHostWhenReady = false
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_MODE, WebActivity.MODE_HOST))
    }

    private fun renderHost() {
        when (HostState.phase) {
            HostState.Phase.STOPPED -> {
                hostStatus.text = "Run a Piik room on this phone and share its screen. " +
                    "You'll pick Local (same Wi-Fi), Public invite (anyone with the link) or your own Piik Site."
                hostPrimary.text = "Start hosting"
                hostStop.visibility = View.GONE
            }
            HostState.Phase.STARTING -> {
                hostStatus.text = "Starting Piik…"
                hostPrimary.text = "Starting…"
                hostStop.visibility = View.VISIBLE
            }
            HostState.Phase.RUNNING -> {
                hostStatus.text = "Piik is running on this phone." +
                    if (!NetInfo.onWifi(this)) "\nYou're not on Wi-Fi: use Public invite so others can join." else ""
                hostPrimary.text = "Open host page"
                hostStop.visibility = View.VISIBLE
                if (openHostWhenReady) openHostPage()
            }
            HostState.Phase.FAILED -> {
                hostStatus.text = HostState.message ?: "Piik stopped."
                hostPrimary.text = "Try again"
                hostStop.visibility = View.GONE
            }
        }
    }

    // ---- UI ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(CARD); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LINE)
        }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = GradientDrawable().apply {
            setColor(if (primary) ACCENT else CARD2); cornerRadius = dp(12).toFloat()
        }
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        column.addView(text("Piik", 30f, TEXT, bold = true))
        column.addView(text("Screen sharing with friends — watch or host from your phone.", 14f, MUTED))
        column.addView(gap(22))

        val watch = card()
        watch.addView(text("Watch a stream", 18f, TEXT, bold = true))
        watch.addView(gap(6))
        watch.addView(text("Paste the invite link you were sent (or share it to Piik from your chat app).", 14f, MUTED))
        watch.addView(gap(12))
        linkInput = EditText(this).apply {
            hint = "https://…"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(INPUT); cornerRadius = dp(10).toFloat(); setStroke(dp(1), LINE) }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO) { watch(text.toString()); true } else false }
        }
        watch.addView(linkInput)
        watch.addView(gap(10))
        watch.addView(button("Watch", true) { watch(linkInput.text.toString()) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        watch.addView(recentBox)
        column.addView(watch)
        column.addView(gap(16))

        val host = card()
        host.addView(text("Host from this phone", 18f, TEXT, bold = true))
        host.addView(gap(6))
        hostStatus = text("", 14f, MUTED)
        host.addView(hostStatus)
        host.addView(gap(12))
        hostPrimary = button("Start hosting", true) {
            if (HostState.phase == HostState.Phase.RUNNING) openHostPage() else startHosting()
        }
        host.addView(hostPrimary, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        hostStop = button("Stop hosting", false) { PiikService.stop(this) }
        host.addView(gap(8))
        host.addView(hostStop, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        column.addView(host)
        column.addView(gap(22))

        column.addView(text("Unofficial Android port of Piik (MIT licence). Not affiliated with the Piik project.", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TNTcraftHIM/Piik"))) }
        })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(column, MATCH_PARENT, WRAP_CONTENT)
            fitsSystemWindows = true
        }
    }

    private fun renderRecents() {
        recentBox.removeAllViews()
        val list = recents()
        if (list.isEmpty()) return
        recentBox.addView(gap(14))
        recentBox.addView(text("Recent", 12f, MUTED, bold = true))
        for (url in list) {
            recentBox.addView(text(Uri.parse(url).host ?: url, 15f, ACCENT).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { watch(url) }
                setOnLongClickListener {
                    prefs.edit().putString("recents", recents().filter { it != url }.joinToString("\n")).apply()
                    renderRecents()
                    true
                }
            })
        }
    }

    companion object {
        private const val REQUEST_HOST_PERMISSIONS = 1
        private val BG = Color.parseColor("#0B0C10")
        private val CARD = Color.parseColor("#15171E")
        private val CARD2 = Color.parseColor("#1E2130")
        private val INPUT = Color.parseColor("#0F1116")
        private val LINE = Color.parseColor("#262A36")
        private val TEXT = Color.parseColor("#E9ECF5")
        private val MUTED = Color.parseColor("#8C93A3")
        private val ACCENT = Color.parseColor("#6E8BFF")

        private val LINK = Regex("""(https?://[^\s<>"']+)|((?:\d{1,3}\.){3}\d{1,3}:\d{2,5}[^\s<>"']*)""")

        fun extractLink(text: String?): String? = text?.let { LINK.find(it)?.value }

        /** Accepts full links, bare hosts and LAN "ip:port" invites. */
        fun normalize(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val withScheme = when {
                t.startsWith("http://") || t.startsWith("https://") -> t
                Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""").matches(t) -> "http://$t" // Local rooms are plain HTTP
                t.contains('.') && !t.contains(' ') -> "https://$t"
                else -> return null
            }
            val uri = Uri.parse(withScheme)
            return if (uri.host.isNullOrBlank()) null else withScheme
        }
    }
}
