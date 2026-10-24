package com.alphainternational.assistant

/**
 * What the phone's screen looks like to a bot, and how a bot points at something on it. All plain Kotlin with no Android types, so the rules are tested on the computer.
 *
 * The screen comes from Android's accessibility tree: every button, field and piece of text with its name, where it is and whether it can be tapped. A raw tree has hundreds of
 * nodes, most of them empty containers, so [PhoneScreen.build] boils it down to a short list of what a person would see and could touch (about 1,000 words at most), each tappable
 * thing with a number. A bot points at things by number or, better, by the words on them ("Send"), because a number is only good until the screen changes.
 */
class RawNode(
    val text:String="",val desc:String="",val hint:String="",val cls:String="",val viewId:String="",
    val l:Int=0,val t:Int=0,val r:Int=0,val b:Int=0,
    val clickable:Boolean=false,val longClickable:Boolean=false,val editable:Boolean=false,val scrollable:Boolean=false,
    val checkable:Boolean=false,val checked:Boolean=false,val selected:Boolean=false,val focused:Boolean=false,val enabled:Boolean=true,val password:Boolean=false,
    val canScrollForward:Boolean=false,val canScrollBackward:Boolean=false,val visible:Boolean=true,
    val children:List<RawNode> = emptyList())

/** Something on the screen a bot can act on (or, for [Kind.TEXT], plain words it can only tap by position). [path] is where it sits in the tree, to find it again. */
class Elem(val id:String,val role:String,val label:String,val state:String,val l:Int,val t:Int,val r:Int,val b:Int,val viewId:String,val path:List<Int>,val kind:Kind) {
    enum class Kind {TAP,INPUT,SCROLL,TEXT}
    val cx get()=(l+r)/2
    val cy get()=(t+b)/2
    val interactive get()=kind!=Kind.TEXT
    /** What was on it when the bot last looked, for finding the same thing on a screen that has since been redrawn. */
    val signature get()=role+"|"+viewId+"|"+label.lowercase()
}

class Snapshot(val app:String,val pkg:String,val width:Int,val height:Int,val elems:List<Elem>,val lines:List<String>,val cut:Boolean) {
    /** What the bot reads. */
    fun text():String=buildString {
        append("App: ").append(app).append(" (").append(pkg).append(")\n")
        lines.forEach {append(it).append('\n')}
        if(cut) append("… more on this screen than fits: scroll down, or look for a smaller part.")
    }.trimEnd()
    /** Changes whenever the words or the things on screen change (to tell whether an action did anything). */
    val fingerprint:String get()=pkg+"|"+lines.joinToString("\n").hashCode()
}

object PhoneScreen {
    const val MAX_ELEMS=110
    const val LIMIT=5400

    private val spaces=Regex("\\s+")
    private fun clean(s:String)=s.replace(spaces," ").trim()
    private fun short(s:String,n:Int=110):String=clean(s).let {if(it.length>n) it.take(n-1)+"…" else it}
    private fun idName(viewId:String):String=viewId.substringAfter(":id/","").replace('_',' ').replace('-',' ').trim()

