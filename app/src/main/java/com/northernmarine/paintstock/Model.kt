package com.northernmarine.paintstock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// One inventory line = a product + colour (+ colour code). QR sticker encodes "code".
class PaintItem(
    var code: String,            // e.g. PNT01  (what the QR encodes)
    var product: String,         // SIGMACOVER 350
    var color: String,           // Grey
    var colorCode: String,       // 5177  (may be blank)
    var category: String,        // PAINT / THINNER / HARDENER
    var canVol: Double,          // litres per can
    var thinner: String,         // 91-92 etc (may be blank)
    var area: String,            // usage / ships area
    var qtyCans: Int,            // current containers on board
    var minCans: Int,            // re-order level (cans); 0 = no auto-suggest
    var pricePerCan: Double,     // optional, 0 = none
    var serial: Int = 0          // last printed can serial (1..200, persisted)
) {
    fun robLitres(): Double = canVol * qtyCans
    fun title(): String {
        val cc = if (colorCode.isBlank()) "" else " $colorCode"
        val col = if (color.isBlank()) "" else " — $color"
        return "$product$col$cc"
    }
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code); put("product", product); put("color", color); put("colorCode", colorCode)
        put("category", category); put("canVol", canVol); put("thinner", thinner); put("area", area)
        put("qtyCans", qtyCans); put("minCans", minCans); put("pricePerCan", pricePerCan); put("serial", serial)
    }
    companion object {
        fun fromJson(o: JSONObject) = PaintItem(
            o.optString("code"), o.optString("product"), o.optString("color"), o.optString("colorCode"),
            o.optString("category", "PAINT"), o.optDouble("canVol", 0.0), o.optString("thinner"),
            o.optString("area"), o.optInt("qtyCans", 0), o.optInt("minCans", 0), o.optDouble("pricePerCan", 0.0),
            o.optInt("serial", 0)
        )
    }
}

object Catalog {
    // [product, color, colorCode, category, canVol, thinner, area, qty]
    private val SEED = listOf(
        arrayOf("SIGMADUR ONE/550", "Blue", "1199", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "2"),
        arrayOf("SIGMADUR ONE/550", "Crème", "3074", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "10"),
        arrayOf("SIGMADUR ONE/550", "Yellow", "3138", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "4"),
        arrayOf("SIGMADUR ONE/550", "Orange", "3149", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "19"),
        arrayOf("SIGMADUR ONE/550", "Dark Green", "4199", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "4"),
        arrayOf("SIGMADUR ONE/550", "Red", "6188", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "0"),
        arrayOf("SIGMADUR ONE/550", "White", "7000", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "7"),
        arrayOf("SIGMADUR ONE/550", "Black", "8000", "PAINT", "13.2", "", "External / open deck (topside, decks, superstructure)", "0"),
        arrayOf("SIGMARINE 48", "Yellow", "3138", "PAINT", "20", "20-05", "Engine room & accommodation", "0"),
        arrayOf("SIGMARINE 48", "Light Green", "4171", "PAINT", "20", "20-05", "Engine room machinery", "4"),
        arrayOf("SIGMARINE 48", "Dark Green", "4199", "PAINT", "20", "20-05", "Engine room & accommodation", "4"),
        arrayOf("SIGMARINE 48", "Grey", "5177", "PAINT", "20", "20-05", "Engine room & accommodation", "0"),
        arrayOf("SIGMARINE 48", "Red", "6188", "PAINT", "20", "20-05", "Engine room & accommodation", "2"),
        arrayOf("SIGMARINE 48", "White", "7000", "PAINT", "20", "20-05", "Engine room & accommodation", "4"),
        arrayOf("SIGMARINE 48", "Black", "8000", "PAINT", "20", "20-05", "Engine room & accommodation", "0"),
        arrayOf("SIGMACOVER 350", "Grey", "5177", "PAINT", "16", "91-92", "Primer — External / open deck (topside, decks, superstructure)", "14"),
        arrayOf("SIGMACOVER 350", "Red Brown", "6179", "PAINT", "16", "91-92", "Primer — External / open deck (topside, decks, superstructure)", "12"),
        arrayOf("SIGMACOVER 380", "Green", "", "PAINT", "20", "91-92", "Ballast tanks", "0"),
        arrayOf("SIGMACOVER 380", "Grey", "", "PAINT", "20", "91-92", "Ballast tanks", "0"),
        arrayOf("SIGMARINE 28", "Grey", "", "PAINT", "20", "21-06", "Engine room primer", "10"),
        arrayOf("SIGMATHERM 175", "Aluminium", "", "PAINT", "20", "", "Hot areas up to 175°C", "3"),
        arrayOf("SIGMATHERM 500", "Aluminium", "", "PAINT", "20", "", "Hot areas 200–500°C", "2"),
        arrayOf("SIGMADUR 520/550 Hardener", "Hardener", "", "HARDENER", "1.8", "", "Hardener for Sigmadur", "46"),
        arrayOf("SIGMACOVER 350 Hardener", "Hardener", "", "HARDENER", "4", "", "Hardener for Sigmacover", "26"),
        arrayOf("SIGMA Thinner", "20-05", "", "THINNER", "18", "", "For SIGMARINE 48", "9"),
        arrayOf("SIGMA Thinner", "91-92", "", "THINNER", "18", "", "For SIGMACOVER 350 / 380", "7"),
        arrayOf("SIGMA Thinner", "21-06", "", "THINNER", "18", "", "For SIGMARINE 28", "7")
    )

