# 原有能力清单（改造前必读）

> **教训**：本项目出现过三次"越改越丢"——覆盖原图标 / 凭空设计已存在页面 / 丢掉原搜索页的
> 实时联想与拼音纠错。根因都是**没先读原实现**。
> **规则**：改动任何页面前，先按本文件格式列出该页原有能力，新设计必须是**超集**。

---

## SearchActivity（搜索页）
- 三段式布局：键盘 / 实时联想 / 结果
- **实时联想**：防抖 320ms（SUG_DELAY），接口 `DouyinApi.fetchSuggest`
- **拼音容错**：打 `meishi` 自动纠错为「美食」；联想接口直接返回词
- **输入为空**：本地搜索历史**先立即显示** + 热搜榜（匿名接口）**异步合入**，点词即搜
- 两段式：有联想词才显示，无词则收起
- 手机输入提示（tvPhoneHint）
- 状态：加载中（pbLoading）/ 空（placeholder）/ 错误重试（btnRetry）
- 清空（btnClear）/ 返回（btnBack）/ 屏幕键盘区（keyboardBox）

## PlayerActivity（播放页）★ 能力最多
- **双解码引擎**（ExoPlayer / IJKPlayer）可切换（引用 90 处，是核心能力）
- **弹幕**渲染与开关（33 处）
- **线路切换**（perfLine 性能线路，27 处）
- **画面比例**切换（25 处）
- **黑屏看门狗**：出首帧检测，黑屏自动重载（17 处）
- **续播/进度保活**：Prefs.touchResumeAlive（16 处）
- **遥控按键直达**：dispatchKeyEvent，**数字键 5 = 点赞**（16 处）
- **播放模式**：自动连播 / 单集循环（14 处）
- **倍速**（12 处）
- **手势**：GestureDetector（7 处）
- **控制栏自动隐藏**：restartHideTimer（需定义唤出方式）
- **后台播放**：PlayService（3 处）
- **接收手机推送播放**（4 处）
- **内存降级/主动重建**：maybeRestartByMemory（9 处，老设备 647MB 约束）
- 音量控制（AudioManager，7 处）
- 菜单入口：作者主页 / 评论区 / 播放模式 / 切换引擎 / 设置

## 其他页面（待补盘）
| 页面 | 需盘点的能力 |
|---|---|
| MainActivity | 分类 Tab 记忆、焦点记忆、加载策略 |
| HotActivity | 热搜刷新机制、时间标记 |
| HistoryActivity | 历史分组 / 清除 / 续播入口 |
| CollectActivity | 收藏夹分组 |
| AuthorActivity | 作品/喜欢分段、关注态 |
| LoginActivity | 11 个 Toast 反馈点、3 个 Dialog 确认流程 |
| AboutActivity | 二维码、开源许可、更新入口 |
| SettingsActivity | 各设置项与 Prefs 键的对应关系 |