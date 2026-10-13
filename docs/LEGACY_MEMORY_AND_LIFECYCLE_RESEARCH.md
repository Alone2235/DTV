# 老设备视频 App 内存 / 生命周期研究（Android 4.2.2 · API 17 · 647MB）

> 研究对象：`com.example.douyintv`（Kotlin + AndroidX + ExoPlayer 2.18.1 + ijkplayer 0.8.8）
> 目标机：创维 8S52 E510E（MStar "nike" / `full_sky818a_e510`，Android 4.2.2，API 17）
> 参照 App：GITV / 爱奇艺（`com.gitvdemo.video`，v16.0.0.205693）、云视听（`com.xiaodianshi.tv.yst`）
> **本文所有"实测"数字均为本次在真机 `10.63.27.191:5555` 上直接采集，非推断。**

---

## 0. 先修正一个度量口径（否则会一直优化错东西）

任务描述里的症状是「dumpsys meminfo 实测 Graphics 从 7.6MB 涨到 34.6MB」。
本次在这台 ROM 上实测 `dumpsys meminfo <pid>`，**输出里根本没有 `Graphics` 分类行**：

```
                         Shared  Private     Heap     Heap     Heap
                   Pss    Dirty    Dirty     Size    Alloc     Free
       Native / Dalvik / Cursor / Ashmem / Other dev / .so mmap /
       .jar mmap / .apk mmap / .ttf mmap / .dex mmap / Other mmap / Unknown
```

`Graphics` 这一档是较新版本（API 19+）才加进 `Debug.MemoryInfo` 的。所以：

- **API 17 上图形/GPU 缓冲最接近的桶是 `Other dev`（/dev 映射 = gralloc / ion 缓冲）与 `Unknown`。**
- "7.6→34.6MB" 这个读数若不是来自别的工具（`dumpsys SurfaceFlinger`、厂商 meminfo、`/proc/<pid>/smaps`），
  那它很可能是把 `Other dev` 或 `Unknown` 当成了 Graphics。
- **动手前先固定度量口径**：请注明这 4 个数字分别来自哪条命令。下文按 `Other dev` / `Unknown` / `Dalvik` / 系统 `MemFree` 四路观测。

### 本机基线（实测）

```
MemTotal           662696 kB   (647 MB)
SwapTotal/SwapFree       0 kB   ← 无 swap，内存压力只能靠杀进程解决
MemFree             31248 kB   (空闲 30MB)
Cached              59660 kB
Active(anon)       356132 kB   ← 各 App 的堆总和，几乎压满
dalvik.vm.heapgrowthlimit = 96m
dalvik.vm.heapsize        = 256m   ← largeHeap 的上限就是它
/sys/module/lowmemorykiller/parameters/minfree = 4712,6181,7649,9697,11165,13521
/sys/module/lowmemorykiller/parameters/adj     = 0,1,2,4,9,15
```

**LMK 阈值换算（页×4KB）与含义**——这是判断"到底谁被杀、为什么"的硬尺子：

| MemFree 低于 | 会被杀的进程 adj | 折算 |
|---|---|---|
| 52.8 MB | adj ≥ 15 | 空进程 |
| 43.6 MB | adj ≥ 9 | 缓存进程（后台） |
| 37.9 MB | adj ≥ 4 | 重量级/上一级后台 |
| 29.9 MB | adj ≥ 2 | 可感知（含前台服务） |
| 24.1 MB | adj ≥ 1 | 可见进程 |
| **18.4 MB** | **adj ≥ 0** | **前台进程** |

**→ 关键推论：用户报告的"MemFree 跌到 27MB 就被杀"在数学上不可能是 LMK 干的。**
27MB 只够触发 `adj ≥ 2` 那一级，而"正在播放的 PlayerActivity"是 `adj 0` 的前台进程，
LMK 在 27MB 时**不会**碰它。所以「退回首页」的真实原因只剩两类：

1. **进程自己死了**：`java.lang.OutOfMemoryError`，或 native 崩溃（解码器/Surface 相关的 SIGSEGV/abort）→ 进程重启 → 落到 `MainActivity`。
   本项目**已经有一次 tombstone 铁证**：把 IJK 改成 `reset()` 复用时，`MediaCodec_loop` 线程 SIGSEGV，进程死掉 → 表现为"播着播着退回首页"（见 `Engines.kt` 注释）。
2. **内核 OOM killer**（不是 LMK）：低内存下某次大块连续分配失败，内核挑 `oom_score` 最高的进程杀。

