# Dtv 更新日志

> 公开版本：请仅从作者官方渠道下载安装，谨防第三方篡改重打包。
> 每版留档：源码备份在 _BACKUP_Dtv_v<版本>，APK 留档在 artifacts/apk/TVDouyinNative/。
> 版本号规则：十进制小进位（1.99 → 2.00 → 2.01）；versionCode 单调递增（Android 升级判断）。

## 2.13（2026-10-12）UI/动效大改版 · 第 1 步：统一动效系统

**动效系统（FocusAnim）**
- 新增统一动效工具 `FocusAnim`：焦点缩放（1.06→1.10x，**ease-out 140ms**）、淡入（220ms）、按下回弹（160ms）
- 播放器底栏 8 个功能键接入：焦点动画带缓动曲线（原来生硬直切）+ 按下回弹反馈
- 全应用焦点手感收敛到同一入口，后续主界面/卡片/侧边栏逐步接入（大改版进行中）

**2.12 已修（并入本版）**
- 手机端搜索传参 bug（/cmd 读 kw 改 arg，HTTP+WS 双通道修）+ 自动拉起搜索页
- 手机「重载」命令播放器无处理 → 补 playCurrent()
- WS 通道搜索不拉起页面 → 补拉起
- APK 分片上传：兼容 NanoHTTPD postData 字符串/文件路径两种形态（大分片不再失败）

## 2.10（2026-10-11）手机客户端 TV 底层（第 1 阶段）

**UDP 设备发现（8089）**
- 手机广播 `DTV_DISCOVER` → 电视应答 `{app,uuid,name,version,port}`，自动发现同 WiFi 设备

**白名单授权（配对即长期授权）**
- 配对码校验通过时，手机 `client_uuid` 写入电视白名单（`/pair?code&client_uuid&name`）
- `/auth?uuid=` 握手鉴权；`/clients` 列表 / `/clients/del` 删除 / `/clients/clear` 清空（电视侧管理）
- `/cmd` 与 `/apk/*` 支持「配对码 或 白名单」双通道鉴权（`authed()`）

**WebSocket 控制通道（8090，RFC6455 极简实现）**
- `ws://电视IP:8090/?uuid=<client_uuid>`，仅白名单放行
- JSON 命令：next/prev/digg/collect/toggle/pause/play/seek/line/engine/danmaku/loop/landscape/speed/search/push/volumeup/volumedown/mute/home

**播放倍速**
- Engines 新增 `setSpeed()`：Exo（PlaybackParameters）/ IJK（setRate）生效，系统播放器空实现
- 手机 `speed` 命令循环 0.75/1.0/1.25/1.5/2.0；快照带上当前倍速

**APK 分发（升级安装）**
- `/apk/info`：版本/大小/剩余空间；`/apk/upload`：分片续传（offset 追加）
- `/apk/install`：FileProvider 唤起系统安装器（4.2 无 root，需电视上手动确认）

**其他**
- `/status` 增加 version / uuid / allowCount；分享推送逻辑抽为静态 `pushVideoStatic`（WS/HTTP 共用）
- 版本号 2.10（versionCode 110）

## 2.09（2026-10-11）

**弹幕功能**
- 新增轻量弹幕层 `DanmakuView`（纯 Canvas，无第三方库，API 17 兼容）：
  评论文案从右向左飞过，5 条横道随机入道、多色（白/黄/品牌红/绿/蓝/橙）、黑描边保证可读
- 数据源 = 当前视频评论区（30 条封顶），切视频自动换文案；暂停/离开前台自动停帧
- 开关在播放器菜单「弹幕 · 开/关」，状态记忆（Prefs）

**手机遥控客户端（第一版：局域网 Web 客户端 + 配对码）**
- 配置服务升级为 **App 级常驻**（App.onCreate 启动，登录页不再独占 8088 端口）
- **6 位配对码**（首次自动生成/可重生成）：命令接口全部校验配对码，防同网段乱控
- 手机端页面 `http://<电视IP>:8088/remote`：
  - 配对（6 位码）+ 状态展示（当前视频/作者/进度条/播放中/引擎/弹幕）
  - **全屏触控板**：轻触播放暂停、左右滑上/下一条、上下滑快退/快进（10s）
  - 快捷按键：上一条/播放暂停/下一条 + 赞/藏/弹幕/线路/引擎/循环/横屏/静音/音量±/首页
  - 搜索（关键词 + 热搜词块）、推视频（分享链接或 aweme_id）
  - **观看历史 / 我的收藏列表**（封面+标题，点一条即推送到电视播放）
