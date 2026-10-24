package com.alphainternational.assistant

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings > Backup, as a small state machine the Settings screen reads: "Save a backup" (passphrase, then Android's own file picker), "Restore from
 * a backup" (pick the file, passphrase, a look at what is inside, add or replace) and "Undo the last restore". The passphrase is typed by the owner,
 * used once, wiped, and never stored or logged. The file format and the rules are in Backup.kt.
 */
class BackupFlow internal constructor(val c:Controller,private val scope:CoroutineScope,private val context:Context) {
    /** "", "save", "pass", "preview", "busy". */
    var stage by mutableStateOf("")
    var busyText by mutableStateOf("")
    var error by mutableStateOf("")
    var parsed by mutableStateOf<Backup.Parsed?>(null)
    var includeHistory by mutableStateOf(true)
    var includeFiles by mutableStateOf(false)
    private var undoing=false
    private var picked:ByteArray?=null
    private var passphrase=CharArray(0)
    internal lateinit var createFile:ManagedActivityResultLauncher<String,Uri?>
    internal lateinit var openFile:ManagedActivityResultLauncher<Array<String>,Uri?>

    private val day=DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
    fun lastBackupText():String {
        val last=c.config("backup").optLong("last")
        return if(last>0) "Last backup: "+day.format(Instant.ofEpochMilli(last)) else "You haven't saved a backup yet. If this phone is lost or reset, everything Alpha knows goes with it."
    }
    fun undoText():String? = c.restoreCopyDate().takeIf {it>0}?.let {"Brings back what you had before the last restore ("+day.format(Instant.ofEpochMilli(it))+")"}

    private fun wipe() {passphrase.fill('0');passphrase=CharArray(0)}
    fun close() {stage="";error="";parsed=null;picked=null;undoing=false;busyText="";wipe()}

    fun startSave() {error="";stage="save"}
    fun startRestore() {error="";undoing=false;openFile.launch(arrayOf("*/*"))}
    fun startUndo() {
        undoing=true;error=""
        picked=runCatching {c.restoreCopyBytes()}.getOrNull()
        if(picked==null) {undoing=false;c.showNotice("There's nothing to undo.");return}
        stage="pass"
    }

    /** Passphrase chosen: Android's file picker asks where to put the file. */
    fun chosenSavePassphrase(text:String) {
        wipe();passphrase=text.toCharArray();stage="";error=""
        createFile.launch("alpha-backup-"+java.time.LocalDate.now()+Backup.EXTENSION)
    }
    internal fun fileCreated(uri:Uri?) {
        if(uri==null) {close();return}
        val options=Backup.Options(history=includeHistory,files=includeFiles)
        stage="busy";busyText="Saving your backup…"
        scope.launch {
            try {
                val bytes=c.makeBackup(passphrase,options)
                withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(bytes)}?:error("Android couldn't open that place to save.")}
                c.saveConfig("backup",obj("last" to System.currentTimeMillis()))
                c.showNotice("Backup saved. Keep your passphrase safe: nobody can open the file without it.")
                close()
            } catch(e:Exception) {
                stage="";busyText="";wipe()
                c.showNotice("The backup couldn't be saved: "+c.redact(e.message.orEmpty()).ifBlank {"please try again"}+".")
            }
        }
    }
    internal fun fileOpened(uri:Uri?) {
        if(uri==null) return
        stage="busy";busyText="Opening the file…"
        scope.launch {
            val bytes=try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {it.readBytes()}?.takeIf {it.size<=200*1024*1024}?:error("That file couldn't be read.")
                }
            } catch(e:Exception) {stage="";busyText="";c.showNotice("That file couldn't be opened.");return@launch}
            picked=bytes;stage="pass";busyText=""
        }
    }
    /** The passphrase for the picked file: if it opens, show what is inside. */
    fun tryPassphrase(text:String) {
        val data=picked?:return
        wipe();passphrase=text.toCharArray();error=""
        val keep=stage;stage="busy";busyText="Opening the backup…"
        scope.launch {
            try {
                val opened=c.openBackup(data,passphrase)
                parsed=opened;stage="preview";busyText=""
            } catch(e:Backup.BackupProblem) {
                error=e.message.orEmpty();stage=keep;busyText="";wipe()
            } catch(e:Exception) {
                error="That backup couldn't be opened.";stage=keep;busyText="";wipe()
            }
        }
    }
    fun restore(replace:Boolean) {
        val backup=parsed?:return
        stage="busy";busyText=if(undoing) "Putting your old setup back…" else "Restoring…"
        scope.launch {
            try {
                val message=c.restoreBackup(backup,passphrase,replace)
                c.showNotice(message)
                close()
            } catch(e:Exception) {
                stage="preview";busyText=""
                error=c.redact(e.message.orEmpty()).ifBlank {"The restore didn't work. Nothing was changed."}
            }
        }
    }
    val isUndo get()=undoing
}

