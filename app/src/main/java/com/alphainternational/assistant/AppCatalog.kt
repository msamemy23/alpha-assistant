package com.alphainternational.assistant

/**
 * Official hosted MCP servers a phone app can connect to without partner approval (checked October 4, 2026).
 * auth: "oauth" = one-tap browser sign-in (dynamic client registration + PKCE);
 * "token" = the owner pastes a personal token, sent as a Bearer header; "url" = a personal secret link.
 */
data class AppConnector(val id:String,val name:String,val blurb:String,val url:String,val auth:String,
    val category:String,val tokenHelp:String="",val helpUrl:String="")

object AppCatalog {
    val all=listOf(
        AppConnector("zapier","Zapier","Outlook, Teams, Slack, Salesforce, QuickBooks and 9,000+ other apps. Each action uses Zapier tasks.",
            "https://mcp.zapier.com/api/v1/connect","token","Everything","In Zapier MCP, open your server's Connect tab and copy the connection token.","https://mcp.zapier.com"),
        AppConnector("notion","Notion","Search, read and update your Notion pages and databases.","https://mcp.notion.com/mcp","oauth","Notes & docs"),
        AppConnector("stripe","Stripe","Customers, invoices, payment links and subscriptions in Stripe.","https://mcp.stripe.com","oauth","Money"),
        AppConnector("paypal","PayPal","PayPal invoices, orders, payments and disputes.","https://mcp.paypal.com/mcp","oauth","Money"),
        AppConnector("expensify","Expensify","Read expenses and reports (read-only).","https://www.expensify.com/mcp/","oauth","Money"),
        AppConnector("calendly","Calendly","Availability, bookings and one-time scheduling links.","https://mcp.calendly.com","oauth","Scheduling"),
        AppConnector("todoist","Todoist","Your tasks and projects.","https://ai.todoist.net/mcp","oauth","Tasks & projects"),
        AppConnector("trello","Trello","Boards, lists and cards.","https://mcp.trello.com/v1","oauth","Tasks & projects"),
        AppConnector("atlassian","Jira & Confluence","Search and update Jira issues and Confluence pages.","https://mcp.atlassian.com/v2/mcp","oauth","Tasks & projects"),
        AppConnector("linear","Linear","Issues, projects and comments.","https://mcp.linear.app/mcp","oauth","Tasks & projects"),
        AppConnector("monday","monday.com","Boards, items and updates.","https://mcp.monday.com/mcp","token","Tasks & projects",
            "In monday.com open your avatar → Developers → My access tokens and copy your personal token.","https://monday.com"),
        AppConnector("airtable","Airtable","Bases, records and tables.","https://mcp.airtable.com/mcp","oauth","Notes & docs"),
        AppConnector("miro","Miro","Boards, sticky notes and diagrams.","https://mcp.miro.com/","oauth","Notes & docs"),
        AppConnector("granola","Granola","Your meeting notes.","https://mcp.granola.ai/mcp","oauth","Meetings"),
        AppConnector("fireflies","Fireflies","Meeting transcripts and summaries.","https://api.fireflies.ai/mcp","oauth","Meetings"),
        AppConnector("klaviyo","Klaviyo","Email and SMS marketing campaigns, flows and reports.","https://mcp.klaviyo.com/mcp","oauth","Marketing"),
        AppConnector("wix","Wix","Your Wix site, store, bookings and content.","https://mcp.wix.com/mcp","oauth","Websites"),
        AppConnector("webflow","Webflow","Your Webflow site content and pages.","https://mcp.webflow.com/mcp","oauth","Websites"),
        AppConnector("intercom","Intercom","Customer conversations, contacts and help articles.","https://mcp.intercom.com/mcp","token","Customers",
            "In Intercom open Settings → Integrations → Developer Hub, create an app and copy its access token.","https://developers.intercom.com/docs/guides/mcp"),
        AppConnector("make","Make","Run your Make scenarios, which connect to many other apps.","https://mcp.make.com","oauth","Everything"),
        AppConnector("pipedream","Pipedream","Thousands of app actions through Pipedream.","https://mcp.pipedream.net/v2","oauth","Everything"),
        AppConnector("github","GitHub","Repositories, issues and pull requests.","https://api.githubcopilot.com/mcp/","token","Developer",
            "On GitHub open Settings → Developer settings → Personal access tokens and create a fine-grained token.","https://github.com/settings/tokens"),
        AppConnector("cloudflare","Cloudflare","Your Cloudflare account, domains and sites.","https://mcp.cloudflare.com/mcp","oauth","Developer"),
        AppConnector("zoho","Zoho","Zoho CRM, Books, Mail, Desk and Projects tools you choose.","","url","Everything",
            "In Zoho MCP create a server, pick tools, then copy its Server URL. It works like a password.","https://www.zoho.com/mcp/")
    )
    fun find(id:String)=all.firstOrNull {it.id==id}
    /** Read-only tools (by the server's own annotations) can run without an approval card; anything else still asks. */
    fun readOnlyTools(tools:List<org.json.JSONObject>):List<String> = tools.filter {
        val a=it.optJSONObject("annotations")
        a?.optBoolean("readOnlyHint")==true && !a.optBoolean("destructiveHint",false)
    }.map {it.optString("name")}.filter {it.isNotBlank()}
}