- 播放页新增遥控命令通道 `PlayerBus`（命令 post 回主线程执行）+ 播放状态快照
- DouyinApi 新增：分享链接解析（短链跟随 302）、aweme_id 提取、单视频详情拉取

## 2.08（2026-10-10）

**播放黑屏自动重载修复（低配+高配全设备）**
- **根因**：播放页按视频比例调整画面容器尺寸（AspectFrame）→ 触发 SurfaceView
  的 Surface 销毁/重建 → 非 Exo 引擎（IJK/系统）不托管 Surface，重建 = 整段重播；
  Exo 也伴随黑帧闪屏。这就是「播放中黑屏然后自动重新加载」的机制。
- 修复：
  - **比例容器每视频只应用一次**（aspectAppliedToken 防抖），杜绝多次 onVideoSize
    回调反复重建 Surface
  - **非 Exo 引擎跳过容器缩放**：IJK/系统引擎不再被 Surface 重建逼着重播；
    Exo 由 setVideoSurfaceHolder 托管，重建仅重绑不重播
  - **黑屏看门狗**：起播后播放位置正常前进但首帧（onVideoSize）迟迟不到 = 黑屏
    → 自动重载一次（每视频最多一次），把「黑屏卡死」变成「黑屏后自动恢复」；
    缓冲中不触发，避免误杀
  - **内存自杀探针只对老设备生效**（DeviceProfile.isLegacy）：那套 native/FD/RSS
    阈值是按 IJK 泄漏的老电视调的，高配机器正常值就高，会误触发「整个 App 重载」
    → 高配只靠连播计数兜底（20 → 60 次）

**点赞/收藏纯本地化（用户拍板）**
- 服务器侧 WebProtect 方案太麻烦且实测易风控（踢下线网页端），
  改为**纯本地记忆**：点赞/收藏只记本机状态，用于回显与收藏页，不再发任何请求
- 彻底告别：写操作不再碰服务器 → 不会再踢掉浏览器登录态

## 2.07（2026-10-10）

**WebProtect / bd_ticket_guard 凭据链路打通（真实凭据分析后落地）**
- 依据用户提供的完整浏览器 Cookie 导出 + 公开算法资料（zcx2077 系列），确认：
  - `webid` 不在 Cookie，藏在 `web_sign_token` **JWT 的 payload.id**（已实测解出 = 抓包同值）
  - `msToken` 不在 Cookie（在 localStorage），**本地随机串必然触发风控踢下线** —— 这就是 2.06 仍被拒的根因
  - WebProtect 层**确实启用**：Cookie 里有 `bd_ticket_guard_client_data`（含客户端 EC 公钥）、
    `bd_ticket_guard_ts_sign_id`、`__security_mc_1_s_sdk_sign_data_key_web_protect`
- 本版落地：
  - **自动薅取**：保存 Cookie 时自动解 webid（JWT）、客户端公钥（bd_ticket_guard_client_data）、
    ts_sign_id 入库；`webid` 从此是真值，不再用随机串
  - **凭据导入**：推送页新增「WebProtect 凭据」粘贴框（浏览器 F12 Console 一条命令导出
    localStorage → 粘贴 → 自动按关键字归档 EC 私钥 / ticket / ts_sign / server_cert）
  - **签名器 WebProtectSigner**：EC P-256 私钥导入（PKCS8 / SEC1 / JWK 三格式）+ 
    `req_sign = ECDSA-SHA256(priv, "ticket=..&path=..&timestamp=..")`，
    按公开样本同型（DER 签名 base64）；凭据齐全时写请求自动附加
    `x-bd-ticket-guard-*` 头（头名以真机抓包为准，待微调）
  - 交互请求 UA/凭据一致性：webid/msToken/uid 全部来自浏览器真实值，不再本地编造
- **待真机确认**：① 需要你从浏览器 localStorage 导出导入 EC 私钥等凭据；
  ② 点赞一次并抓 cURL（含全部请求头）给我，对齐 `x-bd-ticket-guard-*` 头名

## 2.06（2026-10-10）

