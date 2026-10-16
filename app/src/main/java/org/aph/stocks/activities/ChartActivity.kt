package org.aph.stocks.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.humanware.keysoftsdk.selfbrailling.SelfBraillingManager
import com.humanware.keysoftsdk.selfbrailling.aidl.DotsMatrix
import com.humanware.keysoftsdk.selfbrailling.widget.SelfBraillingWidget
import com.humanware.keysoftsdk.translator.Translator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aph.stocks.TwelveDataClient
import org.aph.stocks.graphics.StockChartRenderer
import org.aph.stocks.monarch_utils.BrailleUtil
import org.aph.stocks.monarch_utils.Drawing
import org.aph.stocks.monarch_utils.Position

/**
 * Chart screen: the graph fills the entire canvas (all 8 lines) -- price,
 * change, time, and market-open status are spoken only (spokenSummary()),
 * never drawn on screen, so there's no on-screen header competing with
 * the graph for space.
 *
 * Range cycles Week <-> Month <-> Year <-> All (wrapping both directions)
 * via D-pad Up (forward) / D-pad Down (backward) -- D-pad Up
 * hardware-confirmed twice via live adb key testing. Zoom In/Out are
 * reserved by the device's screen reader and never reach the app, so they
 * are not used.
 *
 * Back navigation is the physical Back button / Android's own back
 * handling (confirmed registering automatically for this screen via
 * logcat's CoreBackPreview callback) -- no custom key handling needed or
 * used. D-pad Down was tried as a manual "back" trigger early on but was
 * removed once the real Back button was confirmed to already work.
 *
 * Double-tap anywhere speaks the nearest point's price + date (price
 * first -- see pointDateLabel's own doc comment for why)
 * (StockChartRenderer.nearestIndexForColumn, same "snap to nearest"
 * pattern PlantWave uses for its axis ticks).
 */
class ChartActivity : AppCompatActivity() {

    private lateinit var manager: SelfBraillingManager
    private lateinit var translator: Translator
    private lateinit var widget: SelfBraillingWidget
    private lateinit var screenDimensions: Size
    private lateinit var brailleScreen: DotsMatrix
    private val mutableLiveDots = MutableLiveData<Array<ByteArray>>()
    private val mutableViewedImage = MutableLiveData<Array<ByteArray>>()

    private var areServicesBound = false
    private var firstRenderDone = false
    private var lastKeyActionMs = 0L

    private lateinit var symbol: String
    private lateinit var companyName: String

    private var closes: List<TwelveDataClient.DailyBar> = emptyList()
    private var quote: TwelveDataClient.Quote? = null
    private var loadError: String? = null
    private var isLoading = true
    private var range = Range.ONE_DAY

