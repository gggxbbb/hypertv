# 09 — EPG 体系

**What to build:** XMLTV 解析（万条 <5s）+ 三级频道匹配（tvg-id 精确 → 忽略大小写 → 频道名归一化）+ 全局/分组级 EPG 源配置 + 启动时距上次成功刷新 >12h 自动拉取 + WebUI 手动刷新 + (channelEpgId, startTime) 索引与过期节目清理。API：PUT /api/epg/source、POST /api/epg/refresh、GET /api/epg/now、GET /api/epg/guide。

**Blocked by:** 02 — 数据层：Room schema 与 Repository；03 — M3U 导入与增量合并

**Status:** ready-for-agent

- [ ] XMLTV 万条节目解析 <5s，UTF-8/GBK 均支持
- [ ] 三级匹配命中率可统计，未匹配频道留空（不做模糊评分）
- [ ] 启动过期即刷（>12h）与手动刷新均可用
- [ ] 过期节目定期清理，查询走复合索引
- [ ] EPG 匹配器单测覆盖三级优先级与边界
