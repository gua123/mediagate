## mediagate 0.1.36

### 你那句"其他播放器已知 WebDAV 可用"，我在开发机上又验了一遍（这次是变体矩阵）

用 `.toolchain/real-server.properties` 里的账号密码，对 `http://vpn.gua233.top:20005/` 跑了 10 种变体：

| 变体 | 结果 |
| --- | --- |
| 我们探针的原样（PROPFIND + Depth:0 + XML body） | **207** ✅ |
| 再加 OkHttp 会带的 `Accept-Encoding: identity` / `User-Agent: okhttp` | **207** ✅ |
| 不带 body | **207** ✅ |
| `Depth: 1` | **207** ✅ |
| 不带 `Depth` 头 | 403 |
| `Depth: infinity` | 403 |
| 普通 `GET /` | 404（正常） |
| `OPTIONS /` | 200 |

⇒ **我们发出去的请求形状是对的**（`Depth` 我们一直带、用的是 0/1），服务端也没问题。
那 App 里那次失败只可能来自"运行时的网络情况"或"它收到了别的东西"。

### 这一版针对"手机上的网络中断"

1. 新增分类「**网络中途断开**」——以前这类失败（`unexpected end of stream`、`connection reset` 等）会兜底成
   「请求失败」再落进「未知错误」，现在直接告诉你："连接被掐断了（移动网络/VPN 常见）：**重试一次多半就好**"；
2. 探针遇到**瞬时 IO 错误会自己重试一次**（间隔 400 ms）——VPN + 5G 这种链路上很常见，
   以前一次失败就报错，现在多半能直接过。

### 还是那句话（这次一定够定位了）

装 0.1.36 → 「连接 → 编辑连接 → 测试一下」→ 点「技术详情」→ 复制发我。
里面会写清：**请求的完整 URL、响应的状态行、响应体开头一段**——认证 / 路径 / 响应格式 / 网络中断，四选一立刻定。

### 装法

`https://github.com/gua123/mediagate/releases/download/v0.1.36/mediagate-0.1.36.apk`
