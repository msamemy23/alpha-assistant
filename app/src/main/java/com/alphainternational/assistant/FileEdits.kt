package com.alphainternational.assistant

/**
 * Changing part of a file instead of rewriting all of it. A bot gives the exact text to replace and the new text; if the exact text
 * isn't found, a version that ignores differences in spacing and line breaks is tried; if that is ambiguous or missing, the bot gets
 * the closest lines back so it can try once more with the real text.
 */
object FileEdits {
    sealed class Result {
        data class Done(val text:String,val replaced:Int,val how:String):Result()
        data class Failed(val reason:String,val closest:String=""):Result()
    }

    /** Replaces [old] with [new] in [text]. [all] replaces every match; otherwise the match must be unique. */
    fun replace(text:String,old:String,new:String,all:Boolean=false):Result {
        if(old.isEmpty()) return Result.Failed("Say which text to replace (old can't be empty). Use file_write to make a new file or file_append to add to the end.")
        if(old==new) return Result.Failed("old and new are the same, so nothing would change.")
        val exact=indicesOf(text,old)
        if(exact.size==1 || (exact.size>1 && all)) {
            val out=if(all) text.replace(old,new) else text.substring(0,exact[0])+new+text.substring(exact[0]+old.length)
            return Result.Done(out,exact.size,"exact")
        }
        if(exact.size>1) return Result.Failed("That text appears ${exact.size} times. Include more of the lines around it so it matches once, or set all to true to change every one.",closest(text,old))
        // Same text with different spacing or line breaks (models often re-indent): match on a whitespace-flexible pattern.
        val flexible=flexible(old)
        if(flexible!=null) {
            val matches=flexible.findAll(text).toList()
            if(matches.size==1 || (matches.size>1 && all)) {
                val out=if(all) flexible.replace(text) {new} else text.substring(0,matches[0].range.first)+new+text.substring(matches[0].range.last+1)
                return Result.Done(out,matches.size,"ignoring spacing")
            }
            if(matches.size>1) return Result.Failed("That text appears ${matches.size} times (ignoring spacing). Include more of the surrounding lines so it matches once.",closest(text,old))
        }
        return Result.Failed("That text isn't in the file. Copy it exactly from file_read, or from the closest lines below.",closest(text,old))
    }

    /** Replaces everything between the markers <!-- section:name --> and <!-- /section:name --> (the markers stay). */
    fun replaceSection(text:String,name:String,inner:String):Result {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,40}"))) {"Use a short section name like hero or services."}
        val open=Regex("<!--\\s*section:"+Regex.escape(name)+"\\s*-->")
        val close=Regex("<!--\\s*/section:"+Regex.escape(name)+"\\s*-->")
        val start=open.find(text)?:return Result.Failed("There is no section named $name. Sections look like <!-- section:$name --> … <!-- /section:$name -->. Known sections: "+sections(text).joinToString().ifBlank {"none"}+".")
        val end=close.find(text,start.range.last+1)?:return Result.Failed("The section $name has no closing <!-- /section:$name --> marker.")
        val out=text.substring(0,start.range.last+1)+"\n"+inner.trim('\n')+"\n"+text.substring(end.range.first)
        return Result.Done(out,1,"section")
    }

    /** The names of the sections marked in a page. */
    fun sections(text:String):List<String> = Regex("<!--\\s*section:([A-Za-z0-9_-]{1,40})\\s*-->").findAll(text).map {it.groupValues[1]}.toList()

    private fun indicesOf(text:String,part:String):List<Int> {
        val out=mutableListOf<Int>();var at=text.indexOf(part)
        while(at>=0) {out+=at;at=text.indexOf(part,at+part.length)}
        return out
    }
    /** A pattern where any run of whitespace in [old] matches any run of whitespace in the file. Null when [old] has no real text. */
    private fun flexible(old:String):Regex? {
        val words=old.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
        if(words.isEmpty()) return null
        return Regex(words.joinToString("\\s+") {Regex.escape(it)})
    }
    /** Up to 6 lines of [text] that look most like the first line of [old], with line numbers, for a retry. */
    fun closest(text:String,old:String):String {
        val key=old.lines().map {it.trim()}.firstOrNull {it.length>=4}?:old.trim()
        if(key.isBlank()) return ""
        val lines=text.lines()
        val words=key.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}.toSet()
        val scored=lines.mapIndexed {i,line->
            val have=line.lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}.toSet()
            val score=if(words.isEmpty()) 0 else words.count {it in have}*100/words.size
            Triple(i,line,score)
        }.filter {it.third>=40}.sortedByDescending {it.third}.take(6).sortedBy {it.first}
        return scored.joinToString("\n") {"${it.first+1}: "+it.second.trim().take(160)}
    }
}
