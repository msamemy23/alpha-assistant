package com.alphainternational.assistant

/**
 * What is built into this copy of the app. A generic copy (any build made without an owner.properties file, and the -PfriendsBuild beta) carries nothing from
 * a particular business: no shop name, address or phone, no project IDs, no starter bots about a shop, and not even those words inside the app file
 * (they come from build settings that are empty then). Everyone who installs it starts with a clean slate; their chats, memory and sign-ins stay on their own phone.
 * A build with owner.properties puts one owner's own business details in, for that owner's own phone only.
 */
object Profile {
    val generic:Boolean get()=BuildConfig.FRIENDS || BuildConfig.SHOP_NAME.isBlank()

    val shopName:String get()=BuildConfig.SHOP_NAME
    /** The shop's name for sentences, or a general word when this copy has none. */
    val shopLabel:String get()=shopName.ifBlank {"business"}

    /** Tools that only make sense for the owner's own shop and database. */
    val hiddenTools:Set<String> get()=if(generic) setOf("invoice_pdf","supabase_sample_invoice") else emptySet()

    /** The line in every bot's instructions about who the bots work for. */
    val shopLine:String get()=if(generic) "The owner can add more bots; each bot has its own chat, memory and files."
        else "The Alpha bot works for $shopName, ${BuildConfig.SHOP_ADDRESS}, ${BuildConfig.SHOP_PHONE}. Personal is separate from the shop."

    /** What the learning step is told about the owner. */
    val ownerLine:String get()=if(generic) "The owner uses the bots for work and for personal life."
        else "The owner runs $shopName, and also uses the bots for personal life."

    /** Who the owner's messages are signed from, unless they say otherwise. */
    val signature:String get()=if(generic) "the owner's name (ask once if you don't know it)" else shopName

    /** What "important updates" watches for, per workspace. */
    fun watchTarget(workspace:String):String=if(!generic && workspace=="Alpha") shopName else "my personal life"

    /** Who the builder bot is helping. */
    val builderOwner:String get()=if(generic) "the owner of this app" else "the owner of $shopName (${BuildConfig.SHOP_ADDRESS})"

    /** Pre-filled Supabase project and organization in the sign-in form (blank for everyone but the owner). */
    val supabaseProject:String get()=BuildConfig.SUPABASE_PROJECT
    val supabaseOrganization:String get()=BuildConfig.SUPABASE_ORG

    /** An example of a standing rule shown when a bot has none. */
    val ruleExample:String get()=if(generic) "Always keep answers short" else "Always sign texts as $shopName"

    /** What the document tool is told about letterhead (the shop name, address and phone at the top of a PDF). */
    val letterheadHelp:String get()=if(generic) "letterhead is not set up in this copy: leave it false." else "letterhead=true adds the $shopName name, address and phone at the top."

    /** The bot that helps someone start and run a business (Start mode). It is made the first time the person taps "Start a business". */
    fun founderBot():Bot=Bot("Founder","Helps you start a business and run it: finds the idea that fits you, checks the numbers, sets it up and keeps you moving every week.",
        "Speak plainly and briefly, like a friend who has run a business. Read business_profile before anything else and keep it up to date with business_update. Never promise income. Never send, charge, buy, file or publish without the person's OK.")

    /** The bots a new install starts with. */
    fun starterBots():List<Bot> = if(generic) listOf(Bot("Alpha","A friendly, capable assistant for research, writing, planning and everyday questions."))
        else listOf(Bot("Alpha","Assistant for $shopName"),Bot("Personal","Personal assistant, separate from the shop"))
}