**点赞/收藏「未登录 + 网页端被踢下线」根治（真机实测反馈）**
- **根因**：旧版用 App 本地随机生成的 webid/msToken 做写操作 —— 抖音风控要求这些
  浏览器级凭据与登录会话严格同源，随机串直接被判定「异常登录」→ 作废整个 sessionid
  （电视显示未登录，**浏览器那边也被踢下线**）。
- 本版改为 **电视完全扮演浏览器**：
  - `webid` / `msToken` / `uid` 优先取登录 Cookie 里的**浏览器真实值**（抖音本来就
    把 webid/msToken/uid_tt 写进 Cookie），本地随机值只作最后兜底
  - **浏览器真实 UA**：推送页 JS 自动捕获 `navigator.userAgent` 随 Cookie 一起保存，
    写请求 UA 与登录会话一致（a_bogus 用同一 UA 生成）—— UA 不符也是「异地登录」诱因
  - Cookie 若缺 webid/msToken：自动以浏览器身份 GET douyin.com 首页，从 Set-Cookie
    补齐服务端签发的真实值（不回写 ttwid，不动会话绑定）
  - 保存 Cookie 后**体检**：检查 sessionid/ttwid/s_v_web_id/UIFID/webid/msToken 是否齐全，
    缺哪个直接提示「请用浏览器全选复制」，避免悄悄带病
  - 失败文案翻译：status_msg 含未登录/login → 提示「浏览器端可能已被踢下线，请重新
    复制 Cookie」；含 verify/captcha → 「触发风控验证」

## 2.05（2026-10-10）

**点赞/收藏：按真实抓包对齐请求结构（2.04 那版仍有偏差）**
- 2.04 用的是「POST + JSON body + 精简参数」，实测仍被拒（403 / 未登录）。
  本版按用户提供的 DevTools 抓包逐字对齐：
  - **URL 带全套浏览器指纹参数**（顺序照抄抓包）：
    `device_platform…round_trip_time=100&webid&uifid&msToken&verifyFp&fp&uid&a_bogus`
  - **a_bogus 签的就是上面这一整串 query**（不含 a_bogus 自己），
    签名 UA 与发送 UA 统一为 Edge/Chromium 154（INTERACT_UA）
  - **POST body 为 form-urlencoded**：`aweme_id=…&item_type=0&type=1|0`
  - **uifid 同时在 URL 与请求头**；补上写接口的 **CSRF 防伪头**
    `x-secsdk-csrf-token`（取 Cookie 的 passport_csrf_token，浏览器端由 sec-sdk 自动携带，
    缺失会触发 403 / verify 校验）
- 结论写死进代码注释：互动接口对浏览器身份的一致性要求极高（UA、webid、uifid、Cookie 必须同源），
  普通 web 指纹（a_bogus）与 CSRF 头都齐了才可能过

**预留 WebProtect 凭据存储**
- Prefs 新增 `wp_ec_private` / `wp_ticket` / `wp_ts_sign` / `wp_server_cert` 四个槽位 + `hasWebProtect()`
- 目前只是「存」，EC P-256 签名算法还没落地（myDV 未开源，且拆人成品不做），
  若真机实测仍被拒，下一步抓一次带完整签名样本即可补齐

**导航**
- 撤销 2.04 遗留的聚合中转，侧边栏固定 **6 项一步直达**：推荐 / 搜索 / 历史 / 收藏 / 设置 / 登录
- 卡片聚焦放大保持 1.04×（10 英尺焦点反馈）

## 2.04（2026-10-09）

**导航回退修正**（采纳用户反馈："换地方更麻烦"）
- 撤销「我的」聚合中转页（MyActivity），侧边栏恢复 **6 项一步直达**：推荐 / 搜索 / 历史 / 收藏 / 设置 / 登录
- 登录沉底（账户操作不占内容首屏）、热搜并入搜索、分类 Tab 常驻保留

**点赞/收藏接口按真实抓包重写**
- 路径修正：`/aweme/v1/web/commit/item/digg/`（真实抓包，原 `/digg/aweme` 是错的）
- 参数全套浏览器指纹凭据：webid / uifid（Cookie UIFID）/ verifyFp+fp（Cookie s_v_web_id）/ msToken / uid / a_bogus
- POST JSON body；webid / msToken / uid 暂需外部配置（Prefs，自动获取待后续）

