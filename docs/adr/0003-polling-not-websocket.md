# WebUI 多端同步采用轮询而非 WebSocket

spec 草案设计了 `/ws/sync` WebSocket 通道用于实时同步。落地分析：电视端刷新走 Room Flow（同进程监听数据库变化，天然实时），WebSocket 的剩余价值仅是"多个浏览器标签页之间的状态同步"——低频场景。v1 改为 5 秒轮询 + 操作后立即拉取；WebSocket 移除，待出现真实实时需求（如多端同屏控制）再加回。
