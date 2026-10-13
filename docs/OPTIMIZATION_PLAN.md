# TVDouyinNative 优化计划（按你的硬性规则逐条对账）

> 原则：**不整体替换 layout**，只在现有 xml / Java 上做局部修改。
> 每条都标出「文件 → 改什么」，做完打勾。

---

## 诊断快照（本次实测）

```
dex 方法数 = 61612 / 65536      ← 仅剩 3924 个方法余量，这是引 RecyclerView 的硬约束
minSdk = 16   targetSdk = 34    ← 与你要求的 API17 基线有偏差，见 P0-0
布局文件 7 个，源码 16 个
```

违规清单：

| 硬性规则 | 现状 | 位置 |
|---|---|---|
| 列表必须 RecyclerView | ❌ ScrollView 承载内容 | `activity_main.xml:2` / `:60` |
| 列表必须 RecyclerView | ❌ ListView | `activity_list.xml:42` |
| 列表必须 RecyclerView | ❌ ListView | `dialog_comments.xml:28` |
| 禁触摸监听 | ❌ OnItemClickListener | `AuthorActivity.kt:64` / `CommentDialog.kt` |
| 焦点高亮 selector | ❌ 仅 btn_chip 一个 | `res/drawable/` |
| 图片占位/兜底 | ❌ 无 | `ImageLoader.kt` |
| Emoji 降级 | ❌ 直接渲染原始字符 | `CommentDialog.kt` |
| 控制栏默认隐藏 | ⚠️ 16 处调用点，计时器被反复重置 | `PlayerActivity.kt` |
| 暂停时控制栏可见 | ❌ 逻辑颠倒 | `PlayerActivity.kt` |
| 字体放大 | ⚠️ 沿用手机 dp | 各 layout |

---

## P0-0　基线对齐（需你确认）

当前 `minSdk 16 / targetSdk 34`，你要求 **API17 基线**。

- 改 `targetSdk 17` 的**收益**：不触发 API18+ 的运行时行为变更，定制 ROM 上最可预测
- **风险**：`targetSdk 17` 时系统按旧规则处理存储/权限，若现有代码依赖新行为需一并调整

**默认动作**：把 `targetSdk` 降到 17，`minSdk` 提到 17（与你设备一致）。
若你希望保留 targetSdk 34 以放宽某些行为，说一声，我不动它。

---

## P0-1　RecyclerView 替换（3 处列表）

**难点**：dex 只剩 3924 方法，而 support-v7 recyclerview 约 2500~3500 方法（含其 core 依赖），**大概率踩线**。

**处置顺序**：
1. 先只加依赖，编译并实测 dex 方法数
2. 若超 65536 → 腾空间，**优先删 gson**（改用系统 `org.json`，省约 1500 方法）
   - 再不够则删 okhttp（TLS 降级已可由 `HttpsURLConnection` + `Tls12SocketFactory` + `RelaxedTrustManager` 承担）
3. 替换：`activity_main.xml`(ScrollView→RecyclerView)、`activity_list.xml`、`dialog_comments.xml`
4. 每个 RecyclerView 配 TV 焦点属性：
   ```xml
   android:focusable="true"
   android:focusableInTouchMode="true"
   android:descendantFocusability="afterDescendants"
   ```
5. item 布局加 `android:focusable="true"` + 焦点 selector（放大/描边）

---

## P0-2　控制栏显隐（TV 通用范式）

目标行为：
1. **默认隐藏**
2. 任意按键 → 显示 + 启动倒计时 → 到点自动隐藏
3. **切换视频/翻页必须重置计时器**
4. **暂停时控制栏保持可见**（不自动隐藏）；**播放时自动隐藏**
5. 控件尺寸放大（TV 远距离）

做法：把散落的 16 处 `showControls()` 收敛为单一入口 + 明确状态机
（`PLAYING` 自动倒计时 / `PAUSED` 不自动隐藏），避免"计时器被反复重置导致常驻"。

---

## P0-3　焦点体系

1. 全部可操作控件补 `focusable` / `focusableInTouchMode`
2. 新增焦点 selector：`drawable/focus_chip.xml`、`drawable/focus_card.xml`
   （放大 → 用 `scale` 不可行于 selector，改用**描边 + 背景变亮 + 轻微内缩**）
3. `nextFocusUp/Down/Left/Right` 显式指定，防焦点飞出屏幕
4. 页面切换 / 翻页 / 切视频后 **显式 requestFocus 到默认控件**

---

## P0-4　图片占位与兜底

`ImageLoader.kt` 增加：
- 加载中占位图
- 失败兜底图
- 复用已取消的请求（避免列表中错位）

---

## P0-5　Emoji 降级

Android 4.2 无完整 emoji 字体（渲染成方块）。在**评论/文案渲染前**做降级：
- 检测到 emoji 码点 → 替换为等价文字或直接过滤
- 底层不改原始数据，只在 UI 展示层转换

---

## P1　播放层

- 现状：错误降级把系统 MediaPlayer 当主力 → 无声/缓冲久/误报失败/内存泄露/ANR
- 目标：**ExoPlayer r1.5 为主力**，MediaPlayer **仅静态降级**（不自动切换）
- **全局单实例**：切换视频先完整 release 旧实例（禁并行跑多个解码器）
- 边播边拉流，**绝不落盘**

---

## P1　业务逻辑

- Cookie：修复"登录后仍游客模式"
- 信息流：cursor 分页
- 竖屏过滤开关

---

## 明确不做的部分

按你的要求，以下**不由我实现**（你手动重构）：
- 完整页面跳转与列表业务
- 评论/作者页的完整交互与布局重构
- 焦点体系之上的一套"TV 交互流"

我只保证：**底层能力齐备 + 现有 UI 局部修正到符合硬性规则**。