**行动含义**：先别继续加"更省内存"的补丁；**先确认是 OOM 还是 native 崩溃**（`adb shell ls /data/tombstones`、
`adb shell dumpsys dropbox --print | Select-String -Pattern "data_app_crash|native_crash"`）。
这两个方向的修法完全不同。

---

## 1. Q1 — API 17 上播放器 App 的内存 / 生命周期关键做法

### 1.1 本项目当前的真实缺口（grep 结果）

```
grep "onTrimMemory|onLowMemory|registerComponentCallbacks" app/src → 0 命中
```

`PlayerActivity`、`MainActivity`、`App` **都没有实现 `onTrimMemory`**。这是当前最大的、成本最低的缺口。

### 1.2 `onTrimMemory` 是 API 17 上唯一"系统主动求助"的通道

- `ComponentCallbacks2.onTrimMemory(int)` 与全部 `TRIM_MEMORY_*` 常量**从 API 14 起可用**，API 17 完全支持（无需兼容库）。
- `Activity` / `Application` / `Service` / `Fragment` 都能收到；也可以在任意对象上 `application.registerComponentCallbacks(cb)`。
- 三级语义（对应本机现实）：

| 回调 | 含义 | 本项目该做什么 |
|---|---|---|
| `TRIM_MEMORY_UI_HIDDEN` | UI 已不可见（进播放页时 MainActivity 收到） | **立刻** `ImageLoader.trimTo(0)` 或 `evictAll()` + 释放列表临时对象 |
| `TRIM_MEMORY_RUNNING_MODERATE/LOW` | 我还在前台，但系统紧张 | 收缩图片缓存到 1~2MB；不要动播放器 |
| `TRIM_MEMORY_RUNNING_CRITICAL` | 系统马上要杀我了 | 释放所有非播放必需资源；**播放器/ Surface 不能在这里动** |
| `TRIM_MEMORY_BACKGROUND/COMPLETE` | 我在后台 | 清空一切可重建的缓存（含 FeedBus） |

**注意反模式**：`onTrimMemory` 里**不要**去 `release()` 播放器或重建 Surface —— 那会在前台播放时造成黑屏/重启播放。
要释放的是"缓存类"资源（Bitmap、JSON、列表），不是"正在用的"资源。

### 1.3 进程优先级：`adj 0` 已经免费拿到了，不需要前台服务

- 只要 `PlayerActivity` 是 resumed，进程就是 `adj 0`（实测：GITV 的 HomeActivity 可见时 `oom_score_adj=0`；我们的 App 退到后台时是 411 ≈ 缓存级）。
- API 17 上**没有**任何公开 API 能把 `adj` 再往上调；唯一的降 adj 手段是 `startForeground()`（→ adj 2），**比可见 Activity 的 adj 0 还差**。
- **结论：播放期间不需要前台服务。** 引入前台服务只会：多一个通知、把进程在退出播放后继续钉在内存里、并**不**提升优先级。

### 1.4 Service 常驻 vs 完全退出

实测 GITV 的答案很清楚：**不常驻播放器**。

```
ServiceRecord{... com.gala.video.plugincenter.install.pm.PluginPackageManagerService}
   startRequested=true  stopIfKilled=true   ← 普通 started service，非前台
ServiceRecord{... com.gala.video.plugincenter.sdk.delegate.LocalService}
   startRequested=true  stopIfKilled=false  ← 也不是前台
Wake Locks: size=2   ← 只有 com.dangbeimarket 持有，GITV 一个都不用
```

它**没有前台服务、没有 wake lock**，播放器不常驻。所以"用常驻服务保命"这条经验在老设备上是**错的**：
647MB 的机器上，多留一份解码器 + 堆 = 把系统推向 LMK 阈值，反而更容易在被杀时牵连自己。

**建议**：保持"退出播放页即彻底拆"的模型；唯一值得常驻的是**无内存成本**的东西（我们不引入）。

### 1.5 `largeHeap="true"` 在这台机器上很可能是负收益（重要）

本机 `heapgrowthlimit=96m`、`heapsize=256m`。Manifest 里已经开着 `android:largeHeap="true"`：

- 开 → Dalvik 堆上限 = **256MB**（`getLargeMemoryClass()`）
- 关 → Dalvik 堆上限 = **96MB**（`getMemoryClass()`）

