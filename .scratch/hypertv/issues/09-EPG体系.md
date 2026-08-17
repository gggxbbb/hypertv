# 09 — EPG 体系

**What to build:** XMLTV 解析（万条 <5s）+ 三级频道匹配（tvg-id 精确 → 忽略大小写 → 频道名归一化）+ 全局/分组级 EPG 源配置 + 启动时距上次成功刷新 >12h 自动拉取 + WebUI 手动刷新 + (channelEpgId, startTime) 索引与过期节目清理。API：PUT /api/epg/source、POST /api/epg/refresh、GET /api/epg/now、GET /api/epg/guide。

**Blocked by:** 02 — 数据层：Room schema 与 Repository；03 — M3U 导入与增量合并

**Status:** resolved

- [x] XMLTV 万条节目解析 <5s，UTF-8/GBK 均支持
- [x] 三级匹配命中率可统计，未匹配频道留空（不做模糊评分）
- [x] 启动过期即刷（>12h）与手动刷新均可用
- [x] 过期节目定期清理，查询走复合索引
- [x] EPG 匹配器单测覆盖三级优先级与边界

## Answer（2026-08-17）

Commit `e932a0e` 实现并验收通过：XmltvParser（kxml2 流式，10k 节目实测 <1s，UTF-8/GBK 复用 EncodingDetector）；EpgChannelMatcher 三级匹配（tvg-id 精确→忽略大小写→名称归一化[全角转半角+小写+仅字母数字]）；EpgRefresher（拉取30s超时→解析→匹配→按作用域清旧数据→批量 upsert 单事务→回写频道 epgId→deleteExpired→写 epg_last_update；refreshIfStale 启动 >12h 即刷 ADR-0005）；分组级源存 **groups.epgUrl 新列**（MIGRATION_1_2，含手工 v1 库迁移测试）；API：PUT/GET /api/epg/source、POST /api/epg/refresh（202 + 409 防并发）、GET /api/epg/now、/api/epg/guide；WebUI /epg 页（源配置/手动刷新/命中率/节目预览）。构建成功，180 个单测全过（新增 47 个）。遗留：名匹配会回写频道 epgId（M3U 重导入会被源数据覆盖，需再刷新）；无 FK，孤儿节目靠 deleteExpired 清理。
