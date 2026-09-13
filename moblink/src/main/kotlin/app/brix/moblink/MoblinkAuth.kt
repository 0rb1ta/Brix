package app.brix.moblink

import java.security.MessageDigest
import java.util.Base64

/** Challenge-response hash, verified identical against both `datagutt/moblink-rust`
 *  (src/protocol.rs) and real Moblin (Moblin/RemoteControl/RemoteControl.swift,
 *  remoteControlHashPassword — Moblink reuses the same scheme). */
object MoblinkAuth {
    fun calculateAuthentication(password: String, salt: String, challenge: String): String {
        val hash1 = sha256("$password$salt")
        val b64Hash1 = Base64.getEncoder().encodeToString(hash1)
        val hash2 = sha256("$b64Hash1$challenge")
        return Base64.getEncoder().encodeToString(hash2)
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}
