## mediagate 0.1.28 —— 真凶抓到了（就是你发来的那段崩溃文本帮我抓到的）

### 你发来的那段文本，直接把答案写在了脸上

```
阶段：容器构造
异常：java.lang.NullPointerException
消息：Attempt to read from field 'a84 ei3.b' on a null object reference in method 'void ph.<init>(...)`
	at ph.<init>(SourceFile:870)
	at io.github.gua123.mediagate.MediaGateApplication.onCreate
```

我用发版时生成的 **R8 混淆对照表** 把这三个代号翻译了回来：

| 混淆名 | 真身 |
| --- | --- |
| `ph` | `AppContainer`（容器本身） |
| `ei3` | `kotlinx.coroutines.flow.ReadonlyStateFlow`（`asStateFlow()` 的返回类型） |
| `a84` | `StateFlowImpl`（它内部那个真正的流） |

⇒ "读一个 **null** 的 StateFlow 的 `.value`"：容器构造到一半，某个属性去读了**声明在它后面**的流。
定位到具体一行：

```kotlin
override val vlcUsable: Boolean? = vlcUsableState.value   // ← 宿主先建，流还没初始化
```

`vlcUsableState` 声明在容器**后面**（第 1018 行），而这个宿主是容器里**较早**构造的属性——
构造到它时那个流还是 null，于是 `Application.onCreate` → 容器构造直接 NPE。

### 为什么前几版都没查出来
- **Kotlin 不拦**：`val` 在 JVM 上默认就是 null，顺序问题编译器看不见；
- **单测拦不住**：JVM 单测不会构造这个 Android 容器；
- **0.1.26 我修的那个 `lateinit` 是另一个真 bug**（同一个提交里埋的），修掉它之后这个 NPE 才露出来；
- **R8 混淆**：报错里的类名变成 `ph/ei3/a84`，没有对照表根本读不懂——这次正好有。

### 修法与护栏
1. 那一行改成 **getter**（`get() = vlcUsableState.value`）：用到时才取，顺序就不再重要；
2. 新增**源码级护栏** `StartupPropertyOrderTest`：扫描启动关键文件，凡是
   「带类型标注的属性初始化器里去读 `.value`」一律测试失败（已实证：把 0.1.27 的源码喂进去会红，修复后变绿）；
3. 0.1.27 的"独立进程错误页 + 启动自检"保留——这次就是它把原因送出来的。

### 装法（App 打不开，直接下载安装，别走应用内更新）

`https://github.com/gua123/mediagate/releases/download/v0.1.28/mediagate-0.1.28.apk`

本版包含 0.1.22 起你点名要的全部功能：LibVLC 内核探针（改为按需触发）、任务入队去重、
「准备中（加载模型…）」、已结束任务可 🗑 移除、极简模式、横滑调进度、预览缩略图。
