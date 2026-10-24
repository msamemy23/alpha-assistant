package com.alphainternational.assistant

import android.app.PendingIntent
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The home-screen widget ("Working on 2 things", Ask, Talk, Away), the Quick Settings tile ("Ask Alpha") and the long-press shortcuts for the bots
 * used most. The words and the routing are in [FrontDoor] (tested); this file only draws them and starts Alpha. Nothing here keeps a service running:
 * the widget is redrawn when a job starts or ends (see [Reach.pulse]) and only when the line it shows has changed.
 */
object Reach {
    private const val PREFS="reach_front_doors"
    private var last:Doors.WidgetState?=null
    private var shortcutSignature=""
    private val needsYouStatuses=setOf("Needs review","Needs you","Needs connection","Check result")

    /** The state the widget shows. In a fresh process nothing can be running yet, so only the saved Away and waiting facts are used. */
    fun current(context:Context):Doors.WidgetState = last?:load(context).copy(running=TaskService.activeCount)

    /**
     * Called by the app when a job starts or ends or Away mode changes (a few cheap lines in Controller). It works out the line the widget and the tile
     * show and only touches them when it differs from last time, so a busy bot costs nothing extra.
     */
    fun pulse(c:Controller) {
        val state=Doors.WidgetState(TaskService.activeCount,c.tasks.count {it.status in needsYouStatuses && !it.from.startsWith("away")},
            c.awaySettings().on,c.awayPending().size)
        pulse(c.context,state)
    }
    fun pulse(context:Context,state:Doors.WidgetState) {
        if(state==last) return
        last=state
        runCatching {context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("needs",state.needsYou).putBoolean("away",state.awayOn).putInt("waiting",state.awayWaiting).apply()}
        AlphaWidget.redrawAll(context,state)
        runCatching {TileService.requestListeningState(context,ComponentName(context,AlphaTile::class.java))}
    }
    private fun load(context:Context):Doors.WidgetState = runCatching {
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        Doors.WidgetState(0,p.getInt("needs",0),p.getBoolean("away",false),p.getInt("waiting",0))
    }.getOrDefault(Doors.WidgetState())

    /** A launch intent for a destination, safe to put in a PendingIntent (its own request code, so the buttons stay different). */
    fun open(context:Context,destination:String,code:Int):PendingIntent =
        PendingIntent.getActivity(context,code,AppNavigation.intent(context,destination),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Long-press shortcuts for the bots used most recently (the fixed Ask, Talk and Away ones are in res/xml/shortcuts.xml). Skips the call when the
     * choice has not changed since last time.
     */
    fun refreshShortcuts(c:Controller) {
        val used=c.tasks.filter {it.routineId.isBlank() && !it.from.startsWith("away")}.groupBy {it.workspace}.mapValues {(_,list)->list.maxOf {it.created}}
        val names=Doors.topBots(c.bots.map {it.name},used,1)
        val signature=names.joinToString("|")
        if(signature==shortcutSignature) return
        shortcutSignature=signature
        runCatching {ShortcutManagerCompat.setDynamicShortcuts(c.context,shortcutsFor(c.context,names))}
    }

    /**
     * Long-press the icon: Ask, Talk and Away mode, then the bot used most recently. They are made here, in code, and not in a shortcuts file, because that
     * file cannot name the app's own package when the friends copy has a different one. Each only opens Alpha at the right place (see Doors.resolve).
     */
    fun shortcutsFor(context:Context,bots:List<String>):List<ShortcutInfoCompat> {
        fun fixed(id:String,word:String,icon:Int,short:Int,long:Int)=ShortcutInfoCompat.Builder(context,id).setShortLabel(context.getString(short)).setLongLabel(context.getString(long))
            .setIcon(IconCompat.createWithResource(context,icon)).setIntent(AppNavigation.intent(context,word).setAction(Intent.ACTION_VIEW)).build()
        return listOf(fixed("ask","ask",R.drawable.ic_shortcut_ask,R.string.shortcut_ask_short,R.string.shortcut_ask_long),
            fixed("talk","talk",R.drawable.ic_shortcut_talk,R.string.shortcut_talk_short,R.string.shortcut_talk_long),
            fixed("away","away_toggle",R.drawable.ic_shortcut_away,R.string.shortcut_away_short,R.string.shortcut_away_long))+
            bots.map {name->ShortcutInfoCompat.Builder(context,"bot:$name").setShortLabel(name).setLongLabel("Chat with $name")
                .setIcon(IconCompat.createWithResource(context,R.drawable.ic_shortcut_ask)).setIntent(AppNavigation.intent(context,"chat",name).setAction(Intent.ACTION_VIEW)).build()}
    }

    /** "Away mode" from a shortcut: turns it off when it is on, and otherwise shows the Away screen where the owner sets it up and switches it on. */
    fun awayShortcut(c:Controller) {
        if(c.awaySettings().on) {c.awaySetOn(false);c.showNotice("Away mode is off.")}
        else c.showNotice("Switch Away mode on here when you are ready.")
        pulse(c)
    }

    /** Android 13+: asks the owner (in Android's own window) to add the "Ask Alpha" tile. The Powers page can offer this. Returns false when Android cannot ask. */
    fun requestTile(context:Context):Boolean {
        if(Build.VERSION.SDK_INT<33) return false
        return runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(context,AlphaTile::class.java),"Ask Alpha",
                Icon.createWithResource(context,R.drawable.ic_glyph_ask),context.mainExecutor) {}
            true
        }.getOrDefault(false)
    }
    /** Asks Android to offer putting the widget on the home screen. The Powers page can offer this. Returns false when the launcher cannot. */
    fun requestWidget(context:Context):Boolean = runCatching {
        val manager=AppWidgetManager.getInstance(context)
        manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context,AlphaWidget::class.java),null,null)
    }.getOrDefault(false)
}

