package com.amll.droidmate.data.network

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端工厂 - 统一配置所有网络请求
 * 缓存存储在 Android cache 目录，可通过系统设置清除
 */
object HttpClientFactory {
    
    private const val CACHE_SIZE = 50L * 1024 * 1024 // 50 MB
    private const val CACHE_DIR_NAME = "http_cache"
    
    /**
     * 创建配置好的 HttpClient，包含缓存支持
     * 
     * @param context Android Context
     * @return 配置好的 HttpClient 实例
     */
    fun create(context: Context): HttpClient {
        // 创建缓存目录（在 Android cache 路径下）
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        
        return HttpClient(OkHttp) {
            // 配置 OkHttp 引擎
            engine {
                config {
                    // 添加 HTTP 缓存（存储在 cache 目录）
                    cache(Cache(cacheDir, CACHE_SIZE))
                    
                    // 连接超时
                    connectTimeout(30, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }
            
            // 内容协商 - JSON 序列化/反序列化
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                })
            }
        }
    }
}
