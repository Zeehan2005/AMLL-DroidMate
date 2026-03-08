package com.amll.droidmate.data.network

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.InflaterInputStream

/**
 * QQ Music QRC decryptor aligned with Unilyric's non-standard DES implementation.
 */
object QqMusicQrcCrypto {
    private const val ROUNDS = 16
    private const val SUB_KEY_SIZE = 6
    private const val DES_BLOCK_SIZE = 8

    private val codec = QqMusicCodec()

    fun decryptQrcHex(encryptedText: String): String {
        android.util.Log.d("QqMusicQrcCrypto", "Starting Hex+3DES+Zlib decryption, input length: ${encryptedText.length}")
        android.util.Log.d("QqMusicQrcCrypto", "Input preview (first 200 chars): ${encryptedText.take(200)}")
        
        val encryptedBytes = decodeHex(encryptedText)
        android.util.Log.d("QqMusicQrcCrypto", "After Hex decode: ${encryptedBytes.size} bytes")
        
        require(encryptedBytes.size % DES_BLOCK_SIZE == 0) {
            "Encrypted data length must be a multiple of $DES_BLOCK_SIZE"
        }

        val decrypted = ByteArray(encryptedBytes.size)
        var offset = 0
        while (offset < encryptedBytes.size) {
            codec.decryptBlock(encryptedBytes, offset, decrypted, offset)
            offset += DES_BLOCK_SIZE
        }
        
        android.util.Log.d("QqMusicQrcCrypto", "After 3DES decrypt: ${decrypted.size} bytes, first 32 bytes: ${decrypted.take(32).joinToString(",") { "%02X".format(it) }}")
        android.util.Log.w("QqMusicQrcCrypto", "WARNING: Valid Zlib data should start with 0x78 (120), but first byte is: 0x${"%02X".format(decrypted[0])} (${decrypted[0].toInt() and 0xFF})")

        val decompressed = decompress(decrypted)
        android.util.Log.d("QqMusicQrcCrypto", "After Zlib decompress: ${decompressed.size} bytes")
        android.util.Log.d("QqMusicQrcCrypto", "Decompressed preview (first 200 chars): ${String(decompressed.take(200).toByteArray(), Charsets.UTF_8)}")
        
        val payload = if (
            decompressed.size >= 3 &&
            decompressed[0] == 0xEF.toByte() &&
            decompressed[1] == 0xBB.toByte() &&
            decompressed[2] == 0xBF.toByte()
        ) {
            android.util.Log.d("QqMusicQrcCrypto", "UTF-8 BOM detected, removing first 3 bytes")
            decompressed.copyOfRange(3, decompressed.size)
        } else {
            decompressed
        }

        val result = payload.toString(Charsets.UTF_8)
        android.util.Log.d("QqMusicQrcCrypto", "Final result length: ${result.length}, preview (first 300 chars): ${result.take(300)}")
        return result
    }

