package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * My business: where the business stands in plain words. The name and idea, how many steps are done, the next step with one button to do it together with the
 * Founder bot, every step (what it is, why, and what only the person can do), and the choices made so far. It reads [Controller.business], which every bot keeps up to date.
 */
@Composable fun BusinessScreen(c:Controller,back:()->Unit,ask:(String)->Unit) {
    val b=c.business
    var confirmForget by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize()) {
        PageBar(c,"My business",false,back)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(b.name.ifBlank {"Not named yet"},fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                    if(b.idea.isNotBlank()) Text(b.idea,color=Ink,fontSize=14.sp)
                    val facts=listOf("For" to b.customers,"Offer" to b.offer,"Price" to b.price,"Where" to b.place,"Goal" to b.goal).filter {it.second.isNotBlank()}
                    facts.forEach {(k,v)->Text("$k: $v",color=Muted,fontSize=13.sp)}
                    Text(stageWords(b.stage),color=Muted,fontSize=12.sp)
                    val (done,all)=b.progress()
                    if(all>0) {
                        LinearProgressIndicator(progress={done.toFloat()/all},modifier=Modifier.fillMaxWidth().padding(top=8.dp),color=Strong,trackColor=Line)
                        Text("$done of $all steps done",color=Ink,fontSize=13.sp)
                    }
                }
            }
            b.next()?.let {step->
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("NEXT",fontSize=11.sp,letterSpacing=1.4.sp,color=Muted)
                        Text(step.title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                        Text(step.why,color=Muted,fontSize=13.sp)
                        if(step.needsYou.isNotBlank()) Text("You: "+step.needsYou,color=Ink,fontSize=13.sp)
                        Button(onClick={ask("Let's do the next step of my business: "+step.title+". Start now and tell me what you need from me.")},
                            colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong),modifier=Modifier.padding(top=6.dp)) {Text("Do this step with me")}
                    }
                }
            }
            if(b.steps.isNotEmpty()) item {Text("ALL STEPS",fontSize=11.sp,letterSpacing=1.4.sp,color=Muted,modifier=Modifier.padding(start=4.dp,top=4.dp))}
            items(b.steps,key={it.id}) {step->
                Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.Top) {
                    Icon(when(step.status) {Journey.DONE->Icons.Outlined.CheckCircle;Journey.DOING->Icons.Outlined.PlayCircle;Journey.SKIPPED->Icons.Outlined.RemoveCircleOutline;else->Icons.Outlined.RadioButtonUnchecked},
                        step.status,tint=if(step.status==Journey.DONE) Strong else Muted,modifier=Modifier.size(22.dp).padding(top=2.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.title,color=Ink,fontSize=15.sp)
                        if(step.note.isNotBlank()) Text(step.note,color=Ink,fontSize=13.sp)
                        if(step.open) {
                            Text(step.why,color=Muted,fontSize=12.sp)
                            if(step.needsYou.isNotBlank()) Text("You: "+step.needsYou,color=Muted,fontSize=12.sp)
                        }
                    }
                }
            }
            if(b.decisions.isNotEmpty()) {
                item {Text("YOUR CHOICES",fontSize=11.sp,letterSpacing=1.4.sp,color=Muted,modifier=Modifier.padding(start=4.dp,top=8.dp))}
                items(b.decisions.asReversed().take(20)) {d->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                        Text(d.question,color=Muted,fontSize=12.sp)
                        Text(d.choice,color=Ink,fontSize=14.sp)
                        if(d.why.isNotBlank()) Text(d.why,color=Muted,fontSize=12.sp)
                    }
                }
            }
            item {
                TextButton(onClick={confirmForget=true},modifier=Modifier.padding(top=8.dp)) {Text("Forget this business",color=MaterialTheme.colorScheme.error)}
                Text("Nothing here is a promise of income. Most new businesses earn little at first. Alpha prepares and explains; it is not legal, tax or financial advice.",color=Muted,fontSize=11.sp,modifier=Modifier.padding(horizontal=4.dp))
            }
        }
    }
    if(confirmForget) AlertDialog(onDismissRequest={confirmForget=false},title={Text("Forget this business?")},
        text={Text("The name, idea, steps and choices are removed from this phone. Files your bots made stay.")},
        confirmButton={TextButton(onClick={c.forgetBusiness();confirmForget=false}) {Text("Forget",color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={confirmForget=false}) {Text("Keep it")}})
}

private fun stageWords(stage:String)=when(stage) {
    Journey.CHECKING->"Checking the idea"
    Journey.SETTING_UP->"Setting up"
    Journey.LAUNCHED->"Launched: first customers"
    Journey.RUNNING->"Running"
    else->"Just an idea so far"
}