    // general usage per product, shown in the inventory/list (not per colour)
    fun generalUse(product: String): String {
        val p = product
        return when {
            p.startsWith("SIGMADUR ONE") -> "External topcoat — topside, decks, superstructure"
            p.startsWith("SIGMARINE 48") -> "Engine room & accommodation"
            p.startsWith("SIGMACOVER 350 Hardener") -> "Hardener"
            p.startsWith("SIGMACOVER 350") -> "Primer — External / open deck (topside, decks, superstructure)"
            p.startsWith("SIGMACOVER 380") -> "Ballast tanks"
            p.startsWith("SIGMARINE 28") -> "Engine room primer"
            p.startsWith("SIGMATHERM 175") -> "Hot areas up to 175°C"
            p.startsWith("SIGMATHERM 500") -> "Hot areas 200–500°C"
            p.contains("Hardener") -> "Hardener"
            p.contains("Thinner") -> "Thinner"
            else -> ""
        }
    }

    fun seed(): MutableList<PaintItem> {
        val list = ArrayList<PaintItem>()
        for ((i, a) in SEED.withIndex()) {
            list.add(PaintItem(
                code = "PNT" + (i + 1).toString().padStart(2, '0'),
                product = a[0], color = a[1], colorCode = a[2], category = a[3],
                canVol = a[4].toDouble(), thinner = a[5], area = a[6],
                qtyCans = a[7].toInt(), minCans = 0, pricePerCan = 0.0
            ))
        }
        return list
    }
}

object Stock {
    private fun file(ctx: Context) = File(ctx.filesDir, "stock.json")
    private fun invFile(ctx: Context) = File(ctx.filesDir, "inventory_state.json")

    fun load(ctx: Context): MutableList<PaintItem> {
        val f = file(ctx)
        if (!f.exists()) { val s = Catalog.seed(); save(ctx, s); return s }
        return try {
            val arr = JSONArray(f.readText())
            val list = ArrayList<PaintItem>()
            for (i in 0 until arr.length()) list.add(PaintItem.fromJson(arr.getJSONObject(i)))
            list
        } catch (e: Exception) { Catalog.seed() }
    }

    fun save(ctx: Context, items: List<PaintItem>) {
        val arr = JSONArray(); for (it in items) arr.put(it.toJson())
        file(ctx).writeText(arr.toString())
    }

