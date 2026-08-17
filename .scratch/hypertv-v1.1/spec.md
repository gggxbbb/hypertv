# HyperTV — v1.1 规格

> v1.0（2026-08-17）：时移回放 + 频道有效性探测 + 换台统计 + 首启通知权限申请。设计经 grill-with-docs 定案（ADR-0013/0014，CONTEXT.md 术语已更新）。**暂不开工**——用户先实测 v1.0.0 并修正 bug，v1.1.0 待 v1.0.0 稳定后启动。

## Problem Statement

v1.0.0 已验证核心播放链路，但两个现实问题浮现：

1. **失效频道无人管理**：导入型 IPTV 源（如 282 频道的 JSIPTV）失效频道是常态，电视端遇到就自动跳台，WebUI 手动排查几百个频道不可行——需要系统自动探测并隐藏失效频道，让电视端的频道列表始终可用。
2. **直播源其实"能回看"**：部分源站（实测 mobaibox 系，约 55% 频道）支持 playseek 时移回放，m3u 头部也有 catchup 声明，但 v1 完全没有利用——用户错过直播节目只能等重播。

另外：换台耗时（<500ms 目标）一直靠感觉，需要长期统计数据；Android 13+ 的前台服务通知权限（POST_NOTIFICATIONS）尚未申请。

## Solution

一个版本三个能力，全部配置与展示走 WebUI、电视端零新增配置入口：

- **频道有效性探测**：导入/刷新后自动探测（新增必测 + 无效重测 + 其余 24h 缓存）+ WebUI 手动重测；三态（未知/有效/无效）；无效频道自动隐藏（可恢复），探测到有效自动恢复；用户手动隐藏的频道不参与探测。**电视端完全无感知**。
- **时移回放**：EPG 时间轴**长按已播出节目**从头回放（正在播的节目也可从头回放），playseek URL 播放；仅对探测确认支持回放的频道开放入口；回放播完自动切回直播；回放中浮层显示"回放中"标识与进度竖线。
- **换台统计**：每次换台记录流就绪（STATE_READY）与首帧近似耗时，Room 持久化，WebUI 面板展示聚合（均值/p95/最近 20 条）。
- **首启通知权限**：POST_NOTIFICATIONS 首次启动申请一次，拒绝不阻塞功能。

## User Stories

**时移回放**

1. 作为电视观众，我希望在 EPG 时间轴上**长按一个已播出的节目**，从节目开始时间点回放它，这样错过开头的节目能从头看。
2. 作为电视观众，我希望**正在播出的节目也能从头回放**（playseek 起点=节目开始），这样"从头看"是常态需求而非例外。
3. 作为电视观众，我希望回放播放中能正常**快进/快退**（回放列表内 seek），这样我能跳过或回看任意片段。
4. 作为电视观众，我希望回放流播到窗口末尾时**自动切回直播**，这样看回放不打断继续看电视。
5. 作为电视观众，我希望回放加载失败时提示并**自动回到直播**，这样坏的回放源不卡住播放。
6. 作为电视观众，我希望回放时浮层/时间轴显示**"回放中"标识与回放进度位置**，这样我知道当前看的是回放而不是直播。
7. 作为电视观众，我希望**不支持回放的频道**不出现回放入口（长按无反应），这样我不会对无效操作产生困惑。

**频道有效性探测**

