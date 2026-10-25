# 方案：借鉴 nPlayer 的网络加载与播放引擎（只做 Media3 能承接的部分）

> 2026-10-25 应你要求「参照之前通过 rea 反编译的 nplayer 修改本项目的网络加载，视频播放引擎等，给出方案等待我后续确认」而写。
> **本轮只出方案，不改代码**；你点头后再按阶段做。

---

## 0. 结论先行

1. **不搬 nPlayer 的 native 引擎**。nPlayer 的播放/缓存核心是闭源 native（libnplayer.core.so / libnplayer.media.*.so + JNI），
   直接嵌入既不合规，也会把我们重新拖回「自研引擎真机黑屏」那条老路。**继续用 Media3**。
2. **可借鉴的是它的缓存/预读/旋钮/诊断设计**，尤其是 FileCachedStream2 的**块表 + LRU**、**缓存上限/缓冲时间**、
   **按编码器控制硬解**、**明确的磁盘余量检查**。
3. 最高性价比的顺序是：**P0 量基线 → P1 缓存/预读 → P2 解码器与缓冲旋钮**。
   不建议一上来重写缓存；先证明瓶颈在哪儿。
4. **可选 P3：SMB/NFS 协议**（nPlayer 有，我们没有）。如果你只用 WebDAV / SFTP / FTP，这条可以不做。
5. 核心三阶段预计 **7–11 人日**；P3 另计 **10–15 人日**。

### 2026-10-25 用户拍板

- **硬解不分编码器**：不做"每个编码器一个硬解开关"；全部按硬解走，但必须保证现有解码链路能覆盖常见视频类型。
- **渲染器不做**：SurfaceView / TextureView 切换不做。
- **缓存按推荐方案 A 改造**：保留 nPlayer 方案 B 作为后备；后续如果不满意可以再改一版，
  或者构建两套实现、用同一个 SFTP 视频跑对照，选更好的一套。
- 测试服务器凭据只放本机 `.toolchain/`（gitignore），**不写进本文、不提交**。

---

## 1. 这次逆向核对了什么（证据）

参考对象：**nPlayer Pro 1.8.0.5_230911**（com.newin.nplayer.pro）。
产物在 /tmp/rea（jadx 反编译 Java + apktool 资源 + native 库），**不在 git 里**；本文只记录行为与结构，不抄代码。

| 证据 | 位置/结论 |
| --- | --- |
| 缓存核心 | libnplayer.core.so 导出 core::FileCachedStream2、MMCachedStream、MemoryCachedStream、BufferedStream |
| 块表结构 | FileCachedStream2::BlockInfo 同时出现在 std::map<long, list_iterator> 与 std::list 中 ⇒ **块索引表 + 访问顺序链表（LRU）** |
| 块大小 | 反汇编里 0x10000（64 KiB）用于块对齐/读取；缓存上限也按 64 KiB 向上取整 |
| 磁盘余量 | FileCachedStream2::open(IOStream*, int) 先算对齐后的缓存上限，再调 core::free_disk_space()，不够就拒绝建缓存 |
| 缓存目录 | native 字符串 /com.newin.nplayer.mm_cache、.cache、App Cache Path: %s |
| 缓冲/缓存旋钮 | setNetworkBufferTime(double) / setNetworkCacheSize(int)；设置页 **Buffer Size 1–30 秒（默认 5）**、**Cache Size 0/16/32/64/128/256/512/1024 MB（默认 128 MB）** |
| 硬解控制 | 全局 use_hardware_video_decoder + **按编码器** HEVC/H.264/Hi10P/H.263/MPEG-4/2/1/MSMPEG4 v1-v3/VP9/VP8/VC1/WMV3/2/1/MJPEG/FLV1 的 18 个开关；另有 isHardwareCodecAvailable |
| 渲染/音频 | video_viewer = SurfaceView / TextureView（实验项）；音频 AudioTrack / OpenSL ES；audio boost 50–400% |
| 网络协议 | NetClient 类型含 **FTP / SFTP / WebDAV / SMB / NFS**；native 侧有 libcurl、libsmb、libnfs 等 |
| 本地 HTTP | native HTTPServer 主要用于 UPnP/DLNA、HLS 会话与 Web 上传，**不是本地播放的唯一通路** |

