package com.shilapi.xcertplay.airplay

/**
 * JNI bridge to the C ChaCha20-Poly1305 used for CarPlay screen frames.
 *
 * API 17 has no platform ChaCha20-Poly1305 (Conscrypt gained it in API 31) and BouncyCastle's
 * Java cipher is the slowest step of the video receive path, so frames are decrypted in native
 * code instead. Every other encryption/decryption use in DiPlay stays on the Java implementation.
 */
object NativeChacha {
    /** True when the native library loaded; [decryptFrame] must not be called when false. */
    val available: Boolean = runCatching { System.loadLibrary("diplay_chacha"); true }.getOrDefault(false)

    /**
     * Decrypts [body] (ciphertext followed by a 16-byte tag) with a 12-byte [nonce] and the
     * header [aad]. Returns the plaintext, or null when the tag does not verify. Never modifies
     * [body].
     */
    @JvmStatic external fun decryptFrame(key: ByteArray, nonce: ByteArray, aad: ByteArray, body: ByteArray): ByteArray?
}