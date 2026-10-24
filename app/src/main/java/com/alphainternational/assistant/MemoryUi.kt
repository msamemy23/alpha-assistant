package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * "What I know about you": everything a bot has learned or been told, in plain cards the owner can read, fix and remove. Dots hides its memory and
 * Muse shows it as a file; here each note says where and when it was learned, and the owner can edit it, forget it, or say "that's wrong" so the bot
 * never learns it again. One bot's notes at a time (a bot only ever uses its own), with a search box.
 */
object KnowsWords {
    /** The kinds of notes, in the order they are shown, with plain names. */
    val groups=listOf("summary" to "Where things stand","preference" to "How you like things","habit" to "What you usually do","person" to "People",
        "fact" to "Facts","lesson" to "What worked and what didn't")
    fun label(kind:String)=groups.firstOrNull {it.first==kind}?.second?:"Facts"
    /** What the owner can pick when adding or editing a note. */
    val pickable=groups.filter {it.first!="summary"}
}

/** The notes of [bot] as cards (or search results), with the bot switcher, the search box and "Add a memory". */
fun LazyListScope.memoryItems(c:Controller,bot:String,query:String,pickBot:(String)->Unit,setQuery:(String)->Unit,edit:(Memory?)->Unit) {
    val mine=c.memories.filter {it.workspace==bot}
    if(c.bots.size>1) item(key="knows-bots") {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            c.bots.forEach {b->FilterChip(selected=b.name==bot,onClick={pickBot(b.name)},label={Text(b.name)})}
        }
    }
    item(key="knows-intro") {
        Text(if(mine.isEmpty()) "" else "${mine.size} ${if(mine.size==1) "thing" else "things"} "+bot+" knows. It learns as you talk and work together, and uses this only for you. Fix or erase anything that's wrong.",
            color=Muted,fontSize=13.sp)
        if(!c.aiSettings.learn) Text("Learning is turned off in Settings, so "+bot+" only keeps what you add here.",color=Amber,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
    }
    item(key="knows-search") {
        OutlinedTextField(query,setQuery,placeholder={Text("Search what "+bot+" knows")},singleLine=true,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth(),
            trailingIcon={if(query.isNotEmpty()) IconButton(onClick={setQuery("")}) {Icon(Icons.Outlined.Close,"Clear search",tint=Muted)}})
    }
    item(key="knows-add") {OutlinedButton(onClick={edit(null)}) {Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("Add a memory",color=Ink)}}
    if(mine.isEmpty()) item(key="knows-empty") {
        KnowsEmpty("Nothing learned yet",bot+" will learn how you like things, what you usually do and the people you deal with as you work together. You can also add things yourself.")
    }
    else if(query.isNotBlank()) {
        val byId=mine.associateBy {it.id}
        val found=MemoryIndex.rank(mine.map(MemoryIndex::docOf),query,limit=40).mapNotNull {byId[it.id]}
        if(found.isEmpty()) item(key="knows-none") {KnowsEmpty("Nothing found","Try another word, like a name or a place.")}
        items(found,key={"knows-hit-"+it.id}) {MemoryCard(c,it,edit)}
    } else {
        KnowsWords.groups.forEach {(kind,label)->
            val group=mine.filter {(it.kind.takeIf {k->KnowsWords.groups.any {g->g.first==k}}?:"fact")==kind}.sortedByDescending {it.updated}
            if(group.isNotEmpty()) {
                item(key="knows-head-$kind") {Text(label.uppercase(),style=Type.label,modifier=Modifier.padding(top=8.dp))}
                items(group,key={"knows-"+it.id}) {MemoryCard(c,it,edit)}
            }
        }
    }
    val wrong=c.wrongNotes(bot)
    if(wrong.isNotEmpty()) {
        item(key="knows-wrong-head") {Text("THINGS I WON'T LEARN AGAIN",style=Type.label,modifier=Modifier.padding(top=14.dp))}
        items(wrong,key={"knows-wrong-"+it.at+it.text.hashCode()}) {note->
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(start=14.dp,top=4.dp,bottom=4.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(note.text,Modifier.weight(1f),color=Muted,fontSize=13.sp,maxLines=3)
                TextButton(onClick={c.allowAgain(bot,note.text)}) {Text("Allow again",color=Ink)}
            }
        }
    }
}