> 说明：nPlayer 的 FileCachedStream2 位于 **demuxer 之下**；我们的 SegmentedCacheBackend 位于 **Media3 DataSource 之下**。
> 两者是同一层（都在"字节 → 解复用器"之间），所以缓存策略可以对照；但 nPlayer 的 **解码/渲染/同步** 我们没法照搬。

---

## 2. nPlayer 与 mediagate 的现状对照

| 维度 | nPlayer Pro | mediagate（0.1.81） | 差距判断 |
| --- | --- | --- | --- |
| 播放内核 | 闭源 native：FFmpeg 解复用/解码 + 自绘/GL 渲染 + 自研同步 | **只有 Media3 ExoPlayer** | **不追平**；方向已由你拍板 |
| 缓存单位 | 64 KiB 块（块表 + LRU，可部分淘汰） | 4 MiB 段（段文件 + 文件 mtime LRU；段内 64 KiB 块级可见） | 首字节体验接近；**LRU 精度与部分淘汰**有差距 |
| 缓存上限 | 0–1024 MB，默认 128 MB | 0 / 128 MB–64 GB，默认 1 GB | 我们更宽 |
| 磁盘余量 | open 时检查 free space | 建缓存前留 25%，不够就关/降 | 已对齐 |
| 缓冲时间 | 1–30 秒，默认 5 | 3–30 秒，默认 5 | 档位少 1/2/15 秒 |
| 预读 | native 缓存内部按块推进（策略不透明） | 固定预读 4 段；滑窗一次 4 段、2 并发；下一集头尾 | **策略是固定的，没按吞吐/卡顿自适应** |
| 缓存命中后的 LRU | 读块会更新访问顺序 | 命中缓存时**不更新段文件 mtime**（只有下载时更新） | **真实差距：热缓存可能被误淘汰** |
| 解码控制 | 全局 + 18 个按编码器硬解开关 | AUTO_HW / FORCE_SW / FORCE_HW 三档全局 | **用户已拍板：不分编码器、全部走硬解**，不做这个面板 |
| 渲染器 | SurfaceView / TextureView | Media3 PlayerView（SurfaceView） | **用户已拍板：不做** |
| 协议 | FTP / SFTP / WebDAV / SMB / NFS | WebDAV / SFTP / FTP | 缺 SMB / NFS |
| 网络栈 | native libcurl（HTTP/2、连接缓存、代理）+ libsmb/libnfs | OkHttp（WebDAV）+ JSch（SFTP）+ Commons Net（FTP） | 我们三条协议够用；SMB/NFS 是功能缺口，不是性能缺口 |
| 元数据 | 独立 MediaInfoFetcher | Media3 自己解析 | 不明显值得另做 |

**结论**：我们不是"没有块级缓存"——0.1.76 已经做了块级可见。真正值得借鉴的是：
**LRU 要按访问更新、预读要自适应、解码器要能按编码器控制、缓冲档位补齐**；
SMB/NFS 是可选功能；native 引擎不碰。

---

## 3. 方案（按性价比排序）

### P0：基线与可观测（1–2 人日，不改行为）

目的：先证明"nPlayer 快一倍"具体快在哪，避免凭感觉改缓存。

- 在现有诊断卡新增/补齐一行 **「网络加载」**：
  - 本次播放的 **请求数 / 读到的字节 / 缓存命中字节 / 命中率**；
  - **起播耗时**（已有）、**seek 到出画**（已有）、**卡顿次数**（可用 Media3 的 onIsLoadingChanged 统计）；
  - 当前 **段大小 / 预读段数 / 缓存上限 / 缓冲秒数**。
