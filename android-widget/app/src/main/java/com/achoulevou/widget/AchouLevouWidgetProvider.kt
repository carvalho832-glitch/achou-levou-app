package com.achoulevou.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class AchouLevouWidgetProvider : AppWidgetProvider() {

    data class Profile(val id: String, val label: String, val baseUrl: String)

    data class Snapshot(
        val connected: Boolean,
        val queueRunning: Boolean,
        val pending: Int,
        val sentToday: Int,
        val groups: Int,
        val nextRunAt: String?,
        val blockReason: String?,
        val windowStart: String,
        val windowEnd: String,
        val offersPerBatch: Int,
        val intervalMinutes: Int
    )

    companion object {
        private const val ACTION_SELECT_JULIO = "com.achoulevou.widget.SELECT_JULIO"
        private const val ACTION_SELECT_RENATA = "com.achoulevou.widget.SELECT_RENATA"
        private const val ACTION_REFRESH = "com.achoulevou.widget.REFRESH"
        private const val EXTRA_WIDGET_ID = "widget_id"
        private const val PREFS = "achou_levou_widget"
        private const val PROFILE_PREFIX = "profile_"

        private val executor = Executors.newCachedThreadPool()
        private val zone = ZoneId.of("America/Sao_Paulo")
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

        private val JULIO = Profile("julio", "Júlio", "https://bot.achoulevoubot.uk")
        private val RENATA = Profile("renata", "Renata", "https://usuario2.achoulevoubot.uk")
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { widgetId ->
            renderLoading(context, manager, widgetId)
            refreshAsync(context.applicationContext, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val action = intent.action ?: return
        if (action != ACTION_SELECT_JULIO && action != ACTION_SELECT_RENATA && action != ACTION_REFRESH) return

        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        if (action == ACTION_SELECT_JULIO || action == ACTION_SELECT_RENATA) {
            val selected = if (action == ACTION_SELECT_RENATA) RENATA.id else JULIO.id
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PROFILE_PREFIX + widgetId, selected)
                .apply()
        }

        val manager = AppWidgetManager.getInstance(context)
        renderLoading(context, manager, widgetId)
        refreshAsync(context.applicationContext, widgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { editor.remove(PROFILE_PREFIX + it) }
        editor.apply()
        super.onDeleted(context, appWidgetIds)
    }

    private fun selectedProfile(context: Context, widgetId: Int): Profile {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_PREFIX + widgetId, JULIO.id)
        return if (id == RENATA.id) RENATA else JULIO
    }

    private fun refreshAsync(context: Context, widgetId: Int) {
        executor.execute {
            val manager = AppWidgetManager.getInstance(context)
            val profile = selectedProfile(context, widgetId)
            try {
                val snapshot = fetchSnapshot(profile)
                renderSnapshot(context, manager, widgetId, profile, snapshot)
            } catch (error: Exception) {
                renderError(context, manager, widgetId, profile, error)
            }
        }
    }

    private fun fetchSnapshot(profile: Profile): Snapshot {
        val status = fetchJson(profile.baseUrl + "/status")
        val settingsRoot = fetchJson(profile.baseUrl + "/settings")
        val settings = settingsRoot.optJSONObject("settings") ?: JSONObject()

        return Snapshot(
            connected = status.optString("status").equals("conectado", ignoreCase = true),
            queueRunning = status.optBoolean("queueRunning", false),
            pending = status.optInt("pendingOffers", 0),
            sentToday = settings.optInt("sentToday", 0),
            groups = status.optInt("selectedGroups", 0),
            nextRunAt = nullableString(status, "nextRunAt"),
            blockReason = nullableString(status, "blockReason"),
            windowStart = settings.optString("windowStart", "--:--"),
            windowEnd = settings.optString("windowEnd", "--:--"),
            offersPerBatch = settings.optInt("offersPerBatch", 0),
            intervalMinutes = settings.optInt("intervalMinutes", 0)
        )
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AchouLevouWidget/1.0")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP " + code)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun renderLoading(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val profile = selectedProfile(context, widgetId)
        val views = baseViews(context, widgetId, profile)
        views.setTextViewText(R.id.txt_status, "🟡 Atualizando " + profile.label + "...")
        views.setTextViewText(R.id.txt_queue, "Buscando dados da fila")
        views.setTextViewText(R.id.txt_groups, "")
        views.setTextViewText(R.id.txt_schedule, "")
        views.setTextViewText(R.id.txt_reason, "")
        manager.updateAppWidget(widgetId, views)
    }

    private fun renderSnapshot(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        profile: Profile,
        snapshot: Snapshot
    ) {
        val views = baseViews(context, widgetId, profile)
        val connection = if (snapshot.connected) "🟢 WhatsApp conectado" else "🔴 WhatsApp offline"
        val queueState = if (snapshot.queueRunning) "fila ativa" else "fila pausada"

        views.setTextViewText(R.id.txt_status, connection + " • " + queueState)
        views.setTextViewText(
            R.id.txt_queue,
            "📦 " + snapshot.pending + " pendentes   •   ✅ " + snapshot.sentToday + " enviadas hoje"
        )
        views.setTextViewText(
            R.id.txt_groups,
            "👥 " + snapshot.groups + " grupos   •   " + snapshot.offersPerBatch +
                " por lote   •   " + snapshot.intervalMinutes + " min"
        )
        views.setTextViewText(
            R.id.txt_schedule,
            "🕒 " + snapshot.windowStart + " → " + snapshot.windowEnd +
                "   •   Próximo: " + formatNext(snapshot.nextRunAt)
        )
        views.setTextViewText(
            R.id.txt_reason,
            snapshot.blockReason?.take(72) ?: "Atualizado às " + clockFormat.format(Instant.now())
        )
        manager.updateAppWidget(widgetId, views)
    }

    private fun renderError(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        profile: Profile,
        error: Exception
    ) {
        val views = baseViews(context, widgetId, profile)
        views.setTextViewText(R.id.txt_status, "⚠️ " + profile.label + ": sem leitura")
        views.setTextViewText(R.id.txt_queue, "Toque em ↻ para tentar novamente")
        views.setTextViewText(R.id.txt_groups, "")
        views.setTextViewText(R.id.txt_schedule, "")
        views.setTextViewText(R.id.txt_reason, error.message?.take(70) ?: "Falha de conexão")
        manager.updateAppWidget(widgetId, views)
    }

    private fun baseViews(context: Context, widgetId: Int, profile: Profile): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_achou_levou)
        views.setTextViewText(R.id.txt_profile, "ACHOU LEVOU • " + profile.label.uppercase())

        views.setInt(
            R.id.btn_julio,
            "setBackgroundResource",
            if (profile.id == JULIO.id) R.drawable.profile_selected else R.drawable.profile_idle
        )
        views.setInt(
            R.id.btn_renata,
            "setBackgroundResource",
            if (profile.id == RENATA.id) R.drawable.profile_selected else R.drawable.profile_idle
        )

        views.setOnClickPendingIntent(
            R.id.btn_julio,
            widgetBroadcast(context, widgetId, ACTION_SELECT_JULIO, 1)
        )
        views.setOnClickPendingIntent(
            R.id.btn_renata,
            widgetBroadcast(context, widgetId, ACTION_SELECT_RENATA, 2)
        )
        views.setOnClickPendingIntent(
            R.id.btn_refresh,
            widgetBroadcast(context, widgetId, ACTION_REFRESH, 3)
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            widgetId * 10 + 4,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_header, openPending)
        return views
    }

    private fun widgetBroadcast(
        context: Context,
        widgetId: Int,
        action: String,
        offset: Int
    ): PendingIntent {
        val intent = Intent(context, AchouLevouWidgetProvider::class.java).apply {
            this.action = action
            putExtra(EXTRA_WIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId * 10 + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatNext(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        return try {
            clockFormat.format(Instant.parse(value))
        } catch (_: Exception) {
            value.take(16)
        }
    }
}
