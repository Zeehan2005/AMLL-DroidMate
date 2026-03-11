package com.amll.droidmate.data.network

import android.util.Base64
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * 酷狗音乐 KRC 歌词解密工具
 * 参考: https://github.com/apoint123/unilyric/tree/main/lyrics_helper_rs/src/providers/kugou
 */
object KugouDecrypter {
    
    // 固定的 16 字节解密密钥
    private val KRC_DECRYPT_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69
    )
    
    /**
     * 解密 KRC 歌词
     * 
     * 流程：
     * 1. Base64 解码
     * 2. 移除前 4 字节（"krc1" 头）
     * 3. XOR 解密（16 字节密钥循环）
     * 4. Zlib 解压缩
     * 5. 转换为 UTF-8 字符串
     */
    fun decryptKrc(encryptedBase64: String): String? {
        return try {
            // Step 1: Base64 解码
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            Timber.d("KRC encrypted data length: ${encryptedBytes.size}")
            
            // 检查最小长度（需要至少 4 字节 header）
            if (encryptedBytes.size < 4) {
                Timber.e("KRC encrypted data too short: ${encryptedBytes.size} bytes")
                return null
            }
            
            // Step 2: 移除前 4 字节头（"krc1"）
            val dataToDecrypt = encryptedBytes.drop(4).toByteArray()
            
            // Step 3: XOR 解密（16 字节密钥循环）
            val decryptedData = ByteArray(dataToDecrypt.size)
            for (i in dataToDecrypt.indices) {
                decryptedData[i] = (dataToDecrypt[i].toInt() xor KRC_DECRYPT_KEY[i % KRC_DECRYPT_KEY.size].toInt()).toByte()
            }
            
            // Step 4: Zlib 解压缩
            val decompressed = decompress(decryptedData)
            
            // Step 5: 转换为 UTF-8 字符串
            String(decompressed, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.f(e, "Failed to decrypt KRC lyrics")
            null
        }
    }
    
    /**
     * Zlib 解压缩
     */
    private fun decompress(data: ByteArray): ByteArray {
        return try {
            val input = ByteArrayInputStream(data)
            val inflater = InflaterInputStream(input)
            inflater.readBytes()
        } catch (e: Exception) {
            Timber.f(e, "Failed to decompress")
            data  // 如果解压失败，返回原数据
        }
    }
}