**在 647MB、无 swap 的设备上，把一个前台进程的 Java 堆上限抬到 256MB 等于拆掉了唯一的护栏**：
任何一次泄漏都能涨到 256MB 才 OOM，而那时 `Active(anon)` 早已压满、系统已经进入 LMK 射程。

- 实测画像支持这一点：GITV 的 Dalvik 是 **Heap Size 43700 kB / Alloc 37381 kB**，即它**主动把堆控制在 43MB 量级**。
- 本项目的 Java 侧需求其实很小（图片缓存封顶 16MB、封面一律 RGB_565、无 Gson），**96MB 绰绰有余**。

**建议（P1，一行改动）**：去掉 `largeHeap`，让 OOM 在 96MB 就以 `OutOfMemoryError` 的形式**干净地暴露**，
而不是拖到系统级内存枯竭。代价：极端情况下图片缓存峰值可能触发 Java OOM —— 需真机验证（见 §6 验证步骤）。

### 1.6 图片缓存要在"UI 不可见"时放，而不是"进播放页那一刻"放

现状：`PlayerActivity.onCreate` 里调 `ImageLoader.trimTo(2MB)`。问题在于

- 它是**一次性**的：播放期间如果又加载了图（作者页/评论），缓存会重新涨回去；
- `MainActivity` 从不主动收紧，`onStop` 也没动作；
- 频道 `FeedBus` 的列表在播放页期间**仍然活着**（`PlayerActivity` 自己持有 `items` 引用）。

**建议**：`MainActivity.onStop()`（或 `onTrimMemory(UI_HIDDEN)`）里 `ImageLoader.trimTo(1~2MB)`，
并在 `App` 里实现 `onTrimMemory` 兜底（Application 的 `onTrimMemory` 在 API 14+ 会被调用）。

### 1.7 Surface / 解码器是"必须显式释放"的资源，GC 管不了它

`Surface`、`MediaCodec`、gralloc 缓冲属于 native/内核资源：**GC 回收 Java 对象不会归还它们**。
必须走显式释放链：

```
IJK : setDisplay(null) → stop() → release()      （项目已实现，正确）
Exo : clearVideoSurface() → stop() → release()   （项目已实现，正确）
并且：SurfaceHolder.Callback.surfaceDestroyed() 时立刻做上面这套
```

项目里这套顺序已经写对了（`Engines.kt` 的注释也记录了"release 前 Surface 还绑着 → overlay 释放不干净 → Other dev 涨到 30MB+"）。
**但要验证"是否真的回落"**——用户的实测说退出播放页只降 1.5MB，说明这条链没走通，见 §6。

---

## 2. Q2 — 为什么 GITV 能用 91MB 还不被系统杀

### 2.1 实测数据

```
$ adb shell dumpsys meminfo com.gitvdemo.video
  Native       28      (堆 Size 31036 / Alloc 24773)   ← native 堆极小
  Dalvik    50514  PSS (Heap Size 43700 / Alloc 37381 / Free 6319)
  Other dev  4905
  .so mmap   8747
  .dex mmap  6662
  Unknown   25400  Private Dirty 25396
  TOTAL     96350 kB  PSS  ≈ 94 MB
Objects: Views 275, Activities 1, AppContexts 5

$ adb shell ps | grep gitv
u0_a47 19974 ... VSIZE 938264  RSS 101976   com.gitvdemo.video     ← 单进程
class=com.gala.video.HostApp                                       ← 宿主类
```

### 2.2 三条真实原因（**没有一条是"进程优先级的魔法"**）

**① 它的服务不是前台服务 —— "前台服务保命"这个假设被实测否掉了。**
`PluginPackageManagerService` / `LocalService` 都是普通 started service，`dumpsys` 里没有 `isForeground`。
它的 `adj` 之所以是 0，只是**因为 `HomeActivity` 正显示在屏幕上**——和我们播放时一样。

**② 它的 94MB 是"可回收的内存"，我们的 20~35MB 里有"不可回收的"内存。**
- GITV：Dalvik 50MB（可被 GC 收缩、页可被内核回收）+ Unknown 25MB（有上限的稳定值）
- 我们：一旦 Surface/gralloc 缓冲没归还，`Other dev`/`Unknown` 是**不可 reclaim 的内核/GPU 内存**，
  它在系统眼里是"永久占用"，会直接把 `MemFree` 往下推。

**这是本题的核心结论：内存"类型"比"大小"重要。** GITV 用 94MB 不炸，不是因为系统偏爱它，
而是因为这 94MB 在压力下**能被回收**；我们的 34MB 里如果有 27MB 是漏掉的 Surface 缓冲，那就是**压死骆驼的那撮**。