- 用同一个文件、同一条连接（LAN/WAN 各一次）各跑：
  - mediagate 0.1.81；
  - nPlayer Pro（同机、同网络）；
  - 记录"首次打开 / 已缓存后打开 / 拖到 50% / 回拖到 20%"四组数。
- 产出：docs/验收记录-网络加载-nPlayer对照.md（数字 + 截图/诊断文本）。
- **验收**：报告能回答"差距在首字节、吞吐、还是缓存命中"；不改任何播放行为。

### P1：缓存/预读（4–6 人日，最可能出效果）—— **2026-10-25 已落地 A**

只改 data/storage-api 与 app/PrefetchWindow，不碰 Media3 解码。

1. **LRU 按访问刷新**（nPlayer 块表 LRU 的最小等价物）
   - CachedStream 命中磁盘段时更新该段的访问时间（内存 lastAccess 优先，避免每次读都写 mtime；定期/淘汰前落盘）。
   - evictIfNeeded 仍保护 activePath 与 .pin；补一条回归："连续读 A、再读 B，A 的段不应比 B 先被淘汰"。
   - 目标：缓存上限较小时，热文件不再被冷文件挤掉。
2. **自适应预读**（替代固定 4 段）
   - 输入：最近 N 秒的吞吐（bytes/ms）、是否发生过 seek、是否卡顿、当前缓存余量。
   - 策略：稳定顺序播放→逐步加大预读（上限仍 4 段）；频繁 seek/带宽下降→立刻降到 1–2 段；接近缓存上限→不加大。
   - 纯函数 + 单测（给定吞吐/seek 序列，输出预读段数）；默认行为与现在一致，设置里可关"自适应"。
3. **段大小/预读仍由用户可调**（已有），但把"自适应"和"手动值"的关系写清楚：手动值当上限，自适应只能往下调。
4. **缓存清理补强**：clearAll 已修 epoch；再补"孤儿目录/过期 .tmp"定期清理（低优先级）。

**实测计划（用户提供的 SFTP 测试链接，凭据只在 .toolchain，不写进仓库）**：
- 用同一路径的视频跑 1 / 2 / 4 / 8 MB 段大小的对照：冷首帧、顺序读 16 MB、随机 seek、缓存命中各测一遍；
- 默认选综合最好的一档；如果 A（访问 LRU + 自适应预读）仍明显落后，再评估 B（64 KiB 块表）。

**验收**：
- 同一文件/网络，0.1.81 vs 本阶段：**首帧不退化**，**seek 出画耗时至少改善 15%**（或缓存命中率提升 ≥10 个百分点），卡顿次数不增加；
- 若数据证明没有改善，**回退自适应预读**，只保留 LRU 修复。

### P2：硬解覆盖与缓冲档位（2–3 人日）—— **硬解覆盖诊断已落地，缓冲档位待做**

**用户拍板：不做按编码器硬解面板；全部按硬解走，但保证常见视频类型可解析。**
这里的"全部硬解"指**不再给用户按编码器切软/硬的开关**，默认始终硬解优先；
设备确实没有对应硬解码器时，仍由 Media3 的 fallback 兜底（否则那类文件会直接不可播，违背"保证常见类型"）。

1. **硬解为默认，不新增按编码器开关**
   - 保持 AUTO_HW 默认（Media3 硬解优先、允许解码器回退）；不新增"每编码器软/硬"面板。
   - 确认 DefaultRenderersFactory 的 setEnableDecoderFallback 已开（已在）；硬件解码失败要给中文原因，不许静默黑屏。
   - **常见类型覆盖验证**：用 MP4(H.264/HEVC)、MKV、TS、AVI/FLV 等样本在真机跑一遍；
     把实际解码器名 / 是否硬解写进 DecoderReport 与诊断卡，供你截图确认。
2. **缓冲档位补齐**：在现有 3/5/10/20/30 秒基础上加 **1 / 2 / 15 秒**（nPlayer 是 1–30），默认仍 5 秒。
3. **顺手清理**：DecoderSelection.note 里还写着"切 LibVLC 内核"——LibVLC 已下线，这类陈旧文案要改。
4. **不做的**：渲染器切换、按编码器硬解开关、显式的软/硬解码器面板。

