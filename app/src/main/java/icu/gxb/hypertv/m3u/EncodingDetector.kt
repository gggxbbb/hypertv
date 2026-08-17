package icu.gxb.hypertv.m3u

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * M3U 内容编码识别与解码。
 *
 * 策略（BOM + 试探解码，JVM/Android 均可单测）：
 * 1. 命中 UTF-8 / UTF-16LE / UTF-16BE BOM 时按对应编码解码；
 * 2. 无 BOM 时先用 UTF-8 严格解码试探（解码器 REPORT 模式，任何非法字节即失败），
 *    成功即 UTF-8；失败（说明是 GBK 等非 UTF-8 内容）回退 GBK。
 *
 * 对纯 ASCII 内容两种编码结果一致；对含中文的 GBK 内容，UTF-8 严格校验
 * 必然失败（GBK 双字节序列极少能构成合法 UTF-8），因此该方案对中文本地
 * 直播源可靠。
 */
object EncodingDetector {

    /** 解码结果：文本 + 识别出的编码名（UTF-8 / UTF-16LE / UTF-16BE / GBK）。 */
    data class DecodedText(val text: String, val encoding: String)

    private val gbk: Charset = Charset.forName("GBK")

    fun decode(bytes: ByteArray): String = decodeDetected(bytes).text

    fun decodeDetected(bytes: ByteArray): DecodedText {
        if (bytes.hasUtf8Bom()) {
            return DecodedText(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        }
        if (bytes.hasUtf16LeBom()) {
            return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.hasUtf16BeBom()) {
            return DecodedText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        return if (isStrictUtf8(bytes)) {
            DecodedText(String(bytes, Charsets.UTF_8), "UTF-8")
        } else {
            DecodedText(String(bytes, gbk), "GBK")
        }
    }

    /** UTF-8 严格解码试探：任何非法字节序列都会抛异常（区别于宽松的替换式解码）。 */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun ByteArray.hasUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

    private fun ByteArray.hasUtf16LeBom(): Boolean =
        size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()

    private fun ByteArray.hasUtf16BeBom(): Boolean =
        size >= 2 && this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()
}
