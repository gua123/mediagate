package io.github.gua123.mediagate.app

import io.github.gua123.mediagate.feature.tasks.TasksRoot

/**
 * 任务中心「来源起点」的判定（**纯函数**，2026-10-03 修 bug 时抽出来）。
 *
 * 背景（真机反馈）：「从本地切换到 sftp 后，任务列表的文件夹没有一起切换，显示的还是本地目录」。
 * 根因不在判定本身，而在**判定用的信号**：原来任务页跟着"当前连接 id"（DataStore）走，
 * 而连接 id 是在切换动作**开始时**就变了的——此时 :app 还没把远端后端装好（`_root` 仍是本地后端），
 * 于是任务页立刻按新 id 去列目录，列到的却是**本地后端**的内容，之后也不会自己再刷。
 *
 * 现在改成跟着**生效的根目录**（`remoteRoot ?: localRoot`）走，并且只在后端真正换好之后才发信号，
 * 判定逻辑收敛到这个纯函数里。
 *
 * **路径一律是后端内的相对路径**：远端后端的根就是连接的 `basePath`（见 SftpConfig 的路径语义注释：
 * 空串或 `/` 表示 basePath 本身），所以起点必须是 `""`，不能再拼一遍 `basePath`——
 * 否则 basePath=/media 时会去列 /media/media。浏览器也是从 `""` 开始的，这里与它同一口径。
 *
 * @param remoteName 生效的远端连接名；null = 当前没有生效的远端连接。
 * @param localDisplay 本地根目录的展示名；null = 本地也没选。
 * @return 任务页该从哪儿开始列（两者都是各自后端的根）；两边都没有时返回 null（页面提示"先去选目录/连接"）。
 */
internal fun tasksRootOf(
    remoteName: String?,
    localDisplay: String?,
): TasksRoot? {
    val name = remoteName?.takeIf { it.isNotBlank() }
    if (name != null) return TasksRoot(label = name, path = "")
    val display = localDisplay?.takeIf { it.isNotBlank() }
    if (display != null) return TasksRoot(label = display, path = "")
    return null
}
