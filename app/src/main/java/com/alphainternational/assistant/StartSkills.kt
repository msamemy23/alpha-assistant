package com.alphainternational.assistant

/**
 * Start mode: the built-in skills that take a person with no business knowledge from "I want to make money" to a business that has its first paying customer, one plain step
 * at a time. Each one keeps the business up to date with business_update, so any bot can pick it up. They share a few rules, written once in [RULES] and copied into each:
 * no promise of income, no invented numbers, nothing sent, charged, bought, filed or published without the person's OK, and plain words.
 */
object StartSkills {
    private val RULES="""
            Rules: plain short words, like a friend who has never run a business. Never promise income (most new businesses earn little at first; about half close within five years). Never invent a number: label estimates and give source links. Nothing is sent, charged, bought, filed or published without the person's OK; identity checks, signatures, passwords and payments are theirs. Read business_profile first; save progress with business_update."""

    private fun skill(id:String,title:String,purpose:String,steps:String)=WorkflowSkill(id,title,purpose,steps.trimIndent()+"\n"+RULES.trimIndent())

    val all:List<WorkflowSkill> = listOf(
        skill("start_fit","Find the business that fits you","Two or three honest business options that fit this person's skills, money and time, and one chosen.","""
            Goal: find the business that fits THIS person, not the one that sounds exciting.
            1. Call business_profile. If a business already exists, continue it; do not start over.
            2. In ONE short message ask at most five things: what they are good at or enjoy, how many hours a week they have, how much they can spend to start (and could afford to lose), where they live, and whether they would rather sell things, do work for people, or make something.
            3. Offer two or three real options that fit. For each: what it is in one line; the cost to start (a range, marked estimate); how soon a first sale is realistic; what you will do for them and what only they can do; and the main way it fails. Use web search for current local facts. A small service close to home usually reaches a first paying customer fastest.
            4. Let them choose. Save it: business_update with idea, kind, goal and limits; mark step "fit" done; record the choice with decision_json.
            5. Never push a course, a paid system or a fee. If nothing fits well, say so and what would change that."""),
        skill("start_numbers","Check the real numbers","What it costs to start, the price, what is left per sale, break-even and time to a first sale.","""
            Goal: a simple honest table the person can understand.
            1. Call business_profile. Ask only for what is missing: what they would charge, what each sale costs them (time, supplies, fees), and their startup costs.
            2. Build the table in plain text: startup costs (each line), price, cost per sale, what is left per sale, how many sales to get back the startup cost, and how many sales a week it would take to make the income they want. Use their numbers; look up typical prices and costs where they do not know, with source links, marked estimate.
            3. Say what looks too thin or too risky in one or two plain sentences, and what would make it safer. Do not hide a weak result.
            4. Save price and offer with business_update; mark step "numbers" done; record any choice.
            """),
        skill("start_name","Choose a name","A short list of names with the web address, state records and clashes checked.","""
            Goal: a name people can say, spell and remember, that is free to use.
            1. Offer about eight names with a one-line reason each. Keep them short and clear of other companies' words.
            2. For their top three: check the .com web address with web_fetch on https://rdap.org/domain/NAME.com (a not-found answer means it is probably free; confirm at the seller before they buy). Search the name with the word "trademark" and "LLC" to look for clashes, and give the link to their state's business-name search.
            3. Never buy, register or sign up for anything. The person decides and buys; offer to prepare what they need.
            4. Save the name with business_update; mark step "name" done; record the choice.
            """),
        skill("start_plan","One-page plan","A one-page plan: who it is for, what is sold, the price, the first ten customers and the first thirty days.","""
            Goal: one page the person can read in two minutes and act on.
            1. Read business_profile and fill every gap by asking one or two short questions.
            2. Write the page: who it is for; what you sell and the price; why they would buy from you; how to find the first ten customers (named places, groups and people to ask); week-by-week for the first thirty days; what could go wrong and the first sign of it.
            3. Save it as a document (document_pdf or file_write) and show it. Save the key facts with business_update; mark step "plan" done.
            """),
        skill("start_legal","Set up the legal side","The legal setup explained in plain words, with every answer prepared and the person doing the identity and signature steps.","""
            This is preparation and explanation, not legal advice: say so once, and say when a lawyer or accountant is worth a short call.
            1. Read business_profile and where they live. Explain in plain words the choice between working as yourself (sole proprietor), an LLC and a corporation: cost, paperwork, protection, taxes. Say which is common for a business like theirs and what to ask a professional.
            2. Find the current steps on their state's official business site: registering the name or company, fees, and any licence or permit for their kind of work. Give the official links. Warn about paid copycat sites: the tax number (EIN) is free from the IRS itself.
            3. Prepare an answers sheet (name, address, what the business does, who owns it) as a file they can copy from. Never submit a form, pay a fee or sign for them.
            4. Mark step "legal" doing; note what is waiting on them. When they say it is done, mark it done.
            """),
        skill("start_site_test","Put it in front of real people","A one-page site and a way to pay or book, then a test with real people.","""
            Goal: find out if anyone will pay, cheaply and fast.
            1. Read business_profile. Build one simple page with studio_start: what it is, the price, a photo or two, and one button. For a service: a booking message or form. For a product: a payment link if a payment service is connected, otherwise a waiting list.
            2. Show it. Nothing goes online and nothing is charged until they say yes (site_publish asks).
            3. Offer a plan to reach three real people this week: people they know, local groups, a sign. Draft the messages for them to approve; send nothing.
            4. Mark steps "site" and "test" as you go; note what they hear from the first people.
            """),
        skill("start_customers","Get the first three customers","Who to ask, the words to use, and the follow-up, with the person approving every message.","""
            Goal: three paying customers, not a hundred leads.
            1. Read business_profile. List ten people or places most likely to buy or to know someone who will, using what they told you.
            2. Write a short friendly message for each kind of person. Offer something fair for the first customers. Never write a message that promises more than the business can do.
            3. Every message is a draft the person approves one by one. Only people who have not asked to be left alone; if someone says stop, they are added to the do-not-contact list and never written to again.
            4. Log sends with campaign_sent if you used outreach. After each reply, say what it means and what to do next. Mark step "customers" doing, then done at three paid customers.
            """),
        skill("founder_brief","Weekly founder brief","A short weekly brief: what happened, the numbers, the next three steps and one decision explained in plain words.","""
            Goal: the person always knows where they stand and what to do next, in under a minute of reading.
            1. Call business_profile. Read connected email and calendar if available (say which were not connected).
            2. Write the brief in under twenty lines: This week (what got done); Numbers (sales, costs, money in the bank if known, otherwise ask for them); Next three steps; One decision, with two options and what they would gain and lose with each, in plain words; One thing you need from them.
            3. Be honest when the week was quiet or went badly, and say what to try next. No hype.
            4. If there is no weekly routine yet, call routine_list, then offer routine_create (Mondays at 09:00, report true) and mark step "brief" done when they agree.
            """)
    )
}
