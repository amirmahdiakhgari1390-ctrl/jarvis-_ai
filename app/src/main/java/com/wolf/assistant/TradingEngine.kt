package com.wolf.assistant

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * موتور اختصاصی تحلیل ترید و بازارهای مالی (رمزارزها، طلا، فارکس، مدیریت ریسک و تحلیل تکنیکال)
 */
object TradingEngine {

    private val COIN_MAP = mapOf(
        "بیت کوین" to "bitcoin", "بیت‌کوین" to "bitcoin", "btc" to "bitcoin", "bitcoin" to "bitcoin",
        "اتریوم" to "ethereum", "eth" to "ethereum", "ethereum" to "ethereum",
        "سولانا" to "solana", "sol" to "solana", "solana" to "solana",
        "تون" to "the-open-network", "تون کوین" to "the-open-network", "ton" to "the-open-network",
        "ریپل" to "ripple", "xrp" to "ripple",
        "دوج" to "dogecoin", "دوج کوین" to "dogecoin", "doge" to "dogecoin",
        "بایننس" to "binancecoin", "bnb" to "binancecoin",
        "کاردانو" to "cardano", "ada" to "cardano",
        "ترون" to "tron", "trx" to "tron",
        "تتر" to "tether", "usdt" to "tether",
        "نات کوین" to "notcoin", "not" to "notcoin",
        "طلا" to "pax-gold", "انس طلا" to "pax-gold", "xau" to "pax-gold"
    )

    data class MarketSnapshot(
        val symbolName: String,
        val priceUsd: Double,
        val change24h: Double,
        val high24h: Double,
        val low24h: Double,
        val volume24h: Double,
        val sparkline7d: List<Double>
    )

    fun detectCoinId(query: String): String? {
        val q = query.lowercase(Locale.ROOT)
        for ((key, id) in COIN_MAP) {
            if (q.contains(key)) return id
        }
        return null
    }

    fun fetchMarketData(coinId: String): MarketSnapshot? {
        return try {
            val url = URL(
                "https://api.coingecko.com/api/v3/coins/markets" +
                    "?vs_currency=usd&ids=$coinId&sparkline=true&price_change_percentage=24h"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val arr = JSONArray(raw)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            val sparkArr = obj.optJSONObject("sparkline_in_7d")?.optJSONArray("price")
            val spark = mutableListOf<Double>()
            if (sparkArr != null) {
                for (i in 0 until sparkArr.length()) {
                    spark.add(sparkArr.optDouble(i))
                }
            }
            MarketSnapshot(
                symbolName = obj.optString("name", coinId),
                priceUsd = obj.optDouble("current_price", 0.0),
                change24h = obj.optDouble("price_change_percentage_24h", 0.0),
                high24h = obj.optDouble("high_24h", 0.0),
                low24h = obj.optDouble("low_24h", 0.0),
                volume24h = obj.optDouble("total_volume", 0.0),
                sparkline7d = spark
            )
        } catch (e: Exception) {
            null
        }
    }

    fun calculateRsi(prices: List<Double>, period: Int = 14): Double? {
        if (prices.size <= period) return null
        val recent = prices.takeLast(period + 1)
        var gains = 0.0
        var losses = 0.0
        for (i in 1 until recent.size) {
            val diff = recent[i] - recent[i - 1]
            if (diff >= 0) gains += diff else losses -= diff
        }
        if (losses == 0.0) return 100.0
        val rs = (gains / period) / (losses / period)
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun buildTechnicalContext(snap: MarketSnapshot): String {
        val rsi = calculateRsi(snap.sparkline7d)
        val min7d = snap.sparkline7d.minOrNull() ?: snap.low24h
        val max7d = snap.sparkline7d.maxOrNull() ?: snap.high24h
        val rsiText = if (rsi != null) String.format(Locale.US, "%.1f", rsi) else "نامشخص"
        return """
            [داده‌های زنده بازار برای ${snap.symbolName}]:
            - قیمت فعلی (دلار): ${snap.priceUsd}
            - تغییر ۲۴ ساعته: ${String.format(Locale.US, "%.2f", snap.change24h)}%
            - سقف ۲۴ ساعته: ${snap.high24h} | کف ۲۴ ساعته: ${snap.low24h}
            - سقف ۷ روزه: $max7d | کف ۷ روزه: $min7d
            - شاخص قدرت نسبی (RSI-14 روی تایم‌فریم ساعتی ۷ روزه): $rsiText
            - حجم معاملات ۲۴ ساعته: ${snap.volume24h.toLong()} دلار
        """.trimIndent()
    }

    fun calculatePositionSize(balance: Double, riskPercent: Double, stopLossPercent: Double): String {
        if (balance <= 0 || riskPercent <= 0 || stopLossPercent <= 0) {
            return "قربان، اعداد سرمایه، درصد ریسک و حد ضرر باید بزرگ‌تر از صفر باشند."
        }
        val riskAmount = balance * (riskPercent / 100.0)
        val positionSize = riskAmount / (stopLossPercent / 100.0)
        val suggestedLeverage = (positionSize / balance).coerceAtLeast(1.0)
        return String.format(
            Locale.US,
            "محاسبه مدیریت ریسک ولف:
" +
                "• حداکثر ضرر مجاز (ریسک): %.2f دلار
" +
                "• حجم کل پوزیشن (Position Size): %.2f دلار
" +
                "• اهرم (Leverage) پیشنهادی: %.1fx
" +
                "• حد سود پیشنهادی (R:R = 1:2): سود %.2f دلار",
            riskAmount,
            positionSize,
            suggestedLeverage,
            riskAmount * 2
        )
    }
}