**UI**
- 卡片聚焦放大 1.04×（10 英尺焦点反馈，Netflix/Apple TV 同款）

## 2.03（2026-10-09）

- **播放页点赞/收藏直达底栏**：新增「赞」「⭐」按钮（高频操作不进菜单），已赞/已藏红色回显
- **「我的」页新增「浏览偏好」开关**：横屏过滤一键切换（开=只留横屏，内容偏少；关=全部内容更丰富）

## 2.02（2026-10-09）

- **热搜并入搜索**：搜索页空态显示「热搜 · 历史」词条（热搜词匿名接口 + 本地搜索历史，点词即搜）
- **收藏列表**：「我的 → 我的收藏」网格页（收藏时本地保存完整视频信息，点卡片续看）
- 「我的」页新增收藏卡片（计数回显）

## 2.01（2026-10-09）★ 信息架构重构（3 模块 + 二级菜单）★

- **侧边栏 7 项 → 3 项**：推荐 / 搜索 / 我的（登录·热搜·历史·设置不再占一级导航）
- **分类 Tab 常驻主界面顶部**：推荐/美食/游戏…横排切频道（不再单独"分类"入口）
- **新「我的」聚合页**（MyActivity）：登录卡片（状态回显）/ 观看历史 / 设置 —— 二级菜单雏形
- **无 Cookie 引导**：冷启动无登录态 → 直接去登录页
- **热搜榜入口移除侧边栏**（保留独立页，搜索页空态后续并入热搜词）

## 2.00（2026-10-09）

- **观看历史按日期分组**：今天 / 昨天 / 更早 三组 + 分组标题（观看时间随记录保存）
- **作者页返回保留上下文**：点视频进播放器不再 finish 作者页，返回回到作者页（不再丢回主界面）

## 1.99（2026-10-09）

**功能完善**
- 评论扩展：支持**图片评论 / 视频评论封面**显示（▶ 角标）；**点评论 → 评论者主页**（作者页）
- 点赞/收藏**状态记忆**：菜单回显「👍 已赞 / ⭐ 已收藏」，支持反转（再点取消），本地记录状态（无需重新点赞即知）
- **搜索历史**：空态显示历史词条（点词即搜），搜索自动记录，上限 20
- 卡片标题占比压缩：标题 2 行→1 行、信息区 52→44dp（封面更突出）

## 1.98（2026-10-09）UI 设计语言统一（融合 iOS / 澎湃审美）

- 大圆角体系：卡片 10→16dp、面板 14→20dp、按钮 6→10dp（radius_xl 令牌）
- 聚焦柔光：focus_card 升级为「白描边 + 外圈光晕」（layer-list+inset 模拟，API 17 兼容）
- 侧边栏：淡品牌胶囊选中（iOS tab 风格）+ 白字；宽度/项高/padding 加大（呼吸感）
- 按钮统一：主按钮去亮黄描边（品牌红+白描边）、次按钮中性灰、播放页按钮高度 42→46dp
- 封面内缩 6dp 呈现大圆角收边；卡片间距加大

## 1.97（2026-10-09）

**新功能**
- 📚 观看历史：起播自动记录（本地文件、去重、上限 100 条），主界面新增「历史」入口 + 历史页网格续看
- 👍⭐ 点赞 / 收藏：播放页菜单新增入口，接口用 a_bogus 签名 + verifyFp（Cookie 内 s_v_web_id 提取），失败时原样显示抖音 status_msg 便于排查（★ 需真机验证）
- 🖥️ 触屏菜单：播放页底栏新增「菜单」按钮（遥控 MENU 键之外的第二入口）

**Bug 修复**
- 🖼️ 老设备封面不加载：封面请求严格 TLS 失败后自动宽松重试（与播放同策略）
- 🔍 搜索界面精简：三段并排改「两段式」——联想词条嵌入结果区顶部，搜到结果自动收起
- 构建 OOM 根治尝试：gradle jvmargs 768m→1024m + workers.max=2（本机 10GB 但常驻程序占 ~9GB，构建时可用内存常跌破 1GB）