**③ 它不会累积 —— 资源有明确 owner，用完即毁。**
- 播放隔离在独立 `PlayerActivity`（实测 ActivityRecord：`com.gala.video.app.epg.HomeActivity` 与播放页是不同 Activity 记录）。
- 每个 UI 域是**独立插件 APK**，可单独卸载/重建（实测 `/data/data/com.gitvdemo.video/app_plugin_patch/`）：
  ```
  com.gala.video.plugin.epg.16.0.1.205799.apk     ← 含自己的 resources.arsc + 字体（HomeActivity 就来自它）
  com.gala.video.plugin.danmaku.16.1.0.207140.apk
  com.gala.video.plugin.push.15.10.0.202153.apk
  ```

### 2.3 关于"插件化"的正确理解（修正一个常见误解）

**GITV 不是多进程。实测只有一个 pid 19974。** 所谓插件化是**同进程内加载独立 APK 资源/Dex 的模块化**：
宿主 `HostApp` + `PluginPackageManagerService` 管理插件生命周期，UI 模块作为版本化插件载入同一个进程。
`mmkv`、`watchsync`、`module.v2.provider.DispatcherProvider` 等 ContentProvider 也在同一进程用插件机制暴露。

**我们要复刻的不是"插件化框架"（成本极高、收益不在这台机器上），而是它的三条纪律：**

| 可复刻 | 做法 | 在本项目里的落点 |
|---|---|---|
| 播放隔离在独立 Activity | 已有 | `PlayerActivity` ✓ |
| 资源有明确 owner，owner 销毁即释放 | 补 | Surface/解码器/图片缓存/FeedBus 各归一个 `onDestroy` 负责 |
| 模块边界清晰 | 补 | 播放相关静态状态（IJK native profile、引擎单例）必须能在 `onDestroy` 全清 |
| ~~多进程~~ | 不复刻 | 单进程 + 一次只一个解码器，正是本项目已验证的正确约束 |
| ~~前台服务~~ | 不复刻 | 实测 GITV 没用，收益为负 |
| targetSdk 更低（26 vs 34） | 可选 | 本机 API 17 < 34，框架实际按 17 走，收益很小；不建议为此调 |

**另注**：GITV 的 `Native` 堆只有 28 kB PSS —— 因为它用 Java 层 `MediaCodec`（不背自带的 native 解码库）。
我们背了 `libijkffmpeg.so` 2.7MB 常驻 + native 堆，这是架构差异，也是我们 `Native/Other dev` 偏高的来源之一。

---

## 3. Q3 —「每视频新开 Activity」vs「同 Activity 复用播放器」

### 结论

**在同一个 Activity 内复用播放器 + 换数据源**，是正确方向（本项目已是如此）；但必须配"兜底重建"。

### 论证

| 维度 | 每视频新 Activity | 同 Activity 复用（+ 每 N 条重建） |
|---|---|---|
| 资源释放确定性 | 高：`onDestroy` 是天然的释放点 | 中：靠手动 `stop/clearMediaItems/setMediaSource`，容易漏 |
| 每次切换的成本 | 高：Activity 创建/销毁 + `Surface` 重建 + 主题/窗口 inflate | 低：只换媒体源 |
| Surface 生命周期 | **每次切换都重建 Surface** → 老 ROM 上 Surface 重建是黑方块/卡顿的既有来源（项目已踩过） | Surface 全程存活，只在必要时重绑 |
| 解码器 | 旧实例的 `release()` 是**异步**的，新 Activity 立刻建新实例 → **两个解码器短暂并存** | 同一时间只有一个解码器 |
| 内存碎片 | 每次切换一次 alloc/free，几十次后碎片化 | 平坦 |
| 切换动画/体验 | 有过渡闪白，TV 上明显 | 无感 |
| 复杂度 | 低 | 中（需要 `setMediaSource` + 异常回落） |

**业界短视频 App 的倾向**：主流做法是**"一个播放宿主 + 换数据源"**
（抖音/TikTok 系、B 站/云视听 TV 端的"小窗/沉浸播放"都是单个播放器实例挂在宿主上，
列表只换数据；YouTube TV 亦同）。**"每条视频一个 Activity"在短视频场景是反模式**：
它把"Surface 重建"和"解码器并存"变成每秒都可能发生的事。
（此项为业界经验共识，不是本次实测所得 —— 标注为**推断**；本项目的实测数据只支持到"单 Activity 复用后内存曲线变平"。）

