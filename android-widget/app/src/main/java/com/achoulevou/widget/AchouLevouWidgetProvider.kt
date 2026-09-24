package com.achoulevou.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.math.max

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
            setRequestProperty("User-Agent", "AchouLevouWidget/2.0")
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
        views.setTextViewText(R.id.txt_status_value, "Atualizando")
        views.setTextViewText(R.id.txt_status_sub, profile.label + " • consultando VM")
        views.setTextViewText(R.id.txt_queue_value, "…")
        views.setTextViewText(R.id.txt_queue_sub, "Buscando fila")
        views.setTextViewText(R.id.txt_sent_value, "…")
        views.setTextViewText(R.id.txt_sent_sub, "Carregando")
        stopCountdown(views, "…")
        views.setTextViewText(R.id.txt_progress_percent, "…")
        views.setProgressBar(R.id.progress_offers, 100, 0, false)
        views.setTextViewText(R.id.txt_progress_detail, "Sincronizando ofertas")
        views.setTextViewText(R.id.txt_footer, "Atualizando dados de " + profile.label)
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
        val total = max(0, snapshot.sentToday + snapshot.pending)
        val progress = if (total > 0) ((snapshot.sentToday * 100.0) / total).toInt().coerceIn(0, 100) else 0

        views.setTextViewText(R.id.txt_status_value, if (snapshot.connected) "Conectado" else "Offline")
        views.setTextColor(
            R.id.txt_status_value,
            Color.parseColor(if (snapshot.connected) "#00F28A" else "#FF5D73")
        )
        views.setTextViewText(
            R.id.txt_status_sub,
            if (snapshot.queueRunning) "WhatsApp Web • fila ativa" else "WhatsApp Web • fila pausada"
        )

        views.setTextViewText(R.id.txt_queue_value, snapshot.pending.toString() + " / " + total)
        views.setTextViewText(
            R.id.txt_queue_sub,
            if (snapshot.queueRunning) "Fila em execução" else "Fila pausada"
        )

        views.setTextViewText(R.id.txt_sent_value, snapshot.sentToday.toString())
        views.setTextViewText(R.id.txt_sent_sub, "Ofertas concluídas hoje")

        startCountdown(views, snapshot.nextRunAt)

        views.setProgressBar(R.id.progress_offers, 100, progress, false)
        views.setTextViewText(R.id.txt_progress_percent, progress.toString() + "%")
        views.setTextViewText(
            R.id.txt_progress_detail,
            snapshot.sentToday.toString() + " de " + total + " ofertas concluídas"
        )

        views.setTextViewText(
            R.id.txt_footer,
            "👥 " + snapshot.groups + " grupos  •  " +
                snapshot.offersPerBatch + " por lote  •  " +
                snapshot.intervalMinutes + " min  •  " +
                snapshot.windowStart + " → " + snapshot.windowEnd
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun startCountdown(views: RemoteViews, nextRunAt: String?) {
        val targetMillis = try {
            nextRunAt?.let { Instant.parse(it).toEpochMilli() }
        } catch (_: Exception) {
            null
        }

        if (targetMillis == null) {
            stopCountdown(views, "—")
            views.setTextViewText(R.id.txt_next_sub, "Sem próximo lote")
            return
        }

        val remaining = targetMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            stopCountdown(views, "AGORA")
            views.setTextViewText(R.id.txt_next_sub, "Próximo lote liberado")
            return
        }

        val base = SystemClock.elapsedRealtime() + remaining
        views.setChronometer(R.id.txt_next_countdown, base, null, true)
        views.setChronometerCountDown(R.id.txt_next_countdown, true)
        views.setTextViewText(R.id.txt_next_sub, "Até o próximo lote")
    }

    private fun stopCountdown(views: RemoteViews, label: String) {
        views.setChronometer(R.id.txt_next_countdown, SystemClock.elapsedRealtime(), null, false)
        views.setChronometerCountDown(R.id.txt_next_countdown, true)
        views.setTextViewText(R.id.txt_next_countdown, label)
    }

    private fun renderError(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        profile: Profile,
        error: Exception
    ) {
        val views = baseViews(context, widgetId, profile)
        views.setTextViewText(R.id.txt_status_value, "Sem leitura")
        views.setTextColor(R.id.txt_status_value, Color.parseColor("#FFB547"))
        views.setTextViewText(R.id.txt_status_sub, "Toque em ↻ para atualizar")
        views.setTextViewText(R.id.txt_queue_value, "—")
        views.setTextViewText(R.id.txt_queue_sub, "VM indisponível")
        views.setTextViewText(R.id.txt_sent_value, "—")
        views.setTextViewText(R.id.txt_sent_sub, "Sem dados")
        stopCountdown(views, "—")
        views.setTextViewText(R.id.txt_next_sub, "Sem leitura")
        views.setProgressBar(R.id.progress_offers, 100, 0, false)
        views.setTextViewText(R.id.txt_progress_percent, "—")
        views.setTextViewText(R.id.txt_progress_detail, "Não foi possível sincronizar")
        views.setTextViewText(R.id.txt_footer, error.message?.take(60) ?: "Falha de conexão")
        manager.updateAppWidget(widgetId, views)
    }

    private fun baseViews(context: Context, widgetId: Int, profile: Profile): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_achou_levou)

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
}
