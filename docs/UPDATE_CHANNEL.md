# DTV · 更新渠道与发布流程

> 本文件说明如何用 **GitHub** 作为 DTV 的更新渠道（托管 APK + 版本清单）。
> 目标：App 内「检查更新」能自动发现新版 → 下载 → 调起安装器。

---

## 一、仓库结构（建议）

```
DTV/                      ← GitHub 仓库
├── version.json          ← ★ 版本清单（App 读取它判断更新）
├── README.md
├── app/                  ← TV 端源码（本工程）
└── remote/               ← 手机遥控端源码（DtvRemote 工程）
```

`version.json` 必须放在**仓库根目录**（App 默认从
`https://raw.githubusercontent.com/<用户名>/DTV/main/version.json` 读取）。

---

## 二、发布一个新版本（每次三步）

### 1. 改 `version.json`
```json
{
  "versionCode": 117,          // ← 必须比上一版大（App 用它比对）
  "versionName": "2.17",       // ← 显示给用户的版本号
  "apkUrl": "https://github.com/<用户名>/DTV/releases/download/v2.17/Dtv-2.17-release.apk",
  "notes": "1. 修复 xxx\n2. 新增 yyy",
  "force": false               // true = 强制更新（暂未启用强制逻辑）
}
```

### 2. 打 tag + 建 Release
```bash
git tag v2.17
git push origin v2.17
```
在 GitHub 网页：**Releases → Draft a new release → 选择 tag `v2.17`**
→ 上传构建好的 APK（重命名为 `Dtv-2.17-release.apk`，与 `apkUrl` 一致）
→ Publish release。

### 3. 提交 version.json
```bash
git add version.json && git commit -m "release: v2.17" && git push
```

完成后，**已装旧版的电视下次启动（或点设置里的检查更新）就会弹升级提示**。

---

## 三、App 端实现（已完成）

| 模块 | 位置 | 说明 |
|---|---|---|
| 更新检查 | `UpdateChecker.kt` | 拉 `version.json` → 比对 `versionCode` → 有新版弹窗（版本号 + 更新说明） |
| 下载 | `UpdateChecker.download()` | 下载到 `filesDir/update.apk`（带进度） |
| 安装 | `UpdateChecker.install()` | 走 **FileProvider** 唤起系统安装器（Android 4.2 无 root，需在电视上手动点确认） |
| 触发时机 | `MainActivity.onCreate` | 启动 3 秒后静默检查；失败静默，不打扰用户 |
| 仓库地址 | `Prefs.getUpdateUrl()` | 可在设置里覆盖，默认取 `UpdateChecker.DEFAULT_MANIFEST` |

### ⚠️ 必做：把仓库地址填进代码
发布前把 `UpdateChecker.DEFAULT_MANIFEST` 里的 `CHANGE_ME` 换成你的 GitHub 用户名：
```kotlin
const val DEFAULT_MANIFEST = "https://raw.githubusercontent.com/<你的用户名>/DTV/main/version.json"
```
（未替换时更新检查会直接跳过，不会报错。）

---

## 四、签名注意（重要）

Debug 签名和 Release 签名不同 → **换签名后无法覆盖安装**（系统会报"应用未安装/签名冲突"）。

建议：
1. 生成一个固定 release keystore，**长期保存**；
2. `app/build.gradle` 里配置 `signingConfigs`，`assembleRelease` 产物作为发布包；
3. 用户从旧 debug 版升级到 release 版时需要**先卸载**（仅这一次）。

---

## 五、手机遥控端（DtvRemote）更新

同一套机制可复用：手机端指向自己的 `version.json`（可放在同一仓库的 `remote/version.json`），
或直接在本仓库 Releases 里放手机端 APK。手机端更新检查尚未接入，需要时照 `UpdateChecker.kt` 复制即可。
