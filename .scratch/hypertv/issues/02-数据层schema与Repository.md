# 02 — 数据层：Room schema 与 Repository

**What to build:** 5 张表的 Room schema（直播源/分组/频道/EPG 节目/应用配置）+ DAO + 统一 Repository。Repository 提供频道/分组/直播源/EPG 的增删改查与 Flow 变更订阅；支持按频道 URL 匹配、批量排序、级联删除语义。

**Blocked by:** 01 — 项目骨架与基础设施

**Status:** ready-for-agent

- [ ] 5 张表 schema 建立，频道号语义（orderIndex + 1）可用
- [ ] DAO 覆盖 CRUD、批量排序、按 URL 查询、级联删除
- [ ] Repository 暴露 Flow 变更流（Room 内存库单测覆盖）
- [ ] 数据库迁移策略就位（version 1 起步）
