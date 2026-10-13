# DTV · 抖音 TV 版 + 手机遥控

> 在 **Android TV / 老安卓盒子**（最低 Android 4.2）上刷抖音的开源客户端，附一套自研的**手机遥控**（UDP 发现 + 配对 + WebSocket 控制 + APK 无线分发）。

[![Build](https://github.com/Alone2235/DTV/actions/workflows/android-build.yml/badge.svg)](https://github.com/Alone2235/DTV/actions/workflows/android-build.yml)
![minSdk](https://img.shields.io/badge/minSdk-17-blue)
![license](https://img.shields.io/badge/license-MIT-green)

---

## ✨ 功能

### 电视端（DTV）
- **推荐流 / 关注 / 搜索 / 分类**，遥控器 D-pad 全键盘导航（焦点态放大动画）
- **双播放引擎**：ExoPlayer + IJKPlayer（老盒子硬解兼容，可切换 / 自动降级）
- **弹幕**、倍速、循环、横竖屏、线路切换、黑屏自动重载
- **本地点赞 / 收藏**（不写服务端，避免风控踢浏览器）
- **手机遥控服务端**：UDP 8089 发现、HTTP 8088 配置/升级、WebSocket 8090 控制
- **在线更新**：读取本仓库 `version.json` → 提示新版 → 下载 → 调起安装器

### 手机端（DtvRemote）
- 自动发现同一 WiFi 下的电视，6 位配对码 + 客户端白名单
- 触摸板手势（单击暂停 / 双击点赞 / 上下滑切视频 / 左右滑翻页）
- 音量、静音、线路、倍速、弹幕、进度、首页
- **在手机上选 APK 无线推送到电视并安装**（解决盒子没有 U 盘/ADB 的痛点）

---

## 📦 下载安装

**方式一（推荐）**：到 [Releases](https://github.com/Alone2235/DTV/releases) 下载最新 `Dtv-x.xx-debug.apk`，拷到电视/U盘安装。

**方式二**：已安装旧版 → App 启动 3 秒后**自动检查更新**，弹窗点「立即升级」即可。

> ⚠️ Android 4.2 盒子需在系统设置里允许「未知来源应用」；电视端升级需在电视上手动点确认（无 root 无法静默安装）。

---

## 🔧 自行编译

```bash
# 需要 JDK 17+（本地实测 JDK 25 可用）、Android SDK Platform 35
git clone https://github.com/Alone2235/DTV.git
cd DTV
./gradlew :app:assembleDebug          # 产物：app/build/outputs/apk/debug/Dtv-x.xx-debug.apk
```

或在 GitHub 上**推一个 tag** 让云端自动打包（见下）。
本地内存紧张时可在 `gradle.properties` 里调大/调小 JVM 参数（默认 `-Xmx896m`）。

---

## 🤖 自动打包（GitHub Actions）

推 tag 即自动编译 + 发布 Release：

```bash
git tag v2.18
git push origin v2.18
```

工作流：[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml)
- push 到 `main`：编译 + 上传 APK 为 Artifact
- push tag `v*`：编译 + **自动创建 Release 并附上 APK**

---

## 🔄 在线更新机制

```
TV App ──GET──> raw.githubusercontent.com/Alone2235/DTV/main/version.json
                    │  比对 versionCode
                    ▼
             有新版 → 弹窗(版本号+更新说明) → 下载 APK → FileProvider 调起安装器
```

`version.json`（仓库根目录，发版时更新）：
```json
{
  "versionCode": 118,
  "versionName": "2.18",
  "apkUrl": "https://github.com/Alone2235/DTV/releases/download/v2.18/Dtv-2.18-debug.apk",
  "notes": "1. 修复xxx\n2. 新增yyy",
  "force": false
}
```

- 客户端实现：`app/src/main/java/com/example/douyintv/UpdateChecker.kt`
- 国内若 `raw.githubusercontent.com` 访问不佳，可切 jsDelivr 镜像（代码内置 `CDN_MANIFEST` 常量）
- **发版流程**详见 [`docs/UPDATE_CHANNEL.md`](docs/UPDATE_CHANNEL.md)

---

## 📁 目录结构

```
DTV/
├── app/                          # 电视端（Android TV，minSdk 17）
│   ├── src/main/java/com/example/douyintv/
│   │   ├── MainActivity.kt       # 首页：导航 + 分类 Tab + 网格
│   │   ├── PlayerActivity.kt     # 播放器：手势/弹幕/弹窗/遥控响应
│   │   ├── Engines.kt            # ExoPlayer / IJKPlayer 双引擎
│   │   ├── DouyinApi.kt          # 抖音接口（含 WebProtect 签名）
│   │   ├── DiscoveryServer.kt    # UDP 发现（8089）
│   │   ├── ConfigServer.kt       # HTTP 配置/升级（8088）
│   │   ├── WSServer.kt           # WebSocket 控制（8090，手写 RFC6455）
│   │   ├── UpdateChecker.kt      # 在线更新
│   │   └── Prefs.kt              # 配置存储
│   └── src/main/jniLibs/         # IJK 播放器 .so（armeabi-v7a）
├── tools/publish.ps1             # 一键发布脚本（升版本→编译→上传→发 Release→更新清单）
├── docs/                         # 技术文档（更新渠道、IJK 研究等）
├── version.json                  # ★ 在线更新清单
├── CHANGELOG.md
└── LICENSE
```

---

## 🧭 设计基线

- **电视端**受 Android 4.2 / 647MB RAM / Mali450 约束：不用 Compose、不用 AndroidX 重依赖，播放器释放顺序按 tombstone 调优，改动需谨慎
- **焦点动画**统一走 `FocusAnim`（140ms DecelerateInterpolator，聚焦 1.10x）
- 配色保留抖音品牌红体系（`#FE2C55`），不套用第三方配色

---

## 📄 License

[MIT](LICENSE)

> 本项目仅用于学习与技术研究，所有视频内容版权归原作者及平台所有。请勿用于商业用途。