@Composable private fun KnowsEmpty(title:String,text:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=30.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoAwesome,null,tint=Mint,modifier=Modifier.size(36.dp));Text(title,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=14.dp),color=Ink)
        Text(text,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun MemoryCard(c:Controller,m:Memory,edit:(Memory?)->Unit) {
    val asked=remember(m.task,c.tasks.size) {m.task.takeIf {it.isNotBlank()}?.let {id->c.tasks.firstOrNull {t->t.id==id}?.prompt}}
    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(20.dp)).padding(start=18.dp,end=8.dp,top=16.dp,bottom=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(KnowsWords.label(m.kind).uppercase(),style=Type.label)
        Text(m.title,style=Type.sub)
        if(m.private) Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock,null,tint=Amber,modifier=Modifier.size(13.dp));Spacer(Modifier.width(4.dp))
            Text("Private · your other bots never see this",color=Amber,fontSize=11.sp)
        }
        SelectionContainer {Text(m.text,style=Type.body.copy(fontSize=14.sp,lineHeight=21.sp))}
        Text(Learning.origin(m,asked),style=Type.small.copy(fontSize=11.sp))
        Row(Modifier.padding(top=2.dp)) {
            TextButton(onClick={edit(m)}) {Text("Edit",color=Ink)}
            TextButton(onClick={c.deleteMemory(m.id)}) {Text("Forget",color=Muted)}
            if(Learning.isLearned(m) && m.kind!="summary") TextButton(onClick={c.markMemoryWrong(m.id);c.showNotice("Okay. "+m.workspace+" won't learn that again.")}) {Text("That's wrong",color=Muted)}
        }
    }
}

/** Add a note, or change one: what it says, what kind of note it is, and whether it stays private to this bot. */
@Composable fun MemoryEditor(c:Controller,bot:String,existing:Memory?,close:()->Unit) {
    var title by remember {mutableStateOf(existing?.title.orEmpty())}
    var text by remember {mutableStateOf(existing?.text.orEmpty())}
    var kind by remember {mutableStateOf(existing?.kind?.takeIf {k->KnowsWords.pickable.any {it.first==k}}?:"fact")}
    var private by remember {mutableStateOf(existing?.private==true)}
    var error by remember {mutableStateOf("")}
    Dialog(onDismissRequest=close) {
        Surface(shape=RoundedCornerShape(24.dp),color=Panel) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(existing==null) "Remember something" else "Change this",style=MaterialTheme.typography.titleLarge,color=Ink)
                Text("Saved in "+(existing?.workspace?:bot)+"'s notes. You can change or remove it any time.",color=Muted,fontSize=13.sp)
                OutlinedTextField(title,{title=it;error=""},label={Text("Short title",fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(text,{text=it;error=""},label={Text("What to remember",fontSize=12.sp)},minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    KnowsWords.pickable.forEach {(k,label)->FilterChip(selected=kind==k,onClick={kind=k},label={Text(label)})}
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep it from my other bots",color=Ink,fontSize=14.sp)
                        Text("Only "+(existing?.workspace?:bot)+" will use it.",color=Muted,fontSize=12.sp)
                    }
                    Switch(checked=private,onCheckedChange={private=it},colors=SwitchDefaults.colors(checkedTrackColor=androidx.compose.ui.graphics.Color.White,checkedThumbColor=androidx.compose.ui.graphics.Color.Black))
                }
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=close) {Text("Cancel",color=Ink)}
                    Button(onClick={
                        if(title.isBlank() || text.isBlank()) error="Give it a title and say what to remember."
                        else {
                            // Whatever the owner types or fixes is theirs: it is marked as added by them, and keeps its place and where it came from.
                            c.saveMemory(title.trim(),text.trim(),"You",existing?.id?:java.util.UUID.randomUUID().toString(),existing?.workspace?:bot,private,kind,existing?.task.orEmpty())
                            close()
                        }
                    }) {Text("Save")}
                }
            }
        }
    }
}
