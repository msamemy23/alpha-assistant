package com.alphainternational.assistant

/** How bots send texts, as the owner chose in Settings: from the phone, through Google Messages for web, or ask each time. */
object Texting {
    val choices=linkedMapOf("phone" to "My phone","google_web" to "Google Messages web","ask" to "Ask me each time")
    fun label(choice:String)=choices[choice]?:choices.getValue("phone")

    private const val PHONE="To text someone, use sms_send: it sends from the owner's own number and shows in their Messages app."
    /** Just enough for the default setting: the long browser steps are only included when the owner picked Google Messages web. */
    private const val WEB_SHORT="If the owner asks to text through Google Messages web, use the browser: open https://messages.google.com/web/conversations; if it isn't paired, tell them to sign in there (Computer → Browser) and confirm the pairing on the phone, then Start chat, enter the number, type the message and tap Send."
    private const val WEB="To text someone, the owner chose Google Messages for web in Alpha's browser (Computer → Browser):\n" +
        "1. browser_open https://messages.google.com/web/conversations and read it.\n" +
        "2. If it shows the welcome page (Sign in, Pair with QR code), it isn't paired yet. Stop and tell the owner, in plain words: open Computer → Browser, tap Sign in, " +
        "sign in to Google there, then confirm the pairing on the phone (the Messages app shows a notice with matching pictures to tap). QR-code pairing cannot work " +
        "because the code is on the same phone that would scan it. Use task_need_user, then continue when they say done.\n" +
        "3. Paired: tap Start chat, type the phone number in the To field, choose it, type the message in the message box with browser_fill, " +
        "then tap Send (the owner approves). Check page_now shows the message in the conversation.\n" +
        "If Google blocks the sign-in (\"This browser or app may not be secure\") or the page keeps failing, say so once and offer sms_send from the phone instead."
    private const val ASK="Before sending a text, ask the owner once whether to send it from their phone (sms_send) or through Google Messages web in Alpha's browser, " +
        "unless they already said. Then follow that way:\n"

    fun instructions(choice:String)=when(choice) {
        "google_web"->WEB
        "ask"->ASK+"- Phone: "+PHONE+"\n- Google Messages web: "+WEB
        else->PHONE+" "+WEB_SHORT
    }
}
