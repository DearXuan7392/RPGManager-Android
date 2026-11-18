package com.dearxuan.rpgmanager.core

/**
 * RPG Maker 资源解密（桌面端 menu/imageDecoder.js 的 Kotlin 版）
 *
 * 加密文件前 16 字节是 "RPGMV\0\0\0" 签名，真正的 PNG 头被替换掉了；
 * 正文前 16 字节与密钥异或，其余字节原样保留。
 * 没有密钥时退化为「剥掉混淆头 + 缝回标准 PNG 头」的暴力修复。
 */
object RpgCrypto {

    private val PNG_STANDARD_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    private val RPGMV_SIGNATURE = byteArrayOf(0x52, 0x50, 0x47, 0x4D, 0x56, 0x00, 0x00, 0x00)

    val ENCRYPTED_IMAGE_EXTS = setOf("rpgmvp", "rmmzmvp", "png__", "png_")
    val ENCRYPTED_AUDIO_EXTS = setOf("rpgmvo", "rpgmvm", "ogg_", "m4a_")

    private const val HEADER_SIZE = 16

    fun isEncryptedImage(name: String): Boolean =
        ENCRYPTED_IMAGE_EXTS.contains(name.substringAfterLast('.', "").lowercase())

    fun isEncryptedAudio(name: String): Boolean =
        ENCRYPTED_AUDIO_EXTS.contains(name.substringAfterLast('.', "").lowercase())

    fun isEncrypted(name: String): Boolean = isEncryptedImage(name) || isEncryptedAudio(name)

    /** 传入完整路径也可以（只看最后一段的扩展名） */
    fun isEncryptedPath(path: String): Boolean = isEncrypted(path.substringAfterLast('/'))

    /** 解密后的文件名（.png / .ogg / .m4a） */
    fun decryptedName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ENCRYPTED_IMAGE_EXTS.contains(ext) -> name.substringBeforeLast('.') + ".png"
            ext == "rpgmvo" || ext == "ogg_" -> name.substringBeforeLast('.') + ".ogg"
            ext == "rpgmvm" || ext == "m4a_" -> name.substringBeforeLast('.') + ".m4a"
            else -> name
        }
    }

    /**
     * 按文件头判断解密后的真实类型。
     * 有些游戏只提供 ogg，引擎却按 m4a 去取（MV 在移动端会优先 .m4a），
     * 靠内容嗅探挑 MIME 才能让 <audio> 正常播放。
     */
    fun sniffMime(data: ByteArray): String? {
        if (data.size < 12) return null
        fun at(i: Int): Int = data[i].toInt() and 0xFF

        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) return "image/png"
        if (at(0) == 0xFF && at(1) == 0xD8) return "image/jpeg"
        if (at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code) return "audio/wav"
        if (at(0) == 'O'.code && at(1) == 'g'.code && at(2) == 'g'.code && at(3) == 'S'.code) return "audio/ogg"
        if (at(4) == 'f'.code && at(5) == 't'.code && at(6) == 'y'.code && at(7) == 'p'.code) return "audio/mp4"
        if (at(0) == 'I'.code && at(1) == 'D'.code && at(2) == '3'.code) return "audio/mpeg"
        if (at(0) == 0xFF && (at(1) and 0xE0) == 0xE0) return "audio/mpeg"
        return null
    }

    private fun hasRpgmvHeader(data: ByteArray): Boolean {
        if (data.size < RPGMV_SIGNATURE.size) return false
        for (i in RPGMV_SIGNATURE.indices) {
            if (data[i] != RPGMV_SIGNATURE[i]) return false
        }
        return true
    }

    /**
     * @param data 原始字节
     * @param keyHex 32 位十六进制密钥，可为 null
     */
    fun decrypt(data: ByteArray, keyHex: String?): ByteArray {
        if (data.size <= HEADER_SIZE) return data

        val rpgmv = hasRpgmvHeader(data)
        val key = keyHex?.takeIf { it.length == 32 }?.let {
            try {
                ByteArray(16) { i -> it.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                null
            }
        }

        // 方案 A：有密钥 + 标准签名 → 标准异或解密
        if (key != null && rpgmv) {
            val bodySize = data.size - HEADER_SIZE
            val out = ByteArray(bodySize)
            for (i in 0 until 16) {
                out[i] = (data[HEADER_SIZE + i].toInt() xor key[i].toInt()).toByte()
            }
            System.arraycopy(data, HEADER_SIZE + 16, out, 16, bodySize - 16)
            return out
        }

        // 方案 B：无密钥 / 魔改格式 → 剥离混淆头，缝回标准 PNG 头
        if (rpgmv || data[0] != 0x89.toByte()) {
            val stripLen = if (rpgmv) 32 else 16
            if (data.size > stripLen) {
                val body = data.copyOfRange(stripLen, data.size)
                val out = ByteArray(PNG_STANDARD_HEADER.size + body.size)
                System.arraycopy(PNG_STANDARD_HEADER, 0, out, 0, PNG_STANDARD_HEADER.size)
                System.arraycopy(body, 0, out, PNG_STANDARD_HEADER.size, body.size)
                return out
            }
        }

        return data
    }

    /**
     * 音频解密后要去掉 16 字节的加密外壳（桌面端同样处理，否则电脑端播放器报错）
     */
    fun stripAudioHeader(data: ByteArray): ByteArray {
        if (data.size > 16 && data[0] == 0x52.toByte() && data[1] == 0x50.toByte()) {
            return data.copyOfRange(16, data.size)
        }
        return data
    }
}
