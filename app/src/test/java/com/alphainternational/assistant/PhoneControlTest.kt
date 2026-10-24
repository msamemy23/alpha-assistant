package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

/** The owner asked for bots to use any app on the phone, fast and accurately. These are the rules that make a screen readable, make a step land on the right thing, and keep it from doing harm. */
class PhoneControlTest {
    private fun node(text:String="",desc:String="",cls:String="android.widget.TextView",clickable:Boolean=false,editable:Boolean=false,hint:String="",password:Boolean=false,
        scrollable:Boolean=false,fwd:Boolean=false,l:Int=0,t:Int=0,r:Int=100,b:Int=50,viewId:String="",checkable:Boolean=false,checked:Boolean=false,kids:List<RawNode> = emptyList())=
        RawNode(text=text,desc=desc,hint=hint,cls=cls,viewId=viewId,l=l,t=t,r=r,b=b,clickable=clickable,editable=editable,scrollable=scrollable,password=password,checkable=checkable,checked=checked,canScrollForward=fwd,children=kids)

    /** A chat screen: a back button, a title, a list with two rows, a text box and a send button. */
    private fun messages():RawNode=node(cls="android.widget.FrameLayout",r=1080,b=2340,kids=listOf(
        node(desc="Back",cls="android.widget.ImageButton",clickable=true,r=120,b=120),
        node(text="Mom",l=200,r=500,b=120),
        node(cls="androidx.recyclerview.widget.RecyclerView",scrollable=true,fwd=true,t=130,b=2000,r=1080,kids=listOf(
            node(cls="android.view.ViewGroup",clickable=true,t=140,b=300,r=1080,kids=listOf(node(text="Dad",t=150,b=200),node(text="Are you coming?",t=200,b=250),node(text="2:15 PM",t=150,b=200))),
            node(cls="android.view.ViewGroup",clickable=true,t=320,b=480,r=1080,kids=listOf(node(text="Mom",t=330,b=380),node(text="Dinner at six",t=380,b=430))))),
        node(cls="android.widget.EditText",editable=true,hint="Text message",t=2100,b=2200,r=900),
        node(desc="Send",cls="android.widget.ImageButton",clickable=true,l=900,t=2100,r=1080,b=2200)))

    private fun snap(root:RawNode=messages())=PhoneScreen.build(root,"Messages","com.google.android.apps.messaging",1080,2340)
    private fun frame(vararg kids:RawNode)=node(cls="android.widget.FrameLayout",r=1080,b=2340,kids=kids.toList())

    @Test fun aScreenBecomesAShortListOfWhatAPersonSeesAndCanTouch() {
        val text=snap().text()
        assertTrue(text,text.startsWith("App: Messages (com.google.android.apps.messaging)"))
        assertTrue(text,text.contains("e1 button \"Back\""))
        // a tappable row says what is inside it once; the words inside are not listed again
        assertTrue(text,text.contains("item \"Dad · Are you coming? · 2:15 PM\""))
        assertFalse(text,text.lines().any {it=="text \"Are you coming?\""})
        assertTrue(text,text.contains("input \"Text message\" [empty]"))
        assertTrue(text,text.contains("button \"Send\""))
        assertTrue(text,text.contains("scroll") && text.contains("more below"))
        assertTrue("short enough to read in one go",text.length<1500)
    }

    @Test fun thingsOffTheScreenAndPasswordsAreLeftOut() {
        val root=frame(node(text="Visible",b=100),node(text="Far below",t=3000,b=3100),RawNode(text="Hidden",visible=false,r=100,b=100),
            node(cls="android.widget.EditText",editable=true,hint="Password",password=true,t=200,b=300))
        val text=snap(root).text()
        assertTrue(text.contains("Visible"));assertFalse(text.contains("Far below"));assertFalse(text.contains("Hidden"))
        assertTrue(text,text.contains("password: Alpha never types here"))
    }

