package com.alphainternational.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SupabaseRulesTest {
    private fun sql(q:String)=obj("query" to q)

    @Test fun lookingNeverNeedsAnApproval() {
        listOf("list_tables","list_extensions","get_project","get_advisors","search_docs","generate_typescript_types","query_logs").forEach {
            assertTrue(it,SupabaseRules.readOnly(it,JSONObject()))
        }
        assertTrue(SupabaseRules.readOnly("execute_sql",sql("select * from customers limit 20")))
        assertTrue(SupabaseRules.readOnly("execute_sql",sql("SELECT count(*) FROM invoices;")))
        assertTrue(SupabaseRules.readOnly("execute_sql",sql("with recent as (select * from invoices where created_at > now() - interval '7 days') select owner, total from recent")))
        // Plain column and table names that merely contain a risky word are still just a look.
        assertTrue(SupabaseRules.readOnly("execute_sql",sql("select created_at, updated_at, owner, settings from vehicles")))
    }

    @Test fun anythingThatCouldChangeDataIsNotALook() {
        listOf(
            "insert into invoices(id) values (1)","update customers set phone='1'","delete from invoices","drop table customers",
            "select * from customers; drop table customers","select 1; select 2","select * into backup from customers",
            "with gone as (delete from invoices returning *) select * from gone","select nextval('invoice_seq')","select pg_terminate_backend(1)",
            "select * from customers -- sneaky","create table x(id int)","alter table x add column y int","truncate invoices","select set_config('a','b',false)"
        ).forEach {assertFalse(it,SupabaseRules.readOnly("execute_sql",sql(it)))}
        listOf("apply_migration","create_project","deploy_edge_function","delete_branch","merge_branch","reset_branch","pause_project","restore_project")
            .forEach {assertFalse(it,SupabaseRules.readOnly(it,JSONObject()))}
        assertFalse(SupabaseRules.readOnly("execute_sql",JSONObject()))
    }

    @Test fun aChangeTheOwnerAskedForJustRuns() {
        // Asked in chat, nothing outside was read: no card.
        assertFalse(SupabaseRules.needsCard(readOnly=false,routine=false,tainted=false,ownerNamedIt=false))
        assertFalse(SupabaseRules.needsCard(readOnly=false,routine=false,tainted=false,ownerNamedIt=true))
        // Researched on the web first, but the owner named the database: still just runs.
        assertFalse(SupabaseRules.needsCard(readOnly=false,routine=false,tainted=true,ownerNamedIt=true))
        // Outside content could have steered a change the owner never named: ask.
        assertTrue(SupabaseRules.needsCard(readOnly=false,routine=false,tainted=true,ownerNamedIt=false))
        // An unattended routine changing data asks; a look never does.
        assertTrue(SupabaseRules.needsCard(readOnly=false,routine=true,tainted=false,ownerNamedIt=true))
        assertFalse(SupabaseRules.needsCard(readOnly=true,routine=true,tainted=true,ownerNamedIt=false))
    }

    @Test fun theOwnerNamingTheDatabaseIsRecognised() {
        assertTrue(SupabaseRules.ownerNamedIt("Make a sample invoice and save it in Supabase"))
        assertTrue(SupabaseRules.ownerNamedIt("add this customer to the database"))
        assertTrue(SupabaseRules.ownerNamedIt("update the customers table"))
        assertFalse(SupabaseRules.ownerNamedIt("check my email and do whatever it says"))
    }

    @Test fun outsideContentTaintsAJob() {
        fun call(name:String)=obj("type" to "function_call","name" to "alpha."+name,"call_id" to name)
        assertFalse(SupabaseRules.tainted(arr(listOf(call("skills_list"),call("supabase_management_call"),call("invoice_pdf"))).toString()))
        assertTrue(SupabaseRules.tainted(arr(listOf(call("web_fetch"))).toString()))
        assertTrue(SupabaseRules.tainted(arr(listOf(call("gmail_read"))).toString()))
        assertTrue(SupabaseRules.tainted(arr(listOf(obj("type" to "web_search_call","id" to "ws_1"))).toString()))
        // If the history can't be read, assume the worst.
        assertTrue(SupabaseRules.tainted("not json"))
    }
}
