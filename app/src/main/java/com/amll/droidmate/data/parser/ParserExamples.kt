package com.amll.droidmate.data.parser

/**
 * 多格式歌词解析器使用示例
 * 
 * 本文件展示如何使用基于 Unilyric 规则的多格式歌词解析器
 */
object ParserExamples {
    
    /**
     * 示例 1: 自动检测格式并解析
     */
    fun exampleAutoDetect() {
        val lrcContent = """
            [ti:示例歌曲]
            [ar:示例歌手]
            [00:12.50]在世界的尽头
            [00:16.80]会有另一个我
        """.trimIndent()
        
        // 自动检测格式（LRC）
        val format = LyricsFormat.detect(lrcContent)
        println("Detected format: ${format.displayName}")
        
        // 解析为 TTMLLyrics
        val lyrics = UnifiedLyricsParser.parse(
            content = lrcContent,
            title = "示例歌曲",
            artist = "示例歌手"
        )
        
        println("Parsed ${lyrics?.lines?.size} lines")
    }
    
    /**
     * 示例 2: 解析 QRC 格式 (QQ音乐)
     */
    fun exampleQrcFormat() {
        val qrcContent = """
            [ti:示例歌曲]
            [ar:示例歌手]
            [0,3500]在(0,500)世(500,300)界(800,400)的(1200,300)尽(1500,400)头(1900,600)
            [3500,3800]会(0,400)有(400,500)另(900,600)一(1500,500)个(2000,400)我(2400,800)
        """.trimIndent()
        
        val lyrics = UnifiedLyricsParser.parse(qrcContent)
        
        // lyrics.lines 包含逐字时间戳信息
        lyrics?.lines?.forEach { line ->
            println("Line: ${line.text}")
            println("  Start: ${line.startTime}ms, End: ${line.endTime}ms")
            println("  Words: ${line.words.size}")
            line.words.forEach { word ->
                println("    - ${word.word} (${word.startTime}ms - ${word.endTime}ms)")
            }
        }
    }
    
    /**
     * 示例 3: 解析 KRC 格式 (酷狗音乐)
     */
    fun exampleKrcFormat() {
        val krcContent = """
            [language:zh-CN]
            [id:12345]
            [0,3500]<0,500,0>在<500,300,0>世<800,400,0>界<1200,300,0>的<1500,400,0>尽<1900,600,0>头
            [3500,3800]<0,400,0>会<400,500,0>有<900,600,0>另<1500,500,0>一<2000,400,0>个<2400,800,0>我
        """.trimIndent()
        
        val lyrics = UnifiedLyricsParser.parse(krcContent)
        
        println("Total lines: ${lyrics?.lines?.size}")
        println("Total words: ${lyrics?.lines?.sumOf { it.words.size }}")
    }
    
    /**
     * 示例 4: 解析 YRC 格式 (网易云音乐)
     */
    fun exampleYrcFormat() {
        val yrcContent = """
            {"t":0,"c":[{"tx":"在","tr":(0,500,0)},{"tx":"世","tr":(500,300,0)},{"tx":"界","tr":(800,400,0)}]}
            {"t":3500,"c":[{"tx":"会","tr":(0,400,0)},{"tx":"有","tr":(400,500,0)},{"tx":"另","tr":(900,600,0)}]}
        """.trimIndent()
        
        val lyrics = UnifiedLyricsParser.parse(yrcContent)
        
        lyrics?.lines?.forEach { line ->
            println("${line.startTime}ms: ${line.text} (${line.words.size} words)")
        }
    }
    
    /**
     * 示例 5: 解析增强型 LRC 格式
     */
    fun exampleEnhancedLrc() {
        val enhancedLrcContent = """
            [00:12.50]<00:12.50>在<00:13.00>世<00:13.30>界<00:13.70>的<00:14.00>尽<00:14.40>头
            [00:16.80]<00:16.80>会<00:17.20>有<00:17.70>另<00:18.30>一<00:18.80>个<00:19.20>我
        """.trimIndent()
        
        val lyrics = UnifiedLyricsParser.parse(enhancedLrcContent)
        
        println("Enhanced LRC parsed successfully")
        println("Has word-level timestamps: ${lyrics?.lines?.all { it.words.size > 1 }}")
    }
    
    /**
     * 示例 6: 使用指定格式解析
     */
    fun exampleParseWithFormat() {
        val content = "[0,3500]测试内容(0,500)示例(500,400)"
        
        // 明确指定格式为 QRC
        val lines = UnifiedLyricsParser.parseWithFormat(
            content = content,
            format = LyricsFormat.QRC
        )
        
        println("Parsed ${lines.size} lines using QRC format")
    }
    
    /**
     * 示例 7: 格式检测示例
     */
    fun exampleFormatDetection() {
        val samples = mapOf(
            "LRC" to "[00:12.50]歌词内容",
            "QRC" to "[0,3500]歌词(0,500)内容(500,400)",
            "KRC" to "[0,3500]<0,500,0>歌<500,400,0>词",
            "YRC" to """{"t":0,"c":[{"tx":"歌","tr":(0,500,0)}]}""",
            "Enhanced LRC" to "[00:12.50]<00:12.50>歌<00:13.00>词",
            "Plain Text" to "这是纯文本歌词"
        )
        
        samples.forEach { (expectedFormat, content) ->
            val detectedFormat = LyricsFormat.detect(content)
            println("Content: ${content.take(30)}...")
            println("Expected: $expectedFormat, Detected: ${detectedFormat.displayName}\n")
        }
    }
    
    /**
     * 示例 8: 在 Repository 中使用
     */
    fun exampleInRepository() {
        // 在 LyricsRepository 中使用
        // val repository = LyricsRepository(httpClient)
        
        // 解析 LRC（自动使用新的统一解析器）
        // val lyrics = repository.parseLRC(lrcContent)
        
        // 解析 LRC 并添加翻译和音译
        // val lyricsWithTranslation = repository.parseLRC(
        //     lrcContent = mainLyrics,
        //     translationLrc = translationLyrics,
        //     transliterationLrc = romanization
        // )
    }
    
    /**
     * 示例 9: 在 ViewModel 中使用
     */
    fun exampleInViewModel() {
        // 在 CustomLyricsViewModel 中，parseInput 方法会自动检测格式
        
        // 支持的输入格式:
        // 1. TTML: <?xml...> 或 <tt...>
        // 2. LRC: [00:12.50]歌词
        // 3. Enhanced LRC: [00:12.50]<00:12.50>歌<00:13.00>词
        // 4. QRC: [0,3500]歌(0,500)词(500,400)
        // 5. KRC: [0,3500]<0,500,0>歌<500,400,0>词
        // 6. YRC: {"t":0,"c":[...]}
        // 7. 纯文本: 无时间戳的文本
        
        // 用户在 CustomLyricsActivity 中粘贴任何格式的歌词，
        // parseInput 会自动检测并解析
    }
    
    /**
     * 示例 10: 错误处理
     */
    fun exampleErrorHandling() {
        val invalidContent = "这不是有效的歌词格式"
        
        val lyrics = UnifiedLyricsParser.parse(invalidContent)
        
        if (lyrics == null) {
            println("Failed to parse lyrics")
            // 会作为纯文本处理
        } else {
            println("Parsed as plain text: ${lyrics.lines.size} lines")
        }
    }
}
