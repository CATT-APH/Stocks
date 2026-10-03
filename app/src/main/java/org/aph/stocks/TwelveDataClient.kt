package org.aph.stocks

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Twelve Data client -- plain HttpURLConnection + org.json, both built into
 * Android, deliberately no HTTP-library dependency (avoids another
 * Maven-resolution risk on a same-day build). Every call runs on
 * Dispatchers.IO; Android throws NetworkOnMainThreadException otherwise.
 *
 * The API key comes from BuildConfig.TWELVE_DATA_API_KEY, itself sourced
 * from a gitignored apikeys.properties file -- never a literal here.
 */
object TwelveDataClient {
    private const val BASE_URL = "https://api.twelvedata.com"
    private const val TIMEOUT_MS = 10_000
    private const val TAG = "StocksNetwork"

    // A completed trading day's closing prices never change -- re-fetching
    // the same (symbol, range) combo within a session wastes an API credit
    // for data that's already correct. Intraday/quote data genuinely is
    // live, so those get a much shorter TTL -- long enough to absorb rapid
    // re-renders (range cycling, a quick re-open) without serving stale
    // "live" data for long. Twelve Data's free tier caps at 8 credits/min;
    // this is also a direct mitigation for that.
    private const val DAILY_TTL_MS = 10 * 60_000L
    private const val INTRADAY_TTL_MS = 60_000L
    private const val QUOTE_TTL_MS = 30_000L

    private data class CacheEntry<T>(val result: Result<T>, val fetchedAt: Long)
    private val closesCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<List<DailyBar>>>()
    private val quoteCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry<Quote>>()

