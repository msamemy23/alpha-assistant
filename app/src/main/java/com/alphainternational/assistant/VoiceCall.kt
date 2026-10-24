package com.alphainternational.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.sqrt

class VoiceCall(private val context:Context,private val c:Controller) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val models=VoiceModels(context)
    var ready by mutableStateOf(models.ready);private set
    var downloadProgress by mutableStateOf(0f);private set
    var downloading by mutableStateOf(false);private set
    var connected by mutableStateOf(false);private set
    var phase by mutableStateOf("Ready");private set
    var transcript by mutableStateOf("");private set
    var muted by mutableStateOf(false);private set
    var speaker by mutableStateOf(true);private set
    var onDeviceRecognition by mutableStateOf(false);private set
    private var selectedStyle by mutableStateOf(c.config("voice").optString("style","Expressive").takeIf {it in VoiceStyle.styles}?:"Expressive")
    val style:String get()=selectedStyle
    fun setStyle(value:String) {
        require(value in VoiceStyle.styles)
        if(connected || speechJob?.isActive==true) return
        c.saveConfig("voice",obj("style" to value))
        selectedStyle=value
    }
    private var recognizer:SpeechRecognizer?=null
    private var engine:Chatterbox?=null
    private val voiceLock=Mutex()
    private var downloadJob:Job?=null
    private var speechJob:Job?=null
    private var observeJob:Job?=null
    private var retryJob:Job?=null
    private var attentionJob:Job?=null
    private var cleanupJob:Job?=null
    private var playback:AudioTrack?=null
    private val turns=CallTurns()
    private val awaitingTask get()=turns.taskId
    private val audio=context.getSystemService(AudioManager::class.java)
    private val audioAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener {
            if(it in setOf(AudioManager.AUDIOFOCUS_LOSS,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))scope.launch {end()}
        }.build()

    fun download() {
        if(downloading) return
        downloadJob=scope.launch {
            downloading=true;phase="Downloading Chatterbox Turbo"
            try {
                models.download {done,total->withContext(Dispatchers.Main){downloadProgress=done.toFloat()/total}}
                ready=models.ready;phase="Voice pack ready";c.showNotice("Chatterbox Turbo is ready. Try the voice test, then start a call.")
            }catch(_:CancellationException){phase="Download paused"}
            catch(e:Exception){phase="Download needs attention";c.showNotice(e.message.orEmpty())}
            finally{downloading=false}
        }
    }
    fun pauseDownload(){downloadJob?.cancel()}
    private suspend fun prepare()=withContext(Dispatchers.IO) {
        if(engine==null) {
            val native=Chatterbox(models.folder)
            try {native.prepareDefault(context.assets.open("chatterbox-default.bin").use {it.readBytes()});engine=native}
            catch(e:Throwable){native.close();throw e}
        }
    }
    /** The human-sounding cloud voice, when the owner added one in Settings → Voice. */
    private fun cloud()=CloudVoice.active(c.config("voice_cloud"))
    /** Speaks [text] in the cloud voice, a sentence or two at a time so the first words come quickly. */
    private suspend fun speakCloud(voice:org.json.JSONObject,text:String) {
        val sentences=text.split(Regex("(?<=[.!?])\\s+")).filter {it.isNotBlank()}
        val pieces=if(sentences.size<=1) listOf(text) else listOf(sentences.first(),sentences.drop(1).joinToString(" "))
        for(piece in pieces) {phase="Speaking";play(CloudVoice.speak(voice,piece))}
    }
    fun preview() {
        if(connected || speechJob?.isActive==true) return
        cloud()?.let {voice->
            speechJob=scope.launch {
                try {phase="Getting the voice";speakCloud(voice,VoiceStyle.preview(style));phase="Voice test complete"}
                catch(_:CancellationException){phase="Ready"}
                catch(e:Exception){phase="Voice test failed";c.showNotice(e.message.orEmpty())}
            }
            return
        }
        if(!ready){c.showNotice("Download the built-in voice pack first, or add a voice in Settings → Voice.");return}
        speechJob=scope.launch {
            try {
                cleanupJob?.join()
                phase="Loading the local voice"
                voiceLock.withLock {prepare();phase="Generating a voice sample"
                    val start=System.currentTimeMillis()
                    val samples=withContext(Dispatchers.IO){engine!!.generate(VoiceStyle.preview(style),style)}
                    android.util.Log.i("AlphaVoice","preview style=$style generation_ms=${System.currentTimeMillis()-start} audio_ms=${samples.size*1000L/24000}")
                    val file=File(models.folder,"voice-test.wav");withContext(Dispatchers.IO){Wav.write(file,samples)}
                    phase="Playing Chatterbox Turbo";play(samples);phase="Voice test complete"
                }
                c.showNotice("Chatterbox Turbo generated and played a local voice sample.")
            }catch(_:CancellationException){phase="Ready"}
            catch(e:Exception){phase="Voice test failed";c.showNotice(e.message.orEmpty())}
        }
    }
    fun start() {
        if(connected) return
        if(speechJob?.isActive==true){c.showNotice("Finish or stop the voice test before starting a call.");return}
        if(!ready && cloud()==null){c.showNotice("Download the built-in voice pack first, or add a voice in Settings → Voice.");return}
        if(!c.aiConnected() || c.selectedModel.isBlank()){
            c.showNotice("Connect your ChatGPT plan and select a model before starting a call.");return
        }
        if(!SpeechRecognizer.isRecognitionAvailable(context)){
            c.showNotice("Install or enable an Android speech recognition service to talk with Alpha. You can still test the local Chatterbox voice.");return
        }
        turns.connect();connected=true;c.voiceMode=true;muted=false;transcript="";phase="Loading the local voice"
        try {ContextCompat.startForegroundService(context,Intent(context,CallService::class.java))}
        catch(e:Exception){turns.end();connected=false;c.voiceMode=false;c.showNotice("Call service could not start: "+e.message);return}
        audio.mode=AudioManager.MODE_IN_COMMUNICATION;routeAudio()
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            c.showNotice("Audio is in use by another call. Try again when it finishes.");end();return
        }
        observeJob=scope.launch {
            c.replies.collect {message->if(turns.finishTask(message.taskId)){attentionJob?.cancel();reply(ChatMarkdown.spoken(message.text).ifBlank {message.text})}}
        }
        val ticket=turns.beginOutput()
        speechJob=scope.launch {
            // The built-in voice loads only when it will be used (no cloud voice, and its pack is downloaded).
            try {cleanupJob?.join();if(cloud()==null && ready) voiceLock.withLock {prepare()};turns.finishOutput(ticket);listen()}
            catch(_:CancellationException){}
            catch(e:Exception){c.showNotice("Call could not start: "+e.message);end()}
            finally {turns.finishOutput(ticket)}
        }
    }
    private fun createRecognizer(lease:CallTurns.Lease) {
        onDeviceRecognition=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer=if(onDeviceRecognition && Build.VERSION.SDK_INT>=31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer!!.setRecognitionListener(object:RecognitionListener {
            override fun onReadyForSpeech(params:Bundle?){if(turns.isCurrentInput(lease))phase="Listening"}
            override fun onBeginningOfSpeech(){if(turns.isCurrentInput(lease)){phase="Listening";transcript=""}}
            override fun onRmsChanged(value:Float){}
            override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){if(turns.isCurrentInput(lease))phase="Hearing you"}
            override fun onEvent(eventType:Int,params:Bundle?){}
            override fun onPartialResults(partial:Bundle?){
                if(turns.isCurrentInput(lease))partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {transcript=it}
            }
            override fun onResults(results:Bundle?) {
                if(!turns.finishInput(lease))return
                destroyRecognizer()
                val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if(text.isBlank()){retryListening();return}
                transcript=c.redact(text)
                if(text.lowercase().trimEnd('.','?','!') in setOf("end call","end the call","hang up","stop the call")){end();return}
                phase="Thinking"
                turns.awaitTask(c.submit(text).orEmpty())
                if(awaitingTask.isBlank()){phase="Tap to listen again";return}
                val taskId=awaitingTask;val session=turns.session
                attentionJob?.cancel()
                attentionJob=scope.launch {
                    while(connected && turns.session==session && awaitingTask==taskId){
                        delay(500)
                        val task=c.tasks.firstOrNull {it.id==taskId}?:break
                        // A very fast response can finish before submit() returns its task id.
                        if(task.status=="Completed") {
                            val message=c.messages.lastOrNull {it.taskId==taskId && it.role=="assistant"}
                            if(message!=null && turns.finishTask(taskId))reply(message.text)
                            break
                        }
                        if(task.status in setOf("Needs review","Needs you","Paused","Check result","Cancelled")){
                            if(task.status=="Cancelled")turns.clearTask()
                            reply(if(task.status=="Needs review") "I need your approval before I run that action. Please review the details on screen." else task.detail.take(220))
                            break
                        }
                    }
                }
            }
            override fun onError(error:Int) {
                if(!turns.finishInput(lease))return
                destroyRecognizer()
                if(error in setOf(SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT,SpeechRecognizer.ERROR_RECOGNIZER_BUSY)){retryListening()}
                else {phase="Microphone needs attention";c.showNotice("Android speech recognition error "+error+". Check microphone permission and the installed English speech pack. Tap Listen to retry.")}
            }
        })
    }
    private fun destroyRecognizer(){recognizer?.destroy();recognizer=null}
    private fun cancelInput(){turns.stopInput();retryJob?.cancel();recognizer?.cancel();destroyRecognizer()}
    private fun retryListening(){
        retryJob?.cancel();val session=turns.session
        retryJob=scope.launch {delay(700);if(turns.session==session && turns.canListen)listen()}
    }
    fun listen() {
        val lease=turns.beginInput()?:return
        retryJob?.cancel();destroyRecognizer()
        phase="Listening"
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
        try {createRecognizer(lease);recognizer!!.startListening(intent)}
        catch(e:Exception){turns.finishInput(lease);destroyRecognizer();phase="Microphone needs attention";c.showNotice(e.message.orEmpty())}
    }
    private fun reply(text:String) {
        if(!connected)return
        val ticket=turns.beginOutput();cancelInput()
        val previous=speechJob;previous?.cancel();stopPlayback()
        speechJob=scope.launch {
            try {
                previous?.join()
                // A human-sounding cloud voice when the owner added one; the built-in voice if not, or if the service fails.
                val voice=cloud()
                val spokeInCloud=voice!=null && runCatching {speakCloud(voice,c.redact(text))}.onFailure {
                    if(it is CancellationException) throw it
                    c.showNotice("The voice service didn't answer ("+it.message.orEmpty().take(120)+"), so the built-in voice is speaking.")
                }.isSuccess
                if(!spokeInCloud && ready) voiceLock.withLock {
                    prepare()
                    val spoken=c.redact(text).replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"),"$1").replace(Regex("[#*_]"),"")
                    val chunks=spoken.split(Regex("(?<=[.!?])\\s+")).flatMap {sentence->sentence.chunked(180)}.take(6)
                    for(chunk in chunks) {
                        phase="Generating speech"
                        val samples=withContext(Dispatchers.IO){engine!!.generate(chunk,style)}
                        phase="Speaking";play(samples)
                    }
                }
            }catch(_:CancellationException){}
            catch(e:Exception){phase="Voice needs attention";c.showNotice("Chatterbox speech failed: "+e.message)}
            finally {
                turns.finishOutput(ticket)
                if(connected && ticket.session==turns.session && !turns.outputActive) {
                    if(awaitingTask.isNotBlank())phase="Waiting for your action on screen"
                    else if(muted)phase="Microphone muted"
                    else listen()
                }
            }
        }
    }
    private suspend fun play(samples:FloatArray)=withContext(Dispatchers.IO) {
        require(samples.isNotEmpty() && samples.all {it.isFinite()}){"The local voice returned invalid audio."}
        val rms=sqrt(samples.take(24000*10).sumOf {it.toDouble()*it}/samples.size.coerceAtMost(24000*10))
        require(rms>0.00001){"The local voice returned silent audio."}
        val buffer=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(24000)
        val track=AudioTrack.Builder().setAudioAttributes(audioAttributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        playback=track
        try {
            track.play();var offset=0
            while(offset<samples.size){
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count=track.write(samples,offset,minOf(4096,samples.size-offset),AudioTrack.WRITE_BLOCKING)
                require(count>0){"The phone audio output stopped."};offset+=count
            }
            while(track.playbackHeadPosition<samples.size){kotlinx.coroutines.currentCoroutineContext().ensureActive();delay(25)}
        }finally{runCatching{track.stop()};track.release();if(playback===track)playback=null}
    }
    private fun stopPlayback(){runCatching{playback?.pause();playback?.flush()}}
    fun interrupt() {
        attentionJob?.cancel();cancelInput()
        val task=turns.clearTask()
        if(task.isNotBlank()) {c.stop(task);if(c.approval?.taskId==task)c.hideReview()}
        val job=speechJob
        val session=turns.session
        job?.cancel();stopPlayback()
        scope.launch {job?.join();if(turns.session==session){if(connected)listen()else phase="Ready"}}
    }
    fun toggleMute(){
        muted=!muted;turns.setMuted(muted)
        if(muted) {
            cancelInput()
            if(!turns.outputActive && awaitingTask.isBlank())phase="Microphone muted"
        }else listen()
    }
    fun toggleSpeaker(){speaker=!speaker;routeAudio()}
    private fun routeAudio(){
        if(Build.VERSION.SDK_INT>=31){
            val type=if(speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            audio.availableCommunicationDevices.firstOrNull {it.type==type}?.let {audio.setCommunicationDevice(it)}
        }else {audio.isSpeakerphoneOn=speaker}
    }
    fun end() {
        connected=false;c.voiceMode=false;turns.end()
        attentionJob?.cancel();cancelInput()
        observeJob?.cancel();val job=speechJob;job?.cancel();runCatching{playback?.pause();playback?.flush()}
        context.stopService(Intent(context,CallService::class.java))
        audio.abandonAudioFocusRequest(focus)
        if(Build.VERSION.SDK_INT>=31)audio.clearCommunicationDevice()
        audio.mode=AudioManager.MODE_NORMAL;phase="Call ended"
        val previousCleanup=cleanupJob
        cleanupJob=scope.launch {previousCleanup?.join();job?.join();voiceLock.withLock {withContext(Dispatchers.IO){engine?.close();engine=null}}}
    }
}
