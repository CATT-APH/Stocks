package org.aph.stocks.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import com.humanware.keysoftsdk.selfbrailling.SelfBraillingManager
import com.humanware.keysoftsdk.ui.dialog.SearchDialog
import com.humanware.keysoftsdk.ui.dialog.SearchDialogListener
import com.humanware.keysoftsdk.ui.menu.AbstractMenuActivity
import com.humanware.keysoftsdk.ui.menu.AccessibleListView
import com.humanware.keysoftsdk.ui.menu.accessibleitem.AccessibleItem
import com.humanware.keysoftsdk.ui.menu.accessibleitem.attributes.AccessibleItemAttributes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aph.stocks.R
import com.humanware.keysoftsdk.R as sdkR

/**
 * Home screen: accessible-menu list of tickers via the SDK's own
 * AbstractMenuActivity/AccessibleListView framework, replacing the old
 * hand-drawn SelfBraillingWidget canvas -- this screen is just a list of
 * names, so the SDK's native list widget (auto-braille-translates plain
 * English text) fits better than a raw pixel canvas + hit testing.
 *
 * Row order is fixed (Sort.BY_POSITION), mirroring the old layout's row
 * indices: 0 = Search (opens the SDK's own SearchDialog -- see
 * showSearchDialog()), 1 = AAPL, 2 = MSFT, 3 = GOOGL. The old "Status:
 * ready" footer has no equivalent slot here and is dropped.
 *
 * Single-tap is confirmed working here (hardware-tested via adb) --
 * AccessibleListView is a plain ListView/AdapterView, a different tap
 * contract from SelfBraillingWidget's double-tap-only rule used elsewhere.
 *
 * A SelfBraillingManager is bound purely to speak the full screen content
 * on every appearance (onResume, not just first creation) so someone who
 * can't read the braille pins still knows what's on screen -- this screen
 * doesn't use the manager for rendering at all, only announceText.
 */
class HomeActivity : AbstractMenuActivity() {

    private lateinit var manager: SelfBraillingManager
    private var areServicesBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = SelfBraillingManager(this)
    }

    override fun onResume() {
        super.onResume()
        manager.bindService()
        if (!areServicesBound) {
            areServicesBound = true
            // announceText silently no-ops if called before the AIDL connection
            // settles -- same 1s discipline used everywhere else in this app.
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

    private fun announceScreen() {
        manager.announceText("Stocks. Search. AAPL Apple Inc. MSFT Microsoft. GOOGL Alphabet.")
    }

    /** Diagnostic only, non-blocking -- logs every key that reaches this Activity, then always defers to normal handling (super) so nothing about real behavior changes. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        Log.d(TAG_KEYS, "$action keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) repeatCount=${event.repeatCount} source=${event.source}")
        return super.dispatchKeyEvent(event)
    }

    override fun getTitleId() = R.string.app_name

    override fun initialize(): AccessibleListView.Sort {
        listview = inflateMenuItems()
        return AccessibleListView.Sort.BY_POSITION
    }

    private fun inflateMenuItems(): AccessibleListView {
        return findViewById<AccessibleListView>(sdkR.id.settings_listview).apply {
            setNext(makeAccessibleItem("Search"))
            setNext(makeAccessibleItem("AAPL Apple Inc."))
            setNext(makeAccessibleItem("MSFT Microsoft"))
            setNext(makeAccessibleItem("GOOGL Alphabet"))

            setOnItemClickListener { _, _, position, _ ->
                when (position) {
                    LINE_SEARCH -> showSearchDialog()
                    LINE_AAPL -> openChart("AAPL", "Apple Inc.")
                    LINE_MSFT -> openChart("MSFT", "Microsoft")
                    LINE_GOOGL -> openChart("GOOGL", "Alphabet")
                }
            }
        }
    }

    /**
     * Uses the SDK's own SearchDialog (a real android.app.Dialog with a
     * plain EditText, confirmed via javap against the resolved SDK jar) --
     * not a hand-built text-entry screen. The known SDK limitation is
     * specifically Jetpack Compose text inputs, not View-based EditText,
     * and this dialog is exactly what HumanWare ships for this purpose
     * elsewhere in the SDK.
     *
     * Whatever's typed is treated directly as the ticker symbol (uppercased,
     * no separate company-name lookup) -- ChartActivity already handles an
     * invalid/unknown symbol gracefully (shows the API's own error message),
     * so a bad entry here fails safely rather than crashing.
     */
    private fun showSearchDialog() {
        val dialog = SearchDialog(this)
        dialog.setSearchListener(object : SearchDialogListener {
            override fun onSearch(query: String) {
                val symbol = query.trim().uppercase()
                if (symbol.isNotEmpty()) openChart(symbol, symbol)
            }
            override fun onCancel() = Unit
        })
        dialog.show()
    }

    private fun makeAccessibleItem(label: String): AccessibleItem =
        AccessibleItem(AccessibleItemAttributes(label, sdkR.layout.one_textview, sdkR.id.textview))

    private fun openChart(symbol: String, companyName: String) {
        startActivity(Intent(this, ChartActivity::class.java).apply {
            putExtra(ChartActivity.EXTRA_SYMBOL, symbol)
            putExtra(ChartActivity.EXTRA_COMPANY_NAME, companyName)
        })
    }

    companion object {
        private const val LINE_SEARCH = 0
        private const val LINE_AAPL = 1
        private const val LINE_MSFT = 2
        private const val LINE_GOOGL = 3
        private const val TAG_KEYS = "StocksKeyLog"
    }
}