8. 作为家庭管理员，我希望导入/刷新直播源后系统**自动探测**频道（新增频道必测、上次无效的重测、其余 24h 内测过的跳过），这样新源导入后频道列表马上可用。
9. 作为家庭管理员，我希望在 WebUI **手动触发"重测无效 / 全部重测"**，这样我可以按需复核。
10. 作为家庭管理员，我希望 WebUI 频道列表显示每个频道的**有效性三态**（未知/有效/无效），并能按状态**过滤**，这样失效频道一目了然。
11. 作为家庭管理员，我希望探测发现的**无效频道自动隐藏**（电视端消失、WebUI 可见、可恢复），这样电视端列表始终干净。
12. 作为家庭管理员，我希望被探测隐藏的频道在**探测到有效后自动恢复**显示，这样失效频道复活无需手动处理。
13. 作为家庭管理员，我希望**手动隐藏**的频道不参与探测，这样我的整理意图不被系统覆盖。
14. 作为家庭管理员，我希望 WebUI 页面顶部显示**各源健康统计**（有效/无效/未测数），这样我能判断哪个源该清理。
15. 作为家庭管理员，我希望探测**进行中看到进度**，新导入数据时旧探测自动中止，这样长时间探测不阻塞后续操作。
16. 作为系统，我希望播放失败累计（重试 3 次耗尽计一次，累计 3 次）将频道标记无效并自动隐藏，成功播放一次即重置，这样播放层面的证据也能反馈到探测结果。
17. 作为电视观众，我希望电视端**全程无探测相关 UI**（无提示、无进度、无菜单项），这样探测完全在后台进行。

**换台统计**

18. 作为家庭管理员，我希望系统记录每次换台的**流就绪耗时与首帧近似耗时**，这样换台体验有真实数据。
19. 作为家庭管理员，我希望 WebUI 有**换台统计面板**展示聚合指标（均值/p95/最近 20 条明细），跨重启保留，这样长期追踪换台性能。
20. 作为电视观众，我希望电视端没有任何统计 UI，这样观看界面不被调试信息打扰。

**遗留收尾**

21. 作为电视观众，我希望 App 首次启动时**申请通知权限一次**，拒绝也能正常使用（前台服务照常运行），这样服务保活合规且不打扰。

## Implementation Decisions

- **数据模型（channels 表 v5→v6 迁移）**：新增 `lastCheckedAt`（最近探测时间）、`checkResult`（unknown/valid/invalid 三态，默认 unknown）、`checkDuration`（探测耗时 ms）、`lastError`（最近失败原因）、`catchupSupported`（回放能力探测结果）、`hiddenSource`（manual/probe，isHidden 已有）。新增 `channel_switch_stats` 表（换台统计：channelId/时间戳/readyMs/firstFrameMs）。
- **探测执行器（ProbeRunner）**：GET 频道 m3u8 列表并校验含 `#EXTM3U` 与有效 `#EXTINF`；连接超时 5s、总超时 10s、失败重试 1 次；并发 8、每频道间隔 ≥100ms、HTTP 连接复用（规避 CDN 短时高频拒答）；结果 + 耗时落库。播放失败联动：重试 3 次耗尽计一次无效信号，累计 3 次标记无效并自动隐藏，成功播放一次重置。
- **探测触发策略**：导入/刷新后自动——新增频道必测、`checkResult=invalid` 的重测、其余 24h 缓存跳过；WebUI 手动"重测无效 / 全部重测"；新导入可中止进行中的探测任务。**电视端零感知**（无任何探测 UI）。
- **隐藏语义**：`hiddenSource=probe` 的频道探测到有效自动恢复显示；`hiddenSource=manual` 跳过探测（尊重用户意图）；`hiddenSource` 为空的历史隐藏频道按 manual 处理。
- **时移播放链路**：播放层 Channel 模型补 catchup 三字段（解析/存储/管理 API v1 已通）；新增 **CatchupUrlBuilder**——按 catchup-source 模板替换 `${(b)}`/`${(e)}`（紧凑 `yyyyMMddHHmmss`）生成 playseek URL，支持 `append`/`default` 两种模式，`shift` 不支持（标记不可用）；回放请求失败视为不支持。
- **回放能力探测**：复用 ProbeRunner 框架，对声明过 catchup 的频道额外发一个回放窗口请求验证，结果落 `catchupSupported`；**时移入口仅对 catchupSupported 频道开放**。
- **播放器**：PlayerController 增加 `PlaybackMode`（live/catchup）；回放流监听 `STATE_ENDED` → 自动重载原直播 URL；回放加载失败 → 提示 + 自动回直播；回放中浮层显示"回放中"标识，时间轴游标指向回放进度位置而非当前时间。
- **时移入口**：频道浮层右栏与全屏 Guide 时间轴，**长按已播出节目条**触发回放（正在播的节目允许从头回放）；无 EPG 频道不开放回放入口。
- **换台统计**：PlayerController 换台路径打点——确认换台到 `STATE_READY` 耗时 + 首帧近似（监听 videoSize 首次变化）；写入 `channel_switch_stats` 表；聚合查询（均值/p95/最近 20 条）；WebUI 独立面板，电视端零 UI。
- **通知权限**：首次启动申请 POST_NOTIFICATIONS 一次；拒绝不阻塞（Android 13+ 无权限前台服务照常运行，仅通知不可见）。
- **API 契约新增**：
  - `POST /api/channels/probe`（body: `{scope: all|invalid}`）、`GET /api/channels/probe/status`（进行中进度）
  - `GET /api/stats/channel-switch`（聚合 + 最近 20 条明细）
  - `/api/channels` DTO 补 `checkResult/lastCheckedAt/checkDuration/lastError/catchupSupported/hiddenSource`
