## mediagate 0.1.52 —— 「检查更新连不上 GitHub」的修法与自查

### 你说的现象我信：网络正常，但 App 里连不上 GitHub

原因很具体：**清单走的是 raw.githubusercontent.com**，而它在国内经常不可达，而且——
**你为 GitHub 配的代理/VPN 未必覆盖这个域名**（很多人只代理了 github.com）。

### 这一版做了两件事

**① 清单多了一个国内可达的镜像源（jsDelivr）**

现在会依次尝试三个地址，取 versionCode 最高的那份：

| 顺序 | 地址 | 说明 |
| --- | --- | --- |
| 1 | `raw.githubusercontent.com/…/main/update.json` | 主源，最新（有 CDN 缓存最多 5 分钟） |
| 2 | `raw.githubusercontent.com/…/refs/heads/main/update.json` | 同一份文件的另一条缓存键 |
| 3 | **`cdn.jsdelivr.net/gh/gua123/mediagate@main/update.json`** | **国内通常不需要代理就能读**（刚实测：直连 200、内容就是最新版） |

只要有**任何一个**能读到，检查更新就能正常工作。

**② 失败时把"每个地址的结果"写出来**

以前只说一句「连不上 GitHub」，现在会跟着一串证据，例如：

```
连不上 GitHub：检查更新需要能访问 GitHub（可能需要代理）
（✗ raw.githubusercontent.com/update.json → 连不上 GitHub；
  ✗ raw.githubusercontent.com/refs → 连不上 GitHub；
  ✗ cdn.jsdelivr.net/update.json → 连不上 GitHub）
```

一看就知道是"全都不通"还是"只有 raw 不通"——不用再猜。

### 如果三个都不通（那说明当前网络确实出不去）

- 把代理**改成按域名分流**：`github.com`、`raw.githubusercontent.com`、`objects.githubusercontent.com`、`cdn.jsdelivr.net` 一起走代理；
- 或者直接**用浏览器打开**发布页下载（浏览器里你的代理是生效的）：
  `https://github.com/gua123/mediagate/releases`
- 顺便说：**APK 本身仍然来自 GitHub Release**（67 MB，任何免费 CDN 都不接这么大的文件），
  所以下载那一步还是需要能访问 GitHub；只有"检查有没有新版本"这一步现在可以不靠代理了。

### 装法

`https://github.com/gua123/mediagate/releases/download/v0.1.52/mediagate-0.1.52.apk`
