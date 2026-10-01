## mediagate 0.1.12

**「开始生成字幕就闪退」找到根因了**——你发来的诊断日志把栈摆得明明白白：

```
Service.startForeground
  → IActivityManager.setServiceForeground
    → Parcel.readException      ← 系统直接拒绝了这次"转前台"
      → AsrForegroundService.onCreate
```

### 根因
字幕服务是 `START_STICKY`（被系统杀掉后由系统重新拉起）。App 进程在**后台**被拉起来时，
`onCreate` 里立刻把服务"转前台"，而 `mediaProcessing` 这个前台服务类型**不允许从后台进入前台**，
系统拒绝 → 抛异常 → 服务崩 → 整机闪退。（日志里正好是"启动 → 恢复字幕队列 3 项 → 崩"，
就是被系统在后台拉起的那一瞬间。）

### 修法
- **不再让系统在后台重启它**：`onStartCommand` 改返回 `START_NOT_STICKY`。队列状态本来就存在数据库里，
  你下次进 App 会看到「已中断，可续跑」，点一下继续即可——比在后台偷偷跑更符合系统规则，也不会再闪退。
- **前台化失败就不硬跑**：`onCreate` 里记下"没前台化成功"，`onStartCommand` 直接退场，
  不去触发另一条崩溃路径（"startForegroundService 没有按时 startForeground"）。
- **拉起失败如实反馈**：`startService` 现在返回成败，起不来就把队列停在「已暂停」，
  不让你对着一个永远不动的"运行中"发呆。

### 说明
- 正常用法不受影响：**你在 App 里点「开始/继续」时，App 一定在前台**，前台化是合法的，照旧后台跑、息屏也跑。
- 包名不变，可直接覆盖安装（这一版也包含 0.1.11 的播放页改造：转屏、白字、同文件夹列表直跳）。