### 但必须加的一条：**兜底重建（periodic rebuild）**

复用有个真实代价：**碎片与 native 残留会缓慢累积**，最终仍需一次彻底重建。
业界成熟做法是**每 N 条（常见 20~50）主动重建播放器或 Activity**：

```kotlin
// 切换视频时计数；到阈值就"真的拆一次"
if (++playCount >= REBUILD_EVERY) {   // 例：30
    playCount = 0
    restartActivitySafely()           // activity.recreate() 或 release+重建引擎
}
```

在本项目上的意义：**这正好能同时兜住"连播十几个后累积"和"Exit 后 Graphics 不回落"** ——
即使某次释放没走干净，也有一个上限。**API 17 完全可行**（`Activity.recreate()` 是 API 11+；
更保守的做法是 `finish()` + `startActivity` 重启自己以拿到干净的进程内状态）。

---

## 4. Q4 — 连播十几个视频后内存累积：验证过的通用解法

按"本机验证程度"排序：

### 4.1 彻底释放 Surface（最高优先，且顺序错了=没修）
`setDisplay(null)`/`clearVideoSurface()` **必须在** `stop()`/`release()` **之前**。
项目已按此实现（`Engines.kt` 注释记录了实测证据），但用户反馈"退出播放页只降 1.5MB"，
说明**释放链没走通或有别的东西持有 Surface**。要查的持有者：
- `PlayerActivity` 的 `surface` 字段 + `SurfaceHolder.Callback` 是否在 `onDestroy` 前解绑；
- `ExoEngine.holder` 是否在 `release()` 里被清（`ExoEngine.release()` 里**没有** `holder = null`！`IjkEngine` 有）；
- `AspectFrame` 是否在 `onDestroy` 解绑回调；
- **引擎是列表 `engines: List<VideoEngine>`，`onDestroy` 里 `forEach { release() }` 只 release，没有清 `holder`/`listener` 引用** —— 静态/长活引用会拖住 Surface。

### 4.2 用 `onTrimMemory` 主动收缓存（成本最低）
见 §1.2 / §1.6。当前 **0 实现**，是投入产出比最高的一条。

### 4.3 每 N 条兜底重建（engineering 上的"碎片保险丝"）
见 §3。REBUILD_EVERY 建议 20~30，**API 17 可行**。

### 4.4 播放器池（player pool）—— **不推荐**
理由：本机实测已经给出反例 —— 把 IJK 改成 `reset()` 复用以"减少 native 反复申请释放"，
结果 `MediaCodec_loop` 线程 SIGSEGV 直接崩进程（tombstone 铁证，见 `Engines.kt`）。
老设备上**并存两个解码器/Surface 的风险远大于省一次构造**。
正确姿势是"**单实例复用**"，不是"池化"。

### 4.5 定期观测 native 侧（诊断，不改行为）
- `android.os.Debug.getNativeHeapAllocatedSize()`（IJK 的库占这块）
- `dumpsys meminfo <pid>` 的 `Native / Other dev / Unknown / Dalvik` 四行
- 系统 `MemFree`（`/proc/meminfo`）

**给用户的采样脚本（可直接跑，用于自证问题是否存在）**：

```powershell
# 在播放页连播时另开一个 PowerShell 跑这个，观察是否累积
$a = "com.example.douyintv"
for ($i=0; $i -lt 40; $i++) {
  $t = (Get-Date).ToString("HH:mm:ss")
  $mi = adb shell dumpsys meminfo $a | Select-String -Pattern "Other dev|Unknown|Dalvik|Native|TOTAL"
  $mf = (adb shell cat /proc/meminfo | Select-String "MemFree").Line -replace "\D",""
  Write-Host "== $t  MemFree=$([int]$mf/1024)MB =="
  $mi
  Start-Sleep -Seconds 5
}
```

判读：`Other dev` / `Unknown` **单调上涨且不回落** = Surface/gralloc 泄漏（去修释放链）；
只有 `Dalvik` 上涨 = Java 侧泄漏（去修缓存/引用）；`MemFree` 单调下降 = 上述两者的后果。

### 4.6 不要再动缓冲大小
`ExoPlayer` 当前 20s/45s/16MB、`IJK` `max-buffer-size=24MB` 是**已经验证过的取舍点**：
再小会因网络抖动重新缓冲（比卡更糟），再大则吃内存。**这一项维持现状。**

---

## 5. Q5 — 降低 Android 4.2 上的启动开销