    @Test fun aSwitchSaysWhetherItIsOn() {
        val root=frame(node(text="Wi-Fi",cls="android.widget.Switch",clickable=true,checkable=true,checked=true))
        assertTrue(snap(root).text().contains("switch \"Wi-Fi\" [on]"))
    }

    @Test fun aStepFindsTheThingByItsWordsAndNeverGuessesBetweenTwo() {
        val s=snap()
        assertEquals("send",(PhoneScreen.find(s,PhoneScreen.Target(words="send")) as PhoneScreen.Found.One).elem.label.lowercase())
        assertEquals("input",(PhoneScreen.find(s,PhoneScreen.Target(words="text message"),wantInput=true) as PhoneScreen.Found.One).elem.role)
        assertTrue(PhoneScreen.find(s,PhoneScreen.Target(words="banana")) is PhoneScreen.Found.None)
        // two buttons with the same name: the bot is told, and nth picks
        val twins=snap(frame(node(text="Delete",clickable=true,cls="android.widget.Button",t=0,b=50),node(text="Delete",clickable=true,cls="android.widget.Button",t=100,b=150)))
        assertTrue(PhoneScreen.find(twins,PhoneScreen.Target(words="delete")) is PhoneScreen.Found.Many)
        assertEquals(100,(PhoneScreen.find(twins,PhoneScreen.Target(words="delete",nth=2)) as PhoneScreen.Found.One).elem.t)
        // the exact name beats a longer one that contains it
        val both=snap(frame(node(text="Send to Mom",clickable=true,cls="android.widget.Button",t=0,b=50),node(text="Send",clickable=true,cls="android.widget.Button",t=100,b=150)))
        assertEquals("Send",(PhoneScreen.find(both,PhoneScreen.Target(words="send")) as PhoneScreen.Found.One).elem.label)
        // a tappable thing beats plain words with the same name
        val plain=snap(frame(node(text="Done",t=0,b=50),node(text="Done",clickable=true,cls="android.widget.Button",t=100,b=150)))
        assertTrue((PhoneScreen.find(plain,PhoneScreen.Target(words="done")) as PhoneScreen.Found.One).elem.interactive)
    }

    @Test fun aNumberFromEarlierStillMeansTheSameThingOnARedrawnScreen() {
        val send=snap().elems.first {it.label=="Send"}
        // a new button appears at the top, so every number shifts
        val redrawn=snap(frame(node(text="New banner",clickable=true,cls="android.widget.Button",t=0,b=40),*messages().children.toTypedArray()))
        val again=PhoneScreen.again(send,redrawn)
        assertNotNull(again)
        assertEquals("Send",again!!.label)
        assertNotEquals(send.id,again.id)
        assertNull(PhoneScreen.again(send,snap(frame())))
    }

    @Test fun theFocusedFieldOrTheOnlyFieldIsWhereTypingGoes() {
        assertEquals("Text message",PhoneScreen.focusedInput(snap())!!.label)
    }

    @Test fun moneyWipesAndSecuritySwitchesAlwaysNeedTheOwner() {
        assertEquals(PhoneSafety.Risk.MONEY,PhoneSafety.risk("Pay now"))
        assertEquals(PhoneSafety.Risk.MONEY,PhoneSafety.risk("Place order"))
        assertEquals(PhoneSafety.Risk.MONEY,PhoneSafety.risk("Buy now"))
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Add to cart"))
        assertEquals(PhoneSafety.Risk.DESTRUCTIVE,PhoneSafety.risk("Factory reset"))
        assertEquals(PhoneSafety.Risk.SECURITY,PhoneSafety.risk("Install unknown apps"))
        assertEquals(PhoneSafety.Risk.SEND,PhoneSafety.risk("Send"))
        assertEquals(PhoneSafety.Risk.SEND,PhoneSafety.risk("Post"))
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Send feedback to the developer about this long thing"))
        assertEquals(PhoneSafety.Risk.NONE,PhoneSafety.risk("Back"))
        val ask=PhonePolicy(approvals="bots",ownerAskedToSend=true)
        assertTrue(PhoneSafety.judgeTap("Pay now","com.shop",ask) is PhoneSafety.Verdict.Confirm)
        assertTrue(PhoneSafety.judgeTap("Factory reset","com.android.settings",ask) is PhoneSafety.Verdict.Confirm)
        assertTrue("an approved step goes ahead",PhoneSafety.judgeTap("Pay now","com.shop",PhonePolicy(approved=true)) is PhoneSafety.Verdict.Go)
    }

