## mediagate 0.1.26 —— 紧急修复：更新后打不开

**先说结论：0.1.22 / 0.1.23 / 0.1.24 / 0.1.25 都装不得，请直接装这一版 0.1.26。**

### 什么坏了
0.1.22 我加「LibVLC 启动探针」时，把启动测试那一行**写在了容器创建之前**：

```kotlin
container.vlcProbeNow()          // ← container 还没赋值（lateinit）
container = AppContainer(this)   // ← 容器在这里才建出来
```

`container` 是延迟初始化属性，未赋值就访问会抛 `UninitializedPropertyAccessException`，
而这个调用在 `Application.onCreate` 里 ⇒ **进程每次启动立刻死掉**，界面根本来不及画。
这就是「更新后直接打不开了」。同一个错误让 0.1.22 起的四个版本全部中招——
0.1.21 及以前是好的（你之前那些截图都是在旧版本上截的）。

### 怎么修的
1. 把"建好之后要自动做的事"**挪进容器自己的 `init`**：只要对象建出来，顺序就不可能写错；
2. `Application.onCreate` 里不再碰 `container` 的任何方法，并加了注释说明原因；
3. **只给主进程建容器**——LibVLC 探针跑在独立进程 `:vlcprobe`，而 `Application` 在每个进程都会跑一遍，
   副进程不该建整个容器（Room/DataStore/服务在副进程里没意义，出问题只会表现为"莫名其妙崩溃"）；
4. **加了源码级回归护栏**（:app 的 `AppStartupOrderTest`）：扫描 `onCreate`，任何 `container.` 访问出现在
   赋值之前就测试失败。已验证：把旧版本源码喂给它 → 报违规 ✅；修复后 → 通过 ✅。

### 顺带
5. 播放页「极简模式」的开关不再在主线程读盘（改 StateFlow，取值即用，避免卡顿）；
6. 0.1.22–0.1.25 里那些你点名要的功能（LibVLC 探针、入队去重、任务可移除、极简模式、横滑调进度、
   预览缩略图）**全部包含在本版**，一个没丢。

### 装法
App 自己打不开，所以**别用应用内更新**：直接用手机浏览器打开下面这个链接下载安装（可覆盖安装，签名一致）：

`https://github.com/gua123/mediagate/releases/download/v0.1.26/mediagate-0.1.26.apk`
