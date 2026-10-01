package io.github.gua123.mediagate.data.storage.api

/**
 * 目录列举分页参数（plan 4.10「目录列表分页 + 缓存」，R2）。
 *
 * @property offset 起始下标（从 0 开始，指排序后的第 offset 条）。
 * @property limit 期望返回的最大条数；**<= 0 表示不限制**（一次返回全部）。
 */
data class Page(
    val offset: Int,
    val limit: Int,
) {
    init {
        require(offset >= 0) { "offset 不能为负：$offset" }
    }
}
