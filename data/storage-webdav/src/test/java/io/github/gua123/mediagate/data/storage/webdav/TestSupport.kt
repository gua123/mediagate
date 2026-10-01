package io.github.gua123.mediagate.data.storage.webdav

import io.github.gua123.mediagate.data.storage.api.RangeStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** 把一段流读到 EOF（单测里反复用）。 */
internal suspend fun readAll(stream: RangeStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8)
    while (true) {
        val n = stream.read(buffer, 0, buffer.size)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

internal fun text(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)
