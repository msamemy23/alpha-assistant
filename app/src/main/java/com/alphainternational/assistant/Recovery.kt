package com.alphainternational.assistant

import org.json.JSONObject

/**
 * Keeping a job alive when the model slips. A tool name that doesn't exist, details that aren't valid, a tool that fails, an answer that was cut off
 * by the length limit: none of these should stop the job. The model gets a plain error as the tool's result and tries again another way.
 */
object Recovery {
    /** What every failed tool result tells the model to do. (It used to say "explain the limitation", which made bots give up after one miss.) */
    const val TRY_ANOTHER_WAY="Try another way: a different tool, source or approach. Only if there is truly no other way, tell the owner plainly what is missing. Never invent a result."

    /** A failed tool result: the error, and the instruction. */
    fun failed(message:String):JSONObject=obj("error" to message,"instruction" to TRY_ANOTHER_WAY)

    /** The model asked for a tool that doesn't exist (or isn't available): say so, name the closest ones, and let it carry on. */
    fun unknownTool(name:String,known:Collection<String>):JSONObject {
        val close=similar(name,known)
        return obj("slip" to true,"error" to "There is no tool called \""+name.take(60)+"\".",
            "instruction" to (if(close.isEmpty()) "" else "The closest tools are: "+close.joinToString()+". ")+"Use one of the tools you have. "+TRY_ANOTHER_WAY)
    }

    /** The details sent to a tool weren't a valid JSON object (usually a cut-off or a stray character). */
    fun badArguments(name:String,why:String):JSONObject=obj("slip" to true,"error" to "The details sent to "+name+" were not valid ("+why.take(120)+"). Nothing was done.",
        "instruction" to "Send the call again with the details as one complete JSON object that matches the tool's description. If a value is very long, send it in smaller pieces (file_append for text files).")

    /** Up to three known tool names that look like [name]: the same start, or one contains the other, or only a few letters differ. */
    fun similar(name:String,known:Collection<String>):List<String> {
        val n=name.lowercase().trim()
        if(n.isBlank()) return emptyList()
        return known.map {it to score(n,it.lowercase())}.filter {it.second>0}.sortedByDescending {it.second}.take(3).map {it.first}
    }
    private fun score(a:String,b:String):Int {
        if(a==b) return 100
        var s=0
        if(b.contains(a) || a.contains(b)) s+=40
        val prefix=a.commonPrefixWith(b).length
        if(prefix>=4) s+=prefix*3
        val partsA=a.split('_').toSet();val partsB=b.split('_').toSet()
        s+=partsA.intersect(partsB).count {it.length>=3}*12
        if(Math.abs(a.length-b.length)<=2 && distance(a,b)<=3) s+=30
        return s
    }
    private fun distance(a:String,b:String):Int {
        val d=Array(a.length+1) {IntArray(b.length+1)}
        for(i in 0..a.length) d[i][0]=i
        for(j in 0..b.length) d[0][j]=j
        for(i in 1..a.length) for(j in 1..b.length) d[i][j]=minOf(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+if(a[i-1]==b[j-1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** How many times in a row a model reply may be cut off by the length limit before the job stops and asks the owner to tap Keep going. */
    const val MAX_CUT_OFFS=2

    /** The note added for the next round after a reply was cut off. */
    fun cutOffNote(attempt:Int):String = "Your last reply was cut off because it was too long, so nothing from it was used. Continue in smaller pieces: " +
        "write long files in several parts with file_append, keep each tool call short, and keep your answer brief"+(if(attempt>1) " (this is the second time: make each piece much smaller)" else "")+"."

    /** A model reply cut off by the length limit (or a tool call cut off halfway): safe to ask again in smaller pieces. */
    fun isCutOff(e:Throwable):Boolean=e is ModelCutOff
}