**UI 统一（三阶段）+ AppleTV 风格主界面**
- 颜色令牌化：12 个布局的散乱色值归一到语义令牌（消灭 6 种"副文字灰"、青色分隔条等）
- 尺寸/字号网格化：随手 dp/sp 值 → space_* / 4 档字号体系
- 主界面 AppleTV 风格：侧边栏分组导航（浏览/工具/账户 + 符号图标）+ 选中态品牌化（淡红底红字）+ 大标题 28sp + meta 副文本
- 按钮统一：btn_chip → focus_chip（聚焦品牌红反馈）

## 1.96（2026-10-08）★★★ IJK「第 5 个必崩/回退推荐页」根治收编 ★★★

> 版本号规则（自本版起）：对外显示 `1.xx`，次版本号 = versionCode（96 → 1.96，
> 97 → 1.97…），主版本固定 1。versionCode 单调递增保证已发布用户可覆盖升级。

**根因定性（/data/tombstones 三连实锤，彻底推翻"内存泄漏/固件杀"旧结论）：**
- tombstone_07：主线程 `eglMakeCurrent` → `gralloc.nike` → `UMP msync` abort（Mali GPU 驱动缺陷）
- tombstone_05/06：`MediaCodec::queueCSDInputBuffer` → memcpy 空指针（分辨率/旋转变化触发 CSD 重传）
- tombstone_09：`ff_read` 读线程撞 `OMX::freeNode` → `CallbackDispatcher` 析构 assert

**三条路径全部在硬解（MediaCodec/OMX）上** —— 逐个修是打地鼠，终局改为**机型自适应分流**：
- 新增 `DeviceProfile` 能力检测：老设备（≤96MB 堆 或 API≤17）→ **IJK 软解 + 540p 档**；
  正常设备 → **硬解 + 1080/720p 高清**（发布到网上不影响其它机型体验）。
- IJK 释放顺序 `stop → 排水400ms → 解绑Surface → release → 排水250ms`（消除 Mali/OMX 并发窗口）；
  `skip_loop_filter=8`（只跳非参考帧：软解省解码又不糊，48 全跳会马赛克）。
- 崩溃熔断：**IJK 崩 1 次 → 自动降级 ExoPlayer**（不再等崩 2 次才切到声音更差的 VLC）。
- 内存探针阈值按机型内存比例（不再写死 80MB/360MB，正常设备不会误触发）。

**真机验证（创维 8S52 / Android 4.2 / 647MB）：软解 540p 连刷 15+ 个视频不崩、基本流畅。**
**留档备注：此版确认收编"刷到第 5 个左右黑屏/回退推荐页"的多年 bug。**

## 80.3-buf8（2026-10-07）
- IJK 包缓冲 24MB → 8MB（native heap 大头，起播峰值砍半）。仍被杀。

## 80.2-lop（2026-10-07）
- 软解 → 硬解（mediacodec=1）：解码帧移到 OMX/GPU 侧（App 进程内存峰值大降；
  释放竞态已由 native 补丁 v2 修复，可放心开回）。
- 选档优先 360p（647MB 盒子砍解码帧缓冲）。仍被杀。

## 80.1-memfix（2026-10-07）
- ★ 真相定性：不是 native 崩溃（无 tombstone），是**被杀**（events 铁证
  am_proc_died "proc died without state saved"）。
- 弃用 reset 复用（reset 不重建 MediaCodec，codec 资源随换源累积）；
  改每次完整释放重建 + drain 延迟 400/300/300 → 100ms。仍被杀。

## 80.0-ijkp（2026-10-07）
- ★ 本地编译 IJK 0.8.8 成功（Windows + MSYS2 + NDK r10e 全程环境攻坚）：
  ffmpeg 全库 + libijkffmpeg.so + libijkplayer.so + libijksdl.so（armv7a）。
- native 补丁 v1（OMX 析构前等 200ms + 唤醒读线程）+ v2（IO 中断 g_ffp_io_abort：
  释放时 av_read_frame 秒退，根治 release 卡网络 10s 的资源堆积）。
- 替换官方 aar so（删 ijkplayer-armv7a/arm64 依赖，jniLibs 放自编译 so）。

## 播放器"第 5 个必退"最终结论（2026-10-07）
- 定性：**非崩溃、非 ANR、非 LMK 阈值、非 App 内存**（软解/硬解/360p/缓冲/
  串行释放/LMK 阈值调低全试无效）。
