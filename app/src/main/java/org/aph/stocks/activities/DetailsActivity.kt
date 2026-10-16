package org.aph.stocks.activities

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import com.humanware.keysoftsdk.selfbrailling.SelfBraillingManager
import com.humanware.keysoftsdk.ui.menu.AbstractMenuActivity
import com.humanware.keysoftsdk.ui.menu.AccessibleListView
import com.humanware.keysoftsdk.ui.menu.accessibleitem.AccessibleItem
import com.humanware.keysoftsdk.ui.menu.accessibleitem.attributes.AccessibleItemAttributes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aph.stocks.R
import com.humanware.keysoftsdk.R as sdkR

/**
 * "Long data" page: Open, Previous Close, Day's Range, Volume, 52-Week
 * Range -- the quote fields that were deliberately dropped from Chart's
 * on-screen/spoken header when that screen was simplified down to just
 * price+time+graph. Day's Range (today's high/low) uses fields that were
 * being fetched all along but never displayed anywhere.
 * Fed directly via Intent extras from ChartActivity's already-loaded quote
 * (no new network call -- Chart already has this data in memory by the
 * time a user could reach this screen).
 *
 * Reaches here via Panning Down (keyCode 421) from Chart, which requires a
 * global commands.xml context (viewIdResourceName="*"). This is not always
 * reliable on the device, so it is a best-effort entry point.
 *
 * Returning uses the real Back button instead of Panning Up -- Panning Up
 * produced zero app-level events on this specific screen even right after
 * Panning Down had just worked to get here, while the Back button returned
 * reliably every time (same proven mechanism already used on Chart). The
 * onKeyDown override below still handles Panning Up too, as a harmless
 * bonus shortcut if it ever does arrive, but nothing depends on it.
 *
 * Built on AbstractMenuActivity/AccessibleListView (same proven framework
 * as Home) rather than a hand-drawn canvas -- this screen is just a list
 * of labeled values, a natural fit, and D-pad Up/Down "previous/next item"
 * list navigation comes for free from the SDK.
 */
class DetailsActivity : AbstractMenuActivity() {

    private lateinit var manager: SelfBraillingManager
    private var areServicesBound = false

    private lateinit var companyName: String
    private var open = 0.0
    private var previousClose = 0.0
    private var dayHigh = 0.0
    private var dayLow = 0.0
    private var volume = 0L
    private var fiftyTwoWeekLow = 0.0
    private var fiftyTwoWeekHigh = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be read before super.onCreate() -- that call triggers
        // initialize() synchronously, which needs these fields already set.
        companyName = intent.getStringExtra(EXTRA_COMPANY_NAME) ?: ""
        open = intent.getDoubleExtra(EXTRA_OPEN, 0.0)
        previousClose = intent.getDoubleExtra(EXTRA_PREVIOUS_CLOSE, 0.0)
        dayHigh = intent.getDoubleExtra(EXTRA_DAY_HIGH, 0.0)
        dayLow = intent.getDoubleExtra(EXTRA_DAY_LOW, 0.0)
        volume = intent.getLongExtra(EXTRA_VOLUME, 0L)
        fiftyTwoWeekLow = intent.getDoubleExtra(EXTRA_52W_LOW, 0.0)
        fiftyTwoWeekHigh = intent.getDoubleExtra(EXTRA_52W_HIGH, 0.0)

        super.onCreate(savedInstanceState)
        manager = SelfBraillingManager(this)
    }

    override fun onResume() {
        super.onResume()
        manager.bindService()
        if (!areServicesBound) {
            areServicesBound = true
            lifecycleScope.launch { delay(1000) }.invokeOnCompletion { announceScreen() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (areServicesBound) {
            areServicesBound = false
            manager.unbindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (areServicesBound) {
            areServicesBound = false
            manager.unbindService()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KEYCODE_PANNING_UP) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KEYCODE_PANNING_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Diagnostic only, non-blocking -- same pattern as Home/Chart. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        Log.d(TAG_KEYS, "$action keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) repeatCount=${event.repeatCount} source=${event.source}")
        return super.dispatchKeyEvent(event)
    }

    private fun announceScreen() {
        manager.announceText(
            "Details for $companyName. " +
                "Open $${"%.2f".format(open)}. " +
                "Previous close $${"%.2f".format(previousClose)}. " +
                "Day's range ${"%.2f".format(dayLow)} to ${"%.2f".format(dayHigh)}. " +
                "Volume ${abbreviateVolume(volume)}. " +
                "52 week range ${"%.2f".format(fiftyTwoWeekLow)} to ${"%.2f".format(fiftyTwoWeekHigh)}."
        )
    }

    /** 712104 -> "712K" -- same convention as ChartActivity. */
    private fun abbreviateVolume(v: Long): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
        v >= 1_000 -> "%.0fK".format(v / 1_000.0)
        else -> v.toString()
    }

    override fun getTitleId() = R.string.details_title

    override fun initialize(): AccessibleListView.Sort {
        listview = inflateDetailItems()
        return AccessibleListView.Sort.BY_POSITION
    }

    private fun inflateDetailItems(): AccessibleListView {
        return findViewById<AccessibleListView>(sdkR.id.settings_listview).apply {
            setNext(makeAccessibleItem("Open $${"%.2f".format(open)}"))
            setNext(makeAccessibleItem("Previous Close $${"%.2f".format(previousClose)}"))
            setNext(makeAccessibleItem("Day's Range ${"%.2f".format(dayLow)} - ${"%.2f".format(dayHigh)}"))
            setNext(makeAccessibleItem("Volume ${abbreviateVolume(volume)}"))
            setNext(makeAccessibleItem("52 Week Range ${"%.2f".format(fiftyTwoWeekLow)} - ${"%.2f".format(fiftyTwoWeekHigh)}"))
        }
    }

    private fun makeAccessibleItem(label: String): AccessibleItem =
        AccessibleItem(AccessibleItemAttributes(label, sdkR.layout.one_textview, sdkR.id.textview))

    companion object {
        const val EXTRA_COMPANY_NAME = "company_name"
        const val EXTRA_OPEN = "open"
        const val EXTRA_PREVIOUS_CLOSE = "previous_close"
        const val EXTRA_DAY_HIGH = "day_high"
        const val EXTRA_DAY_LOW = "day_low"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_52W_LOW = "fifty_two_week_low"
        const val EXTRA_52W_HIGH = "fifty_two_week_high"

        private const val KEYCODE_PANNING_UP = 420
        private const val TAG_KEYS = "StocksKeyLog"
    }
}