/** The home-screen widget: a status line and Ask, Talk and Away buttons. Resizable; one row tall it shows just the buttons. */
class AlphaWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        val state=Reach.current(context)
        ids.forEach {draw(context,manager,it,state)}
    }
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,newOptions:android.os.Bundle?) {
        draw(context,manager,id,Reach.current(context))
    }
    companion object {
        /** Under this height (in dp) the widget is one row of buttons. */
        const val COMPACT_BELOW_DP=88
        fun redrawAll(context:Context,state:Doors.WidgetState) {
            runCatching {
                val manager=AppWidgetManager.getInstance(context)
                val ids=manager.getAppWidgetIds(ComponentName(context,AlphaWidget::class.java))
                ids.forEach {draw(context,manager,it,state)}
            }
        }
        fun draw(context:Context,manager:AppWidgetManager,id:Int,state:Doors.WidgetState) {
            val height=manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0)
            manager.updateAppWidget(id,views(context,state,height in 1 until COMPACT_BELOW_DP))
        }
        /** The widget as drawn for [state]; [compact] is the one-row layout. Separate from [draw] so it can be checked on its own. */
        fun views(context:Context,state:Doors.WidgetState,compact:Boolean):RemoteViews {
            val views=RemoteViews(context.packageName,if(compact) R.layout.widget_alpha_compact else R.layout.widget_alpha)
            views.setTextViewText(R.id.widget_status,Doors.statusLine(state))
            views.setTextViewText(R.id.widget_away,Doors.awayLabel(state.awayOn))
            views.setInt(R.id.widget_away,"setBackgroundResource",if(state.awayOn) R.drawable.widget_btn_on_bg else R.drawable.widget_btn_bg)
            views.setTextColor(R.id.widget_away,if(state.awayOn) 0xFF9FE3CD.toInt() else 0xFFECEFF1.toInt())
            views.setOnClickPendingIntent(R.id.widget_ask,Reach.open(context,"ask",61))
            views.setOnClickPendingIntent(R.id.widget_talk,Reach.open(context,"talk",62))
            views.setOnClickPendingIntent(R.id.widget_status,Reach.open(context,"tasks",64))
            views.setOnClickPendingIntent(R.id.widget_away,PendingIntent.getBroadcast(context,63,Intent(context,WidgetReceiver::class.java).setAction(WidgetReceiver.ACTION_AWAY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return views
        }
    }
}

/**
 * The widget's Away button. It is not reachable from other apps (not exported), so the owner's own tap is the only way in: it switches Away mode on or off
 * without opening Alpha, and when Away mode cannot start yet it posts a notification that opens the Away screen.
 */
class WidgetReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=ACTION_AWAY) return
        val c=(context.applicationContext as? AlphaApplication)?.controller?:return
        val problem=c.awaySetOn(!c.awaySettings().on)
        if(problem.isNotBlank()) Updates.notify(context,"widget-away","Away mode needs one more step",problem,"","away")
        Reach.pulse(c)
    }
    companion object {const val ACTION_AWAY="com.alphainternational.assistant.WIDGET_AWAY"}
}

/** The Quick Settings tile "Ask Alpha": tap it and Alpha opens with the cursor in the text box. Its small line shows what Alpha is doing. */
class AlphaTile:TileService() {
    override fun onStartListening() {
        val tile=qsTile?:return
        tile.state=Tile.STATE_INACTIVE
        tile.label="Ask Alpha"
        if(Build.VERSION.SDK_INT>=29) tile.subtitle=Doors.statusLine(Reach.current(applicationContext))
        tile.updateTile()
    }
    override fun onClick() {
        if(isSecure && isLocked) unlockAndRun {open()} else open()
    }
    /** Android 14 stops accepting a plain Intent here, so a PendingIntent is used there; the older call is only reached on phones before Android 14, where it is the only one. */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(Reach.open(this,"ask",65))
        else @Suppress("DEPRECATION") startActivityAndCollapse(AppNavigation.intent(this,"ask"))
    }
}
