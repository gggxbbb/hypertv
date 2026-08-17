# HyperTV — v1 规格

> v0.4（2026-08-17）：浮层布局定稿（全高左栏 + 矮右栏）、EPG 时间轴窗口 [当前整点-1h, +3h)、固定 GMT+8 显示、marquee 滚动
> v0.3（2026-08-17）：整合迭代 2 —— txt 格式、EPG 多源/规则/五级匹配/来源追踪、动态频道号、频道详情卡片、浮层左右布局（ADR-0009~0012）

## Problem Statement

电视观众想要"开机即看"的 IPTV 体验，但主流方案存在两个痛点：电视端配置繁琐（要在电视上输入 M3U URL、管理频道文件，遥控器输入极其痛苦）；或功能残缺（无收藏、无 EPG、无频道管理）。同时家庭管理员需要一种低摩擦的方式来维护直播源（在手机/电脑上完成），而不是在电视上打字。

## Solution

一台 Android TV 上的原生播放器 + 一个局域网 WebUI：

- 电视端开机自动播放上次观看的频道，只提供观看与换台交互（上下键换台、OK 呼出列表、星号键收藏、Info 看节目、Menu 进入功能菜单），零配置、零文本输入、零文件选择。
- WebUI 是唯一的配置入口（导入直播源、频道/分组/收藏管理、EPG 配置），通过电视"关于"页展示的 IP 地址 + 二维码访问，改动实时同步到电视端。

## User Stories

1. 作为电视观众，我希望 App 启动时自动加载上次观看的频道并开始播放，这样我不需要任何操作就能看电视。
2. 作为电视观众，我希望按上下键按列表顺序切换频道（目标 <500ms），这样换台体验接近机顶盒。
3. 作为电视观众，我希望按 OK 键呼出频道列表浮层，看到频道名称、频道号与台标，这样我可以浏览选择频道。
4. 作为电视观众，我希望列表浮层中按左右键在分组标签间切换（全部/各分组/收藏），按 OK 确认换台、返回键收起列表，这样分组导航不打断观看。
5. 作为电视观众，我希望按星号键一键收藏/取消收藏当前频道并看到短暂提示，这样收藏操作不用进入菜单。
6. 作为电视观众，我希望从主菜单进入收藏列表，浏览并播放收藏频道。
7. 作为电视观众，我希望按 Info 键查看当前频道正在播放的节目名称、起止时间与简介，这样我可以了解正在看什么。
8. 作为电视观众，我希望从主菜单进入节目表（EPG Guide），以时间轴网格浏览各频道节目并跳转播放，这样我可以提前规划观看。
9. 作为电视观众，我希望系统音量键直接控制音量，App 内没有独立的音量 UI。
10. 作为电视观众，我希望按数字键输入频道号（全局编号，与分组无关）跳转到对应频道，这样我可以直达频道。
11. 作为电视观众，我希望播放失败时 App 重试 3 次后自动切换到下一个频道并短暂提示"信号中断"，这样坏台不阻塞观看。
12. 作为电视观众，我希望电视端全程不出现系统软键盘与文件选择器，这样我永远不需要在电视上输入。
13. 作为家庭管理员，我希望从"关于"页看到电视的局域网 IP、端口与二维码，这样我可以用手机浏览器打开 WebUI 配置。
14. 作为家庭管理员，我希望用手机浏览器直接访问 WebUI 而不安装任何 App，这样配置门槛最低。
15. 作为家庭管理员，我希望在 WebUI 粘贴 M3U/M3U8 URL 导入直播源，看到解析预览（频道数、分组、编码识别），这样导入前可确认。
16. 作为家庭管理员，我希望在 WebUI 上传本地 .m3u 文件导入直播源，解析效果与 URL 一致，这样我可以使用下载好的源文件。
17. 作为家庭管理员，我希望管理多个直播源（增删、刷新、重命名），重复导入同一源时增量合并（保留收藏与自定义顺序），这样源维护不会丢失我的整理成果。
18. 作为家庭管理员，我希望在 WebUI 管理频道：搜索、拖拽排序、改名、隐藏/恢复、删除、改分组、编辑台标，这样我可以在浏览器里整理频道。
19. 作为家庭管理员，我希望在 WebUI 创建/重命名/删除分组并拖拽频道入组，这样分组结构随时可调。
20. 作为家庭管理员，我希望在 WebUI 查看并编辑收藏列表，与电视端实时一致，这样收藏管理可以批量操作。
21. 作为家庭管理员，我希望为全局或特定分组配置 XMLTV EPG 源，系统自动匹配节目单，这样节目表可用。
22. 作为家庭管理员，我希望 WebUI 的修改立即生效（即时自动保存），电视端 1 秒内刷新，这样不用重启电视。
23. 作为家庭管理员，我希望多台手机/电脑同时访问 WebUI 且状态一致，任一设备的修改对所有人可见。
24. 作为系统，我希望所有数据（频道、分组、收藏、EPG）持久化在本地 SQLite，重启不丢失。
25. 作为系统，我希望首次启动（无直播源）时电视端显示引导画面，展示 WebUI 地址与二维码。
26. 作为系统，我希望频道列表在 5000+ 频道下滚动不卡顿，WebUI 搜索响应 <200ms。
27. 作为家庭管理员，我希望导入国内 txt 直播源（`名称,URL` + `#genre#` 分组），与标准 M3U 效果一致。
28. 作为家庭管理员，我希望配置**多个全局 EPG 源**（增删、启用/停用），系统全部拉取合并节目，这样不同源覆盖不同频道、互为补充。
29. 作为家庭管理员，我希望在 WebUI 为频道手动绑定 EPG（设置 epgId），或在 EPG 页为 EPG 频道编写**关键字匹配规则**（前缀/包含），这样"同一频道不同清晰度的多个源"能批量归并到同一 EPG 频道；手动绑定与规则命中不会被重导入/刷新覆盖。
30. 作为家庭管理员，我希望看到每个频道的 **EPG 匹配来源**（手动/规则/自动第几级/未匹配），这样我能判断匹配质量。
31. 作为家庭管理员，我希望 WebUI 频道列表可**展开详情卡片**，查看完整频道元数据与**今日节目单**。
32. 作为电视观众，我希望 OK 键浮层**左侧选分组（收藏第一）+ 频道、右侧看选中频道的 EPG 时间轴**，这样一屏完成"找台 + 看节目"。
33. 作为电视观众，我希望书签键（BOOKMARK）与星号键一样能收藏/取消收藏。
34. 作为家庭管理员，我希望频道号在每次变动后连续无空洞（删除频道后自动重排）。

