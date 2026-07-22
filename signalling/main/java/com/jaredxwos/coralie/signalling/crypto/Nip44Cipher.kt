package com.jaredxwos.coralie.signalling.crypto

interface Nip44Cipher {
    fun encrypt(plaintext: String): String
    fun decrypt(payload: String): Result<String>
}