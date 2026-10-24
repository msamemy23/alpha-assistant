package com.alphainternational.assistant

import android.content.Context
import java.io.File

/**
 * Ready-made games and toys the bots start from. Each is one finished HTML page with a CONFIG block at the top (title, words, colours, levels,
 * questions, prizes, tune) and the shared "juice" kit for feel (screen shake, particles, sound, saved scores, pause, touch controls), so a bot
 * changes wording and numbers instead of writing a game engine from scratch. Everything works offline from the bot's own files.
 */
object Games {
    data class Kind(val id:String,val summary:String)
    val kinds=listOf(
        Kind("arcade","One-screen brick-breaker. Drag to slide the paddle, power-ups, levels from rows of digits. The shop's offer shows when the game ends."),
        Kind("platformer","Tile-map platformer: run, jump, collect, dodge, several levels drawn as text rows."),
        Kind("puzzle","Match-three puzzle: swipe parts to line up three or more, goals and moves per level."),
        Kind("shooter","Top-down survivor: left thumb moves, right thumb aims and throws, waves of enemies, upgrades."),
        Kind("runner3d","Low-poly 3D endless runner (three.js): swipe lanes, jump, collect, dodge, billboards with the shop's name."),
        Kind("quiz","Trivia with a solo mode and a party mode (2 to 4 teams pass the phone). Questions are plain text in CONFIG."),
        Kind("story","Branching story or text adventure driven by a script in CONFIG, with several endings."),
        Kind("musictoy","16-step beat maker: tap pads to build a loop, presets, tempo."),
        Kind("studio","Creative studio: draw, add stickers and meme text, save the picture as a PNG."),
        Kind("promo","Shop promo kit: a prize wheel, a scratch card and a waiting-room slideshow, with the odds shown to players worked out from the real weights."))

    /** The libraries a bot can ask for by name, and the files each one needs next to the page. */
    val libraries=mapOf(
        "phaser" to listOf("phaser.min.js"),
        "howler" to listOf("howler.min.js"),
        "three" to listOf("three.module.min.js","three.core.js"))
    private val libraryHelp=mapOf(
        "phaser" to "<script src=\"phaser.min.js\"></script> (Phaser 3: full 2D game engine with physics, tilemaps and sprites)",
        "howler" to "<script src=\"howler.min.js\"></script> (Howler: play sound files)",
        "three" to "<script type=\"importmap\">{\"imports\":{\"three\":\"three.module.min.js\"}}</script> in the head, then <script type=\"module\">import * as THREE from 'three'; ...</script> (three.js 3D; the page must be opened from the app's preview, not from a file)")

    class Made(val page:String,val files:List<String>,val config:String)

    private fun asset(context:Context,path:String):ByteArray=context.assets.open("games/"+path).use {it.readBytes()}

    /** The template's paths changed from "next to the templates folder" to "next to the page", which is where the bot's files are. */
    fun rewrite(html:String):String = html
        .replace("../juice.js","juice.js")
        .replace(",\"three/addons/\":\"../lib/addons/\"","")
        .replace("../lib/","")

    /** The CONFIG block at the top of a template: what a bot edits. */
    fun configOf(html:String):String {
        val start=html.indexOf("const CONFIG")
        if(start<0) return ""
        val end=html.indexOf("\n};",start)
        return html.substring(start,if(end<0) minOf(html.length,start+9000) else end+3).take(12_000)
    }

    fun kindOf(kind:String):Kind=kinds.firstOrNull {it.id==kind.trim().lowercase()}
        ?:throw IllegalArgumentException("kind must be one of: "+kinds.joinToString(", ") {it.id}+".")

    /** Copies [kind] into [folder] as [name] (an .html file), with the shared kit beside it. Returns what was written. */
    fun make(context:Context,folder:File,kind:String,name:String):Made {
        val chosen=kindOf(kind)
        val page=name.trim().ifBlank {chosen.id+".html"}.let {if(it.endsWith(".html",true)) it else "$it.html"}
        runCatching {Rules.textFileName(page)}.getOrElse {throw IllegalArgumentException("$page isn't a usable file name. Use something like brake-blaster.html.")}
        val html=rewrite(String(asset(context,"templates/"+chosen.id+".html"),Charsets.UTF_8))
        val written=mutableListOf<String>()
        File(folder,page).writeText(html);written+=page
        File(folder,"juice.js").writeBytes(asset(context,"juice.js"));written+="juice.js"
        if(chosen.id=="runner3d") libraries.getValue("three").forEach {f->File(folder,f).writeBytes(asset(context,"lib/$f"));written+=f}
        return Made(page,written,configOf(html))
    }

    /** Copies a game library into [folder] and says how to include it. */
    fun library(context:Context,folder:File,which:String):Pair<List<String>,String> {
        val key=which.trim().lowercase()
        val files=libraries[key]?:throw IllegalArgumentException("library must be one of: "+libraries.keys.joinToString(", ")+".")
        files.forEach {f->File(folder,f).writeBytes(asset(context,"lib/$f"))}
        return files to libraryHelp.getValue(key)
    }
}