    /**
     * calendarDays is an exact day count back from today (not a bar count --
     * see TwelveDataClient.fetchDailyCloses), null when there's no natural
     * start date (ONE_DAY, ALL), which fall back to outputSize instead.
     * Cycling (D-pad Up/Down) follows declaration order, wrapping both ways.
     *
     * ONE_DAY and ONE_WEEK use intraday data (5min/1h) instead of daily
     * closes -- with only one dot per day, short ranges looked much
     * "straighter" than a real reference (Yahoo Finance), which plots
     * intraday movement, not just one closing price per day. ONE_DAY also
     * sets filterToLatestSession so a 5-minute fetch near market open/close
     * doesn't blend today's session with the tail of the previous one.
     */
    private enum class Range(
        val calendarDays: Int?,
        val label: String,
        val interval: String = "1day",
        val outputSize: Int = 1,
        val filterToLatestSession: Boolean = false
    ) {
        ONE_DAY(null, "1D", interval = "5min", outputSize = 100, filterToLatestSession = true),
        ONE_WEEK(7, "1W", interval = "1h"),
        ONE_MONTH(30, "1M"),
        ONE_YEAR(365, "1Y"),
        ALL(null, "All", outputSize = MAX_OUTPUT_SIZE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        areServicesBound = true

        symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: ""
        companyName = intent.getStringExtra(EXTRA_COMPANY_NAME) ?: ""

        manager = SelfBraillingManager(this).apply { bindService() }
        translator = Translator(this).apply { bindService() }
        widget = SelfBraillingWidget(this).also { setContentView(it) }
        supportActionBar?.hide()

        mutableViewedImage.observe(this) { widget.refresh(it) }
        mutableLiveDots.observe(this) { manager.displayDots(it) }
        screenDimensions = Size(manager.brailleDisplayDotsSizeX, manager.brailleDisplayDotsSizeY)
        brailleScreen = DotsMatrix(screenDimensions.height, screenDimensions.width)

        widget.onSelfBraillingWidgetListener = object : SelfBraillingWidget.OnSelfBraillingWidgetListener {
            override fun onFocused() = paint()
            override fun onSingleTapAtBraillePosition(p0: Int, p1: Int) {
                // No-op: single-tap is blocked by the screen reader on this device.
            }
            override fun onDoubleTapAtBraillePosition(p0: Int, p1: Int) {
                speakPointAt(p0)
            }
        }

        lifecycleScope.launch { delay(1000) }.invokeOnCompletion {
            firstRenderDone = true
            paint()
            lifecycleScope.launch { speak("Loading $companyName") }
            loadData() // starts immediately, in parallel with the announcement above -- not gated behind it
        }
    }

    override fun onResume() {
        super.onResume()
        manager.bindService()
        translator.bindService()
        if (!areServicesBound) {
            areServicesBound = true
            lifecycleScope.launch { delay(1000) }.invokeOnCompletion {
                firstRenderDone = true
                paint()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (areServicesBound) {
            areServicesBound = false
            firstRenderDone = false
            manager.unbindService()
            translator.unbindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (areServicesBound) {
            areServicesBound = false
            manager.unbindService()
            translator.unbindService()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // D-pad Up/Down cycle forward/backward through the range list.
            // D-pad Down is NOT "back" -- the physical Back button and
            // Android's own back handling (confirmed registering
            // automatically for this screen via logcat's CoreBackPreview
            // callback) already work, with no custom code needed, so Down
            // was free to repurpose here.
            KeyEvent.KEYCODE_DPAD_UP -> debounced { setRange(adjacentRange(1)) }
            KeyEvent.KEYCODE_DPAD_DOWN -> debounced { setRange(adjacentRange(-1)) }
            // Panning Down opens Details (Open/Previous Close/Volume/52-week
            // range -- the fields dropped from this screen's own header).
            // Requires a global commands.xml context. Zoom In/Out never reach
            // the app.
            KEYCODE_PANNING_DOWN -> debounced { openDetails() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KEYCODE_PANNING_DOWN -> true
        else -> super.onKeyUp(keyCode, event)
    }

    private fun openDetails() {
        val q = quote ?: return // nothing to show yet if the quote hasn't loaded
        startActivity(Intent(this, DetailsActivity::class.java).apply {
            putExtra(DetailsActivity.EXTRA_COMPANY_NAME, companyName)
            putExtra(DetailsActivity.EXTRA_OPEN, q.open)
            putExtra(DetailsActivity.EXTRA_PREVIOUS_CLOSE, q.previousClose)
            putExtra(DetailsActivity.EXTRA_DAY_HIGH, q.high)
            putExtra(DetailsActivity.EXTRA_DAY_LOW, q.low)
            putExtra(DetailsActivity.EXTRA_VOLUME, q.volume)
            putExtra(DetailsActivity.EXTRA_52W_LOW, q.fiftyTwoWeekLow)
            putExtra(DetailsActivity.EXTRA_52W_HIGH, q.fiftyTwoWeekHigh)
        })
    }

    private fun adjacentRange(step: Int): Range {
        val values = Range.values()
        val nextOrdinal = (range.ordinal + step + values.size) % values.size
        return values[nextOrdinal]
    }

    /** Diagnostic only, non-blocking -- logs every key that reaches this Activity, then always defers to normal handling (super) so nothing about real behavior changes. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        Log.d(TAG_KEYS, "$action keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) repeatCount=${event.repeatCount} source=${event.source}")
        return super.dispatchKeyEvent(event)
    }

    /** Physical presses on this hardware can surface more than one key event; only the first within the window counts (see PlantWave's identical pattern). */
    private fun debounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastKeyActionMs < KEY_DEBOUNCE_MS) return
        lastKeyActionMs = now
        action()
    }

    private fun setRange(newRange: Range) {
        if (range == newRange) return
        range = newRange
        if (firstRenderDone) lifecycleScope.launch { speak("Now showing ${spokenRangeLabel(newRange)}.") }
        loadData() // starts immediately, in parallel with the announcement above -- not gated behind it
    }

    private var lastAnnounceAt = 0L
    private var lastAnnounceDurationMs = 0L

    /**
     * announceText returns almost immediately -- it doesn't block for the
     * full utterance to finish playing, it just hands text to the TTS
     * engine, which interrupts whatever it's currently saying the moment the
     * NEXT announceText call arrives. Two calls fired close together can cut
     * the first one off mid-sentence, or worse: if the SECOND call arrives
     * while the first is still actively playing (not just "recently
     * started"), the interruption can misfire and the second utterance gets
     * dropped entirely instead of cleanly taking over -- e.g. switching
     * ranges while the long "Apple Inc. is trading at..." summary is still
     * being read goes silent instead of announcing the new range.
     *
     * A single fixed delay can't handle this: a short phrase like "Now
     * showing the past week" finishes fast, but the full loaded-summary
     * sentence takes much longer to actually finish playing. So this waits
     * out an ESTIMATE of how long the previous utterance takes to speak
     * (word count, not a flat constant) rather than a fixed gap -- if
     * enough time has already passed (the common case, since network calls
     * usually take a while), there's no extra wait at all.
     */
    private suspend fun speak(text: String) {
        val elapsed = System.currentTimeMillis() - lastAnnounceAt
        val remainingWait = lastAnnounceDurationMs - elapsed
        if (remainingWait > 0) delay(remainingWait)
        manager.announceText(text)
        lastAnnounceAt = System.currentTimeMillis()
        lastAnnounceDurationMs = estimateSpeechDurationMs(text)
    }

    /** Rough estimate at a typical screen-reader speech rate (~150 words/minute = ~400ms/word), plus a small buffer for pacing/pauses between sentences. */
    private fun estimateSpeechDurationMs(text: String): Long {
        val wordCount = text.trim().split(Regex("\\s+")).size
        return wordCount * 400L + 300L
    }

    private fun spokenRangeLabel(r: Range): String = when (r) {
        Range.ONE_DAY -> "today"
        Range.ONE_WEEK -> "the past week"
        Range.ONE_MONTH -> "the past month"
        Range.ONE_YEAR -> "the past year"
        Range.ALL -> "all time"
    }

    /** "yyyy-MM-dd" exactly `r.calendarDays` days before today, or null for ALL (no natural start date -- see TwelveDataClient.fetchDailyCloses). */
    private fun startDateFor(r: Range): String? {
        val days = r.calendarDays ?: return null
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -days)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(calendar.time)
    }

    private var loadGeneration = 0

    private fun loadData() {
        isLoading = true
        loadError = null
        paint()
        // Cycling ranges fires a new loadData() before a slower, earlier
        // fetch (same symbol, different range -- e.g. the just-left range)
        // has finished; without this guard an out-of-order response can
        // land last and paint data for a range the user already left.
        val myGeneration = ++loadGeneration
        lifecycleScope.launch {
            // Both calls run in parallel (not one-after-the-other) -- sequential awaits
            // nearly doubled worst-case load time, especially on a slow connection where
            // each call can take up to TIMEOUT_MS on its own.
            val closesDeferred = async {
                TwelveDataClient.fetchDailyCloses(
                    symbol,
                    outputSize = range.outputSize,
                    startDate = startDateFor(range),
                    interval = range.interval,
                    filterToLatestSession = range.filterToLatestSession
                )
            }
            val quoteDeferred = async { TwelveDataClient.fetchQuote(symbol) }
            val closesResult = closesDeferred.await()
            val quoteResult = quoteDeferred.await()
            if (myGeneration != loadGeneration) return@launch // a newer load has since started -- discard this stale result
            isLoading = false

            val closesValue = (closesResult as? TwelveDataClient.Result.Success)?.value
            val quoteValue = (quoteResult as? TwelveDataClient.Result.Success)?.value

            if (closesValue == null) {
                loadError = (closesResult as? TwelveDataClient.Result.Failure)?.message ?: "Couldn't load $symbol"
                quote = null // don't let Details open with stale figures from an earlier successful load while this screen shows an error
                Log.w(TAG_KEYS, "loadData($symbol, range=$range) closes FAILED: $loadError; quoteResult=$quoteResult")
                if (firstRenderDone) speak(loadError!!)
            } else {
                closes = closesValue
                quote = quoteValue // quote failure alone isn't fatal -- the graph still renders without it
                // Prefer the API's own company name (e.g. for a Search-entered
                // ticker, where the caller only knows the raw symbol) over
                // whatever was passed in via the launching Intent.
                if (quoteValue != null) companyName = quoteValue.name
                Log.d(TAG_KEYS, "loadData($symbol, range=$range) OK: ${closesValue.size} bars, quote=${if (quoteValue != null) "ok" else "FAILED: $quoteResult"}")
                if (firstRenderDone) speak(spokenSummary())
            }
            paint()
        }
    }

    /** Full spoken content -- the header is spoken only (never drawn), so this is the only place a listener learns the price/time/market status. */
    private fun spokenSummary(): String {
        val q = quote
        return if (q != null) {
            val direction = if (q.change >= 0) "up" else "down"
            val status = if (q.isMarketOpen) "open" else "closed"
            "$companyName is trading at $${"%.2f".format(q.price)}, $direction ${"%.1f".format(kotlin.math.abs(q.percentChange))} percent, " +
                "as of ${formatMarketTime(q.lastQuoteAt)}. Market is $status."
        } else {
            "$companyName loaded, but price is unavailable right now."
        }
    }

    /** Epoch seconds -> "2:49 PM" in US Eastern time -- all current tickers (AAPL/MSFT/GOOGL) are NASDAQ-listed, so this is a reasonable simplification rather than threading exchange timezone through generically. */
    private fun formatMarketTime(epochSeconds: Long): String {
        val formatter = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("America/New_York")
        return formatter.format(java.util.Date(epochSeconds * 1000))
    }

    /** 712104 -> "712K", 32034880 -> "32.0M" -- raw volume digit strings are hard to parse by ear or by braille at a glance. */
    private fun abbreviateVolume(v: Long): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
        v >= 1_000 -> "%.0fK".format(v / 1_000.0)
        else -> v.toString()
    }

    private fun speakPointAt(column: Int) {
        if (!firstRenderDone || closes.isEmpty()) return
        val index = StockChartRenderer.nearestIndexForColumn(column, closes.size, PLOT_WIDTH)
        val bar = closes[index]
        // Routed through speak() like every other announcement -- calling
        // announceText() directly here let a double-tap collide with (and
        // get silently dropped by, or itself cut off) a still-playing
        // longer announcement, the same class of bug speak() exists to fix.
        lifecycleScope.launch { speak("$${"%.2f".format(bar.close)} ${pointDateLabel(bar.date)}") }
    }

    /**
     * Price-first phrasing (people want the number before the date). How much
     * date detail to speak depends on the active range -- a year number is
     * noise for Week/Month/Year (the range itself already narrows "when"),
     * but matters for All since points there can span many different years.
     */
    private fun pointDateLabel(rawDate: String): String = try {
        // Intraday intervals (ONE_WEEK's "1h") return "yyyy-MM-dd HH:mm:ss"; daily intervals return just "yyyy-MM-dd".
        val hasTime = rawDate.contains(" ")
        val input = java.text.SimpleDateFormat(if (hasTime) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd", java.util.Locale.US)
        val parsed = input.parse(rawDate)!!
        val pattern = when (range) {
            // Already established by context (today) -- just the time, no date at all.
            Range.ONE_DAY -> "'at' h:mm a"
            // With hourly points now, a bare date would be ambiguous (several points share the same day) -- include the time.
            Range.ONE_WEEK -> "'on' EEEE, MMMM d 'at' h:mm a"
            Range.ONE_MONTH, Range.ONE_YEAR -> "'on' MMMM d"
            Range.ALL -> "'on' MMMM d, yyyy"
        }
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(parsed)
    } catch (e: Exception) {
        "on $rawDate" // never crash on an unexpected date format -- fall back to the raw string
    }

    private fun clearScreen() {
        brailleScreen.include(DotsMatrix(Array(screenDimensions.height) { ByteArray(screenDimensions.width) }), 0, 0)
    }

    private fun paint() {
        if (!firstRenderDone) return
        clearScreen()

        when {
            isLoading -> drawLine("Loading $symbol...", 0)
            loadError != null -> {
                drawLine("Error:", 0)
                drawLine(loadError!!, 1)
            }
            closes.isEmpty() -> drawLine("No data for $symbol", 0)
            else -> {
                val values = closes.map { it.close }
                val minValue = values.min()
                val maxValue = values.max()
                val axisPixels = Array(GRAPH_HEIGHT) { ByteArray(PLOT_WIDTH + 1) }
                for (row in 0 until GRAPH_HEIGHT) axisPixels[row][0] = 1 // left reference spine, matching the mockup
                brailleScreen.include(DotsMatrix(axisPixels), 0, 0)
                val linePixels = StockChartRenderer.render(values, minValue, maxValue, PLOT_WIDTH, GRAPH_HEIGHT)
                brailleScreen.include(DotsMatrix(linePixels), PLOT_COL_START, 0)
                // Price/time/market-status header is spoken only (spokenSummary), not drawn --
                // the graph uses the full canvas since there's no on-screen header to make room for.
            }
        }

        mutableViewedImage.value = brailleScreen.matrix
        mutableLiveDots.value = brailleScreen.matrix
    }

    /** Renders one line of plain text as real UEB braille, capped so it can never overrun the next line's row band -- same pattern as HomeActivity. */
    private fun drawLine(text: String, line: Int) {
        val translated = BrailleUtil.translate(text, translator).take(LINE_CELLS)
        Drawing.freeformBraille(translated, brailleScreen, Position(0, LINE_HEIGHT * line))
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_COMPANY_NAME = "company_name"

        private const val KEY_DEBOUNCE_MS = 400L
        private const val MAX_OUTPUT_SIZE = 5000 // Twelve Data's max bar count -- used as the outputsize fallback only when no startDate applies (ALL)
        private const val LINE_HEIGHT = 5   // 4 content rows + 1 gap row, same convention as HomeActivity
        private const val LINE_CELLS = 32
        private const val GRAPH_HEIGHT = 40  // full canvas -- header is spoken only, not drawn
        private const val PLOT_COL_START = 1 // col 0 is the left reference spine
        // Plot uses the remaining width right of the spine; named this way
        // since it mirrors PlantWave's AxisRenderer.PLOT_WIDTH constant.
        private const val PLOT_WIDTH = 95
        private const val TAG_KEYS = "StocksKeyLog"
        private const val KEYCODE_PANNING_DOWN = 421
    }
}
