package io.github.gua123.mediagate.media.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 字幕文本编解码（R14 容错：BOM / CRLF / 编码差异都不该让播放页炸掉）。
 *
 * 外挂字幕来自各种来源，编码并不统一：UTF-8（带或不带 BOM）最常见，中文老字幕常见 GBK。
 * 因此这里依次尝试：BOM 识别（UTF-8 / UTF-16LE / UTF-16BE）→ 严格 UTF-8 解码 → GBK 回退，
 * 全部失败才退回「按 UTF-8 宽松解码」，保证**任何字节串都能得到一份可显示的文本**。
 */
object SubtitleTextCodec {

    /** UTF-8 BOM。 */
    private const val BOM_UTF8 = '\uFEFF'

    /** 去掉开头的 UTF-8 BOM（有的文件把 BOM 当正文首字符，会让第一行序号解析歪掉）。 */
    fun stripBom(text: String): String = text.removePrefix(BOM_UTF8.toString())

    /**
     * 归一化：去 BOM + 换行统一成 \n（CRLF / CR 两种历史写法都要能解析）。
     */
    fun normalize(text: String): String = stripBom(text).replace("\r\n", "\n").replace('\r', '\n')

    /**
     * 字节 → 文本（容错解码）。
     *
     * 顺序：BOM（UTF-8 / UTF-16）→ 严格 UTF-8 → GBK → 宽松 UTF-8（必定成功）。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return decodeUtf8OrGbk(bytes)
    }

    /** 严格 UTF-8 解码失败就按 GBK 解（中文老字幕的通例）；GBK 不可用时宽松 UTF-8 兜底。 */
    private fun decodeUtf8OrGbk(bytes: ByteArray): String {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }
            .onSuccess { return it }
        return runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }
}