    /** A screen as the bot reads it. [root] is the app in front; [width] and [height] are the display's, to drop what is off screen. */
    fun build(root:RawNode,app:String,pkg:String,width:Int,height:Int,limit:Int=LIMIT,maxElems:Int=MAX_ELEMS):Snapshot {
        val lines=ArrayList<String>();val elems=ArrayList<Elem>()
        var chars=0;var cut=false;var last=""
        var interactiveCount=0
        fun room(line:String):Boolean {
            if(chars+line.length+1>limit || interactiveCount>=maxElems) {cut=true;return false}
            return true
        }
        fun onScreen(n:RawNode)=n.visible && !(n.r<=0 || n.b<=0 || n.l>=width || n.t>=height) && !(n.r<=n.l && n.b<=n.t && n.children.isEmpty())
        // The words inside a node that are not inside something else tappable, in reading order.
        fun words(n:RawNode,out:MutableList<String>,depth:Int) {
            if(!onScreen(n) || depth>8 || out.size>=6) return
            val own=short(n.text.ifBlank {n.desc})
            if(own.isNotBlank()) out+=own
            for(c in n.children) if(!(c.clickable || c.editable || c.checkable || c.longClickable)) words(c,out,depth+1)
        }
        fun emit(line:String,e:Elem?):Boolean {
            if(line==last) return true
            if(!room(line)) return false
            lines+=line;chars+=line.length+1;last=line
            if(e!=null) {elems+=e;if(e.interactive) interactiveCount++}
            return true
        }
        fun walk(n:RawNode,path:List<Int>,depth:Int,used:Set<String>):Boolean {
            if(!onScreen(n)) return true
            val state=ArrayList<String>()
            if(!n.enabled) state+="disabled"
            if(n.selected) state+="selected"
            if(n.focused) state+="focused"
            when {
                n.editable -> {
                    val label=short(n.hint.ifBlank {n.desc}.ifBlank {idName(n.viewId)}.ifBlank {"text field"})
                    val value=if(n.password) "" else short(n.text.takeIf {it!=n.hint}.orEmpty(),80)
                    if(n.password) state+="password: Alpha never types here" else if(value.isBlank()) state+="empty"
                    val id="e"+(interactiveCount+1)
                    val e=Elem(id,"input",label,(if(value.isNotBlank()) "has \""+value+"\"" else "")+state.joinToString(", "),n.l,n.t,n.r,n.b,n.viewId,path,Elem.Kind.INPUT)
                    val shown=listOf(if(value.isNotBlank()) "has \""+value+"\"" else "",state.joinToString(", ")).filter {it.isNotBlank()}.joinToString("; ")
                    if(!emit(id+" input \""+label+"\""+(if(shown.isNotBlank()) " ["+shown+"]" else ""),e)) return false
                    return true
                }
                n.checkable -> {
                    val inner=ArrayList<String>().also {words(n,it,0)}
                    val label=short(n.text.ifBlank {n.desc}.ifBlank {inner.joinToString(" · ")}.ifBlank {idName(n.viewId)}.ifBlank {"option"})
                    val role=when {n.cls.contains("Switch") || n.cls.contains("Toggle") -> "switch"; n.cls.contains("Radio") -> "radio"; else -> "checkbox"}
                    state+=if(n.checked) "on" else "off"
                    val id="e"+(interactiveCount+1)
                    val e=Elem(id,role,label,state.joinToString(", "),n.l,n.t,n.r,n.b,n.viewId,path,Elem.Kind.TAP)
                    if(!emit(id+" "+role+" \""+label+"\" ["+state.joinToString(", ")+"]",e)) return false
                    return true
                }
                n.clickable || n.longClickable -> {
                    val inner=ArrayList<String>().also {words(n,it,0)}
                    val label=short(n.text.ifBlank {n.desc}.ifBlank {inner.joinToString(" · ")}.ifBlank {idName(n.viewId)}.ifBlank {"unlabeled button"},140)
                    val role=when {
                        n.cls.contains("Tab") -> "tab"
                        n.cls.contains("Button") || n.cls.contains("Image") -> "button"
                        inner.size>=2 || label.length>34 -> "item"
                        else -> "button"
                    }
                    val id="e"+(interactiveCount+1)
                    val e=Elem(id,role,label,state.joinToString(", "),n.l,n.t,n.r,n.b,n.viewId,path,Elem.Kind.TAP)
                    if(!emit(id+" "+role+" \""+label+"\""+(if(state.isNotEmpty()) " ["+state.joinToString(", ")+"]" else ""),e)) return false
                    // The words inside are in the label already, so they are not listed a second time; anything tappable inside still is.
                    val nowUsed=used+inner
                    for((i,c) in n.children.withIndex()) if(!walk(c,path+i,depth+1,nowUsed)) return false
                    return true
                }
                n.scrollable && (n.canScrollForward || n.canScrollBackward) -> {
                    val label=short(n.desc.ifBlank {idName(n.viewId)}.ifBlank {"scrollable area"})
                    val more=listOfNotNull(if(n.canScrollBackward) "more above" else null,if(n.canScrollForward) "more below" else null).joinToString(", ")
                    val id="e"+(interactiveCount+1)
                    val e=Elem(id,"scroll",label,more,n.l,n.t,n.r,n.b,n.viewId,path,Elem.Kind.SCROLL)
                    if(!emit(id+" scroll \""+label+"\" ["+more+"]",e)) return false
                }
            }
            val own=short(n.text.ifBlank {n.desc})
            if(own.isNotBlank() && own !in used) {
                val e=Elem("t"+(elems.size+1),"text",own,"",n.l,n.t,n.r,n.b,n.viewId,path,Elem.Kind.TEXT)
                if(!emit("text \""+own+"\"",e)) return false
            }
            for((i,c) in n.children.withIndex()) if(!walk(c,path+i,depth+1,used+own)) return false
            return true
        }
        walk(root,emptyList(),0,emptySet())
        return Snapshot(app,pkg,width,height,elems,lines,cut)
    }