    private fun readInv(ctx: Context): JSONObject? = try { val f = invFile(ctx); if (f.exists()) JSONObject(f.readText()) else null } catch (_: Exception) { null }

    // items with stock > 0 that were NOT scanned this session (will be zeroed by a full inventory)
    fun notScanned(items: List<PaintItem>, tally: Map<String, Int>): List<PaintItem> =
        items.filter { it.qtyCans > 0 && !tally.containsKey(it.code) }

    // Apply a full month-end inventory: counted -> stock, unscanned -> 0, snapshot + consumption vs previous.
    fun finishInventory(ctx: Context, items: MutableList<PaintItem>, tally: Map<String, Int>) {
        val newQty = HashMap<String, Int>(); for (it in items) newQty[it.code] = tally[it.code] ?: 0
        val state = readInv(ctx)
        val prevQty = HashMap<String, Int>(); var prevDate = ""
        if (state != null) {
            prevDate = state.optString("invDate", "")
            val q = state.optJSONObject("invQty"); if (q != null) for (k in q.keys()) prevQty[k] = q.optInt(k, 0)
        }
        val consArr = JSONArray()
        if (prevDate.isNotEmpty()) for (it in items) {
            val pq = prevQty[it.code] ?: 0; val nq = newQty[it.code] ?: 0
            if (pq == 0 && nq == 0) continue
            consArr.put(JSONObject().apply { put("code", it.code); put("title", it.title()); put("canVol", it.canVol); put("prev", pq); put("now", nq) })
        }
        for (it in items) it.qtyCans = newQty[it.code] ?: 0
        save(ctx, items)
        val out = JSONObject()
        out.put("invDate", dmy())
        val q = JSONObject(); for ((k, v) in newQty) q.put(k, v); out.put("invQty", q)
        if (prevDate.isNotEmpty()) { out.put("consFrom", prevDate); out.put("consTo", dmy()); out.put("consRows", consArr) }
        else if (state != null && state.has("consRows")) { out.put("consFrom", state.optString("consFrom")); out.put("consTo", state.optString("consTo")); out.put("consRows", state.optJSONArray("consRows")) }
        invFile(ctx).writeText(out.toString())
    }

    // returns the saved consumption state (or null if fewer than two inventories done)
    fun consumption(ctx: Context): JSONObject? { val s = readInv(ctx) ?: return null; return if (s.has("consRows") && (s.optJSONArray("consRows")?.length() ?: 0) > 0) s else null }

    fun nextCode(items: List<PaintItem>): String {
        var max = 0
        for (it in items) { val n = it.code.removePrefix("PNT").toIntOrNull() ?: 0; if (n > max) max = n }
        return "PNT" + (max + 1).toString().padStart(2, '0')
    }

    fun now(): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.UK).format(Date())
    fun dmy(): String = SimpleDateFormat("dd.MM.yyyy", Locale.UK).format(Date())
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.UK).format(Date())
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("paint", Context.MODE_PRIVATE)
    fun vessel(ctx: Context) = p(ctx).getString("vessel", "Navigator Copernico") ?: "Navigator Copernico"
    fun setVessel(ctx: Context, v: String) = p(ctx).edit().putString("vessel", v).apply()
    fun email(ctx: Context) = p(ctx).getString("email", "") ?: ""
    fun setEmail(ctx: Context, v: String) = p(ctx).edit().putString("email", v).apply()
    fun supplier(ctx: Context) = p(ctx).getString("supplier", "PPG / Sigma Coatings") ?: "PPG / Sigma Coatings"
    fun setSupplier(ctx: Context, v: String) = p(ctx).edit().putString("supplier", v).apply()
    fun folderUri(ctx: Context): android.net.Uri? { val s = p(ctx).getString("folder", null); return if (s == null) null else android.net.Uri.parse(s) }
    fun setFolderUri(ctx: Context, u: android.net.Uri) = p(ctx).edit().putString("folder", u.toString()).apply()
}