## Implementation Decisions

- **进程与架构**：单进程 Android App，内部划分数据层（Room）、解析层（M3U/XMLTV）、播放层（Media3）、服务层（Ktor）、展示层（Compose TV + WebUI）。WebUI 静态资源随 APK 打包，由 Ktor 托管。
- **流协议范围与格式**：v1 仅支持 http(s) 承载的 HLS（m3u8，依赖 media3-exoplayer-hls 模块）、裸 MPEG-TS、MP4；UDP/RTP/RTSP 不在 v1（ADR-0001）。M3U 解析同时支持标准 M3U/M3U8 与国内 **txt 直播源格式**（`名称,URL` 行 + `分组名,#genre#` 行），UTF-8/GBK 编码自动识别。
- **数据模型**：8 张表——`playlist_sources`（直播源）、`groups`（含分组级 epgUrl 覆盖）、`channels`（含 epgId/epgManual/epgMatchSource）、`epg_programs`（节目，复合索引 (channelEpgId, startTime)）、`app_config`（键值）、`epg_sources`（全局多 EPG 源）、`epg_match_rules`（匹配规则）、`epg_channels`（EPG 频道目录，displayName 持久化）。**频道号 = 排序后位置 + 1（查询时动态生成，永远连续无空洞，ADR-0011）**。EPG 节目表带过期清理。
- **多源语义**：重复导入同一直播源按频道 URL 增量合并（保留收藏/自定义顺序/名称）；源内消失频道标记隐藏；删除直播源级联删除其频道；**导入时解析出的分组同步进 groups 表**（ADR-0004）。
- **同步机制**：电视端通过 Room Flow 订阅数据库变化自动刷新；WebUI 多标签页 5 秒轮询 + 操作后立即拉取，无 WebSocket（ADR-0003）。
- **WebUI 交互**：即时自动保存 + 500ms 防抖，无保存按钮；频道隐藏可恢复、删除不可恢复；频道列表支持**展开详情卡片**（完整元数据 + EPG 匹配来源 + 今日节目单）；UI 不含 emoji（统一 Element Plus 图标）。
- **播放策略**：单 ExoPlayer 实例复用（切换 MediaItem 不重建），不做预热（ADR-0006）；播放失败重试当前频道 3 次（间隔 2s）后自动切换下一个（ADR-0007）。
- **EPG 刷新**：启动时距上次成功刷新 >12h 自动拉取 + WebUI 手动刷新；无后台定时任务（ADR-0005）；**源支持 gzip 压缩流**（魔数检测解压）。
- **EPG 源**：**全局可配置多个源**（epg_sources 表），全局刷新按顺序全部拉取合并——节目按 (channelEpgId, startTime) 去重、后源覆盖、单源失败继续（ADR-0009）；分组级源（groups.epgUrl）为覆盖。
- **EPG 匹配**：**五级**——① tvg-id 精确 ② 忽略大小写 ③ 名称归一化精确 ④ 归一化前缀（边界检查 + 清晰度后缀白名单）⑤ 归一化包含（长度阈值 4、最长优先）（ADR-0010）；匹配来源持久化为 epgMatchSource（manual/rule/level1-5）；**手动绑定（频道侧设置 epgId 或 EPG 侧关键字规则 prefix/contains）** 标记 epgManual 防覆盖；不做模糊评分。
- **电视端频道浮层**：OK 键呼出**左右两栏**——左栏分组标签（**收藏第一** → 全部 → 各分组）+ 频道列表（行内收藏★，频道名超长 **marquee 横向滚动**），左栏**全高**（占 40% 宽，延伸到屏幕底）；右栏 **100dp 矮条**显示选中频道的 **EPG 时间轴**（窗口 **4h = [当前整点-1h, +3h)**，节目条白字、当前时间游标以覆盖层绘制在节目条上方、显示**固定 GMT+8**、超长节目标题 marquee 滚动），下方露出视频（ADR-0012）；收藏切换支持 **星号键 / 红键 / BOOKMARK 键**。
- **EPG 时间轴窗口**：全屏 Guide 页与浮层右栏共用同一窗口函数——默认窗口 **4h**，起点 = 当前整点 **-1h**（如 14:37 打开 → [13:00, 17:00)），左右键按 1h 步进滚动；所有时间戳解析后按 **固定 GMT+8（EPG_ZONE）** 显示，不随系统时区（XMLTV 源多为 +0800）。
- **配置入口**：电视端零配置，WebUI 唯一配置入口；"关于"页只读展示连接信息（ADR-0002）。
- **Android 系统要求**：Network Security Config 放行明文 http（否则 http 源不可播）；Ktor 服务运行于前台服务（含常驻通知）保活；根容器焦点保证遥控器按键可达（Compose 焦点）；无 mDNS、无 PIN（v2）。
- **API 契约（Ktor REST，局域网，动态端口 49152-65535：随机生成 + 保存复用 + 失败重试，实际端口从"关于"页获取，见 ADR-0008）**：
  - GET /api/status → {version, ip, port}
  - GET /api/channels（含 epgId/epgMatchSource/orderIndex/catchup 等完整元数据）、/api/channels/favorites、GET/POST /api/groups、POST /api/channels/reorder、PUT/DELETE /api/channels/{id}（PUT 支持 epgId 手动绑定）、POST /api/channels/{id}/favorite
  - POST /api/playlist/import/preview、POST /api/playlist/import（URL）、POST /api/playlist/upload/preview、POST /api/playlist/upload（multipart，可选 sourceName 同源匹配）、POST /api/playlist/{id}/refresh、DELETE /api/playlist/{id}；GET/PUT/DELETE /api/playlists（多源管理）
  - GET/POST/PUT/DELETE /api/epg/source（**多源 CRUD**）、POST /api/epg/refresh、GET /api/epg/now、GET /api/epg/guide；GET/POST/DELETE /api/epg/rules（匹配规则）+ POST /api/epg/rules/apply；GET /api/epg/channels（EPG 频道目录：epgId+displayName+matchedCount）
