package io.github.gua123.mediagate.data.storage.ftp

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 一条 LIST / MLSD 解析结果（**R2** 列目录 / plan 4.1「FTP：MLSD→LIST」）。
 *
 * @param name 文件名（不含路径）。
 * @param isDirectory 是否目录。
 * @param size 字节数；目录或未知为 -1（与 [io.github.gua123.mediagate.core.model.RemoteEntry] 同口径）。
 * @param mtimeMs 最后修改时间（Unix 毫秒）；未知为 0。
 */
internal data class FtpListEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mtimeMs: Long,
)

/**
 * 解析 MLSD 响应（RFC 3659）：`type=file;size=1234;modify=20240101120000;UNIX.mode=0644; 名字`。
 *
 * 规则：
 * - facts 段到**第一个空格**为止（标准要求 facts 以 `; ` 结束），其后整段是文件名（可含空格）；
 * - `type=dir` 是目录，`cdir` / `pdir`（当前目录/父目录）**过滤掉**，与 `.` / `..` 同义；
 * - `modify` 是 UTC 的 `yyyyMMddHHmmss[.SSS]`；
 * - 解析不了的行**跳过**而不是抛异常（真实服务器总有奇奇怪怪的行）。
 */
internal fun parseMlsd(lines: List<String>): List<FtpListEntry> {
    val result = ArrayList<FtpListEntry>(lines.size)
    for (line in lines) {
        parseMlsdLine(line)?.let { result.add(it) }
    }
    return result
}

/** 解析单行 MLSD。 */
internal fun parseMlsdLine(line: String): FtpListEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val separator = trimmed.indexOf(' ')
    if (separator <= 0) return null
    val factsPart = trimmed.substring(0, separator)
    val name = trimmed.substring(separator + 1).trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    if (!factsPart.contains('=')) return null
    val facts = HashMap<String, String>()
    for (fact in factsPart.split(';')) {
        if (fact.isEmpty()) continue
        val index = fact.indexOf('=')
        if (index <= 0) continue
        facts[fact.substring(0, index).lowercase()] = fact.substring(index + 1)
    }
    val type = facts["type"]?.lowercase() ?: return null
    if (type == "cdir" || type == "pdir") return null
    val isDirectory = type == "dir"
    val size = facts["size"]?.toLongOrNull() ?: -1L
    val mtime = facts["modify"]?.let { parseMlsdTimestamp(it) } ?: 0L
    return FtpListEntry(
        name = name,
        isDirectory = isDirectory,
        size = if (isDirectory) -1L else size,
        mtimeMs = mtime,
    )
}

/** MLSD 的时间戳：UTC `yyyyMMddHHmmss`，可带小数秒。 */
internal fun parseMlsdTimestamp(text: String): Long {
    val normalized = text.trim().substringBefore('.').substringBefore(',')
    if (normalized.length < 14) return 0L
    return runCatching {
        LocalDateTime.parse(normalized.substring(0, 14), MLSX_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0L)
}

/**
 * 解析 LIST 响应（**R2**：MLSD 不可用时的回退路径）。
 *
 * 同时支持两种最常见的方言：
 * - **Unix `ls -l`**：`-rw-r--r-- 1 owner group 1234 Jan  1 12:00 name`（首字符 d/-/l 判类型，
 *   第 5 列是大小，第 6~8 列是「月 日 时间|年」；文件名可含空格；软链接去掉 ` -> target`）；
 * - **DOS/Windows**：`01-02-24  03:04PM  <DIR>  name` 或 `01-02-24  03:04PM  1234 name`。
 *
 * 解析不了的行（`total 12`、欢迎语、`.` / `..`）**跳过**，不抛异常。
 */
internal fun parseList(lines: List<String>): List<FtpListEntry> {
    val result = ArrayList<FtpListEntry>(lines.size)
    for (line in lines) {
        val entry = parseUnixListLine(line) ?: parseDosListLine(line) ?: continue
        if (entry.name == "." || entry.name == "..") continue
        result.add(entry)
    }
    return result
}

/** 解析一行 Unix `ls -l` 风格 LIST。 */
internal fun parseUnixListLine(line: String): FtpListEntry? {
    val trimmed = line.trim()
    if (trimmed.length < 10) return null
    val type = trimmed[0]
    if (type != 'd' && type != '-' && type != 'l' && type != 'b' && type != 'c' && type != 'p' && type != 's') {
        return null
    }
    // 权限串必须是 9 个 rwx 位，否则多半不是 ls -l（例如 DOS 格式或 `total 12`）
    val permissions = trimmed.substring(1, 10)
    if (!permissions.all { it in "rwxstST-." }) return null
    val sizeToken = nthToken(trimmed, 5) ?: return null
    val size = sizeToken.toLongOrNull() ?: return null
    val month = nthToken(trimmed, 6) ?: return null
    val day = nthToken(trimmed, 7) ?: return null
    val timeOrYear = nthToken(trimmed, 8) ?: return null
    if (month.toIntOrNull() != null) return null
    val nameStart = indexAfterNthToken(trimmed, 8) ?: return null
    var name = trimmed.substring(nameStart).trim()
    if (name.isEmpty()) return null
    if (type == 'l') name = name.substringBefore(" -> ").trim()
    if (name.isEmpty()) return null
    val mtime = parseUnixTimestamp(month, day, timeOrYear)
    val isDirectory = type == 'd'
    return FtpListEntry(
        name = name,
        isDirectory = isDirectory,
        size = if (isDirectory) -1L else size,
        mtimeMs = mtime,
    )
}

/** 解析一行 DOS/Windows `dir` 风格 LIST。 */
internal fun parseDosListLine(line: String): FtpListEntry? {
    val trimmed = line.trim()
    if (trimmed.length < 10) return null
    val tokens = trimmed.split(Regex("\\s+"))
    if (tokens.size < 3) return null
    val date = tokens[0]
    val time = tokens[1]
    if (!DOS_DATE.matches(date) || !DOS_TIME.matches(time)) return null
    val third = tokens[2]
    val isDirectory = third.equals("<DIR>", ignoreCase = true)
    val size = if (isDirectory) -1L else third.toLongOrNull() ?: return null
    val nameStart = indexAfterNthToken(trimmed, if (isDirectory) 3 else 3) ?: return null
    val name = trimmed.substring(nameStart).trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    return FtpListEntry(name = name, isDirectory = isDirectory, size = size, mtimeMs = parseDosTimestamp(date, time))
}

// ------------------------------------------------------------------ 内部工具

private val MLSX_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

private val DOS_DATE = Regex("\\d{2}-\\d{2}-\\d{2,4}")
private val DOS_TIME = Regex("\\d{2}:\\d{2}(AM|PM|am|pm)?")

private val MONTHS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)

