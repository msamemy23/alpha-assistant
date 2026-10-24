package com.alphainternational.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * An edit list for a video, written by a bot as JSON: clips, text, captions, pictures on top, and music. This file only reads and checks it
 * (pure code, tested without a phone); MediaEdit turns a checked list into a video with Android's own hardware video tools.
 */
object MediaSpec {
    const val MAX_SECONDS=180.0
    const val MAX_CLIPS=24

    data class Clip(val file:String,val image:Boolean,val start:Double,val end:Double,val seconds:Double,val speed:Double,val fit:String,val mute:Boolean,val zoom:Boolean) {
        /** How long this clip lasts in the finished video. */
        val length get()=if(image) seconds else (end-start)/speed
    }
    data class Text(val text:String,val start:Double,val end:Double,val position:String,val size:Int,val color:String,val background:String)
    data class Caption(val text:String,val start:Double,val end:Double)
    data class Picture(val file:String,val start:Double,val end:Double,val position:String,val scale:Double)
    data class Sound(val file:String,val volume:Double,val loop:Boolean,val fadeIn:Double,val fadeOut:Double)
    data class Spec(val output:String,val width:Int,val height:Int,val fps:Int,val clips:List<Clip>,val texts:List<Text>,val captions:List<Caption>,val pictures:List<Picture>,
        val sounds:List<Sound>,val captionSize:Int,val total:Double)