- **技术选型**：Compose for TV、Media3（exoplayer + **exoplayer-hls** + ui）、Ktor（CIO 引擎）、Room、Hilt、Kotlinx Serialization、Coil（台标）、ZXing core（二维码）、Vue 3 + Vite + TypeScript（WebUI）。M3U/XMLTV 解析需处理 GBK/UTF-8 编码与 gzip 压缩流。WebUI 构建产物经 Gradle 增量打包进 APK assets（不入库）。

## Testing Decisions

- **测试原则**：只测外部行为，不测实现细节；每个 seam 通过其公开接口验证。
- **模块 seams（从高到低）**：
  1. **M3U 解析器**——输入 URL/文件内容，输出规范化频道列表（名称/URL/分组/台标/tvg-id/catchup 字段）；覆盖 UTF-8/GBK、异常行。
  2. **增量合并器**——输入旧频道集 + 新解析结果，输出合并后的增/改/隐藏集合；验证收藏与自定义顺序保留。
  3. **EPG 匹配器**——输入 XMLTV 频道 + 频道表，输出匹配结果；验证三级匹配优先级与未匹配留空。
  4. **Repository（Room 内存库）**——CRUD、排序、级联删除、Flow 变更观察。
  5. **Ktor API 层**——routes 的请求/响应契约（Ktor testApplication）。
  6. **PlayerController**——播放/换台/失败重试的状态机行为（注入 fake player）。
- **优先测试**：导入（03）、EPG 匹配（09）为纯逻辑，最高 ROI；数据层测试随 02 一起写。
- **UI 测试**：电视端 Compose UI 测试覆盖频道列表浮层的焦点导航（可选，v1 以手动验证为主）。

## Out of Scope

- UDP/RTP/RTSP 流协议、时移回放（Catch-up）、mDNS 设备发现、PIN 认证、手机/平板端 UI、WebView、后台定时 EPG 任务、多浏览器标签页实时推送（WebSocket）。

## Further Notes

- 性能目标：冷启动到播放 <3s（有缓存源）；换台 <500ms（同编码，实测驱动）；5000 频道列表 60fps；WebUI 首屏 <2s；EPG 万条解析 <5s。
- 里程碑估算：原 spec 19d 偏乐观，按 25-30d 排期。
- 公开仓库：无密钥、无签名文件入库；采用 MIT 协议（LICENSE 已就位）。