    @Test fun sendingNeedsAskOnlyWhenTheOwnerDidNotAskForIt() {
        val app="com.google.android.apps.messaging"
        assertTrue("the owner said send it",PhoneSafety.judgeTap("Send",app,PhonePolicy("bots",true)) is PhoneSafety.Verdict.Go)
        assertTrue("the screen suggested it",PhoneSafety.judgeTap("Send",app,PhonePolicy("bots",false)) is PhoneSafety.Verdict.Confirm)
        assertTrue("the owner wants to be asked about sends",PhoneSafety.judgeTap("Send",app,PhonePolicy("important",true)) is PhoneSafety.Verdict.Confirm)
        assertTrue(PhoneSafety.ownerAskedToSend("Text Mom that I am running late"))
        assertTrue(PhoneSafety.ownerAskedToSend("Reply to Dad on WhatsApp"))
        assertFalse(PhoneSafety.ownerAskedToSend("What does my screen say"))
    }

    @Test fun passwordsAndCodesAreNeverTypedAndSomeAppsAreNeverTouched() {
        assertNotNull(PhoneSafety.typingBlocked("Password","",false))
        assertNotNull(PhoneSafety.typingBlocked("Card number","",false))
        assertNotNull(PhoneSafety.typingBlocked("Enter the code","verification_code_input",false))
        assertNotNull(PhoneSafety.typingBlocked("Anything","",true))
        assertNotNull(PhoneSafety.typingBlocked("","pin_entry",false))
        assertNull(PhoneSafety.typingBlocked("Text message","message_input",false))
        assertNull(PhoneSafety.typingBlocked("Search","",false))
        assertNotNull(PhoneSafety.appBlocked("com.alphainternational.assistant",PhonePolicy()))
        assertNotNull(PhoneSafety.appBlocked("com.x8bit.bitwarden",PhonePolicy()))
        assertNull(PhoneSafety.appBlocked("com.x8bit.bitwarden",PhonePolicy(allowVaults=true)))
        assertNull(PhoneSafety.appBlocked("com.chase.sig.android",PhonePolicy()))
        assertTrue(PhoneSafety.judgeTap("Open","com.alphainternational.assistant",PhonePolicy(approved=true)) is PhoneSafety.Verdict.Block)
    }

    @Test fun stepsAreReadFromTheBotsJsonAndBadOnesAreExplained() {
        val steps=PhoneSteps.parse("""[{"do":"open","app":"Messages"},{"do":"tap","on":"New message"},{"do":"type","into":"To","text":"Mom"},{"do":"type","text":"hi","enter":true},{"do":"scroll","dir":"down"},{"do":"wait_for","on":"Sent","ms":3000},{"do":"back"}]""")
        assertEquals(listOf("open","tap","type","type","scroll","wait_for","back"),steps.map {it.op})
        assertEquals("Messages",steps[0].app)
        assertEquals("New message",steps[1].target.words)
        assertEquals("To",steps[2].target.words);assertEquals("Mom",steps[2].text)
        assertTrue(steps[3].enter && !steps[3].target.given)
        assertEquals(3000L,steps[5].ms)
        assertEquals("Send",PhoneSteps.parse("""{"do":"tap","text":"Send"}""")[0].target.words)
        assertEquals("e5",PhoneSteps.parse("""[{"do":"tap","id":"e5"}]""")[0].target.id)
        for(bad in listOf("","[]","""[{"do":"dance"}]""","""[{"do":"tap"}]""","""[{"do":"type"}]""","""[{"do":"open"}]""","""[{"do":"scroll","dir":"sideways"}]""","[1]")) {
            try {PhoneSteps.parse(bad);fail("should refuse: $bad")} catch(e:IllegalArgumentException) {assertTrue(e.message,e.message!!.length>10)} catch(e:org.json.JSONException) {}
        }
    }

