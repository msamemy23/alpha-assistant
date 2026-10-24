package com.alphainternational.assistant

import org.junit.Assert.*
import org.junit.Test

class ProjectAccessTest {
    private val project="abcdefghijklmnopqrst"
    private val token="sbp_fc_example_test_token_1234567890"

    @Test fun ManagementKeyCannotBeSentToAnArbitraryServer() {
        val config=ProjectAccess.supabase(" $project "," $token ")
        assertEquals("https://mcp.supabase.com",Rules.origin(config.getString("url")))
        assertTrue(config.getString("url").contains("project_ref=$project&"))
        assertFalse(config.getString("url").contains(token))
        assertFalse(config.getString("url").contains("skip_elicitations"))
        assertEquals(token,config.getString("token"))
        listOf("https://attacker.example","$project&read_only=false","../other-project","").forEach {
            assertThrows(IllegalArgumentException::class.java) {ProjectAccess.supabase(it,token)}
        }
    }
    @Test fun ManagementSetupRejectsServiceRoleAndUserSessionKeys() {
        listOf("sb_secret_example_server_key_1234567890","sb_publishable_example_key_1234567890","eyJheader.payload.signature",
            token+"\nInjected: header","").forEach {
            assertThrows(IllegalArgumentException::class.java) {ProjectAccess.supabase(project,it)}
        }
    }
    @Test fun VercelScopeIncludesOnlyProjectsVisibleToTheSuppliedToken() {
        assertEquals(listOf("prj_alpha"),ProjectAccess.vercelProjects(listOf("prj_alpha"),emptyList()))
        assertEquals(listOf("prj_alpha"),ProjectAccess.vercelProjects(listOf("prj_alpha","prj_other"),listOf("prj_alpha","prj_alpha")))
        assertThrows(IllegalArgumentException::class.java) {ProjectAccess.vercelProjects(listOf("prj_alpha"),listOf("prj_unavailable"))}
        assertThrows(IllegalArgumentException::class.java) {ProjectAccess.vercelProjects(emptyList(),emptyList())}
    }
    @Test fun ManagementCallsKeepHumanReviewAndTokenRedaction() {
        assertTrue(allTools.single {it.name=="supabase_management_call"}.review)
        assertFalse(allTools.single {it.name=="supabase_management_list"}.review)
        val text=Rules.redact("failed $token and vcp_example_test_token_1234567890")
        assertFalse(text.contains(token))
        assertFalse(text.contains("vcp_example"))
    }
}
