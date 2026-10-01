package io.github.gua123.mediagate.core.model

/**
 * 后端能力声明（plan 4.1 表格 / R4 拖拽 seek / R7 网络切换 / R14 字幕写回）。
 *
 * 上层按能力决定策略，绝不假定「所有后端都支持随机读」：
 * 精确随机读 → 分段缓存 → 全量下载（plan 4.1 的降级链）。
 *
 * 默认值取最保守的一档（什么都不支持、并发 1、只读），由各后端显式声明自己具备的能力。
 */
data class Caps(
    /** 是否支持任意偏移随机读（R4 拖拽 seek 的前提）。 */
    val randomAccess: Boolean = false,
    /** 是否支持 HTTP Range 请求头（WebDAV 等；本地无此概念）。 */
    val rangeHeader: Boolean = false,
    /** 是否支持 FTP REST 断点续传式定位（REST + RETR）。 */
    val resumeByRest: Boolean = false,
    /** 建议的最大并行读取数（SFTP/FTP 等要降到 2，避免打满单会话）。 */
    val maxParallelReads: Int = 1,
    /** 是否可写：字幕写回视频同目录（R14）；无写权限时落本地缓存并提示。 */
    val writable: Boolean = false,
)
