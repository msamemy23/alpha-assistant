package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun CallDialog(activity:MainActivity,c:Controller,dismiss:()->Unit,connect:()->Unit) {
    val voice=c.voice
    // Only during a call does the screen stay awake.
    DisposableEffect(Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    Dialog(onDismissRequest={voice.end();dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=Background,modifier=Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Call with "+c.workspace,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=18.sp)
                    IconButton(onClick={voice.end();dismiss()}) {Icon(Icons.Outlined.Close,"Close call")}
                }
                Spacer(Modifier.height(56.dp))
                Box(Modifier.size(156.dp).border(1.dp,Mint.copy(alpha=0.2f),CircleShape).padding(15.dp)
                    .background(Mint.copy(alpha=0.07f),CircleShape),contentAlignment=Alignment.Center) {
                    Box(Modifier.size(86.dp).background(Mint,RoundedCornerShape(28.dp)),contentAlignment=Alignment.Center) {
                        Text("A",fontSize=48.sp,fontWeight=FontWeight.Bold,color=Background)
                    }
                }
                Text(if(voice.connected) "I'm here." else "Let's talk.",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=28.dp))
                Text(voice.phase,color=Mint,fontSize=14.sp,modifier=Modifier.padding(top=12.dp))
                if(c.notice.isNotBlank()) Text(c.notice,color=MaterialTheme.colorScheme.error,fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=12.dp))
                if(voice.transcript.isNotBlank()) Text(voice.transcript,color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(top=22.dp))
                Spacer(Modifier.height(30.dp))
                val cloud=CloudVoice.active(c.config("voice_cloud"))
                if(!voice.ready && cloud==null) {
                    Text("Chatterbox Turbo runs on your phone. Download its 725 MB voice pack once over Wi-Fi.",color=Muted,fontSize=14.sp,textAlign=TextAlign.Center)
                    if(voice.downloading) {
                        LinearProgressIndicator(progress={voice.downloadProgress},modifier=Modifier.fillMaxWidth().padding(top=20.dp))
                        Text((voice.downloadProgress*100).toInt().toString()+"%",color=Muted,modifier=Modifier.padding(top=8.dp))
                        TextButton(onClick={voice.pauseDownload()}) {Text("Pause download")}
                    } else Button(onClick={voice.download()},modifier=Modifier.fillMaxWidth().padding(top=20.dp)) {Text("Download Chatterbox Turbo")}
                } else if(!voice.connected) {
                    Text(if(cloud!=null) "Talk naturally. "+c.workspace+" answers in the "+CloudVoice.voiceName(cloud).substringBefore(" ·")+" voice." else "Talk naturally. Replies use the built-in voice.",color=Muted,fontSize=14.sp,textAlign=TextAlign.Center)
                    Text("Voice style",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=16.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        VoiceStyle.styles.forEach {style->FilterChip(selected=voice.style==style,onClick={voice.setStyle(style)},label={Text(style)})}
                    }
                    Button(onClick={activity.startCall()},modifier=Modifier.fillMaxWidth().padding(top=22.dp)) {Icon(Icons.Outlined.Call,null);Spacer(Modifier.width(10.dp));Text("Start call")}
                    Row {
                        TextButton(onClick={voice.preview()}) {Text("Test the voice")}
                        TextButton(onClick={voice.interrupt()}) {Text("Stop voice test",color=Muted)}
                    }
                    if(!c.aiConnected()) TextButton(onClick=connect) {Text("Connect ChatGPT")}
                } else {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            FilledTonalIconButton(onClick={voice.toggleMute()},modifier=Modifier.size(56.dp)) {Icon(if(voice.muted)Icons.Outlined.MicOff else Icons.Outlined.Mic,"Mute microphone")}
                            Text(if(voice.muted)"Unmute" else "Mute",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            FilledTonalIconButton(onClick={voice.toggleSpeaker()},modifier=Modifier.size(56.dp)) {Icon(Icons.Outlined.VolumeUp,"Speaker")}
                            Text(if(voice.speaker)"Speaker" else "Earpiece",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            FilledIconButton(onClick={voice.end()},modifier=Modifier.size(56.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Danger)) {Icon(Icons.Outlined.CallEnd,"End call",tint=Color.White)}
                            Text("End",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))
                        }
                    }
                    TextButton(onClick={voice.interrupt()},modifier=Modifier.padding(top=18.dp)) {Text("Interrupt & listen")}
                    Text(if(voice.onDeviceRecognition)"Android on-device recognition" else "Your Android speech service · prefers offline recognition",color=Muted,fontSize=11.sp,textAlign=TextAlign.Center)
                    c.tasks.firstOrNull {it.status=="Needs review"}?.let {task->OutlinedButton(onClick={c.reopenReview(task.id)}) {Text("Review pending action")}}
                }
                Spacer(Modifier.height(32.dp))
                Text(if(CloudVoice.active(c.config("voice_cloud"))!=null) "Thinking uses your ChatGPT plan; the voice uses your voice account." else "The built-in voice runs on your phone. Thinking uses your ChatGPT plan.",color=Muted,fontSize=11.sp,textAlign=TextAlign.Center)
            }
        }
    }
}