- **版本**：versionName 1.1.0；release 继续 debug 签名（签名/CI 暂不处理，另行决策）。

## Testing Decisions

- **测试原则**：只测外部行为；纯逻辑 seam 优先；网络类 seam 注入 fake。
- **新增 seams**：
  1. **CatchupUrlBuilder**（纯函数）——模板替换：append/default 两种模式的 URL 生成、`${(b)}/${(e)}` 变量、异常模板降级。
  2. **ProbeRunner**（注入 fake HTTP）——三态判定、超时/重试、并发调度、播放失败信号累计、24h 缓存与触发范围选择。
- **扩展既有 seams**：
  - **PlayerController**——PlaybackMode 状态机（live/catchup 切换、播完回直播、回放失败回直播）、换台统计打点（fake player 控制 ready/firstFrame 时序）。
  - **Repository（Room 内存库）**——v6 迁移、探测字段读写、hiddenSource 语义、channel_switch_stats 写入与聚合查询。
  - **Ktor API 层**——probe 触发/status、stats/channel-switch 的请求/响应契约。
- **UI**：电视端长按回放交互、回放中标识、WebUI 探测列/统计面板——手动验证为主（沿用 v1 惯例）；时间轴长按冲突（与现有按键行为）需冒烟测试重点覆盖。
- **参考先例**：ProbeRunner 的 fake 注入仿照 PlayerControllerTest 的 fake player；CatchupUrlBuilder 仿照 M3uParserTest 的纯函数测试。

## Out of Scope

- 直播中实时后退（timeshift 拖拽，换流非无缝 seek，留后续增强）。
- 无 EPG 频道的回放兜底入口（"任意时间回放"窗口边界复杂）。
- catchup `shift` 模式。
- 后台定时探测任务（当前仅导入/刷新自动 + WebUI 手动）。
- release 签名配置与 CI（继续 debug 签名，另行决策）。
- UDP/RTP/RTSP、mDNS、PIN、手机端 UI、WebSocket（保持 v2 范围）。

## Further Notes

- **开工时机**：本 spec 与 tickets 先行定稿；实施等用户完成 v1.0.0 实测与 bug 修复后启动（届时从 frontier 领取）。
- 探测并发 8 + 间隔 ≥100ms 是规避 mobaibox 式 CDN 短时高频拒答的实测依据；282 频道全量探测约 1-2 分钟，WebUI 进度可见、可中止。
- 时移仅对标准 IPTV 平台源（playseek 实测有效）开放入口；catchup 能力探测结果可能误判，回放失败时按"提示 + 回直播"兜底，不阻塞观看。
- v1.0.0 的 bug 修复 tickets 继续进 `.scratch/hypertv/issues/`，与本目录互不干扰。
