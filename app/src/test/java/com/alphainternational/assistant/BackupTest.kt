package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val pass="correct horse battery".toCharArray()
    private val fast=1_000
    private val bots=listOf(Bot("Alpha","Runs the shop",rules="Keep texts short",skills=listOf("weekly_specials")),Bot("Personal","My life"))
    private val memories=listOf(Memory("m1","Labor rate","\$120 an hour","You said",5,"Alpha",kind="preference"),
        Memory("m2","Trip","Likes Galveston","Learned while working",9,"Personal",private=true,kind="habit"))
    private val skills=listOf(UserSkill("weekly_specials","Weekly specials","post specials","1. pick\n2. post",7))
    private val routines=listOf(Routine("r1","Morning brief","Brief me",24,"Alpha","08:00","weekdays",true))
    private val tools=listOf(CustomTool("calc","Calc","adds","code",listOf("a","b"),"return input.a+input.b",3))
    private val tasks=(1..3).map {TaskRecord("t$it","Title $it","Prompt $it","Completed","done",1000L+it,history="[{\"big\":\"working notes\"}]",trace=listOf("x"),effects="{\"a\":1}",
        workspace="Alpha",digest="Asked: $it",evidence="[{\"url\":\"https://x\"}]")}
    private val messages=(1..5).map {Message("g$it",if(it%2==1) "user" else "assistant","chat text $it","t${1+it%3}",bot="Alpha",at=2000L+it,evidence="[{\"shot\":\"a.png\"}]")}
    private val configs=mapOf(
        "chatgpt" to obj("access_token" to "secret-access","refresh_token" to "secret-refresh","email" to "o@x.com"),
        "google" to obj("connected" to true,"access_token" to "ya29.abc","scopes" to arr(listOf("gmail"))),
        "supabase_management" to obj("token" to "sbp_abcdefabcdefabcdef","project" to "p"),
        "vercel" to obj("token" to "vcp_abcdefabcdefabcdef"),"telnyx" to obj("api_key" to "KEY123"),"mcp_servers" to obj("notion" to obj("token" to "t")),
        "voice_cloud" to obj("provider" to "elevenlabs","key" to "xi-secret","key_openai" to "sk-abcdefabcdefabcdef"),
        "gifs" to obj("off" to true),"voice" to obj("style" to "Expressive"),
        "cases" to obj("c1" to obj("title" to "Brake job","owner_token" to "hidden-here","notes" to "Maria","note_with_secret" to "Bearer abcdef123456789"),"next" to 2),
        "followups" to arr(listOf(obj("when" to 5))).let {obj("list" to it)},"empty_after_scrub" to obj("api_key" to "x"))

    private fun content(options:Backup.Options=Backup.Options()) = Backup.collect(1_760_000_000_000L,"0.15.1",options,AiSettings(learn=false).json(),bots,memories,skills,routines,tools,
        listOf(Learning.Blocked("Alpha","Maria drives a Civic",1)),configs,tasks,messages,mapOf("Alpha" to mapOf("notes.txt" to "note body","big.txt" to "x".repeat(Backup.MAX_FILE_CHARS+1))))

    @Test fun theFileRoundTripsWithTheRightPassphrase() {
        val text=content().toString()
        val file=Backup.seal(text,pass,fast)
        assertEquals(text,Backup.open(file,pass))
        assertTrue(file.copyOfRange(0,7).contentEquals("ALPHABK".toByteArray()))
        assertEquals(Backup.FORMAT,file[7].toInt())
    }
    @Test fun theFileIsLockedNotJustHidden() {
        val file=Backup.seal(content().toString(),pass,fast)
        val raw=file.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("Labor rate") || raw.contains("Alpha") || raw.contains("alpha-backup"))
        // Two files of the same content never look alike (fresh salt and IV each time).
        assertFalse(file.contentEquals(Backup.seal(content().toString(),pass,fast)))
    }
    @Test fun aWrongPassphraseOrAChangedFileIsRefusedInPlainWords() {
        val file=Backup.seal(content().toString(),pass,fast)
        val wrong=assertThrows(Backup.BackupProblem::class.java) {Backup.open(file,"wrong passphrase".toCharArray())}
        assertTrue(wrong.message!!.contains("passphrase") && !wrong.message!!.contains("Exception"))
        // One flipped bit anywhere after the header, in the header's settings, or in the sealed part is caught.
        listOf(file.size-1,file.size/2,50,20,13,9).forEach {at->
            val bad=file.copyOf();bad[at]=(bad[at].toInt() xor 1).toByte()
            assertThrows("byte $at",Backup.BackupProblem::class.java) {Backup.open(bad,pass)}
        }
        // A cut-off file.
        assertThrows(Backup.BackupProblem::class.java) {Backup.open(file.copyOf(file.size-5),pass)}
    }
    @Test fun otherFilesAndNewerBackupsAreRecognised() {
        assertEquals("That file isn't an Alpha backup.",assertThrows(Backup.BackupProblem::class.java) {Backup.open("hello, I am a text file".toByteArray().copyOf(120),pass)}.message)
        assertEquals("That file isn't an Alpha backup.",assertThrows(Backup.BackupProblem::class.java) {Backup.open(ByteArray(3),pass)}.message)
        val file=Backup.seal(content().toString(),pass,fast).copyOf()
        file[7]=(Backup.FORMAT+1).toByte()
        assertTrue(assertThrows(Backup.BackupProblem::class.java) {Backup.open(file,pass)}.message!!.contains("newer Alpha"))
    }
    @Test fun aShortPassphraseIsNotAccepted() {
        assertThrows(IllegalArgumentException::class.java) {Backup.seal("x","short".toCharArray(),fast)}
    }
    @Test fun noSignInOrKeyEverGoesIntoABackup() {
        val text=content().toString()
        listOf("secret-access","secret-refresh","ya29.abc","sbp_abcdef","vcp_abcdef","KEY123","xi-secret","sk-abcdef","hidden-here","Bearer abcdef","o@x.com").forEach {
            assertFalse("$it must not be in the backup",text.contains(it))
        }
        val inside=JSONObject(text).getJSONObject("configs")
        listOf("chatgpt","google","supabase_management","vercel","telnyx","mcp_servers","voice_cloud").forEach {assertFalse("$it left out",inside.has(it))}
        // Harmless settings, and other parts of Alpha's own saved data (cases, follow-ups), are kept, minus any field that looks like a key.
        assertTrue(inside.getJSONObject("gifs").getBoolean("off"))
        assertEquals("Expressive",inside.getJSONObject("voice").getString("style"))
        assertEquals("Maria",inside.getJSONObject("cases").getJSONObject("c1").getString("notes"))
        assertEquals(2,inside.getJSONObject("cases").getInt("next"))
        assertFalse(inside.getJSONObject("cases").getJSONObject("c1").has("owner_token"))
        assertFalse(inside.has("empty_after_scrub"))
        assertEquals(5,inside.getJSONObject("followups").getJSONArray("list").getJSONObject(0).getInt("when").let {if(it==5) 5 else 0})
    }
    @Test fun aBackupFileFromElsewhereCannotPutSignInsBack() {
        val hostile=content().also {
            it.getJSONObject("configs").put("chatgpt",obj("access_token" to "attacker-token")).put("vercel",obj("token" to "attacker-vercel"))
            it.getJSONObject("configs").put("cases",obj("c1" to obj("api_key" to "attacker-key","title" to "ok")))
        }
        val parsed=Backup.parse(hostile.toString())
        assertFalse(parsed.configs.containsKey("chatgpt") || parsed.configs.containsKey("vercel"))
        assertFalse(parsed.configs.getValue("cases").toString().contains("attacker"))
    }
    @Test fun secretLookingNamesAreCaught() {
        listOf("chatgpt","google","vercel","voice_cloud","my_api_key","session_cookie","oauth_token","password_hint").forEach {assertTrue(it,Backup.isSecretConfig(it))}
        listOf("gifs","voice","sms","background","cases","goals","followups","triggers","backup").forEach {assertFalse(it,Backup.isSecretConfig(it))}
    }
    @Test fun theChatsAndFilesAreOptionalAndCompact() {
        val without=content(Backup.Options(history=false,files=false))
        assertEquals(0,without.getJSONArray("messages").length());assertEquals(0,without.getJSONArray("tasks").length());assertEquals(0,without.getJSONObject("files").length())
        val full=content(Backup.Options(history=true,files=true))
        assertEquals(5,full.getJSONArray("messages").length());assertEquals(3,full.getJSONArray("tasks").length())
        // Past jobs lose their long working notes and screenshots, but keep what they asked and found.
        val job=TaskRecord.parse(full.getJSONArray("tasks").getJSONObject(0))
        assertEquals("[]",job.history);assertEquals("",job.evidence);assertTrue(job.trace.isEmpty());assertEquals("Asked: ${job.id.last()}",job.digest)
        assertEquals("",Message.parse(full.getJSONArray("messages").getJSONObject(0)).evidence)
        // Only small text files go in.
        val files=full.getJSONObject("files").getJSONObject("Alpha")
        assertEquals("note body",files.getString("notes.txt"));assertFalse(files.has("big.txt"))
    }
    @Test fun aHugeHistoryIsCutFromTheOldEndToFit() {
        val many=(1..6000).map {Message("x$it","assistant","word ".repeat(30)+it,"t1",bot="Alpha",at=it.toLong())}
        val content=Backup.collect(1L,"v",Backup.Options(),JSONObject(),bots,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyMap(),tasks,many,emptyMap())
        val kept=content.getJSONArray("messages")
        assertTrue(kept.length()<=Backup.MAX_MESSAGES)
        assertEquals("x6000",kept.getJSONObject(kept.length()-1).getString("id"))
    }
    @Test fun whatIsInsideIsDescribedInPlainWords() {
        val lines=Backup.summary(content(Backup.Options(history=true,files=true)),java.time.ZoneId.of("UTC"))
        assertTrue(lines[0].startsWith("Made on Oct 9, 2025") && lines[0].contains("Alpha 0.15.1"))
        val all=lines.joinToString("\n")
        assertTrue(all.contains("2 bots") && all.contains("2 memories") && all.contains("1 skill") && all.contains("1 routine") && all.contains("1 tool your bots made"))
        assertTrue(all.contains("5 chat texts") && all.contains("3 past jobs") && all.contains("1 text file"))
        assertTrue(all.contains("sign in") || all.contains("sign-ins"))
        listOf("JSON","token","API","endpoint").forEach {assertFalse(it,all.contains(it))}
    }
    @Test fun parsingKeepsEverythingAndSkipsDamagedEntries() {
        val text=content(Backup.Options(history=true,files=true))
        text.getJSONArray("memories").put(JSONObject().put("oops",1))
        val parsed=Backup.parse(text.toString())
        assertEquals(2,parsed.bots.size);assertEquals(2,parsed.memories.size);assertEquals(1,parsed.skills.size);assertEquals(1,parsed.routines.size);assertEquals(1,parsed.tools.size)
        assertEquals("m2",parsed.memories[1].id);assertTrue(parsed.memories[1].private)
        assertEquals(5,parsed.messages.size);assertEquals("Maria drives a Civic",parsed.blocked.single().text)
        assertEquals("note body",parsed.files.getValue("Alpha").getValue("notes.txt"))
        assertThrows(Backup.BackupProblem::class.java) {Backup.parse("""{"kind":"something-else"}""")}
        assertThrows(Backup.BackupProblem::class.java) {Backup.parse("not json")}
    }
    @Test fun addingKeepsWhatIsThereAndFillsInWhatIsMissing() {
        val here=listOf(Memory("m1","Labor rate","\$130 an hour (newer)","You said",50,"Alpha"),Memory("keep","Mine","only here","You",1,"Alpha"))
        val there=listOf(Memory("m1","Labor rate","\$120 an hour","You said",5,"Alpha"),Memory("m3","New","from backup","You",2,"Alpha"),Memory("gone","Other","bot is gone","You",2,"Deleted bot"))
        val merged=Backup.mergeMemories(here,there,setOf("Alpha","Personal"))
        assertEquals(setOf("m1","keep","m3"),merged.map {it.id}.toSet())
        assertEquals("\$130 an hour (newer)",merged.first {it.id=="m1"}.text)
        // A newer note in the backup does win.
        assertEquals("fresh",Backup.mergeMemories(here,listOf(Memory("m1","Labor rate","fresh","You",99,"Alpha")),setOf("Alpha")).first {it.id=="m1"}.text)
        val s1=UserSkill("a","A","p","old",1);val s2=UserSkill("a","A","p","new",2)
        assertEquals("new",Backup.mergeSkills(listOf(s1),listOf(s2)).single().instructions);assertEquals("new",Backup.mergeSkills(listOf(s2),listOf(s1)).single().instructions)
        val m=Backup.mergeMessages(listOf(Message("a","user","1","t",at=5)),listOf(Message("a","user","1","t",at=5),Message("b","user","0","t",at=1)))
        assertEquals(listOf("b","a"),m.map {it.id})
        assertEquals(listOf("t1","t2"),Backup.mergeTasks(listOf(tasks[0].copy(id="t1")),listOf(tasks[0].copy(id="t1"),tasks[1].copy(id="t2"))).map {it.id})
    }
    @Test fun runningWorkIsNotRestoredAsRunning() {
        assertEquals("Paused",Backup.compactTask(tasks[0].copy(status="Running")).status)
        assertEquals("Completed",Backup.compactTask(tasks[0]).status)
    }
}