### 5.1 实测根因：multidex 的 dex 体量与压缩状态

```
$ unzip -l DouyinTV-54.0-mem-trim-debug.apk | (dex/lib 部分，未压缩大小)
classes2.dex                        7.298 MB   ← 主凶
classes.dex                         3.318 MB
lib/armeabi-v7a/libijkffmpeg.so     2.718 MB
lib/armeabi-v7a/libconscrypt_jni.so 1.221 MB
lib/armeabi-v7a/libijkplayer.so     0.237 MB
lib/armeabi-v7a/libijksdl.so        0.205 MB
（debug 产物 10.21 MB；设备上已安装的 APK 6.99 MB）
```

在 **Dalvik（API < 21）** 上，`MultiDexApplication` 在**首次启动**时会把 `classes2.dex` 从 APK 里
**解压并 dexopt** 到应用数据目录 —— 一个 7.3MB 的 `classes2.dex` 在 Cortex-A7 上就是几十秒。
这正是用户看到的 `Activity idle timeout for ...MainActivity` + 启动极慢的来源。

### 5.2 P0：把 dex 压回单 dex，**彻底消灭 multidex**

对一个只有 ~25 个 Kotlin 源文件的 App，10.6MB dex 说明 **dex 体量来自依赖，不是代码**。
`classes2.dex` 之所以有 7.3MB，最可能是**当前装机的是未混淆的 debug 产物**（`minifyEnabled` 只在 release 开）。

按收益排序：
1. **确认装机的是 release（`minifyEnabled true` + `shrinkResources true`）产物**，并实测其 dex 总量。
   R8 对 `exoplayer-core` 这类组件化库收缩率很高。
2. 打开 R8 full mode / 类重打包（AGP 8 默认已开 full mode）：
   `gradle.properties` 里确认 `android.enableR8.fullMode=true`，`proguard-rules.pro` 里加
   `-repackageclasses 'o'`、`-allowaccessmodification`。
3. **删掉不会用到的依赖声明**：`ijkplayer-arm64`（`abiFilters` 已滤掉 .so，但 java 依赖仍在，
   且 Manifest 里还留着 `tools:overrideLibrary="tv.danmaku.ijk.media.player_arm64"`）；
   确认 `zxing` 是否真被使用（3.3.3 只为二维码登录）。
4. **`androidx.multidex` 只在必要时代价**：如果 R8 之后方法数 < 64K，直接 `multiDexEnabled false`
   并让 `App` 退回普通 `Application` —— **启动开销的最大一块直接归零**。

### 5.3 P1：若 multidex 无法避免，让 `classes2.dex` **不被压缩**打进 APK
按需解压是一回事，"解压 + 写入 + dexopt"是另一回事。让 `classes2.dex` 以 **STORED（不压缩）**
方式打包，则 `MultiDexExtractor` 的"解压"退化成一次**文件拷贝**，首启能显著变快。
**API 17 可行**，但**需要实测 zipalign 后的对齐是否满足 Dalvik 要求**（这一步务必用真机首启时间验证）。
（本条为工程手法，收益随 ROM/打包方式浮动 —— 标注为**待实测**。）

### 5.4 P0：删掉 `PlayerActivity.onCreate` 里的 IJK 预加载

现状（`PlayerActivity.kt` L232-237）：**无论选哪个引擎**，进播放页都执行

```kotlin
IjkMediaPlayer.loadLibrariesOnce(null)
IjkMediaPlayer.native_profileBegin("libijkplayer.so")
```

后果：每次进播放页都把 **~3.2MB 的 native .so + 相应 native 堆**搬进进程，
并常驻到进程结束；若当前用的是 ExoPlayer，这份成本**纯属白付**。

**改法**：把加载移进 `IjkEngine` 的**首次使用**（构造函数或首次 `prepare()`），
并保证 `native_profileEnd()` 与之配对。同一原则已在 skill 里记为"native 库懒加载"。
**API 17 可行**，且能同时改善启动时间与常驻内存。

### 5.5 其它启动项
- `App.onCreate` 里的 `Security.insertProviderAt(Conscrypt.newProvider(), 1)` 有实测开销（几十~几百 ms）；
  如果 TLS 只在发第一个请求时才需要，可**推迟到首次网络调用前**（需保证早于任何 HTTPS）。