- 剩余嫌疑：创维酷开固件进程管理（ipcsvc 体系，GCService 已禁用但被反复拉起）。
  深挖 ROI 低，搁置。
- 可选路线：VLC 引擎（65-77 实测从不被杀，声音断续为硬件缺陷）；崩溃自愈兜底保留。

## 79.0-resume-c（2026-10-06）

- 崩溃自愈落盘改 commit()（同步写盘）：apply() 是异步写，进程瞬死时
  恢复点丢失 → 重启仍回推荐页。commit 保证崩溃前恢复点必在盘上。

## 78.0-resume
- ★ 崩溃自愈：播放中每次起播成功实时存恢复点（列表+位置+来源），
  冷启动 30 秒内检测到恢复点 → 自动回到崩前视频继续播（不再闪回首页丢位置）。
- 同一位置连续恢复 2 次（IJK 反复崩）→ 自动强制 VLC 引擎兜底。
- 正常退出（onDestroy）清恢复点，不干扰正常使用。

## 77.0-vlc-rate48
- VLC 音频 `--aout-rate=48000`：从 libvlc.so 逆向挖出的**正确**采样率选项
  （force-audio-rate 在 3.6 不存在）。强喂 48k 绕开系统 HAL 的 44.1k 缺陷。
- 声音断续仍存在 → 判定为这台电视音频 HAL 的硬件级缺陷。

## 76.0-vlc-aout2
- 删雷：`--force-audio-rate` / `--audio-jitter-compensation` 在 VLC 3.6.3 不存在，
  libvlc_new 遇未知选项直接失败。改 `--audio-resampler=soxr`（唯一可用重采样器）。

## 75.0-vlc-ajit
- audiotrack + 48k 强制 + jitter 补偿（force-audio-rate 是雷 → VLC 启动失败）

## 74.0-vlc-48k
- `--force-audio-rate=48000`（VLC 3.6.3 无此选项 → libvlc_new 直接失败，踩雷）

## 73.0-vlc-buf8
- 网络缓存 3s → 8s：视频"整体不卡了"（确认卡=网络喂不饱）；音频断续仍在。

## 72.0-vlc-layout
- ★ 画面全屏：布局回调 OnNewVideoLayoutListener 里 setWindowSize(屏幕尺寸)
  （setVideoScale 只对 VLCVideoLayout 有效，setVideoSurface 模式必须走回调）。
- 音频回 OpenSL ES（绕开 AudioTrack 硬件缺陷）+ 8s 缓冲 + 音频时钟同步。

## 71.0-vlc-aout
- VLC 音频输出 opensles → audiotrack + 3s 缓冲（断续问题依旧，更严重）。

## 70.0-vlc-fit
- setVideoScale(SURFACE_BEST_FIT)（实测对 setVideoSurface 模式无效，72 才真修）；
  含 69.0 的缓冲提示修复。

## 69.0-vlc-buf
- VLC "缓冲中"常驻修复：Buffering 事件只在未进入 Playing 时显示
  （VLC 无"缓冲结束"事件，播放中水位波动会反复触发 → 之前提示永远挂着）。

## 68.0-vlc-http
- ★ VLC 真正起播：视频直链 https → http 降级（VLC 的 gnutls 在 Android 4.2
  上找不到系统 CA（ENOENT）→ TLS 必然失败；http 明文绕开）。
- 清掉全部踩雷参数（--no-ssl-verify / -vv / --no-mediacodec）。

## 67.0-vlc-diag
- 诊断版（-vv）：确认 `--no-ssl-verify` 是 unknown option → libvlc_new 返回 null
  （VLC 3.6 对未知选项直接失败，不忽略）。

## 66.0-vlc-tls
- 加 `--no-ssl-verify`（雷：libvlc 创建失败，VLC 启动失败）。

## 65.0-vlc
- ★ 接入 libVLC 3.6.3 引擎（官方 aar，minSdk 17 正好支持 Android 4.2；
  更新的 Media3 要 minSdk 21 用不了）。VLC 自带 ffmpeg，无 IJK 的 OMX 释放竞态。
- minSdk 16 → 17（libvlc 要求；目标机就是 API 17，零影响）。
- 首次实测：libvlc 起播成功（解码器用上 VPU），但 TLS 证书验证失败。

