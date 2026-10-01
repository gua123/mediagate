package io.github.gua123.mediagate.media.playback

import io.github.gua123.mediagate.data.storage.api.StorageBackend

/**
 * 一次播放断点（R18「断点续播 / 被系统清理后重进恢复位置」）。
 *
 * 存储方（:app 用 DataStore 实现，将来可换成 Room 表）只认这四个字段，不感知播放器。
 *
 * @property backendId 后端 id（[StorageBackend.id]）。
 * @property path 后端内路径。
 * @property positionMs 上次播放到的位置（毫秒）。
 * @property updatedAt 写入时间（Unix 毫秒），供上层做「最近播放」排序与过期清理。
 */
data class PlaybackProgress(
    val backendId: String,
    val path: String,
    val positionMs: Long,
    val updatedAt: Long,
)

/**
 * 断点续播存储（R18）。
 *
 * 为什么是接口：:media:playback 不碰 Room 表（plan 第 7 章的 playback_progress 由 :app 决定怎么落），
 * 后台服务只负责「进曲目时读一次、播放中定时写一次」，具体存储由 :app 注入。
 *
 * 实现约定：挂起函数内部自己切 IO；写失败不应让播放中断（服务侧已做兜底捕获）。
 */
interface PlaybackProgressStore {

    /** 读取某条目的断点；没有记录返回 null。 */
    suspend fun load(backendId: String, path: String): PlaybackProgress?

    /** 写入 / 覆盖断点。 */
    suspend fun save(progress: PlaybackProgress)
}

/**
 * 播放服务需要的宿主能力（手写 DI 的注入点，R4/R18）。
 *
 * 为什么是接口而不是直接依赖 :app：MediaSessionService 由系统创建，拿不到构造注入，
 * 只能从 Service.getApplication() 反查；让 :app 的 Application 实现本接口即可，
 * 依赖方向仍是 :app → :media:playback（不反向依赖）。
 */
interface PlaybackHost {

    /**
     * 当前浏览根目录的存储后端（R12）；尚未选择根目录时为 null
     * （此时播放以 DataSourceException 失败并给中文错误，而不是崩溃）。
     */
    val playbackBackend: StorageBackend?

    /** 断点续播存储（R18）；不需要时为 null（服务退化成「不记位置」）。 */
    val playbackProgress: PlaybackProgressStore?
}
