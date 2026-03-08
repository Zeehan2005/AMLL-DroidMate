package com.amll.droidmate.data.network

import java.security.MessageDigest
import timber.log.Timber

/**
 * 酷狗音乐签名工具
 * 参考: https://github.com/apoint123/unilyric/tree/main/lyrics_helper_rs/src/providers/kugou
 */
object KugouSignature {
    
    // 常量（对应 Unilyric 的配置）
    private const val KUGOU_ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val APP_ID = "1005"
    private const val CLIENT_VER = "12569"
    
    /**
     * 生成 Android 签名
     * 
     * 签名规则：MD5(salt + sortedParams + body + salt)
     */
    fun generateSignature(params: Map<String, String>, body: String = ""): String {
        return try {
            // 构建参数字符串（需要按key排序）
            val sortedParams = params.toSortedMap()
            val paramsString = sortedParams.entries.joinToString("") { (k, v) -> "$k=$v" }
            
            // 构建待签名字符串：salt + params + body + salt
            val stringToSign = KUGOU_ANDROID_SALT + paramsString + body + KUGOU_ANDROID_SALT
            
            // 计算 MD5
            val md5 = MessageDigest.getInstance("MD5")
            val digest = md5.digest(stringToSign.toByteArray())
            
            // 转换为 16 进制字符串
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate Kugou signature")
            ""
        }
    }
    
    /**
     * 生成 Device Mid（MD5 hash of "-"）
     */
    fun generateDeviceMid(): String {
        return try {
            val md5 = MessageDigest.getInstance("MD5")
            val digest = md5.digest("-".toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate device mid")
            "00000000000000000000000000000000"
        }
    }
    
    /**
     * 获取应用 ID
     */
    fun getAppId(): String = APP_ID
    
    /**
     * 获取客户端版本
     */
    fun getClientVer(): String = CLIENT_VER
}
