# 02 — 数据层：Room schema 与 Repository

**What to build:** 5 张表的 Room schema（直播源/分组/频道/EPG 节目/应用配置）+ DAO + 统一 Repository。Repository 提供频道/分组/直播源/EPG 的增删改查与 Flow 变更订阅；支持按频道 URL 匹配、批量排序、级联删除语义。

**Blocked by:** 01 — 项目骨架与基础设施

**Status:** resolved

- [x] 5 张表 schema 建立，频道号语义（orderIndex + 1）可用
- [x] DAO 覆盖 CRUD、批量排序、按 URL 查询、级联删除
- [x] Repository 暴露 Flow 变更流（Room 内存库单测覆盖）
- [x] 数据库迁移策略就位（version 1 起步）

## Answer（2026-08-17）

Commit `1bb712c` 实现并验收通过：5 张表（playlist_sources/groups/channels/epg_programs/app_config）；channels.sourceId 外键 CASCADE（删源级联删频道）+ url/sourceId 索引；epg_programs 复合索引 (channelEpgId, startTime)；5 个 DAO + 单一 HypertvRepository（Flow 订阅 + suspend 写入）；DatabaseModule（@ApplicationContext）提供单例；exportSchema=true + schema 导出 app/schemas/1.json。构建成功，19 个单测全过（数据层 9 个新增）。Robolectric 用 4.16.1（4.17 未发布）。
