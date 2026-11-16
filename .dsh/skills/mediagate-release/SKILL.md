---
name: mediagate-release
description: mediagate 发版全流程（版本核对、全量测试、推源码、打 tag、发 Release、匿名复核、冻结源码）。要发新版或准备发版时使用。
---

# mediagate 发版流程

> 项目：/root/project/mediagate｜纪律出处：AGENTS.md、用户 2026-10-02/10-03 定下的规则

## 0. 先判断该不该发

- **重大错误（崩溃 / 打不开 / 数据丢失 / 核心功能不可用）修好 → 立刻发**，不等攒批。
- **小改动、文案、内部重构 → 攒着**，写进下一版 release notes 草稿，等攒到用户能感知的东西再发。
- 判据站在用户角度：**他能感觉到区别吗？**

## 1. 发前必做（顺序不能反）

1. **核对版本号**：`app/build.gradle.kts` 的 versionName / versionCode，与 `update.json` 的已发布版本对比，确认领先量。
2. **全量测试**：`source scripts/env.sh && ./gradlew :app:testDebugUnitTest`（以及受影响模块）。**注意：`publish-update.sh` 自己只跑 `assembleRelease`，不跑测试**——这一步必须人工补。
3. **文档同步**：本次改动涉及的 README / docs/plan.md / AGENTS.md 必须与代码现状一致（全局 AGENTS.md §1 硬要求）。
4. **工作区干净**：`git status` 无未提交改动，`.dsh-meow/` 未被跟踪。

## 2. 推源码（最容易漏的一步）

```bash
git push origin HEAD:main
git ls-remote origin refs/heads/main   # 用 ls-remote 复核，别只看 exit code
```

**为什么必须先推**：`publish-update.sh` 是先建 GitHub Release（L91 一带）再 push（L173 一带）。若不先推源码，Release 的 `v<version>` tag 会落在**旧的远端 HEAD**上，源码与产物对不上。

## 3. 发版

```bash
source scripts/env.sh
./gradlew :app:assembleRelease        # 或交给脚本
scripts/publish-update.sh             # 内含：构建/复用 APK → 建 Release → 传资产 → 写 update.json → commit+push
```

要点：资产名**不能带中文**（服务端会吞掉，需 PATCH 改名）；脚本会先 DELETE 同名资产再上传。

## 4. 补 tag 并核对指向（脚本不做）

```bash
git tag -a vX.Y.Z -m "mediagate X.Y.Z"
git push origin vX.Y.Z
git rev-parse vX.Y.Z^{commit}   # 必须等于 release commit
```

## 5. 匿名复核（脚本没有，必须人工）

- 不带任何凭据 `curl -s https://raw.githubusercontent.com/.../update.json` → 200 且字段正确
- **匿名下载 APK**，比对 `sha256sum` 与 `update.json` 的 sha256、`sizeBytes` 逐字相等
- 核对 tag 指向 release commit
- 口径参考 `docs/验收记录-M2-M8.md`

## 6. 收尾

- 给用户 APK 直链 + sha256。
- 冻结源码（发版后不再往该 tag 加提交）。
- 把本次改动同步进文档；关掉对应的发布待办。

## 坑清单

| 坑 | 应对 |
|---|---|
| 脚本不跑测试 | 人工跑全量再发 |
| 脚本不建 tag | 手工 `git tag -a` + `push` + 核对指向 |
| 不先推源码 → tag 落旧 commit | 第 2 步先 push，用 `ls-remote` 复核 |
| 资产名中文被吞 | 用 ASCII 名，或 API PATCH 改名 |
| 私有库下载链接 404 | 走 API 下载资产，别用 releases/download |
| 只看 exit code 判断推送成功 | 一律 `git ls-remote` 复核 |
