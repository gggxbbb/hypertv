# 01 — 数据层 schema v6 + 字段扩展

**What to build:** 为探测、时移与换台统计打好数据地基：channels 表迁移 v5→v6（新增探测/回放能力/隐藏来源字段），新建换台统计表，实体/DTO/Repository 全部支持新字段——让上层功能（探测执行器、时移播放、统计）都能读写自己的数据。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] channels 表 v5→v6 迁移：新增 `lastCheckedAt`（最近探测时间）、`checkResult`（unknown/valid/invalid 三态，默认 unknown）、`checkDuration`（探测耗时 ms）、`lastError`、`catchupSupported`（回放能力探测结果）、`hiddenSource`（manual/probe，isHidden 已有）；旧数据 hiddenSource 空值按 manual 语义处理
- [ ] 新建 `channel_switch_stats` 表（换台统计：channelId/时间戳/readyMs/firstFrameMs）
- [ ] Channel 实体 + ChannelDTO + 播放层 Channel 模型同步扩展新字段
- [ ] Repository 支持新字段读写（含 hiddenSource 语义：probe 可被探测恢复、manual 跳过探测）
- [ ] 迁移测试（v5 数据升 v6 不丢）与 Repository 测试通过（Roombased 内存库）
