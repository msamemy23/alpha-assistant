package com.alphainternational.assistant
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SupabaseOAuthTest {
    @Test fun scopesAreSetDuringRegistrationAndOldBroadClientsAreNotReused() {
        val redirect="http://127.0.0.1:49189/supabase/callback"
        val request=SupabaseOAuth.registrationBody(redirect)
        assertEquals(SupabaseOAuth.SCOPES,request.getString("scope"))
        assertFalse(request.getString("scope").contains("secrets"))
        assertFalse(request.getString("scope").contains("projects:write"))
        val previous=obj("redirect_uri" to redirect,"client_id" to "test-id","client_secret" to "test-secret")
        assertFalse(SupabaseOAuth.canReuseRegistration(previous,redirect))
        previous.put("registration_scope",SupabaseOAuth.SCOPES)
        assertTrue(SupabaseOAuth.canReuseRegistration(previous,redirect))
        assertFalse(SupabaseOAuth.canReuseRegistration(previous,redirect+"-different"))
    }
    @Test fun rejectsStateMismatchAndDeniedAuthorization() {
        assertTrue(runCatching {SupabaseOAuth.callbackCode(mapOf("state" to "wrong","code" to "a"),"expected")}.isFailure)
        assertTrue(runCatching {SupabaseOAuth.callbackCode(mapOf("state" to "expected","error" to "access_denied"),"expected")}.isFailure)
        assertEquals("a",SupabaseOAuth.callbackCode(mapOf("state" to "expected","code" to "a"),"expected"))
    }
    @Test fun rejectsForeignIssuerAndMissingPkce() {
        val metadata=JSONObject("""{"issuer":"https://api.supabase.com","authorization_endpoint":"https://api.supabase.com/v1/oauth/authorize","token_endpoint":"https://api.supabase.com/v1/oauth/token","registration_endpoint":"https://api.supabase.com/platform/oauth/apps/register","code_challenge_methods_supported":["S256"]}""")
        SupabaseOAuth.verifyMetadata(metadata)
        assertTrue(runCatching {SupabaseOAuth.verifyMetadata(JSONObject(metadata.toString()).put("token_endpoint","https://other.example/token"))}.isFailure)
        assertTrue(runCatching {SupabaseOAuth.verifyMetadata(JSONObject(metadata.toString()).put("code_challenge_methods_supported",org.json.JSONArray("[\"plain\"]")))}.isFailure)
    }
}