    private fun decodeHex(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "Invalid hex string length" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            val hi = clean[i].digitToIntOrNull(16) ?: error("Invalid hex string")
            val lo = clean[i + 1].digitToIntOrNull(16) ?: error("Invalid hex string")
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun decompress(data: ByteArray): ByteArray {
        return InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private class QqMusicCodec {
        private val decryptSchedule: Array<Array<ByteArray>> = arrayOf(
            CustomDes.keySchedule(CustomDes.KEY_3, CustomDes.Mode.Decrypt),
            CustomDes.keySchedule(CustomDes.KEY_2, CustomDes.Mode.Encrypt),
            CustomDes.keySchedule(CustomDes.KEY_1, CustomDes.Mode.Decrypt)
        )

        fun decryptBlock(input: ByteArray, inputOffset: Int, output: ByteArray, outputOffset: Int) {
            val temp1 = ByteArray(8)
            val temp2 = ByteArray(8)
            val block = input.copyOfRange(inputOffset, inputOffset + 8)
            CustomDes.desCrypt(block, temp1, decryptSchedule[0])
            CustomDes.desCrypt(temp1, temp2, decryptSchedule[1])
            CustomDes.desCrypt(temp2, output, decryptSchedule[2], outputOffset)
        }
    }

    private object CustomDes {
        enum class Mode { Encrypt, Decrypt }

        val KEY_1: ByteArray = "!@#)(*$%".toByteArray(Charsets.US_ASCII)
        val KEY_2: ByteArray = "123ZXC!@".toByteArray(Charsets.US_ASCII)
        val KEY_3: ByteArray = "!@#)(NHL".toByteArray(Charsets.US_ASCII)

        private val SBOX1 = intArrayOf(
            14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,
            0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,
            4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,
            15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13
        )
        private val SBOX2 = intArrayOf(
            15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,
            3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5,  // 修复：第8个数从 15 改为 14
            0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,
            13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9
        )
        private val SBOX3 = intArrayOf(
            10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,
            13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,
            13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,
            1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12
        )
        private val SBOX4 = intArrayOf(
            7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,
            13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,
            10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,
            3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14
        )
        private val SBOX5 = intArrayOf(
            2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,
            14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,
            4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,
            11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3
        )
        private val SBOX6 = intArrayOf(
            12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,
            10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,
            9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,
            4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13
        )
        private val SBOX7 = intArrayOf(
            4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,
            13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,
            1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,
            6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12
        )
        private val SBOX8 = intArrayOf(
            13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,
            1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,
            7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,
            2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11
        )

        private val S_BOXES = arrayOf(SBOX1, SBOX2, SBOX3, SBOX4, SBOX5, SBOX6, SBOX7, SBOX8)

        private val P_BOX = intArrayOf(
            16,7,20,21,29,12,28,17,
            1,15,23,26,5,18,31,10,
            2,8,24,14,32,27,3,9,
            19,13,30,6,22,11,4,25
        )

        private val E_BOX_TABLE = intArrayOf(
            32,1,2,3,4,5,
            4,5,6,7,8,9,
            8,9,10,11,12,13,
            12,13,14,15,16,17,
            16,17,18,19,20,21,
            20,21,22,23,24,25,
            24,25,26,27,28,29,
            28,29,30,31,32,1
        )

        private val SP_TABLES: Array<IntArray> by lazy { generateSpTables() }
        private val TABLES: DesPermutationTables by lazy { DesPermutationTables() }

        fun keySchedule(key: ByteArray, mode: Mode): Array<ByteArray> {
            val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
            val keyPermC = intArrayOf(
                56, 48, 40, 32, 24, 16, 8,
                0, 57, 49, 41, 33, 25, 17,
                9, 1, 58, 50, 42, 34, 26,
                18, 10, 2, 59, 51, 43, 35
            )
            val keyPermD = intArrayOf(
                62, 54, 46, 38, 30, 22, 14,
                6, 61, 53, 45, 37, 29, 21,
                13, 5, 60, 52, 44, 36, 28,
                20, 12, 4, 27, 19, 11, 3
            )
            val keyCompression = intArrayOf(
                13, 16, 10, 23, 0, 4, 2, 27,
                14, 5, 20, 9, 22, 18, 11, 3,
                25, 7, 15, 6, 26, 19, 12, 1,
                40, 51, 30, 36, 46, 54, 29, 39,
                50, 44, 32, 47, 43, 48, 38, 55,
                33, 52, 45, 41, 49, 35, 28, 31
            )

            require(key.size == 8) { "DES key must be 8 bytes" }
            val schedule = Array(ROUNDS) { ByteArray(SUB_KEY_SIZE) }

            val c0 = permuteFromKeyBytes(key, keyPermC)
            val d0 = permuteFromKeyBytes(key, keyPermD)
            var c = (c0.toInt() shl 4)
            var d = (d0.toInt() shl 4)

            keyRndShift.forEachIndexed { i, shift ->
                c = rotateLeft28bitInU32(c, shift)
                d = rotateLeft28bitInU32(d, shift)
                val toGen = if (mode == Mode.Decrypt) 15 - i else i

                var subkey48 = 0L
                keyCompression.forEachIndexed { k, pos ->
                    val bit = if (pos < 28) {
                        (c ushr (31 - pos)) and 1
                    } else {
                        (d ushr (31 - (pos - 27))) and 1
                    }
                    if (bit != 0) {
                        subkey48 = subkey48 or (1L shl (47 - k))
                    }
                }

                val bytes = longToBytesBE(subkey48)
                bytes.copyInto(schedule[toGen], 0, 2, 8)
            }

            return schedule
        }

        fun desCrypt(input: ByteArray, output: ByteArray, key: Array<ByteArray>, outputOffset: Int = 0) {
            val state = IntArray(2)
            initialPermutation(state, input)

            for (roundKey in key.take(15)) {
                val prevRight = state[1]
                val prevLeft = state[0]
                state[1] = prevLeft xor fFunction(prevRight, roundKey)
                state[0] = prevRight
            }

            state[0] = state[0] xor fFunction(state[1], key[15])
            inversePermutation(state, output, outputOffset)
        }

        private fun fFunction(state: Int, key: ByteArray): Int {
            val expanded = applyEBoxPermutation(state)
            val key64 = bytesToLongBE(byteArrayOf(0, 0) + key)
            val xor = expanded xor key64
           
            // QQ Music 使用自定义的 S-Box 索引计算（非标准 DES）
            // 索引计算：((a & 0x20) | ((a & 0x1f) >> 1) | ((a & 0x01) << 4))
            fun calculateSBoxIndex(value: Long): Int {
                val a = value.toInt()
                return ((a and 0x20) or ((a and 0x1f) shr 1) or ((a and 0x01) shl 4))
            }
            
            return SP_TABLES[0][calculateSBoxIndex(xor ushr 42)] or
                SP_TABLES[1][calculateSBoxIndex(xor ushr 36)] or
                SP_TABLES[2][calculateSBoxIndex(xor ushr 30)] or
                SP_TABLES[3][calculateSBoxIndex(xor ushr 24)] or
                SP_TABLES[4][calculateSBoxIndex(xor ushr 18)] or
                SP_TABLES[5][calculateSBoxIndex(xor ushr 12)] or
                SP_TABLES[6][calculateSBoxIndex(xor ushr 6)] or
                SP_TABLES[7][calculateSBoxIndex(xor and 0x3F)]
        }

        private fun initialPermutation(state: IntArray, input: ByteArray) {
            state[0] = 0
            state[1] = 0
            TABLES.ipTable.forEachIndexed { i, tSlice ->
                val lookup = tSlice[input[i].toInt() and 0xFF]
                state[0] = state[0] or lookup.first
                state[1] = state[1] or lookup.second
            }
        }

        private fun inversePermutation(state: IntArray, output: ByteArray, outputOffset: Int) {
            val stateU64 = ((state[0].toLong() and 0xFFFF_FFFFL) shl 32) or
                (state[1].toLong() and 0xFFFF_FFFFL)
            val stateBytes = longToBytesBE(stateU64)
            var result = 0L
            stateBytes.forEachIndexed { i, b ->
                result = result or TABLES.invIpTable[i][b.toInt() and 0xFF]
            }
            longToBytesBE(result).copyInto(output, outputOffset)
        }

        private fun generateSpTables(): Array<IntArray> {
            return Array(8) { sBoxIdx ->
                IntArray(64) { sBoxInput ->
                    val sBoxIndex = calculateSboxIndex(sBoxInput)
                    val fourBitOutput = S_BOXES[sBoxIdx][sBoxIndex]
                    val preP = fourBitOutput shl (28 - (sBoxIdx * 4))
                    applyQqPboxPermutation(preP, P_BOX)
                }
            }
        }

        private fun applyQqPboxPermutation(input: Int, table: IntArray): Int {
            val sourceBits = IntArray(32) { i -> (input ushr (31 - i)) and 1 }
            val destBits = IntArray(32) { idx -> sourceBits[table[idx] - 1] }
            var output = 0
            destBits.forEachIndexed { i, bit ->
                output = output or (bit shl (31 - i))
            }
            return output
        }

        private fun calculateSboxIndex(a: Int): Int {
            return (a and 0x20) or ((a and 0x1F) ushr 1) or ((a and 0x01) shl 4)
        }

        private fun rotateLeft28bitInU32(value: Int, amount: Int): Int {
            val bits28Mask = 0xFFFF_FFF0.toInt()
            return ((value shl amount) or (value ushr (28 - amount))) and bits28Mask
        }

        private fun permuteFromKeyBytes(key: ByteArray, table: IntArray): Long {
            val word1 = u32FromLe(key, 0)
            val word2 = u32FromLe(key, 4)
            val composed = ((word1.toLong() and 0xFFFF_FFFFL) shl 32) or (word2.toLong() and 0xFFFF_FFFFL)
            var output = 0L
            val outputLen = table.size
            table.forEachIndexed { i, pos ->
                val bit = (composed ushr (63 - pos)) and 1L
                if (bit != 0L) {
                    output = output or (1L shl (outputLen - 1 - i))
                }
            }
            return output
        }

        private fun applyEBoxPermutation(input: Int): Long {
            var output = 0L
            E_BOX_TABLE.forEachIndexed { i, sourceBitPos ->
                val shift = 32 - sourceBitPos
                val bit = (input ushr shift) and 1
                output = output or (bit.toLong() shl (47 - i))
            }
            return output
        }

        private class DesPermutationTables {
            val ipTable: Array<Array<Pair<Int, Int>>> = Array(8) { Array(256) { 0 to 0 } }
            val invIpTable: Array<LongArray> = Array(8) { LongArray(256) }

            init {
                val ipRule = intArrayOf(
                    34, 42, 50, 58, 2, 10, 18, 26,
                    36, 44, 52, 60, 4, 12, 20, 28,
                    38, 46, 54, 62, 6, 14, 22, 30,
                    40, 48, 56, 64, 8, 16, 24, 32,
                    33, 41, 49, 57, 1, 9, 17, 25,
                    35, 43, 51, 59, 3, 11, 19, 27,
                    37, 45, 53, 61, 5, 13, 21, 29,
                    39, 47, 55, 63, 7, 15, 23, 31
                )
                val invIpRule = intArrayOf(
                    37, 5, 45, 13, 53, 21, 61, 29,
                    38, 6, 46, 14, 54, 22, 62, 30,
                    39, 7, 47, 15, 55, 23, 63, 31,
                    40, 8, 48, 16, 56, 24, 64, 32,
                    33, 1, 41, 9, 49, 17, 57, 25,
                    34, 2, 42, 10, 50, 18, 58, 26,
                    35, 3, 43, 11, 51, 19, 59, 27,
                    36, 4, 44, 12, 52, 20, 60, 28
                )

                val input = ByteArray(8)
                for (bytePos in 0 until 8) {
                    for (byteVal in 0 until 256) {
                        input.fill(0)
                        input[bytePos] = byteVal.toByte()
                        val permuted = applyPermutation(input, ipRule)
                        val hi = (permuted ushr 32).toInt()
                        val lo = permuted.toInt()
                        ipTable[bytePos][byteVal] = hi to lo
                    }
                }

                for (blockPos in 0 until 8) {
                    for (blockVal in 0 until 256) {
                        val tempInput = (blockVal.toLong() shl (56 - (blockPos * 8)))
                        val permuted = applyPermutation(longToBytesBE(tempInput), invIpRule)
                        invIpTable[blockPos][blockVal] = permuted
                    }
                }
            }

            private fun applyPermutation(input: ByteArray, rule: IntArray): Long {
                val normalized = bytesToLongBE(input)
                var result = 0L
                rule.forEachIndexed { i, srcBitPosFrom1 ->
                    val srcBitPos = srcBitPosFrom1 - 1
                    val bit = (normalized ushr (63 - srcBitPos)) and 1L
                    result = result or (bit shl (63 - i))
                }
                return result
            }
        }

        private fun u32FromLe(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun bytesToLongBE(bytes: ByteArray): Long {
            var value = 0L
            for (b in bytes) {
                value = (value shl 8) or (b.toLong() and 0xFF)
            }
            return value
        }

        private fun longToBytesBE(value: Long): ByteArray {
            val out = ByteArray(8)
            for (i in 0 until 8) {
                out[7 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
            return out
        }
    }

    fun looksLikeHex(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
        return normalized.isNotEmpty() && normalized.length % 2 == 0 && normalized.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