## 64.0-exo-audio
- Exo 宽容音频 sink 委托包装（DefaultAudioSink 是 final 类，用接口委托 +
  handleBuffer 容错吞掉时间戳 discontinuity）—— 实测未生效（sink 没被调用，
  buildAudioSink 覆写链路在 2.18.1 上未分派），Exo 音频问题搁置。

## 63.0-ijk-softtest
- IJK 软解实测版（mediacodec=0，完全绕开 OMX）—— 用户实测：真卡
  （A7 单核软解 540p 临界确认），软解路线否定。

## 62.0-ijk-throttle
- IJK 连刷节流：1.5 秒内不允许连切（快速连按撞释放竞态的防抖）。
  正常观看节奏无感。

## 61.0-ijk-reuse
- IJK 复用实例（reset + 换源，对齐 Exo 策略）—— 实测仍崩：
  tombstone_03 与 09 完全同款（ff_read 撞 OMX::CallbackDispatcher 析构）→
  实例数量不是变量，确认是 native 释放时序竞态。

## 60.0-ijk-drain
- IJK 释放改后台线程：stop → 等 400ms → release → 等 300ms → 再建。
  主线程不卡；实测仍崩（竞态在 stop/release 之间的线程退出，Java 层无解）。

## 59.0-csd-fix
- 关 mediacodec-handle-resolution-change=0：修 tombstone_07（MediaCodec_loop +
  queueCSDInputBuffer 空指针，分辨率变化重启 codec 重传 CSD 触发）。
- 新崩点浮出：tombstone_09（ff_read + CallbackDispatcher 析构）——
  IJK 0.8.8 native 释放竞态正式定性，Java 层调用序列无法根治。

## 58.0-dtv
- 中间版本（被 59.0 覆盖，未交付）。

## 57.0-dtv（2026-10-05）

- **修复 IJK 连播闪退**（刷约 5 个视频后退回推荐页）：
  根因是 release() 后 native 线程（ff_read / MediaCodec_loop）仍在退出，
  旧代码在 old == null 分支会**立即** new 新实例，与将死未死的旧线程
  并发访问已释放结构 → use-after-free → SIGSEGV → 进程被杀冷启动回首页。
  修复：统一 300ms release 守卫（lastReleaseAt + scheduleCreate），
  距上次 release 不足 300ms 一律推迟创建，堵死竞态窗口。
- **正式更名 Dtv**：应用名 / APK 输出名 / 版本号统一为 Dtv 体系
（applicationId 保持不变，酷安老用户可正常覆盖升级）。
- **设置 → 关于页**：作者名称 + 作者主页二维码 + 版本号 + 签名指纹（SHA-256），
  签名与官方不符时显示红色防篡改警告条。
- 修复 App.kt / AboutActivity.kt 编译错误（上一分支遗留）。

## 56.0-no-uaf（上一版）
- IJK 释放改为「release 后异步 250ms 再新建」，避免紧接 new 的 UAF；
  释放前先 setDisplay(null) 解绑 Surface，修复 Graphics 内存只涨不落、
  连播后被 LMK 杀回首页的问题。

## 更早关键版本（55.0 及以前）
| 版本 | 要点 |
|---|---|
| 55.0-self-restart | 进程被杀后的自我恢复路径 |
| 54.0-mem-trim | 播放器内存回收优化 |
| 50.0-h264-only | 仅播 H.264（避开 HEVC 硬解缺陷） |
| 49.0-hwonly | 强制仅硬解（软解在 A7 上卡音画同步） |
| 48.0-dec-diag / 47.0-play-diag | 解码器 / 播放链路诊断 |
| 45.0-ijk-hardened | IJK 强制硬解参数加固 |
| 44.0-ijk-restore | 恢复 IJK 为主力引擎 |
| 43.0-oom-fix | 内存泄漏修复 |
| 40.0-login-page | 登录页（扫码推 Cookie） |
| 36.0/35.0 | 真实搜索接通 / 搜索入口 |
| 33.0-abogus-search | ABogus 签名 + 搜索 |
| 31.0-aspect-center | 画面比例居中修复 |
| 29.0-overlay-clean | 移除诊断横幅等浮层 |
| 26.0-aspect-fix | Exo r1.5 Handler null 根因修复（视频尺寸回调恢复） |
| 20.0-surface-fix | 基准版本（流畅，偶无声） |
