package com.alphainternational.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File

class MainActivity:FragmentActivity() {
    lateinit var c:Controller
    /** Alpha's one browser: it belongs to the app, so a bot keeps browsing whether or not this screen is showing. */
    val browser:BrowserEngine get()=c.browserEngine()
    var navigationRequest by mutableStateOf<NavigationRequest?>(null);private set
    private var navigationId=0L
    private var requestedScopes=emptyList<String>()
    var onSpeech:((String)->Unit)?=null
    /** Set by Chat: a photo or document picked from + is attached to the next message instead of only being filed. */
    var onAttached:((String)->Unit)?=null
    private var pendingPhoto=""
    private val cameraResult=registerForActivityResult(ActivityResultContracts.TakePicture()) {saved->
        val name=pendingPhoto;pendingPhoto=""
        if(name.isBlank())return@registerForActivityResult
        if(saved && c.file(name).length()>0) {c.refreshFiles();onAttached?.invoke(name)?:c.showNotice("Photo saved in Computer → Files.")}
        else runCatching {c.file(name).delete()}
    }
    private val googleResult=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result->
        try {
            val authorization=Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
            c.connectors.rememberGoogle(authorization,requestedScopes); c.googleConnected();c.showNotice("Google service connected.")
        } catch(e:Exception) { c.googleFailed(e) }
    }
    private val speechResult=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onSpeech?.invoke(it) }
    }
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val microphonePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) c.voice.start() else c.showNotice("Allow microphone access to talk with Alpha.")
    }
    private var afterSmsPermission:((Boolean)->Unit)?=null
    private val smsPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        afterSmsPermission?.invoke(granted);afterSmsPermission=null
    }
    /** Asks Android once for permission to send texts the owner approves; runs [done] with the answer. */
    fun requestSms(done:(Boolean)->Unit) {
        if(PhoneSms.allowed(this)) {done(true);return}
        afterSmsPermission=done;smsPermission.launch(Manifest.permission.SEND_SMS)
    }
    private val photoResult=registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri->if(uri!=null) importImage(uri)}
    /** A photo or GIF (from the picker or the keyboard's GIF button) saved into the current bot's files and attached to the next text. */
    fun importImage(uri:Uri,mimeHint:String?=null) {
        lifecycleScope.launch {
            try {
                val mime=contentResolver.getType(uri)?:mimeHint
                val type=mime.orEmpty().lowercase()
                // Keyboards label stickers things like image/webp.wasticker, so match the start of the type.
                val extension=when {type.startsWith("image/jpeg") || type.startsWith("image/jpg")->"jpg";type.startsWith("image/png")->"png"
                    type.startsWith("image/webp")->"webp";type.startsWith("image/gif")->"gif";else->error("Choose a photo or GIF.")}
                val target=c.file((if(extension=="gif") "gif-" else "photo-")+System.currentTimeMillis()+"."+extension)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input->
                        target.outputStream().use { output->
                            val bytes=ByteArray(8192);var total=0
                            while(true) {val count=input.read(bytes);if(count<0) break;total+=count;require(total<=10_000_000) {"Choose a photo smaller than 10 MB."};output.write(bytes,0,count)}
                        }
                    }?:error("The selected image could not be opened.")
                }
                c.refreshFiles()
                onAttached?.invoke(target.name)?:c.showNotice("Photo imported into "+c.workspace+". Open Computer → Files to share it.")
            } catch(e:Exception){c.showNotice(plainError(e))}
        }
    }
    private val documentResult=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) lifecycleScope.launch {
            try {
                val mime=contentResolver.getType(uri).orEmpty()
                val stamp=System.currentTimeMillis()
                val name=when {
                    mime=="application/pdf"->copyBinary(uri,"doc-$stamp.pdf",12_000_000)
                    Office.extensionForMime(mime)!=null->copyBinary(uri,"doc-$stamp."+Office.extensionForMime(mime),12_000_000)
                    mime.startsWith("image/")->copyBinary(uri,"photo-$stamp."+when(mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"},10_000_000)
                    else-> {
                        val text=withContext(Dispatchers.IO) {contentResolver.openInputStream(uri)?.bufferedReader()?.use {reader->
                            val buffer=CharArray(4096);val result=StringBuilder()
                            while(true){val n=reader.read(buffer);if(n<0)break;require(result.length+n<=100_000){"Choose a document under 100,000 characters."};result.append(buffer,0,n)}
                            result.toString()
                        }?:error("The selected document could not be read.")}
                        val file="import-$stamp."+(if(mime=="text/csv") "csv" else "txt")
                        c.saveLocalFile(file,text);file
                    }
                }
                c.refreshFiles()
                onAttached?.invoke(name)?:c.showNotice("Document added to "+c.workspace+"'s files. Ask about it any time.")
            }catch(e:Exception){c.showNotice(plainError(e))}
        }
    }
    private suspend fun copyBinary(uri:Uri,name:String,limit:Int):String {
        val target=c.file(name)
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use {input->target.outputStream().use {output->
                val bytes=ByteArray(8192);var total=0
                while(true) {val n=input.read(bytes);if(n<0)break;total+=n;require(total<=limit) {"Choose a file under ${limit/1_000_000} MB."};output.write(bytes,0,n)}
            }}?:error("The selected file could not be opened.")
        }
        return name
    }
    /** "Ask" from a shortcut, the tile or the widget: bumped to put the cursor in the text box (see Modifier.focusOnAsk). The box clears it once it has focus. */
    var composerFocus by mutableStateOf(0L);private set
    fun focusComposer() {composerFocus=System.nanoTime()}
    fun composerFocused() {composerFocus=0L}

    private var afterDataPermission:((Boolean)->Unit)?=null
    private val dataPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        afterDataPermission?.invoke(granted);afterDataPermission=null
    }
    private val permissionGate=kotlinx.coroutines.sync.Mutex()
    /**
     * Asks Android for a permission a bot's tool needs (the calendar, the contacts), the first time it is needed, and waits for the owner's answer.
     * True at once when it is already allowed; false when the owner says no or does not answer. One question is on screen at a time.
     */
    suspend fun askPermission(permission:String):Boolean {
        if(ContextCompat.checkSelfPermission(this,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        return permissionGate.withLock {
            if(ContextCompat.checkSelfPermission(this,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED) return@withLock true
            val answer=kotlinx.coroutines.CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {afterDataPermission={answer.complete(it)};dataPermission.launch(permission)}
            kotlinx.coroutines.withTimeoutOrNull(120_000) {answer.await()}?:false
        }
    }
    /** Opens a Word, Excel or PowerPoint file in whatever app on this phone can show it (Docs, Sheets, Slides, Office, Samsung's viewers). */
    fun openOffice(file:File) {
        try {
            val uri=FileProvider.getUriForFile(this,packageName+".files",file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,Office.mime(file.name)?:"*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(_:android.content.ActivityNotFoundException) {c.showNotice("No app on this phone opens this kind of file. Install Google Docs, Sheets or Slides, or use Share to send it to one.")}
        catch(e:Exception) {c.showNotice("Could not open the file: "+e.message)}
    }
    fun takePhoto() {
        try {
            val name="photo-"+System.currentTimeMillis()+".jpg";val file=c.file(name)
            pendingPhoto=name
            cameraResult.launch(FileProvider.getUriForFile(this,packageName+".files",file))
        } catch(e:Exception) {pendingPhoto="";c.showNotice("The camera could not be opened. "+plainError(e))}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        c=(application as AlphaApplication).controller
        readNavigation(intent)
        // The screen sleeps normally; the voice call keeps it on only while a call is live.
        setContent { AlphaTheme { AlphaApp(this,c) } }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent);readNavigation(intent)
    }
    /** Something another app shared to Alpha ("Share" in the gallery, a browser, a mail): lands in the chat box as a draft for the owner to send or change. */
    class Shared(val id:Long,val text:String,val files:List<String>)
    var shared by mutableStateOf<Shared?>(null);private set
    fun consumeShared(item:Shared) {if(shared==item) shared=null}
    private fun readShare(intent:Intent) {
        val text=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim().take(4_000)
        val stream:Uri?=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val type=intent.type.orEmpty().lowercase()
        intent.action=null;intent.removeExtra(Intent.EXTRA_TEXT);intent.removeExtra(Intent.EXTRA_STREAM)
        navigationRequest=NavigationRequest(++navigationId,"chat","")
        lifecycleScope.launch {
            val name=if(stream==null) null else try {
                val mime=(contentResolver.getType(stream)?:type).lowercase();val stamp=System.currentTimeMillis()
                val saved=when {
                    mime.startsWith("image/gif")->copyBinary(stream,"gif-$stamp.gif",10_000_000)
                    mime.startsWith("image/png")->copyBinary(stream,"photo-$stamp.png",10_000_000)
                    mime.startsWith("image/webp")->copyBinary(stream,"photo-$stamp.webp",10_000_000)
                    mime.startsWith("image/")->copyBinary(stream,"photo-$stamp.jpg",10_000_000)
                    mime=="application/pdf"->copyBinary(stream,"doc-$stamp.pdf",12_000_000)
                    Office.extensionForMime(mime)!=null->copyBinary(stream,"doc-$stamp."+Office.extensionForMime(mime),12_000_000)
                    else->null
                }
                if(saved!=null) c.refreshFiles()
                saved
            } catch(e:Exception) {c.showNotice(plainError(e));null}
            if(text.isNotBlank() || name!=null) shared=Shared(System.nanoTime(),text,listOfNotNull(name))
        }
    }
    private fun readNavigation(intent:Intent) {
        if(intent.action==Intent.ACTION_SEND) {readShare(intent);return}
        val returnedFromSignIn=intent.data?.let {it.scheme=="alphaassistant" && it.host=="signin" && it.path=="/complete"}==true
        // What a launch means (notifications, shortcuts, the tile, the widget) is decided in FrontDoor, where it is tested.
        val destination=Doors.resolve(intent.getStringExtra("destination"),intent.getBooleanExtra("open_call",false),intent.getBooleanExtra("open_tasks",false),returnedFromSignIn)
        if(destination!=null) {
            navigationRequest=NavigationRequest(++navigationId,destination,intent.getStringExtra("workspace").orEmpty())
        }
        intent.removeExtra("destination");intent.removeExtra("workspace")
        intent.removeExtra("open_call");intent.removeExtra("open_tasks")
        if(returnedFromSignIn) intent.data=null
    }
    fun consumeNavigation(request:NavigationRequest) {if(navigationRequest==request)navigationRequest=null}
    override fun onStart() {
        super.onStart(); c.uiStarted(this,browser)
        // The long-press shortcuts follow the bots used most; the widget and tile are brought up to date.
        runCatching {Reach.refreshShortcuts(c);Reach.pulse(c)}
        // Bots text from the phone without approval cards, so Android's one-time "allow texts" question comes up once, here, instead of mid-task.
        if(c.aiSettings.texting!="google_web" && !PhoneSms.allowed(this) && !c.config("sms").optBoolean("asked")) {
            c.saveConfig("sms",obj("asked" to true));requestSms {}
        }
        // Once: Android's own "let Alpha keep working in the background" question, so bots aren't slowed down or stopped while the owner uses other apps.
        if(!backgroundAllowed() && !c.config("background").optBoolean("asked")) {
            c.saveConfig("background",obj("asked" to true));allowBackground()
        }
    }
    override fun onStop() { c.uiStopped(this); super.onStop() }
    override fun onDestroy() {
        (browser.view.parent as? ViewGroup)?.removeView(browser.view);super.onDestroy()
    }
    fun connectGoogle(group:String) {
        c.googleConnecting(group)
        val scopes=(c.config("google").optJSONArray("scopes")?.strings().orEmpty()+GoogleAccess.groups.getValue(group)).distinct(); requestedScopes=scopes
        lifecycleScope.launch {
            try {
                val result=GoogleAccess.authorize(this@MainActivity,scopes,c.config("google").optString("account"))
                if(result.hasResolution()) {
                    googleResult.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else {
                    c.connectors.rememberGoogle(result,scopes);c.googleConnected(); c.showNotice("Granted Google services updated.")
                }
            } catch(e:Exception) { c.googleFailed(e) }
        }
    }
    fun authenticate(action:()->Unit) {
        val authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(this).canAuthenticate(authenticators)!=BiometricManager.BIOMETRIC_SUCCESS) {
            c.showNotice("Set a phone screen lock or fingerprint before using the login vault."); return
        }
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                try { action() } catch(e:Exception) { c.showNotice("That didn't work. "+plainError(e)) }
            }
            override fun onAuthenticationError(code:Int,message:CharSequence) { c.showNotice(message.toString()) }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Alpha vault")
            .setSubtitle("Use a saved login on this phone").setAllowedAuthenticators(authenticators).build())
    }
    fun secureScreen(secure:Boolean) {
        if(secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun speak() {
        try { speechResult.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT,"Tell Alpha what you need")) }
        catch(_:Exception) { c.showNotice("No speech recognition app is available. You can type your request.") }
    }
    fun openExternal(url:String) {
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(Rules.httpsUrl(url)))) } catch(e:Exception) { c.showNotice(plainError(e)) }
    }
    /**
     * A link inside a reply. Web pages open in the browser; a phone number opens the dialer, a text link opens the messages app with the number filled in, and an
     * email link opens a new email. Nothing is sent from here: the owner still presses call or send.
     */
    fun openLink(url:String) {
        val target=url.trim()
        val scheme=target.substringBefore(':',"").lowercase()
        val rest=target.substringAfter(':',"").trim()
        val ok=when(scheme) {
            "tel","sms"->rest.substringBefore('?').matches(Regex("\\+?[0-9()\\-.\\s]{3,24}"))
            "mailto"->rest.substringBefore('?').matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            else->false
        }
        if(!ok) {openExternal(target);return}
        try {
            startActivity(Intent(if(scheme=="tel") Intent.ACTION_DIAL else Intent.ACTION_SENDTO,Uri.parse(scheme+":"+rest.replace(" ",""))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(_:Exception) {c.showNotice("This phone has no app for that.")}
    }
    fun copyText(text:String) {
        try {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Alpha",text))
            c.showNotice("Copied.")
        } catch(_:Exception) {}
    }
    /** Shares a reply as plain text, with the headline marks and fences taken out so it reads well in a message or an email. */
    fun shareText(text:String) {
        val plain=ChatMarkdown.readable(text)
        try {startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,plain),"Share this answer"))}
        catch(e:Exception) {c.showNotice("Couldn't share that. "+plainError(e))}
    }
    fun share(file:File) {
        try {
            if(file.extension.lowercase() in setOf("txt","md","csv")) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,file.readText())
                    .putExtra(Intent.EXTRA_SUBJECT,file.nameWithoutExtension),"Share with an app"))
            } else {
                val uri=FileProvider.getUriForFile(this,packageName+".files",file)
                val mime=Office.mime(file.name)?:SitePage.mime(file.name).takeIf {it!="application/octet-stream"}?:"application/json"
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share file"))
            }
        } catch(e:Exception) { c.showNotice("Couldn't share that. "+plainError(e)) }
    }
    fun enableNotifications() { if(Build.VERSION.SDK_INT>=33) {notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)} }
    /** Whether Android lets Alpha keep working freely while the owner uses other apps (no battery limits). */
    fun backgroundAllowed():Boolean=getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    /** Android's own "let this app run in the background" question; the owner taps Allow. */
    fun allowBackground() {
        if(backgroundAllowed()) {c.showNotice("Already allowed. Bots keep working while you use other apps.");return}
        try {startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+packageName)))}
        catch(_:Exception) {c.showNotice("Open your phone's Settings > Apps > Alpha Assistant > Battery and choose Unrestricted.")}
    }
    fun openNotificationSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName)) }
        catch(_:Exception) { c.showNotice("Open your phone's Settings > Apps > Alpha Assistant > Notifications.") }
    }
    fun startCall(){
        if(!c.aiConnected()){
            c.showNotice("Connect ChatGPT before starting a call. The local Chatterbox voice test works without sign-in.");return
        }
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun pickPhoto(){photoResult.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
    fun pickDocument(){documentResult.launch(arrayOf("application/pdf","image/*","text/plain","text/csv","application/json","text/markdown",Office.DOCX_MIME,Office.XLSX_MIME,Office.PPTX_MIME))}
}