**验收**：
- 常见视频类型（MP4/H.264、HEVC、MKV、TS、AVI/FLV）在真机能起播；DecoderReport 显示实际解码器；
- 硬件解码不可用时按 Media3 回退，并给出可读原因；不出现黑屏/崩溃；
- 缓冲档位可保存、重启生效。

### P2.5（可选）：多音轨选择（2–3 人日）

- nPlayer 有 getTrackCount / getTrack / selectTrack 这类音视频轨切换；我们目前没有暴露多音轨选择。
- Media3 本身支持 DefaultTrackSelector；可以在播放页加一个"音轨"芯片（只在文件确实有多条音轨时出现），列出语言/声道/码率，切换不重建内核。
- 收益：多语言/国粤双语的片子不用回桌面换播放器；与"加载更快"无关，属于引擎能力补齐。
- 如果本次只关心速度，这条可以后置。

### P3（可选）：SMB / NFS 协议（10–15 人日，独立立项）

- nPlayer 的 NetClient 明确有 SMB / NFS；native 侧有 libsmb/libnfs。我们只有 WebDAV/SFTP/FTP。
- 如果**你的服务器确实有 SMB/NFS 共享**，再考虑：
  - SMB：smbj（SMB2/3，Android 可用性较好）或 jcifs-ng；需要处理域/来宾/签名/大文件 Range。
  - NFS：nfs-client-java 一类库；NFSv3/v4、UID/GID、锁、Android 兼容性都要实测。
  - 新模块 data/storage-smb / data/storage-nfs，复用 StorageBackend / RandomAccessSource，接连接编辑器与选路。
- **建议**：除非你现在就有 SMB/NFS 服务器，否则不做；它不会让现有 WebDAV/SFTP 播放变快。

### 明确不做

- **不嵌入 nPlayer 的 .so / JNI**：闭源、许可、ABI、体积、维护都不合适；也违背"只保留 Media3"的既定方向。
- **不重写 native 播放引擎**：自研引擎已在真机黑屏后下线，教训已记档。
- **不为了对齐 nPlayer 把 4 MB 段直接改成 64 KiB 段**：nPlayer 的 64 KiB 是 native 块表单位；
  我们的 4 MB 段 + 64 KiB 块级可见已经覆盖"首字节不等整段"的体验。**先测再决定**。
- **不搬本地 HTTP Server / UPnP / DLNA**：nPlayer 的 HTTP Server 主要服务 UPnP/HLS/Web 上传，Media3 用 DataSource 不需要它。
- **不做音频直通 / OpenSL ES / audio boost**：与本次"加载更快"无关；需要时单独立项。

---

## 3.5 本次落地（2026-10-25）

- **P1 A 已实现**：
  - 段访问 LRU：命中缓存会刷新访问序号，不再只看"下载时间"（修掉热缓存被冷段误淘汰）；
  - 自适应预读：按最近吞吐与 seek 状态把一次滑窗抓取从 4 段往下收，用户设置仍是上限；
  - 缓存统计：命中/未命中、下载字节、最近吞吐，进设置页诊断卡；
  - 回归：LRU 热/冷段、冷读/命中统计、清空缓存作废旧任务。
- **真机前的 SFTP 基准已完成**（同一 1 GB 视频，1/2/4/8 MB 段）：4 MB 综合最好，
  **默认段大小不变**；明细见 `docs/验收记录-网络加载-SFTP基准.md`。
- **硬解覆盖诊断已实现**：设置页诊断卡显示 H.264/HEVC/VP9/AV1/MPEG-4/MPEG-2/H.263
  各自的硬解/软解数量；**不提供按编码器切换**（按你的拍板）。
- **明确未做**：缓冲 1/2/15 秒档位、渲染器切换、B 方案（64 KiB 块表）、SMB/NFS。

