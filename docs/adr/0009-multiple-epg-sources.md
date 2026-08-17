# 全局 EPG 多源，全部拉取合并

原设计全局仅一个 EPG 源（app_config 单 key `epg_source_url`）。用户需求：全局可配置多个源。落地：新表 `epg_sources`（迁移 2→3），全局刷新按 orderIndex 顺序拉取全部启用源，节目按 (channelEpgId, startTime) 合并（后源覆盖），单源失败记警告继续、全部失败才报错。分组级源（groups.epgUrl）仍为覆盖。选"全部拉取合并"而非主备优先级：不同源覆盖不同频道、互为补充，单源时效差异不影响整体；代价是流量与解析开销随源数量线性增长。