- `MainActivity` 加 `android:launchMode="singleTask"`，避免从播放页返回时重建首页。
- Manifest 里 `<uses-sdk>` 已经出现重复注释块（L10-13 两行一样的注释），清理掉（不影响构建，但误导人）。
- 打包：`packagingOptions { jniLibs { useLegacyPackaging = true } }` 在 API 17 上**必须保留**
  （API < 23 无法直接从 APK 加载未压缩 .so）。

---

## 6. 方案优先级总表（含 API 17 可行性）

| P | 方案 | 预期收益 | API 17 | 代价/风险 | 验证方法 |
|---|---|---|---|---|---|
| **P0** | 先判定"被杀"是 **OOM 还是 native 崩溃** | 决定后续所有方向 | ✅ | 无 | `ls /data/tombstones`；`dumpsys dropbox --print` 里的 `data_app_crash` / `native_crash` |
| **P0** | 固定度量口径：改用 `Other dev` / `Unknown` / `Dalvik` / `MemFree` | 避免继续优化错对象 | ✅ | 无 | 跑 §4.5 采样脚本，先记录基线曲线 |
| **P0** | 补 `onTrimMemory`（App + MainActivity + PlayerActivity） | 高（当前 0 实现） | ✅ API 14+ | 低 | 进播放页时主界面 `ImageLoader.cacheSize()` 应≈0；`MemFree` 抬高 |
| **P0** | 修 Surface 释放链（`ExoEngine.release()` 补 `holder=null`；`onDestroy` 清 listener/holder；`engines` 引用清空） | 高（直接治 Graphics 不回落） | ✅ | 低 | 退出播放页后 `Other dev`/`Unknown` 应回落，`MemFree` 回到 45MB+ |
| **P0** | 删 `PlayerActivity.onCreate` 的 IJK 预加载 → 改懒加载 | 启动 + 常驻内存 | ✅ | 低（需保证 IJK 首次用前加载完） | 进播放页用 Exo 时 `dumpsys meminfo` 的 `.so mmap` 不应含 ijk* |
| **P0** | 确认装机是 **release/R8** 产物并测 dex 总量 | 启动（multidex 慢） | ✅ | 低 | 对比 debug/release APK 的 `classes*.dex` 字节数 |
| **P1** | 去掉 `android:largeHeap` | 中（恢复 96MB 护栏，避免拖垮系统） | ✅ | 中：可能提前暴露 Java OOM → **必须真机验证** | 播放 30+ 条，观察是否出现 `OutOfMemoryError` 而非系统级死亡 |
| **P1** | R8 收缩 + 删冗余依赖（ijk-arm64 / zxing 等）→ 目标 **单 dex** | 高（消灭 multidex） | ✅ | 低 | `multiDexEnabled false` 后构建通过 = 已成单 dex |
| **P1** | 每 N 条（20~30）兜底重建（`recreate()`） | 中（碎片保险丝） | ✅ API 11+ | 低（切歌点会闪一下） | 连播 40 条，`Other dev`/`Unknown` 呈锯齿而非单调上涨 |
| **P1** | `classes2.dex` 以 STORED 打包（若仍需 multidex） | 中 | ✅（待实测） | 需验证对齐/首启 | 真机首启耗时应下降；`Activity idle timeout` 应消失 |
| **P2** | `MainActivity launchMode=singleTask`；Conscrypt 初始化推迟 | 低~中 | ✅ | 低 | 冷启动计时 |
| **P2** | 引擎默认值对齐用户实测偏好（IJK）并保证懒加载配套 | 体验 | ✅ | 低 | `Prefs.getEngine` 默认值 + 首次 IJK 使用时加载 |
| **✗** | 播放器池 / 多实例并存 | **不做** | — | 反例已实测（IJK reset 复用 SIGSEGV） | — |
| **✗** | 前台服务保命 | **不做** | ✅（但收益为负） | 多通知、常驻占内存 | — |
| **✗** | 进一步缩小 Exo/IJK 缓冲 | **不做** | ✅ | 已踩过"越改越卡" | — |
| **✗** | 在 API 17 上自己实现"插件化框架" | **不做** | ✅（可行） | 成本极高、收益不在本机 | 复刻其"owner/释放"纪律即可 |

---

## 7. 信息来源

