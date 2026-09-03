package com.moyue.reader.parser.txt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class TxtEncoding(val charsetName: String) {
    UTF8("UTF-8"),
    GBK("GBK"),
    GB2312("GB2312"),
    UTF16_LE("UTF-16LE"),
    UTF16_BE("UTF-16BE"),
    BIG5("Big5"),
    ;

    val charset: Charset get() = Charset.forName(charsetName)
}

data class TxtEncodingResult(
    val encoding: TxtEncoding,
    val bomBytes: Int = 0,
    val confidence: Float,
) {
    val charset: Charset get() = encoding.charset

    fun decode(bytes: ByteArray): String {
        val offset = bomBytes.coerceAtMost(bytes.size)
        return bytes.copyOfRange(offset, bytes.size).toString(charset)
    }
}

class TxtEncodingDetector {
    fun detect(bytes: ByteArray): TxtEncodingResult {
        detectBom(bytes)?.let { return it }
        detectUtf16WithoutBom(bytes)?.let { return it }
        if (strictDecode(bytes, Charsets.UTF_8) != null) {
            return TxtEncodingResult(TxtEncoding.UTF8, confidence = 0.99f)
        }

        val candidates = listOf(TxtEncoding.GB2312, TxtEncoding.GBK, TxtEncoding.BIG5)
            .mapNotNull { encoding ->
                strictDecode(bytes, encoding.charset)?.let { text ->
                    Triple(encoding, text, chineseTextScore(text, encoding))
                }
            }
        val best = candidates.maxByOrNull { it.third }
            ?: return TxtEncodingResult(TxtEncoding.GBK, confidence = 0.25f)
        return TxtEncodingResult(best.first, confidence = 0.65f)
    }

    private fun detectBom(bytes: ByteArray): TxtEncodingResult? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) ->
            TxtEncodingResult(TxtEncoding.UTF8, bomBytes = 3, confidence = 1f)
        bytes.startsWith(0xFF, 0xFE) ->
            TxtEncodingResult(TxtEncoding.UTF16_LE, bomBytes = 2, confidence = 1f)
        bytes.startsWith(0xFE, 0xFF) ->
            TxtEncodingResult(TxtEncoding.UTF16_BE, bomBytes = 2, confidence = 1f)
        else -> null
    }

    private fun detectUtf16WithoutBom(bytes: ByteArray): TxtEncodingResult? {
        if (bytes.size < 8) return null
        val sampleSize = minOf(bytes.size, 4096)
        var evenZeros = 0
        var oddZeros = 0
        var pairs = 0
        var index = 0
        while (index + 1 < sampleSize) {
            if (bytes[index].toInt() == 0) evenZeros++
            if (bytes[index + 1].toInt() == 0) oddZeros++
            pairs++
            index += 2
        }
        if (oddZeros > pairs * 0.35 && evenZeros < pairs * 0.1) {
            return TxtEncodingResult(TxtEncoding.UTF16_LE, confidence = 0.85f)
        }
        if (evenZeros > pairs * 0.35 && oddZeros < pairs * 0.1) {
            return TxtEncodingResult(TxtEncoding.UTF16_BE, confidence = 0.85f)
        }
        return null
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun chineseTextScore(text: String, encoding: TxtEncoding): Int {
        val common = "的一是了我不人在他有这这個个上们來来时大地为子中你说生年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第章回卷节部篇开始故事测试阅读正文下一页風雪开端"
        val simplified = "这一个测试阅读页开始后里发为们"
        val traditional = "這一個測試閱讀頁開始後裡發為們"
        var score = 0
        text.forEach { char ->
            score += when {
                char == '\n' || char == '\r' || char == '\t' -> 1
                char in common -> 6
                char.code in 0x4E00..0x9FFF -> 2
                !char.isISOControl() -> 1
                else -> -8
            }
        }
        if (Regex("(?im)^(第.{1,16}[章回卷节部篇]|序章|楔子|番外|Chapter\\s+\\d+)").containsMatchIn(text)) {
            score += 40
        }
        if (encoding == TxtEncoding.BIG5) score += text.count { it in traditional } * 5
        if (encoding == TxtEncoding.GBK || encoding == TxtEncoding.GB2312) {
            score += text.count { it in simplified } * 5
        }
        if (encoding == TxtEncoding.GB2312 && canEncodeExactly(text, encoding.charset)) score += 1
        return score
    }

    private fun canEncodeExactly(text: String, charset: Charset): Boolean =
        charset.newEncoder().canEncode(text)

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }
}
