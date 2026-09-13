package app.brix.moblink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MoblinkAuthTest {

    // Vectors computed independently in Python (hashlib.sha256 + base64),
    // not derived from the Kotlin implementation under test.
    @Test
    fun `matches independently computed vector 1`() {
        val result = MoblinkAuth.calculateAuthentication("1234", "testsalt", "testchallenge")
        assertEquals("hMCXk9HU8hc9MRtSSS0bK7pOF9oc9gNtCCnR3F8nG10=", result)
    }

    @Test
    fun `matches independently computed vector 2`() {
        val result = MoblinkAuth.calculateAuthentication("hunter2", "abc", "xyz")
        assertEquals("44gZHD/vLVj7Kq3oNHv+P6iH8Di07kDy350ZiQFZU88=", result)
    }

    @Test
    fun `different passwords produce different hashes`() {
        val a = MoblinkAuth.calculateAuthentication("password1", "salt", "challenge")
        val b = MoblinkAuth.calculateAuthentication("password2", "salt", "challenge")
        assertNotEquals(a, b)
    }

    @Test
    fun `is deterministic for the same inputs`() {
        val a = MoblinkAuth.calculateAuthentication("pw", "salt", "challenge")
        val b = MoblinkAuth.calculateAuthentication("pw", "salt", "challenge")
        assertEquals(a, b)
    }
}