    data class DailyBar(val date: String, val close: Double)
    data class Quote(
        val name: String,
        val price: Double,
        val change: Double,
        val percentChange: Double,
        val high: Double,
        val low: Double,
        val open: Double,
        val previousClose: Double,
        val volume: Long,
        val fiftyTwoWeekLow: Double,
        val fiftyTwoWeekHigh: Double,
        val isMarketOpen: Boolean,
        val lastQuoteAt: Long
    )

    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure(val message: String) : Result<Nothing>()
    }

    /**
     * Daily closes, oldest-to-newest (the API itself returns newest-first --
     * reversed here so column 0 is the oldest day, matching how the chart
     * reads left-to-right).
     *
     * `outputsize` alone is a BAR count, not a calendar-day count -- markets
     * are closed weekends/holidays, so e.g. "30" returns the last 30 trading
     * days, which actually spans about 6 calendar weeks, not 1 month. When
     * `startDate` ("yyyy-MM-dd") is given, it's used instead (Twelve Data's
     * `start_date` param, confirmed via a live call to return exactly the
     * trading days within that real calendar range) so "1 month" means
     * exactly 30 calendar days back from today, not 30 bars of data.
     * `outputSize` is the fallback when `startDate` is null (used for "All",
     * which has no natural start date -- just the API's max bar count -- and
     * for intraday "Day", which has no calendar-day start date either).
     *
     * `filterToLatestSession`, for the "Day" range: a `5min`/`1h` fetch near
     * market open/close can include the tail of the PREVIOUS trading
     * session alongside today's, since `outputsize` is a bar count, not a
     * session boundary. When true, only bars matching the most recent date
     * present are kept, so "Day" always shows exactly one clean session.
     * Not used for Week's intraday data, which deliberately spans multiple
     * days.
     */
    suspend fun fetchDailyCloses(symbol: String, outputSize: Int, startDate: String? = null, interval: String = "1day", filterToLatestSession: Boolean = false): Result<List<DailyBar>> = withContext(Dispatchers.IO) {
        if (BuildConfig.TWELVE_DATA_API_KEY.isBlank()) return@withContext Result.Failure("No API key configured")

        val cacheKey = "$symbol|$interval|$startDate|$outputSize|$filterToLatestSession"
        val ttl = if (interval == "1day") DAILY_TTL_MS else INTRADAY_TTL_MS
        closesCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAt < ttl) {
                Log.d(TAG, "fetchDailyCloses($symbol) cache hit ($interval)")
                return@withContext cached.result
            }
        }

        try {
            val sizeOrRange = if (startDate != null) "start_date=$startDate" else "outputsize=$outputSize"
            val url = "$BASE_URL/time_series?symbol=${encode(symbol)}&interval=$interval&$sizeOrRange&apikey=${BuildConfig.TWELVE_DATA_API_KEY}"
            val body = httpGet(url) ?: return@withContext Result.Failure("Network error fetching $symbol")
            val json = JSONObject(body)

            if (json.optString("status") == "error") {
                return@withContext Result.Failure(json.optString("message", "Twelve Data returned an error"))
            }

            val values = json.optJSONArray("values")
                ?: return@withContext Result.Failure("No price history for $symbol")
            val bars = ArrayList<DailyBar>(values.length())
            for (i in 0 until values.length()) {
                val entry = values.getJSONObject(i)
                bars += DailyBar(entry.getString("datetime"), entry.getString("close").toDouble())
            }
            val ordered = bars.asReversed() // API is newest-first; chart wants oldest-first
            val filtered = if (filterToLatestSession && ordered.isNotEmpty()) {
                val latestDate = ordered.last().date.substringBefore(" ")
                ordered.filter { it.date.startsWith(latestDate) }
            } else {
                ordered
            }
            val result = Result.Success(filtered)
            closesCache[cacheKey] = CacheEntry(result, System.currentTimeMillis()) // only successes are cached -- a transient failure (e.g. rate limit) shouldn't get "stuck" being served back
            result
        } catch (e: Exception) {
            Log.w(TAG, "fetchDailyCloses($symbol) failed: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.Failure("Couldn't read price data for $symbol")
        }
    }

    suspend fun fetchQuote(symbol: String): Result<Quote> = withContext(Dispatchers.IO) {
        if (BuildConfig.TWELVE_DATA_API_KEY.isBlank()) return@withContext Result.Failure("No API key configured")

        quoteCache[symbol]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAt < QUOTE_TTL_MS) {
                Log.d(TAG, "fetchQuote($symbol) cache hit")
                return@withContext cached.result
            }
        }

        try {
            val url = "$BASE_URL/quote?symbol=${encode(symbol)}&apikey=${BuildConfig.TWELVE_DATA_API_KEY}"
            val body = httpGet(url) ?: return@withContext Result.Failure("Network error fetching $symbol")
            val json = JSONObject(body)

            if (json.optString("status") == "error" || !json.has("close")) {
                return@withContext Result.Failure(json.optString("message", "No quote for $symbol"))
            }

            val fiftyTwoWeek = json.optJSONObject("fifty_two_week")

            val result = Result.Success(
                Quote(
                    name = json.optString("name", symbol),
                    price = json.getString("close").toDouble(),
                    change = json.getString("change").toDouble(),
                    percentChange = json.getString("percent_change").toDouble(),
                    high = json.getString("high").toDouble(),
                    low = json.getString("low").toDouble(),
                    open = json.getString("open").toDouble(),
                    previousClose = json.getString("previous_close").toDouble(),
                    volume = json.getString("volume").toLong(),
                    fiftyTwoWeekLow = fiftyTwoWeek?.optString("low")?.toDoubleOrNull() ?: 0.0,
                    fiftyTwoWeekHigh = fiftyTwoWeek?.optString("high")?.toDoubleOrNull() ?: 0.0,
                    isMarketOpen = json.optBoolean("is_market_open", false),
                    lastQuoteAt = json.optLong("last_quote_at", 0L)
                )
            )
            quoteCache[symbol] = CacheEntry(result, System.currentTimeMillis())
            result
        } catch (e: Exception) {
            Log.w(TAG, "fetchQuote($symbol) failed: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.Failure("Couldn't read quote for $symbol")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Returns the response body on HTTP 200, null on any transport/HTTP-status failure -- callers turn that into a Result.Failure. */
    private fun httpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }
                Log.w(TAG, "httpGet non-200: code=$code url=${redactApiKey(urlString)} body=$errorBody")
                return null
            }
            return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed: ${e.javaClass.simpleName}: ${e.message} url=${redactApiKey(urlString)}", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** The live API key must never land in logcat/bug reports -- gitignored apikeys.properties was always kept out of source for the same reason. */
    private fun redactApiKey(urlString: String): String = urlString.replace(Regex("apikey=[^&]*"), "apikey=REDACTED")
}