    private fun fileName(raw:String,kinds:Set<String>,what:String):String {
        val name=raw.trim();val kind=Attachments.kind(name)
        require(name.isNotBlank()) {"Every $what needs a file name from files_list."}
        runCatching {Rules.fileName(name)}.getOrElse {throw IllegalArgumentException("$name isn't a usable file name for a $what.")}
        require(kind in kinds) {"$name can't be used as a $what (it is a $kind file)."}
        return name
    }
    private fun num(o:JSONObject,key:String,default:Double)=if(o.has(key) && !o.isNull(key)) o.getDouble(key) else default
    private fun color(raw:String,default:String):String {
        val c=raw.trim().ifBlank {default}
        require(c.matches(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) {"Colours are written like #ffffff or #cc000000 (with transparency)."}
        return c
    }

    /** Reads and checks [json]. Throws a plain-words IllegalArgumentException that tells the bot what to fix. */
    fun parse(json:String):Spec {
        val o=try {JSONObject(json)} catch(e:Exception) {throw IllegalArgumentException("The edit list isn't valid JSON: "+(e.message?.take(100)?:""))}
        val output=o.optString("output","video.mp4").trim().ifBlank {"video.mp4"}.let {if(it.endsWith(".mp4",true)) it else "$it.mp4"}
        runCatching {Rules.fileName(output)}.getOrElse {throw IllegalArgumentException("output must be a simple file name like reel.mp4.")}
        val width=o.optInt("width",1080);val height=o.optInt("height",1920);val fps=o.optInt("fps",30)
        require(width in 240..2160 && height in 240..3840 && width%2==0 && height%2==0) {"width and height must be even numbers between 240 and 2160 (1080x1920 is a vertical reel, 1920x1080 is wide)."}
        require(fps in 15..60) {"fps must be between 15 and 60."}
        val clipList=o.optJSONArray("clips")?:throw IllegalArgumentException("Add at least one clip in clips: [{\"file\":\"clip.mp4\",\"start\":0,\"end\":8}] or [{\"image\":\"photo.jpg\",\"seconds\":3}].")
        require(clipList.length() in 1..MAX_CLIPS) {"Use between 1 and $MAX_CLIPS clips."}
        val clips=(0 until clipList.length()).map {i->
            val c=clipList.getJSONObject(i)
            if(c.has("image")) {
                val seconds=num(c,"seconds",3.0);require(seconds in 0.5..30.0) {"A picture clip lasts 0.5 to 30 seconds."}
                Clip(fileName(c.getString("image"),setOf("image"),"picture clip"),true,0.0,seconds,seconds,1.0,c.optString("fit","crop"),true,c.optBoolean("zoom",true))
            } else {
                val start=num(c,"start",0.0);val end=num(c,"end",-1.0);val speed=num(c,"speed",1.0)
                require(start>=0 && end>start) {"Clip ${i+1}: end must be later than start (seconds). Use media_probe to find the length."}
                require(speed in 0.25..4.0) {"Clip ${i+1}: speed must be between 0.25 and 4."}
                Clip(fileName(c.getString("file"),setOf("video"),"video clip"),false,start,end,0.0,speed,c.optString("fit","crop"),c.optBoolean("mute"),false)
            }.also {require(it.fit in setOf("crop","fit")) {"fit must be crop (fill the frame) or fit (show everything, with bars)."}}
        }
        val total=clips.sumOf {it.length}
        require(total in 0.5..MAX_SECONDS) {"The video would be ${"%.0f".format(total)} seconds. Keep it between 1 and ${MAX_SECONDS.toInt()} seconds."}
        fun window(c:JSONObject,what:String):Pair<Double,Double> {
            val s=num(c,"start",0.0);val e=num(c,"end",total);require(s>=0 && e>s) {"$what: end must be later than start."};return s to minOf(e,total)
        }
        val texts=(o.optJSONArray("text")?:JSONArray()).let {a->(0 until a.length()).map {i->
            val t=a.getJSONObject(i);val (s,e)=window(t,"Text ${i+1}")
            val text=t.getString("text").trim();require(text.isNotBlank() && text.length<=160) {"Text ${i+1} needs 1 to 160 characters."}
            Text(text,s,e,t.optString("position","bottom").also {p->require(p in setOf("top","center","bottom")) {"position must be top, center or bottom."}},
                t.optInt("size",(height*0.065).toInt()).coerceIn(14,height/6),color(t.optString("color"),"#ffffff"),color(t.optString("background"),"#99000000"))
        }}
        require(texts.size<=30) {"At most 30 text overlays."}
        val captions=(o.optJSONArray("captions")?:JSONArray()).let {a->(0 until a.length()).map {i->
            val c=a.getJSONObject(i);val (s,e)=window(c,"Caption ${i+1}")
            val text=c.getString("text").trim();require(text.isNotBlank() && text.length<=120) {"Caption ${i+1} needs 1 to 120 characters."}
            Caption(text,s,e)
        }}
        require(captions.size<=600) {"Too many captions."}
        val pictures=(o.optJSONArray("pictures")?:JSONArray()).let {a->(0 until a.length()).map {i->
            val p=a.getJSONObject(i);val (s,e)=window(p,"Picture ${i+1}")
            Picture(fileName(p.getString("file"),setOf("image"),"picture overlay"),s,e,p.optString("position","top-right").also {pos->require(pos in setOf("top-left","top-right","bottom-left","bottom-right","center")) {"picture position must be top-left, top-right, bottom-left, bottom-right or center."}},
                num(p,"scale",0.2).coerceIn(0.05,1.0))
        }}
        require(pictures.size<=6) {"At most 6 pictures on top of the video."}
        val sounds=(o.optJSONArray("audio")?:JSONArray()).let {a->(0 until a.length()).map {i->
            val s=a.getJSONObject(i)
            Sound(fileName(s.getString("file"),setOf("audio","video"),"sound track"),num(s,"volume",0.5).coerceIn(0.0,2.0),s.optBoolean("loop",true),num(s,"fade_in",0.0).coerceIn(0.0,10.0),num(s,"fade_out",1.0).coerceIn(0.0,10.0))
        }}
        require(sounds.size<=3) {"At most 3 sound tracks."}
        return Spec(output,width,height,fps,clips,texts,captions,pictures,sounds,o.optInt("caption_size",(height*0.045).toInt()).coerceIn(14,height/6),total)
    }

    /** Breaks [text] into lines that fit a frame [width] pixels wide at text [size] (about half a text height per character). */
    fun wrap(text:String,width:Int,size:Int):String {
        val perLine=((width*0.86)/(size*0.52)).toInt().coerceAtLeast(8)
        val lines=mutableListOf<String>();var line=StringBuilder()
        for(word in text.trim().split(Regex("\\s+"))) {
            if(line.isNotEmpty() && line.length+1+word.length>perLine) {lines+=line.toString();line=StringBuilder()}
            if(line.isNotEmpty()) line.append(' ');line.append(word)
        }
        if(line.isNotEmpty()) lines+=line.toString()
        return lines.joinToString("\n")
    }

    /** The caption showing at [seconds], or null. */
    fun captionAt(captions:List<Caption>,seconds:Double):Caption?=captions.firstOrNull {seconds>=it.start && seconds<it.end}

    /** Words with start/end seconds → caption lines of at most [maxChars] characters and [maxSeconds], like the lines in a subtitle file. */
    fun lines(words:List<Triple<String,Double,Double>>,maxChars:Int=32,maxSeconds:Double=3.0):List<Caption> {
        val out=mutableListOf<Caption>();var text=StringBuilder();var start=0.0;var end=0.0
        fun flush() {if(text.isNotBlank()) out+=Caption(text.toString().trim(),start,end);text=StringBuilder()}
        for((word,s,e) in words) {
            if(text.isEmpty()) start=s
            else if(text.length+1+word.length>maxChars || e-start>maxSeconds) {flush();start=s}
            if(text.isNotEmpty()) text.append(' ');text.append(word);end=e
            if(word.endsWith('.') || word.endsWith('?') || word.endsWith('!')) flush()
        }
        flush()
        return out
    }
}
