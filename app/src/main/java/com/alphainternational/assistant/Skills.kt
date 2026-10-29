package com.alphainternational.assistant

/** Small, versioned workflows owned by this app. Web pages cannot install or alter them. */
data class WorkflowSkill(val id:String,val title:String,val purpose:String,val instructions:String) {
    fun summary()=obj("id" to id,"title" to title,"purpose" to purpose,"version" to 1)
}
object Skills {
    val builtIn=listOf(
        WorkflowSkill("product_research","Find a product","Current products, prices and working source links.","""
            Inputs: requested product, budget and store. Start with web search and web_fetch on product pages; open the browser only if a store shows prices or stock only on the rendered page.
            Read the actual product page; confirm product name, variant, displayed price, currency and canonical product link.
            Return source URL and check time. State unknown shipping, tax or availability. A listing snippet alone is not proof of stock or a final price.
            No cart, purchase or checkout. If CAPTCHA/login prevents verification, use task_need_user and preserve the task.
        """.trimIndent()),
        WorkflowSkill("gmail_draft","Write a verified email draft","A saved Gmail draft with correct recipient, subject and body.","""
            Inputs: one complete recipient, purpose/content and optional subject. Check Gmail capability; if absent use task_need_connection.
            Draft the complete message before gmail_draft. The native review shows exact content. Save only after approval.
            Use returned draft id with gmail_read_draft; compare recipient, subject, complete body and DRAFT label.
            Completion evidence must include the read-back call. Say saved draft, not sent. Sending is a separate explicit request and review.
            If the write result is uncertain, check Gmail before repeating it. Never create a second draft just to recover a missing acknowledgement.
        """.trimIndent()),
        WorkflowSkill("invoice_pdf","Make an invoice PDF","An Alpha invoice PDF made on this phone, ready to open and share.","""
            Use invoice_pdf for any invoice or invoice PDF. It needs no Supabase, account or connection and sends nothing.
            Sample: pick a sample customer and two to four realistic part/labor lines yourself, set sample=true, and do not ask questions first.
            Real invoice: use the customer, vehicle and line items the user gave; ask only for what is missing. Labor quantity is hours.
            Tax applies to parts only. Use tax_rate_percent 0 unless the user gave a rate; never invent taxes or fees.
            Return the invoice number and total. The PDF opens from Chat and can be shared from its preview or Computer → Files.
        """.trimIndent()),
        WorkflowSkill("sample_invoice","Save a sample invoice in Supabase","Only when explicitly requested: a sample Supabase record and an openable PDF.","""
            Use only when the user explicitly asks to save the sample in Supabase. Otherwise use invoice_pdf.
            Inputs: sample customer and line items with kind part/labor, description, quantity and unit_price in USD.
            Check Supabase project access. If absent, offer invoice_pdf now and use task_need_connection for supabase only if the user still wants the database save. Use supabase_sample_invoice, not arbitrary model SQL.
            It reads the shop metadata, validates decimal amounts, uses the existing schema, creates a deterministic sample Draft record,
            independently reads it back and generates PDF, HTML and JSON. Return invoice number, amounts and saved filenames.
            Open the PDF from the delivered artifact. Sample tax is illustrative; this workflow does not issue a bill or contact customers.
            A local markdown invoice does not satisfy a requested database save. An uncertain database write needs reconciliation, not another insert.
        """.trimIndent()),
        WorkflowSkill("calendar_plan","Check and plan appointments","Current calendar evidence and a reviewed event when requested.","""
            Check Calendar capability, list permitted calendars, read events for the requested time range including timezone.
            Use live availability; never treat remembered appointments as current. Explain conflicts before creating an event.
            If creation is requested, preview the exact title, calendar, start/end and description with native review.
            Read events again for that range to verify the new id and times. No guest invitation is supported by calendar_create.
        """.trimIndent()),
        WorkflowSkill("important_updates","Important customer updates","Useful alerts with duplicate suppression.","""
            Use only granted Gmail/Calendar services. Read recent relevant customer inquiries, imminent appointments and material changes.
            Do not notify on every message. Base event_key on source id plus revision or time. Include what changed, why it matters and source.
            notify_important saves an update and reports whether Android displayed a notification. Keep unchanged checks quiet.
            Do not contact customers, edit calendars or publish content during monitoring. Background checks may be delayed by Android.
        """.trimIndent()),
        WorkflowSkill("vercel_preview","Check a Vercel preview","Reviewed preview deployment with an observed final state.","""
            Check Vercel capability and allowed project ids. Read deployments/build logs to diagnose existing problems first.
            Create a preview only for the user's specified project and GitHub branch, after native review. Never claim production deployment.
            Use returned deployment id with vercel_deployment and vercel_logs. Report READY with its URL only after that state is observed.
            BUILDING is in progress; ERROR is failure. Existing hosting charges may apply. Do not repeat an uncertain deployment request.
        """.trimIndent()),
        WorkflowSkill("social_draft","Prepare a social post","Finished content ready for the user's personal social app.","""
            The user's Facebook/Instagram accounts are personal profiles. Prepare finished text/media captions in a workspace file.
            Read back the file, then social_prepare gives the user the Share handoff. Report draft ready, never posted.
            Publishing to a business Page/professional Instagram account would require a separate supported Meta integration and grant.
        """.trimIndent()),
        WorkflowSkill("daily_brief","Daily brief (chief of staff)","A short morning brief: today's schedule, what needs a reply, and the top things to do.","""
            Use only connected services; say which ones were not connected instead of stopping.
            Calendar: today's events with times and anything needing prep. Gmail: search newer_than:1d, read only messages that look like customers, leads or decisions.
            Google Tasks: open items due today or overdue. Alpha Tasks: anything Paused, Needs you or Needs connection.
            Reply in under 15 lines: Today, Needs a reply, Top 3 to do. Never send, accept, delete or change anything during a brief.
            If the user wants this every day, offer routine_create (for example weekdays at 07:30 with report=true).
        """.trimIndent()),
        WorkflowSkill("web_research","Research a question","A sourced answer or short report from several current web pages.","""
            Use web search, then read at least three original sources with web_fetch (not just result snippets).
            Prefer official, primary and recent sources. Note the date checked. Separate facts from opinions and say what could not be confirmed.
            Short questions: answer in chat with source links. Longer requests: write a report with document_pdf (headings, bullets, a Sources list).
        """.trimIndent()),
        WorkflowSkill("repair_estimate","Customer repair estimate","An estimate PDF for a customer's vehicle repair.","""
            Inputs: customer, vehicle (year make model, mileage if known) and each part and labor line with prices.
            Use only prices the user gives or a saved pricing skill/memory; never invent prices, taxes or fees. Ask for any missing price.
            Use invoice_pdf with document_type estimate (sample=false for a real customer). Labor quantity is hours.
            Report the estimate number and estimated total; the PDF can be shared from Chat. Offer phone_action text or email to send it.
        """.trimIndent()),
        WorkflowSkill("teach_skill","Teach Alpha a new skill","Turn a process the user explains or just walked through into a saved, reusable skill.","""
            Ask the user to describe the task once, one step at a time, or reuse the steps just completed in this conversation.
            Capture inputs, ordered steps, which tools/sites to use, rules (what never to do without approval) and what the finished result looks like.
            Write clear numbered instructions another assistant could follow. Use a short lowercase id like weekly_specials.
            Save with skill_save; the user reviews it on screen. Then offer to run it now or put it on a schedule with routine_create.
        """.trimIndent()),
        WorkflowSkill("photo_help","Look at a photo","Explain what's in a photo the owner attached: a part, damage, a label, a receipt, a document or anything else.","""
            Look carefully at the attached photo (file_view if it is in files). Describe what you see, read any text, model or part numbers exactly,
            and answer the owner's question. Say how sure you are. For vehicles, safety or health, flag anything that needs a professional to check in person.
            If useful, search the web for the part number or product and give links.
        """.trimIndent()),
        WorkflowSkill("data_analysis","Crunch numbers or a spreadsheet","Totals, comparisons and tables from numbers, a CSV or a pasted list.","""
            Put the data in a CSV (file_write) or read an existing file. Use run_code for every calculation; never do arithmetic in your head.
            Save results with output[...] (for example summary.csv). Explain the answer in plain words with the key numbers, then offer a PDF report with document_pdf.
        """.trimIndent()),
        WorkflowSkill("trip_planning","Plan a trip or outing","A day-by-day plan with times, places, costs and links.","""
            Ask only for missing essentials (where, dates, budget, who is going). Search the web for current hours, prices, travel times and reviews.
            Build a day-by-day plan with times, addresses and links; total the costs with run_code. Offer it as a PDF and offer calendar events (with approval).
            Never book or pay; give booking links instead.
        """.trimIndent()),
        WorkflowSkill("shopping_compare","Compare products and prices","The best options for the budget with current prices and links.","""
            Clarify budget and must-haves only if missing. Search the web and read real product pages for 3 to 5 options. Compare price, key specs, ratings and
            warranty in a table; recommend one with the reason. Prices change: say when you checked. Never add to cart or buy.
        """.trimIndent()),
        WorkflowSkill("price_watch","Watch a price or a page","A routine that checks a page and speaks up only when something changes.","""
            Confirm the page and what counts as a change (price below X, back in stock, new post). Read it once now with web_fetch and note the current value in memory.
            Create a routine (routine_create, report=false) whose prompt reads the page with web_fetch, compares with the saved value, updates memory,
            and uses notify_important only when the condition is met.
        """.trimIndent()),
        WorkflowSkill("write_edit","Write or polish anything","A clear letter, post, review reply, bio, flyer text or email in the owner's voice.","""
            Ask for the purpose and audience only if unclear. Write it short and natural in the owner's voice (check memory for tone).
            Offer two versions when tone matters. Save longer pieces as a file or PDF. Never send or post; use phone_action, gmail_draft or social_prepare hand-offs.
        """.trimIndent()),
        WorkflowSkill("meeting_prep","Prepare for a meeting or call","A one-page brief: who, what they want, history, and talking points.","""
            Read the calendar event if connected, recent emails with the person and any memory. Search the web for the company or person when helpful.
            Write: who and why, what happened before, what they likely want, three talking points, questions to ask, and what to bring.
        """.trimIndent()),
        WorkflowSkill("expense_summary","Summarize spending","A weekly or monthly summary of expenses from receipts, emails or a sheet.","""
            Collect amounts from attached receipt photos, Gmail receipts or a spreadsheet. Put them in a CSV and total by category with run_code.
            Flag duplicates, unusual charges and missing receipts. Save summary.csv and offer a PDF. Never pay or move money.
        """.trimIndent()),
        WorkflowSkill("sales_outreach","Find and contact leads","A short list of likely customers with tailored outreach drafts.","""
            Clarify the offer and ideal customer. Search the web for real prospects (businesses, fleets, neighbors) with public contact details.
            Score them for fit in a table, then draft a short personal message for the top ones. Drafts only: gmail_draft or phone_action, never send automatically.
        """.trimIndent()),
        WorkflowSkill("talent_scout","Hire someone","A job post, where to share it, screening questions and a candidate shortlist.","""
            Ask for the role, pay range and must-haves. Write a clear job post, list where to post it, and write 5 screening questions with what good answers sound like.
            When the owner shares applicants, compare them against the must-haves in a table. Never contact candidates without approval.
        """.trimIndent()),
        WorkflowSkill("social_media","Run social media","A week of posts with captions and a posting plan.","""
            Check memory for the brand voice and offers. Draft a week of posts (idea, caption, hashtags, best time), saved as a file.
            Use social_prepare to hand each post to the owner's social app. Never claim something was posted.
        """.trimIndent()),
        WorkflowSkill("learn_topic","Learn something","A plain-language explanation, a short lesson plan and a quick quiz.","""
            Explain the topic simply with an everyday example, then deeper detail if asked. Search the web for current or technical facts and cite them.
            Offer a 5-question quiz and a one-page PDF summary.
        """.trimIndent()),
        WorkflowSkill("customer_message","Text or email a customer","A ready-to-send customer text or email the owner sends with one tap.","""
            Inputs: customer name and phone or email, and what to say. Use contacts_read when Contacts is connected and only a name is given.
            Write a short, friendly message signed ${Profile.signature} unless told otherwise.
            Use phone_action text (or email) to open the phone's app with the message filled in. The owner taps Send; never claim it was sent.
        """.trimIndent())
    )+StartSkills.all+ReachSkills.all
    private val idPattern=Regex("[a-z][a-z0-9_]{1,39}")
    fun all(user:List<UserSkill>)=builtIn.map {it.summary().put("source","built-in")}+user.map {
        obj("id" to it.id,"title" to it.title,"purpose" to it.purpose,"source" to "taught by you")
    }
    fun read(id:String,user:List<UserSkill> = emptyList()):org.json.JSONObject {
        user.firstOrNull {it.id==id}?.let {return obj("id" to it.id,"title" to it.title,"purpose" to it.purpose,"instructions" to it.instructions,"source" to "Skill the owner taught Alpha")}
        return builtIn.firstOrNull {it.id==id}?.let {it.summary().put("instructions",it.instructions).put("source","Alpha Assistant built-in workflow")}
            ?:error("Unknown workflow. Use skills_list to find an installed skill.")
    }
    /** Validates a skill the owner asked Alpha to save. Built-in workflows cannot be overwritten. */
    fun taught(id:String,title:String,purpose:String,instructions:String,now:Long=System.currentTimeMillis()):UserSkill {
        val key=id.trim().lowercase()
        require(key.matches(idPattern)) {"Use a short lowercase skill id with letters, numbers or underscores, like weekly_specials."}
        require(builtIn.none {it.id==key}) {"That id belongs to a built-in skill. Choose another id."}
        require(title.isNotBlank() && title.length<=60) {"Give the skill a title under 60 characters."}
        require(purpose.length<=200) {"Keep the purpose under 200 characters."}
        require(instructions.isNotBlank() && instructions.length<=6000) {"Skill instructions must be 1 to 6,000 characters."}
        return UserSkill(key,title.trim(),purpose.trim(),instructions.trim(),now)
    }
}
