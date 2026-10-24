package com.alphainternational.assistant

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Finding things in what a bot knows, on the phone, with no service: words are cut to their stems, ranked like a small search engine (BM25: rare
 * words that match count more, long notes don't win just by being long) and fresher notes get a small lift. Used for memories, finished-job
 * digests, chat texts and files alike, so every kind of search in Alpha behaves the same way.
 */
object MemoryIndex {
    private val stop=setOf("the","and","for","you","your","are","with","this","that","what","have","can","please","about","from","just","get","make","need","want",
        "into","then","them","they","there","their","will","would","could","should","when","how","who","why","all","any","out","our","was","has","not","but","its",
        "did","does","had","her","him","his","she","let","may","now","one","own","say","see","too","use","way","yes","too","also","been","than","some","very","more",
        "like","over","such","only","each","does","doing","done","still","here","where","which","while","these","those",
        "is","my","an","of","to","in","on","at","it","be","as","or","if","so","do","we","me","he","by","up","am","us")

    /** Lowercase words and numbers, common filler dropped, plural and -ing/-ed endings trimmed so "estimates" finds "estimate". */
    fun tokens(text:String):List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter {it.isNotEmpty() && (it.length>=2 || it[0].isDigit()) && it !in stop}.map(::stem)

    fun stem(word:String):String {
        var w=word
        if(w.length>4 && w.endsWith("ies")) return w.dropLast(3)+"y"
        if(w.length>5 && w.endsWith("ing")) w=w.dropLast(3)
        else if(w.length>4 && w.endsWith("ed")) w=w.dropLast(2)
        else if(w.length>4 && (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zes") || w.endsWith("ches") || w.endsWith("shes"))) w=w.dropLast(2)
        else if(w.length>3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) w=w.dropLast(1)
        if(w.length>3 && w[w.length-1]==w[w.length-2] && w[w.length-1] !in "sl") w=w.dropLast(1)
        return w
    }

    /** One thing that can be found. [title] counts double; [weight] lets a kind of thing rank higher (a preference over a passing fact). */
    data class Doc(val id:String,val title:String,val text:String,val updated:Long,val weight:Double=1.0)
    data class Hit(val id:String,val score:Double)

    private const val K1=1.2
    private const val B=0.75

    /**
     * The best matches for [query], best first. A blank query returns the newest [limit]. Only notes that share a word with the query are returned,
     * so an unrelated question finds nothing rather than something random. [halfLifeDays]: how fast the freshness lift fades.
     */
    fun rank(docs:List<Doc>,query:String,now:Long=System.currentTimeMillis(),limit:Int=10,halfLifeDays:Double=45.0):List<Hit> {
        if(docs.isEmpty()) return emptyList()
        val q=tokens(query).distinct()
        if(q.isEmpty()) return docs.sortedByDescending {it.updated}.take(limit).map {Hit(it.id,0.0)}
        val bags=docs.map {d->
            val counts=HashMap<String,Int>()
            (tokens(d.title)+tokens(d.title)+tokens(d.text)).forEach {counts[it]=(counts[it]?:0)+1}
            counts
        }
        val lengths=bags.map {it.values.sum().toDouble()}
        val average=max(1.0,lengths.average())
        val n=docs.size.toDouble()
        // How many notes have each query word: exactly, or as the start of a longer word ("cam" finds "camry").
        val hits=q.associateWith {term->bags.count {bag->bag.containsKey(term) || (term.length>=3 && bag.keys.any {it.startsWith(term)})}}
        val scored=docs.mapIndexedNotNull {i,doc->
            var score=0.0
            q.forEach {term->
                val bag=bags[i]
                val exact=bag[term]?:0
                val partial=if(term.length>=3) bag.entries.filter {it.key!=term && it.key.startsWith(term)}.sumOf {it.value} else 0
                val tf=exact+0.5*partial
                if(tf>0.0) {
                    val df=hits.getValue(term).toDouble()
                    val idf=ln(1.0+(n-df+0.5)/(df+0.5))
                    score+=idf*(tf*(K1+1.0))/(tf+K1*(1.0-B+B*lengths[i]/average))
                }
            }
            if(score<=0.0) return@mapIndexedNotNull null
            val ageDays=max(0.0,(now-doc.updated)/86_400_000.0)
            Hit(doc.id,score*doc.weight*(1.0+0.35*0.5.pow(ageDays/halfLifeDays)))
        }
        return scored.sortedByDescending {it.score}.take(limit)
    }

    /** 0 to 1: how much two texts say the same thing (shared words over all words). */
    fun similarity(a:String,b:String):Double {
        val x=tokens(a).toSet();val y=tokens(b).toSet()
        if(x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble()/x.union(y).size
    }
    /** 0 to 1: the share of [part]'s words that also appear in [whole]. */
    fun covered(part:String,whole:String):Double {
        val x=tokens(part).toSet();val y=tokens(whole).toSet()
        if(x.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble()/x.size
    }
    /** How much of the shorter text is inside the longer (a rewording that only adds a detail is the same note). */
    fun containment(a:String,b:String):Double {
        val x=tokens(a).toSet();val y=tokens(b).toSet()
        if(x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble()/minOf(x.size,y.size)
    }

    // ---- Keeping a bot's memory a sensible size ----

    /** The most memories one bot keeps. Near-identical ones are merged first; only then are the least useful dropped. */
    const val MAX_PER_BOT=300
    /** Same kind and almost the same words: one note said twice. */
    const val SAME=0.72

    private fun keepScore(m:Memory,now:Long):Double {
        val kind=when(m.kind) {"summary"->9.0;"preference"->5.0;"habit"->4.0;"person"->4.0;"fact"->3.0;"lesson"->2.0;else->3.0}
        // Things the owner said or added themselves matter more than something a bot picked up while reading a page.
        val source=when {m.source.startsWith("You said") || m.source=="You" || !Learning.isLearned(m)->2.5;m.source.contains("reading",true)->0.0;else->1.0}
        val age=max(0.0,(now-m.updated)/86_400_000.0)
        return kind+source+2.0*0.5.pow(age/90.0)
    }

    /**
     * Merges notes that say the same thing (the newest wording wins; if either was private the result stays private) and, if a bot still has more
     * than [cap], drops the least useful. Other bots' memories are returned unchanged. Returns the new list and how many notes went away.
     */
    fun consolidate(all:List<Memory>,space:String,cap:Int=MAX_PER_BOT,now:Long=System.currentTimeMillis()):Pair<List<Memory>,Int> {
        val mine=all.filter {it.workspace==space}
        val others=all.filter {it.workspace!=space}
        val kept=ArrayList<Memory>()
        var removed=0
        // Newest first, so the first of a group of look-alikes is the one to keep.
        for(m in mine.sortedByDescending {it.updated}) {
            if(m.kind=="summary") {
                val have=kept.firstOrNull {it.kind=="summary"}
                if(have==null) kept+=m else removed++
                continue
            }
            val twin=kept.indexOfFirst {k->k.kind==m.kind && k.kind!="summary" && (similarity(k.title+" "+k.text,m.title+" "+m.text)>=SAME ||
                (k.title.equals(m.title,true) && containment(k.text,m.text)>=0.9))}
            if(twin<0) kept+=m
            else {
                if(m.private && !kept[twin].private) kept[twin]=kept[twin].copy(private=true)
                removed++
            }
        }
        var result=kept.toList()
        if(result.size>cap) {
            val drop=result.sortedBy {keepScore(it,now)}.take(result.size-cap).map {it.id}.toSet()
            result=result.filterNot {it.id in drop};removed+=drop.size
        }
        if(removed==0) return all to 0
        // Keep the original order of everything that stays.
        val survivors=result.map {it.id}.toSet()
        val byId=result.associateBy {it.id}
        return all.filter {it.workspace!=space || it.id in survivors}.map {byId[it.id]?.takeIf {_->it.workspace==space}?:it} to removed
    }

    fun docOf(m:Memory)=Doc(m.id,m.title,m.text,m.updated,when(m.kind) {"preference","habit"->1.3;"person"->1.15;"summary"->1.2;else->1.0})
}