    @Test fun aShareStepNeedsAnAppAndText() {
        val s=PhoneSteps.parse("""[{"do":"share","app":"Notes","text":"A poem"}]""")
        assertEquals("share",s[0].op);assertEquals("Notes",s[0].app);assertEquals("A poem",s[0].text)
        assertEquals("share text with Notes",s[0].describe())
        for(bad in listOf("""[{"do":"share","app":"Notes"}]""","""[{"do":"share","text":"x"}]""")) try {PhoneSteps.parse(bad);fail("should refuse")} catch(e:IllegalArgumentException) {}
    }

    @Test fun aBotThatKeepsFailingIsToldToChangeMethod() {
        assertTrue(PhoneControl.stuckHint("type",2).contains("share"))
        assertTrue(PhoneControl.stuckHint("tap",2).contains("screen_look"))
        assertTrue(PhoneControl.stuckHint("tap",4).contains("Stop now"))
    }

    @Test fun aJobThatUsedThePhoneIsRecognisedSoAlphaComesBack() {
        fun call(name:String)=org.json.JSONObject().put("type","function_call").put("name",name)
        assertTrue(PhoneControl.usedPhone(org.json.JSONArray().put(call("web_fetch")).put(call("screen_do"))))
        assertTrue(PhoneControl.usedPhone(org.json.JSONArray().put(call("screen_read"))))
        assertFalse(PhoneControl.usedPhone(org.json.JSONArray().put(call("web_fetch")).put(call("apps_list"))))
        assertFalse(PhoneControl.usedPhone(org.json.JSONArray()))
    }

    @Test fun aReadStepKeepsAScreenInTheMiddleOfABatch() {
        val steps=PhoneSteps.parse("""[{"do":"open","app":"Settings"},{"do":"read"},{"do":"open","app":"Clock"},{"do":"read"}]""")
        assertEquals(listOf("open","read","open","read"),steps.map {it.op})
        assertEquals("read",steps[1].describe())
    }

    @Test fun anAppIsFoundByItsNameInTheLooseWayAPersonSaysIt() {
        val apps=listOf(PhoneControl.App("Messages","com.google.android.apps.messaging"),PhoneControl.App("Messenger","com.facebook.orca"),PhoneControl.App("WhatsApp","com.whatsapp"),
            PhoneControl.App("Google Maps","com.google.android.apps.maps"),PhoneControl.App("Clock","com.google.android.deskclock"))
        assertEquals("com.whatsapp",PhoneControl.matchApps(apps,"whatsapp").single().pkg)
        assertEquals("Messages",PhoneControl.matchApps(apps,"messages").single().label)
        assertEquals(2,PhoneControl.matchApps(apps,"mess").size)
        assertEquals("Google Maps",PhoneControl.matchApps(apps,"maps").single().label)
        assertEquals("com.google.android.deskclock",PhoneControl.matchApps(apps,"com.google.android.deskclock").single().pkg)
        assertTrue(PhoneControl.matchApps(apps,"tiktok").isEmpty())
        // apps_list shows "Settings (com.android.settings)": a bot may hand it back whole
        assertEquals("com.whatsapp",PhoneControl.matchApps(apps,"WhatsApp (com.whatsapp)").single().pkg)
        assertEquals("com.google.android.deskclock",PhoneControl.matchApps(apps,"Clock (com.google.android.deskclock)").single().pkg)
    }
}
