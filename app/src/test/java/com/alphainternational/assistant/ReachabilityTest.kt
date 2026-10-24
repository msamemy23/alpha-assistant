package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Every top-level screen must be reachable from Home by taps. (The Away mode screen was built and wired to a route, but nothing on Home linked to it,
 * so the owner could not find it.) This reads the app's navigation source (the `when(screen)` in AlphaApp) and follows the links the screens
 * and their menus offer, starting from Home, exactly like a person tapping through. Add a screen without a link to it and this test names it.
 */
class ReachabilityTest {
    private val sourceDir:File = listOf(File("src/main/java/com/alphainternational/assistant"),File("app/src/main/java/com/alphainternational/assistant")).first {it.isDirectory}

    /** The text from the `{` at [open] to its matching `}`, skipping strings. */
    private fun block(text:String,open:Int):String {
        var depth=0;var i=open;var inString=false
        while(i<text.length) {
            val ch=text[i]
            if(inString) {if(ch=='\\') i++ else if(ch=='"') inString=false}
            else if(ch=='"') inString=true
            else if(ch=='{') depth++
            else if(ch=='}') {depth--;if(depth==0) return text.substring(open,i+1)}
            i++
        }
        return text.substring(open)
    }
    private val links=listOf(Regex("""\bgo\("(\w+)"\)"""),Regex("""route\s*=\s*"home/([\w/]+)""""),Regex("""route\s*=\s*"(home)""""))
    private fun targets(text:String):Set<String> = links.flatMap {r->r.findAll(text).map {it.groupValues[1].substringAfterLast('/')}.toList()}.toSet()

    /** Names of screens that cannot be reached from "home" in [ui] (the navigation source), following the composables defined in [others]. */
    fun unreachable(ui:String,others:Map<String,String>):List<String> {
        val whenAt=ui.indexOf("when(screen) {",ui.indexOf("Scaffold(containerColor"))
        require(whenAt>=0) {"The navigation `when(screen)` was not found: update this test with the navigation."}
        val whenBlock=block(ui,ui.indexOf('{',whenAt))
        val lines=whenBlock.lines()
        val header=Regex("""^(\s*)(?:"(\w+)"|else)->""")
        val starts=lines.mapIndexedNotNull {i,l->header.find(l)?.let {Triple(i,it.groupValues[1].length,it.groupValues[2].ifBlank {"home"})}}
        val indent=starts.minOf {it.second}
        val top=starts.filter {it.second==indent}
        val bodies=top.mapIndexed {n,(line,_,name)->name to lines.subList(line,if(n+1<top.size) top[n+1].first else lines.size).joinToString("\n")}
        // Helpers declared inside AlphaApp before the screens (openSignIn, signIn…): a screen that calls one reaches whatever the helper opens.
        val before=ui.substring(ui.indexOf("fun AlphaApp").coerceAtLeast(0),whenAt)
        val helpers=Regex("""(?:fun|val)\s+(\w+)\s*[(:=]""").findAll(before).filter {it.groupValues[1]!="AlphaApp"}.mapNotNull {m->
            val open=before.indexOf('{',m.range.last).takeIf {it>=0}?:return@mapNotNull null
            m.groupValues[1] to targets(block(before,open))
        }.filter {it.second.isNotEmpty()}.toMap()
        // Composables a screen shows (ChatHeader and its menu…) carry links of their own.
        fun composableTargets(body:String):Set<String> = Regex("""\b([A-Z]\w+)\(""").findAll(body).map {it.groupValues[1]}.toSet().flatMap {name->
            others.values.mapNotNull {src->Regex("""fun\s+$name\s*\(""").find(src)?.let {m->src.indexOf('{',m.range.last).takeIf {it>=0}?.let {open->targets(block(src,open))}}}.flatten()
        }.toSet()
        val graph=bodies.associate {(name,body)->name to (targets(body)+helpers.filterKeys {h->Regex("""\b$h\b""").containsMatchIn(body)}.values.flatten()+composableTargets(body))}
        val seen=mutableSetOf("home");val queue=ArrayDeque(listOf("home"))
        while(queue.isNotEmpty()) graph[queue.removeFirst()].orEmpty().forEach {if(it in graph && seen.add(it)) queue.add(it)}
        return graph.keys.filter {it !in seen}
    }

    private fun sources():Map<String,String> = sourceDir.listFiles().orEmpty().filter {it.name.endsWith(".kt")}.associate {it.name to it.readText()}

    @Test fun everyScreenCanBeReachedFromHome() {
        val all=sources()
        val missing=unreachable(all.getValue("Ui.kt"),all)
        assertTrue("These screens exist but nothing on Home (or on a screen reachable from it) opens them: $missing",missing.isEmpty())
    }
    @Test fun theKnownFeatureScreensAreAllThere() {
        val ui=sources().getValue("Ui.kt")
        listOf("chat","team","settings","away","library","marketplace","profile","builder","activity","computer").forEach {
            assertTrue("screen $it is missing from the navigation",ui.contains("\"$it\"->"))
        }
    }
    @Test fun theCheckWouldHaveCaughtTheMissingAwayLink() {
        // The navigation as it was before the Away card was added to Home: the "away" screen exists, but nothing links to it.
        val ui = """
            fun AlphaApp() {
                Scaffold(containerColor=Background) { padding ->
                    Box {
                        when(screen) {
                            "chat"->Column { ChatHeader(c,{back()},{go("profile")}) }
                            "away"->Column { AwayScreen() }
                            "profile"->MemoryScreen()
                            else->HomeScreen(c,{route="home/chat"},{go("marketplace")})
                        }
                    }
                }
            }
        """.trimIndent()
        assertEquals(listOf("away"),unreachable(ui,emptyMap()))
        val fixed=ui.replace("""{go("marketplace")})""","""{go("marketplace")},{go("away")})""")
        assertFalse("away" in unreachable(fixed,emptyMap()))
    }
    @Test fun linksFromMenusAndHelpersCount() {
        val ui = """
            fun AlphaApp() {
                fun openSignIn(url:String) { go("signin") }
                Scaffold(containerColor=Background) { padding ->
                    when(screen) {
                        "chat"->Column { ChatHeader(c,{go(it)}); Bot(c) { url -> openSignIn(url) } }
                        "activity"->Tasks()
                        "signin"->SignIn()
                        else->HomeScreen(c,{route="home/chat"})
                    }
                }
            }
        """.trimIndent()
        val menu = "fun ChatHeader(c:Controller,go:(String)->Unit) { Item(onClick={go(\"activity\")}) }"
        assertEquals(emptyList<String>(),unreachable(ui,mapOf("GrokUi.kt" to menu)))
        assertEquals(listOf("activity"),unreachable(ui,emptyMap()))
    }
}
