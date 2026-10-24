package com.alphainternational.assistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SignInChecksTest {
    private val site="https://portal.telnyx.com/#/login/sign-in"

    @Test fun anEmailedSignInLinkOpensOnlyForTheSiteBeingSignedInTo() {
        assertNull(SignInLinks.problem("https://portal.telnyx.com/#/login/token?x=1",site))
        assertNull(SignInLinks.problem("https://api.telnyx.com/v2/magic/abc",site))
        assertNotNull(SignInLinks.problem("https://telnyx.com.evil.example/login",site))
        assertNotNull(SignInLinks.problem("http://portal.telnyx.com/login",site))
        assertNotNull(SignInLinks.problem("https://example.com/login",site))
    }

    @Test fun nothingOrJunkOnTheClipboardIsRefusedPolitely() {
        assertNotNull(SignInLinks.problem("",site))
        assertNotNull(SignInLinks.problem("hello there",site))
        assertNotNull(SignInLinks.problem("javascript:alert(1)",site))
    }
}