    // ---------------------------------------------------------------------------------------------------------------- pointing at things

    /** What a step points at: a number from the last look, or the words on it. [nth] picks one of several with the same words (1 is the first). */
    class Target(val id:String="",val words:String="",val role:String="",val nth:Int=0) {
        val given get()=id.isNotBlank() || words.isNotBlank()
        override fun toString()=if(id.isNotBlank()) id else "\""+words+"\""
    }
    sealed class Found {
        class One(val elem:Elem):Found()
        class Many(val list:List<Elem>):Found()
        object None:Found()
    }

    /**
     * The thing on [snap] that [target] means. By number it is that element. By words, the best match: exactly those words, then starting with them, then containing them;
     * things that can be tapped beat plain text; and if several match equally well the bot is told which (it never guesses between a Send button and a Send-to field).
     */
    fun find(snap:Snapshot,target:Target,wantInput:Boolean=false):Found {
        if(target.id.isNotBlank()) return snap.elems.firstOrNull {it.id==target.id}?.let {Found.One(it)}?:Found.None
        val words=clean(target.words).lowercase()
        if(words.isBlank()) return Found.None
        fun rank(e:Elem):Int {
            val label=e.label.lowercase()
            return when {
                label==words -> 0
                label.startsWith(words) -> 1
                Regex("\\b"+Regex.escape(words)+"\\b").containsMatchIn(label) -> 2
                label.contains(words) -> 3
                e.viewId.lowercase().contains(words.replace(' ','_')) -> 4
                else -> 99
            }
        }
        val pool=snap.elems.filter {e->
            (if(wantInput) e.kind==Elem.Kind.INPUT else true) && (target.role.isBlank() || e.role==target.role)
        }
        val ranked=pool.map {it to rank(it)}.filter {it.second<99}
        if(ranked.isEmpty()) return Found.None
        // Tappable things first, then plain words; within that the closest match.
        val best=ranked.minOf {(e,r)->r+(if(e.interactive) 0 else 10)}
        val group=ranked.filter {(e,r)->r+(if(e.interactive) 0 else 10)==best}.map {it.first}
        if(target.nth>0) return group.getOrNull(target.nth-1)?.let {Found.One(it)}?:Found.None
        return if(group.size==1) Found.One(group[0]) else Found.Many(group.take(5))
    }

    /** The same thing on a redrawn screen (for a number the bot read a moment ago): the element with the same name and kind, nearest to where it was. */
    fun again(old:Elem,fresh:Snapshot):Elem? =
        fresh.elems.filter {it.signature==old.signature}.minByOrNull {Math.abs(it.cx-old.cx)+Math.abs(it.cy-old.cy)}

    /** The field being typed in: the focused one, or the only one on the screen. */
    fun focusedInput(snap:Snapshot):Elem? = snap.elems.firstOrNull {it.kind==Elem.Kind.INPUT && it.state.contains("focused")}
        ?:snap.elems.filter {it.kind==Elem.Kind.INPUT}.singleOrNull()

    /** Whether any element on the screen has these words (for waiting and for checking a result). */
    fun has(snap:Snapshot,words:String):Boolean {
        val w=clean(words).lowercase()
        return w.isNotBlank() && snap.elems.any {it.label.lowercase().contains(w)}
    }
}
