package com.alphainternational.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Keeps jobs alive while the owner uses other apps. Its ongoing notification says what the work is doing right now ("Step 3 of 6: Reading ebay.com"
 * when the job has a plan, otherwise its latest step) and has a Stop button for that job. It updates when the words change, never more than about
 * once every three seconds, so it never flickers or nags. A job that needs the owner is mentioned and can be opened from here.
 */
class TaskService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var ticker:Job?=null
    /** Keeps the processor and Wi-Fi running for as long as a job works, whatever the screen does (see [KeepAwake]). */
    private val awake by lazy {KeepAwake(this)}
    private var lastKey=""
    private var lastAt=0L
    /** A sign-in or Away mode asked for its own words on the notice; it keeps them while no ordinary job is running. */
    private var staticTitle:String?=null
    private var staticText:String?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val controller=(application as AlphaApplication).controller
        when(intent?.action) {
            "PAUSE"-> {
                controller.interruptBackgroundTask("Paused from the task notification. Your completed steps are saved.")
                stopSelf();return START_NOT_STICKY
            }
            // The Stop button: this one job ends. The service itself goes away when the job's clean-up releases it.
            "STOP"-> {
                intent?.getStringExtra("job").orEmpty().takeIf {it.isNotBlank()}?.let {controller.cancel(it)}
                if(!running) stopSelf()
                return START_NOT_STICKY
            }
        }
        val id=intent?.getStringExtra("task").orEmpty()
        if(id.isBlank()){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tasks","Active Alpha tasks",NotificationManager.IMPORTANCE_LOW))
        // A sign-in only needs Alpha kept awake (Android freezes background apps) while you approve in the browser: its notice stays as it is.
        // (Its words are shown whenever no ordinary job is running; once one is, that job's own progress takes the notice over.)
        intent?.getStringExtra("title")?.let {staticTitle=it;staticText=intent.getStringExtra("text")}
        val (notice,key)=progressNotice(controller)
        startForeground(1002,notice)
        awake.hold()
        lastKey=key;lastAt=System.currentTimeMillis()
        startTicker(controller)
        running=true;ready?.complete(Unit)
        return START_NOT_STICKY
    }

    private fun openIntent()=PendingIntent.getActivity(this,31,AppNavigation.intent(this,"tasks"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The ongoing notice for what is running now, and a key that changes only when its words do. */
    private fun progressNotice(controller:Controller):Pair<Notification,String> {
        val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val note=ProgressNote.build(controller.tasks,active.toSet()) {controller.jobProgress(it)}
        val builder=NotificationCompat.Builder(this,"tasks").setSmallIcon(R.drawable.ic_alpha).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openIntent())
        if(note==null && staticTitle!=null) {
            builder.setContentTitle(staticTitle).setContentText(staticText)
            return builder.build() to "static:"+staticTitle
        }
        if(note==null) {
            builder.setContentTitle("Alpha is working on your request").setContentText("It keeps working while you use other apps. Progress is saved.")
            return builder.build() to "plain"
        }
        builder.setContentTitle(note.title).setContentText(note.text).setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
        builder.addAction(0,"Stop",PendingIntent.getService(this,32,Intent(this,TaskService::class.java).setAction("STOP").putExtra("job",note.stopId),flags))
        if(note.waitingId.isNotBlank()) builder.addAction(0,if(note.waitingIsReview) "Review" else "Open",
            PendingIntent.getActivity(this,34,AppNavigation.intent(this,if(note.waitingIsReview) "review" else "chat",note.waitingBot),flags))
        return builder.build() to (note.title+"|"+note.text+"|"+note.stopId+"|"+note.waitingId)
    }

    /** Looks at the work every couple of seconds, but only posts when the words changed and at least three seconds have passed. */
    private fun startTicker(controller:Controller) {
        ticker?.cancel()
        ticker=scope.launch {
            while(true) {
                delay(2_500)
                awake.hold()
                val (notice,key)=progressNotice(controller)
                if(key!=lastKey && System.currentTimeMillis()-lastAt>=MIN_GAP_MS) {
                    lastKey=key;lastAt=System.currentTimeMillis()
                    runCatching {getSystemService(NotificationManager::class.java).notify(1002,notice)}
                }
            }
        }
    }

    override fun onTimeout(startId:Int,fgsType:Int) {
        (application as AlphaApplication).controller.interruptBackgroundTask("Android stopped background work at its time limit. Open Alpha and resume your saved task.")
        stopSelf()
    }
    override fun onDestroy(){running=false;active.clear();staticTitle=null;staticText=null;ticker?.cancel();awake.release();scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        /** The least time between two changes of the ongoing notice. */
        const val MIN_GAP_MS=3_000L
        /** The jobs this notice is protecting (main thread). It stays up while any of them runs and stops when the last one ends. */
        private val active=LinkedHashSet<String>()
        private var running=false
        private var ready:CompletableDeferred<Unit>?=null
        val busy get()=active.isNotEmpty()
        /** How many jobs the notice is protecting right now: the number the home-screen widget shows. */
        val activeCount get()=active.size
        /**
         * Protects [id] from Android freezing it while it works. The first job starts the notice; the rest just join it, so a bot
         * starting while another finishes never leaves a gap.
         */
        suspend fun keepAlive(context:Context,id:String,title:String?=null,text:String?=null)=withContext(Dispatchers.Main) {
            active+=id
            if(running && title==null) return@withContext
            val signal=CompletableDeferred<Unit>();ready=signal
            try {
                ContextCompat.startForegroundService(context,Intent(context,TaskService::class.java).putExtra("task",id).apply {if(title!=null) putExtra("title",title).putExtra("text",text)})
                withTimeout(5000){signal.await()}
            }finally {if(ready===signal)ready=null}
        }
        /** A job is about to start: count it right away, so the notice never drops in the gap between one job ending and the next beginning. */
        fun claim(id:String){active+=id}
        /** Joins a notice that is already up (Android won't let a background app start a new one). */
        fun adopt(id:String){if(running) active+=id}
        /** [id] is done; the notice goes away when the last job does. */
        fun release(context:Context,id:String){
            active-=id
            if(active.isEmpty()) context.stopService(Intent(context,TaskService::class.java))
        }
        fun finish(context:Context,id:String)=release(context,id)
    }
}
