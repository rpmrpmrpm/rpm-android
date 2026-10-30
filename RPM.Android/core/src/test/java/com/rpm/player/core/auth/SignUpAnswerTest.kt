package com.rpm.player.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** How an answer of `auth/register`, `auth/confirm` or `auth/resend-code` is read, as the iPhone's `SignUpAnswer`. */
class SignUpAnswerTest {
    @Test fun aSuccessIsDone() {
        assertEquals(SignUpAnswer.Done, SignUpAnswer.read(200, """{"status":"confirm_required","email":"a@b.c"}"""))
        assertEquals(SignUpAnswer.Done, SignUpAnswer.read(204, ""))
    }

    /** The service's English sentence is not kept: the screen says its own. */
    @Test fun aRefusalCarriesTheServicesCode() {
        assertEquals(
            SignUpAnswer.Refused("already_registered"),
            SignUpAnswer.read(400, """{"error":"An account with this email already exists.","code":"already_registered"}"""),
        )
    }

    @Test fun aRefusalWithNoCodeIsRefused() {
        assertEquals(SignUpAnswer.Refused("refused"), SignUpAnswer.read(400, """{"error":"email is required."}"""))
        assertEquals(SignUpAnswer.Refused("refused"), SignUpAnswer.read(429, "<html>Too Many Requests</html>"))
        assertEquals(SignUpAnswer.Refused("refused"), SignUpAnswer.read(400, """["code"]"""))
    }

    @Test fun aServerErrorIsFailedWithItsStatus() {
        assertEquals(SignUpAnswer.Failed(502), SignUpAnswer.read(502, "<html>Bad Gateway</html>"))
    }
}