**本次真机实测（一手证据，命令可复现）**
- `adb devices -l` → `10.63.27.191:5555`，`Skyworth_8S52_E510E` / `full_sky818a_e510`
- `cat /proc/meminfo`（MemTotal 662696 kB / MemFree 31248 kB / Swap 0）
- `getprop dalvik.vm.heapgrowthlimit`（96m）、`dalvik.vm.heapsize`（256m）
- `/sys/module/lowmemorykiller/parameters/{minfree,adj}`（4712..13521 / 0,1,2,4,9,15）
- `dumpsys meminfo com.gitvdemo.video`（TOTAL 96350 kB；Dalvik 50514；Unknown 25400；Native 28）
- `dumpsys meminfo com.example.douyintv`（TOTAL 11899 kB 空闲态）
- `dumpsys activity processes` → GITV `class=com.gala.video.HostApp`、`PluginPackageManagerService`、`LocalService`、ActivityRecord `HomeActivity`
- `dumpsys activity services com.gitvdemo.video` → 无 `isForeground`
- `dumpsys power` → 无 GITV wake lock
- `dumpsys package` → GITV `targetSdk=26` / v16.0.0.205693；本项目 `targetSdk=34` / v54.0-mem-trim
- APK 结构（本机 `unzip`）：`classes2.dex` 7.298MB、`classes.dex` 3.318MB、`libijkffmpeg.so` 2.718MB

**本地代码事实（grep/read 所得）**
- `PlayerActivity.kt`：`onCreate` 无条件 `IjkMediaPlayer.loadLibrariesOnce` + `native_profileBegin`（L232-237）
- `PlayerActivity.kt`：`onDestroy` 只 `engines.forEach { release() }`，不清 `holder`/`listener`
- `Engines.kt`：`ExoEngine.release()` 无 `holder = null`；`IjkEngine.release()` 有
- `Engines.kt` 注释：IJK `reset()` 复用导致 `MediaCodec_loop` SIGSEGV（tombstone）→ 已回退为每次重建
- 全工程 grep `onTrimMemory|onLowMemory|registerComponentCallbacks` = **0 命中**
- `ImageLoader.kt`：LRU 4~16MB 封顶、`trimTo()` 仅在 `PlayerActivity.onCreate` 调用一次
- `app/build.gradle`：`multiDexEnabled true`、`abiFilters 'armeabi-v7a'`、`minSdk 16`、`targetSdk 34`、release 才 `minifyEnabled`

**官方文档 / AOSP（URL 引用）**
- 内存与 `onTrimMemory`：`https://developer.android.com/topic/performance/memory`
- `ComponentCallbacks2`（全部 `TRIM_MEMORY_*` 常量，API 14+）：`https://developer.android.com/reference/android/content/ComponentCallbacks2`
- `Application.registerComponentCallbacks`（API 14+）：`https://developer.android.com/reference/android/app/Application`
- `android:largeHeap` / `ActivityManager.getMemoryClass`：`https://developer.android.com/guide/topics/manifest/application-element#largeHeap`
- LMK 与 `oom_score_adj`：AOSP `drivers/staging/android/lowmemorykiller.c`、`frameworks/base/services/java/com/android/server/am/{ProcessList,ActivityManagerService}.java`
- MultiDex（Dalvik 上的 secondary dex 抽取）：`https://developer.android.com/studio/build/multidex`
- ExoPlayer / Media3 `LoadControl`、`setMediaSource`、`release()` 异步语义：`https://developer.android.com/media/media3/exoplayer`
- `SurfaceHolder.setType(SURFACE_TYPE_PUSH_BUFFERS)` 自 API 11 废弃：`https://developer.android.com/reference/android/view/SurfaceHolder`

**本项目既有经验库（同仓库技能，已在上一轮沉淀）**
- `skills/software-development/android-legacy-build/references/legacy-playback-memory-and-bitrate.md`
  （"是谁退出的"判定法、Exo/IJK 复用、LoadControl、图片缓存封顶）
- `.../references/legacy-video-playback-tuning.md` §8
  （竞品 GITV 内存/架构对比表、`libgalauniplayersdk.so` 的 Java 层 MediaCodec 策略、Android 4.2 平台怪癖）
- `.../references/heavy-media-engine-integration.md` §2
  （引擎懒加载、每个引擎独立 `try/catch`、用户实测偏好落到默认值）
- `E:\Android-Builder-MCP\workspace\TVDouyinNative\docs\OPTIMIZATION_PLAN.md`

**标注为推断（非实测）的部分**
- "业界短视频 App 倾向单播放宿主 + 换数据源"：业界经验共识，非本次实测；
  本次实测只能证明 GITV 使用独立播放 Activity + 单进程 + 无前台服务。
- "`classes2.dex` STORED 打包可加速首启"：工程手法，收益需真机实测确认。
