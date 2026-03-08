package com.amll.droidmate.data.network

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteaseEapiCrypto {

    private const val EAPI_KEY = "e82ckenh8dichen8"

    fun prepareEapiParams(urlPath: String, paramsJson: String): String {
        val message = "nobody${urlPath}use${paramsJson}md5forencrypt"
        val digest = md5Hex(message.toByteArray())
        val payload = "${urlPath}-36cd479b6b5-${paramsJson}-36cd479b6b5-${digest}"
        return aesEcbEncryptToUpperHex(payload.toByteArray(), EAPI_KEY.toByteArray())
    }

    private fun aesEcbEncryptToUpperHex(data: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return toHexUpper(cipher.doFinal(data))
    }

    private fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun toHexUpper(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (b in data) {
            out.append("%02X".format(b))
        }
        return out.toString()
    }
}
