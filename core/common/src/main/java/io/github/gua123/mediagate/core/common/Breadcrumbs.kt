package io.github.gua123.mediagate.core.common

/**
 * 全局面包屑（**2026-10-03**：用户报"点内核闪退但没捕捉到日志"之后加的）。
 *
 * 为什么不用 [AppLog]：内存环形缓冲会随进程一起消失，而**原生崩溃**（LibVLC / MediaCodec / ffmpeg 的 .so）
 * 恰好就是"进程直接没了"这种情形。面包屑由 :app 在启动时装上"同步写盘"的实现，
 * 于是"最后走到哪一步"能活下来（例：`创建 LibVLC 内核（未返回）`）。
 *
 * 调用点要**少而关键**（建内核、装载、起播、切内核、开始识别…），不要拿它当日志用。
 */
object Breadcrumbs {

    /** 落盘实现；:app 启动时装（见 MediaGateApplication）。未安装时是空操作。 */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** 记一条面包屑（同步写盘由 sink 决定；异常一律吞掉——诊断代码不能反过来把 App 弄崩）。 */
    fun mark(text: String) {
        runCatching { sink?.invoke(text) }
    }
}