/** 取第 [n] 个（1 基）空白分隔 token。 */
private fun nthToken(text: String, n: Int): String? {
    var index = 0
    var count = 0
    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) return null
        val start = index
        while (index < text.length && !text[index].isWhitespace()) index++
        if (++count == n) return text.substring(start, index)
    }
    return null
}

/** 第 [n] 个 token 结束之后的字符下标（文件名可能含空格，所以要保留下标而不是再分词）。 */
private fun indexAfterNthToken(text: String, n: Int): Int? {
    var index = 0
    var count = 0
    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) return null
        while (index < text.length && !text[index].isWhitespace()) index++
        if (++count == n) return index
    }
    return null
}

/**
 * Unix LIST 的时间列：`HH:mm` 表示**今年**（服务器只在 6 个月内显示时间），
 * `YYYY` 表示更早的年份（此时时间按 00:00 处理）。
 */
private fun parseUnixTimestamp(month: String, day: String, timeOrYear: String): Long {
    val monthValue = MONTHS[month.lowercase().take(3)] ?: return 0L
    val dayValue = day.toIntOrNull() ?: return 0L
    return runCatching {
        val now = LocalDateTime.now()
        if (timeOrYear.contains(':')) {
            val parts = timeOrYear.split(':')
            var hour = parts[0].toInt()
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (hour == 24) hour = 0
            LocalDateTime.of(now.year, monthValue, dayValue, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            val year = timeOrYear.toInt()
            LocalDateTime.of(year, monthValue, dayValue, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }.getOrDefault(0L)
}

/** DOS LIST 的时间列：`MM-dd-yy` 或 `MM-dd-yyyy` + `hh:mmAM/PM`。 */
private fun parseDosTimestamp(date: String, time: String): Long {
    val dateParts = date.split('-')
    if (dateParts.size != 3) return 0L
    val month = dateParts[0].toIntOrNull() ?: return 0L
    val day = dateParts[1].toIntOrNull() ?: return 0L
    val rawYear = dateParts[2].toIntOrNull() ?: return 0L
    val year = if (rawYear < 100) (if (rawYear >= 70) 1900 + rawYear else 2000 + rawYear) else rawYear
    val pm = time.endsWith("PM", true) || time.endsWith("pm", true)
    val clock = time.dropLastWhile { it.isLetter() }
    val clockParts = clock.split(':')
    var hour = clockParts.getOrNull(0)?.toIntOrNull() ?: return 0L
    val minute = clockParts.getOrNull(1)?.toIntOrNull() ?: 0
    if (pm && hour < 12) hour += 12
    if (!pm && hour == 12) hour = 0
    return runCatching {
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrDefault(0L)
}