@Composable fun rememberBackupFlow(c:Controller):BackupFlow {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val flow=remember(c) {BackupFlow(c,scope,context)}
    flow.createFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {flow.fileCreated(it)}
    flow.openFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {flow.fileOpened(it)}
    // The owner may leave Settings in the middle: the typed passphrase goes with it.
    DisposableEffect(flow) {onDispose {flow.close()}}
    return flow
}

/** The dialogs of the backup flow; put once on the Settings screen. */
@Composable fun BackupDialogs(flow:BackupFlow) {
    val box=Color(0xFF161616)
    when(flow.stage) {
        "save"-> {
            var first by remember {mutableStateOf("")}
            var again by remember {mutableStateOf("")}
            var problem by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={flow.close()},containerColor=box,title={Text("Save a backup")},
                text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("One file with your bots, memories, skills, routines and settings. It's locked with a passphrase that only you know. If you forget it, nobody can open the backup, not even Alpha. Passwords and sign-ins are never put in it.",color=Muted,fontSize=13.sp)
                    OutlinedTextField(first,{first=it;problem=""},singleLine=true,label={Text("Choose a passphrase")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(again,{again=it;problem=""},singleLine=true,label={Text("Type it again")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    if(problem.isNotBlank()) Text(problem,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                    BackupSwitch("Include chats and past jobs","The newest chats, and a short note on each past job.",flow.includeHistory) {flow.includeHistory=it}
                    BackupSwitch("Include text files my bots made","Notes, pages and spreadsheets. Not photos or videos.",flow.includeFiles) {flow.includeFiles=it}
                }},
                confirmButton={TextButton(onClick={
                    when {
                        first.length<Backup.MIN_PASSPHRASE->problem="Use at least ${Backup.MIN_PASSPHRASE} letters, numbers or symbols."
                        first!=again->problem="The two don't match."
                        else->flow.chosenSavePassphrase(first)
                    }
                }) {Text("Choose where to save",color=Ink)}},
                dismissButton={TextButton(onClick={flow.close()}) {Text("Cancel",color=Muted)}})
        }
        "pass"-> {
            var typed by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={flow.close()},containerColor=box,title={Text(if(flow.isUndo) "Undo the last restore" else "Open the backup")},
                text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(if(flow.isUndo) "Type the passphrase you used for that restore." else "Type the passphrase you chose when you saved it.",color=Muted,fontSize=13.sp)
                    OutlinedTextField(typed,{typed=it},singleLine=true,label={Text("Passphrase")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    if(flow.error.isNotBlank()) Text(flow.error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                }},
                confirmButton={TextButton(enabled=typed.isNotEmpty(),onClick={flow.tryPassphrase(typed)}) {Text("Open",color=Ink)}},
                dismissButton={TextButton(onClick={flow.close()}) {Text("Cancel",color=Muted)}})
        }
        "preview"-> {
            val backup=flow.parsed
            var sure by remember {mutableStateOf(false)}
            AlertDialog(onDismissRequest={flow.close()},containerColor=box,title={Text(if(flow.isUndo) "Bring back your old setup?" else "What's in this backup")},
                text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    backup?.let {Backup.summary(it.content)}.orEmpty().forEach {Text(it,color=Ink,fontSize=14.sp)}
                    Text("Add to what I have: only what's missing is added.\nReplace what I have: your bots, memories, skills, routines, tools and settings are made to match this backup. A copy of what you have now is kept on this phone first, and \"Undo the last restore\" brings it back. Chats are never removed.",color=Muted,fontSize=12.sp)
                    if(flow.error.isNotBlank()) Text(flow.error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                    if(sure) Text("Replace everything with this backup?",color=MaterialTheme.colorScheme.error,fontSize=13.sp)
                }},
                confirmButton={Row {
                    TextButton(onClick={flow.restore(false)}) {Text("Add",color=Ink)}
                    TextButton(onClick={if(sure) flow.restore(true) else sure=true}) {Text(if(sure) "Yes, replace" else "Replace",color=MaterialTheme.colorScheme.error)}
                }},
                dismissButton={TextButton(onClick={flow.close()}) {Text("Cancel",color=Muted)}})
        }
        "busy"-> AlertDialog(onDismissRequest={},containerColor=box,title={Text(flow.busyText.ifBlank {"One moment…"})},
            text={Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);Spacer(Modifier.width(14.dp));Text("This can take a few seconds.",color=Muted,fontSize=13.sp)}},
            confirmButton={})
    }
}

@Composable private fun BackupSwitch(title:String,note:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {Text(title,color=Ink,fontSize=14.sp);Text(note,color=Muted,fontSize=12.sp)}
        Switch(checked=checked,onCheckedChange=change,colors=SwitchDefaults.colors(checkedTrackColor=Color.White,checkedThumbColor=Color.Black))
    }
}
