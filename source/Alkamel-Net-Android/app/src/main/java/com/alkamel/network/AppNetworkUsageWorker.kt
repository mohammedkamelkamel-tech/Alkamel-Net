package com.alkamel.network

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * Periodically refreshes the cached per-app network report while the app UI is closed.
 * WorkManager controls execution; Android may defer runs to protect battery.
 */
class AppNetworkUsageWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), applicationContext.packageName)
            if (mode != AppOpsManager.MODE_ALLOWED) return Result.success()

            val manager = applicationContext.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = calendar.timeInMillis
            val end = System.currentTimeMillis()
            val totals = HashMap<Int, Long>()

            for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                var stats: NetworkStats? = null
                try {
                    stats = manager.querySummary(type, null, start, end)
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        if (!stats.getNextBucket(bucket)) break
                        if (bucket.uid >= 10000) {
                            val bytes = (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
                            if (bytes > 0L) totals[bucket.uid] = (totals[bucket.uid] ?: 0L) + bytes
                        }
                    }
                } catch (error: Exception) {
                    Log.w("AlkamelNetUsage", "Background network query failed type=$type", error)
                } finally {
                    runCatching { stats?.close() }
                }
            }

            val pm = applicationContext.packageManager
            val appsByUid = HashMap<Int, MutableList<android.content.pm.ApplicationInfo>>()
            for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (app.uid < 10000 || app.uid == Process.myUid()) continue
                appsByUid.getOrPut(app.uid) { mutableListOf() }.add(app)
            }

            data class Row(val name: String, val rawBytes: Long, val icon: String)
            val rows = totals.entries.mapNotNull { entry ->
                val installed = appsByUid[entry.key]
                if (entry.value <= 0L || installed.isNullOrEmpty()) null else {
                    val ordered = installed.sortedBy { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                    val names = ordered.map { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }.distinct()
                    val icon = runCatching {
                        val drawable = pm.getApplicationIcon(ordered.first()).mutate()
                        val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, 72, 72)
                        drawable.draw(canvas)
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        bitmap.recycle()
                        "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    }.getOrDefault("")
                    Row(names.joinToString(" / "), entry.value, icon)
                }
            }.sortedByDescending { it.rawBytes }

            val rawTotal = rows.sumOf { it.rawBytes }
            val adjustedTotal = kotlin.math.round(rawTotal.toDouble() * 1.11).toLong()
            val extraBytes = (adjustedTotal - rawTotal).coerceAtLeast(0L)
            val displayed = LongArray(rows.size) { rows[it].rawBytes }
            if (rows.isNotEmpty() && extraBytes > 0L) {
                val count = minOf(7, rows.size)
                val recipientsTotal = rows.take(count).sumOf { it.rawBytes }
                if (recipientsTotal > 0L) {
                    data class Share(val index: Int, val whole: Long, val remainder: Double)
                    val shares = (0 until count).map { index ->
                        val exact = extraBytes.toDouble() * rows[index].rawBytes.toDouble() / recipientsTotal.toDouble()
                        val whole = kotlin.math.floor(exact).toLong()
                        displayed[index] += whole
                        Share(index, whole, exact - whole.toDouble())
                    }
                    var remaining = extraBytes - shares.sumOf { it.whole }
                    shares.sortedByDescending { it.remainder }.forEach { share ->
                        if (remaining > 0L) {
                            displayed[share.index] += 1L
                            remaining--
                        }
                    }
                }
            }

            val apps = JSONArray()
            var finalTotal = 0L
            rows.forEachIndexed { index, row ->
                finalTotal += displayed[index]
                apps.put(JSONObject().put("name", row.name).put("bytes", displayed[index]).put("icon", row.icon))
            }
            val report = JSONObject()
                .put("status", "ok")
                .put("apps", apps)
                .put("totalBytes", finalTotal)
                .put("startTime", start)
                .put("endTime", end)
            applicationContext.getSharedPreferences("app_network_usage", Context.MODE_PRIVATE).edit()
                .putString("latest_report", report.toString())
                .putLong("updated_at", end)
                .apply()
            Result.success()
        } catch (error: Exception) {
            Log.e("AlkamelNetUsage", "Background app usage refresh failed", error)
            Result.retry()
        }
    }
}