## 4. 验收指标（客观、可对比）

| 指标 | 采集方式 | P0 目标 | P1/P2 目标 |
| --- | --- | --- | --- |
| 首帧耗时（未缓存） | 诊断卡已有"起播" | 测出 LAN/WAN 基线 | 不比 0.1.81 差；有改善则记录 |
| 已缓存文件首帧 | 同上 | 测出 | ≤ 0.5 s（LAN） |
| seek 到出画 | 诊断卡已有"seek 等待" | 测出 | 比基线改善 ≥15% 或命中率 +10pp |
| 缓存命中率 | 新增统计 | 测出 | 提升或持平 |
| 卡顿次数/分钟 | Media3 loading 事件 | 测出 | 不增加 |
| 与 nPlayer 对比 | 同机同文件同网络 | 记录四组场景 | 首帧 ≤ nPlayer×1.3；seek ≤×1.5（目标，不承诺追平 native） |

> "不承诺追平"是实话：nPlayer 的 native 解复用/解码/渲染是一整套，Media3 下我们只能优化数据层与旋钮。
> 但如果 P1 后仍明显落后，说明瓶颈在 **链路/服务器** 或 **Media3 解码器**，不是缓存；到那时再决定要不要为某个格式做特殊处理。

---

## 5. 风险与回退

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 自适应预读反而增加流量/卡顿 | 弱网更差 | 默认保守；可一键关；A/B 数据说话；失败回退 |
| LRU 改动导致缓存被误删 | 回拖变慢 | 保留 activePath/.pin；新增"热文件不被冷文件挤掉"回归 |
| 按编码器过滤解码器破坏 secure/tunneled | 受保护内容播不了 | 不允许覆盖 secure/tunneled；失败回退默认并上报 |
| SMB/NFS 库在 Android 上不成熟 | 新协议不可用 | 独立模块、opt-in、真机验收；不影响现有三协议 |
| 没有 adb，真机只能靠你 | 改完不知道好坏 | 每阶段给可复现对比步骤 + 诊断卡可复制文本 |
| 逆向产物在 /tmp，可能丢 | 以后无法复核 | 本文记录关键结论；不依赖仓库外代码 |

---

## 6. 需要你拍板（每项带我的推荐）

1. **范围**：只做网络/缓存 + 解码旋钮（推荐），还是同时加 SMB/NFS？
   - 推荐：先做核心三阶段；SMB/NFS 除非你现在就有服务器，否则不做。
2. **缓存改造深度**：
   - A（推荐）：保留 4 MB 段缓存，补 **访问 LRU + 自适应预读 + 统计**；
   - B：重写成 nPlayer 式 **64 KiB 块表 + 稀疏缓存文件 + 块级淘汰**（成本高、收益未证，不推荐先做）。
3. **按编码器硬解开关**：**已拍板不做**（全部硬解；仅保留 Media3 的不可用回退）。
4. **渲染器 SurfaceView/TextureView 切换**：**已拍板不做**。
5. **缓存对照**：先按 A 做并测；如果 A 的数据不够好，再决定是否让我补 B（64 KiB 块表）做同路径 A/B。
6. **验收口径**：同文件、同网络对比 nPlayer 的四组数（未缓存首帧 / 已缓存首帧 / seek / 回拖），接受"不承诺完全追平 native 引擎"吗？
7. **阶段顺序**：P0 → P1 → P2（推荐）；SMB/NFS 与多音轨是否后置？

---

## 7. 参考

- 现有方案：docs/方案-网络加载增强.md（P0/P1/P1.8/P1.9 已落地，P2 已作废）。
- 现有引擎：media/engine/.../ExoPlayerEngine.kt、DecoderSelection.kt、PlaybackBufferPolicy.kt。
- 现有缓存：data/storage-api/.../SegmentedCacheBackend.kt、CacheTuning.kt。
- 本次逆向：/tmp/rea（nPlayer Pro 1.8.0.5_230911；仅作行为参考，不复制代码）。
