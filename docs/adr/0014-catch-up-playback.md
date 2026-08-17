# 时移回放：EPG 节目回放形态，playseek 能力探测过滤

m3u 头部/频道级 catchup 声明（append/default 模式）只是"模板声明"，实测（JSIPTV.m3u，2026-08-17）确认只有标准 IPTV 平台源（mobaibox 系，约 55% 频道）真正响应 playseek 回放。决策：v1.1 时移采用 **EPG 节目回放**形态——在频道浮层/Guide 时间轴上长按"已播出"节目条（正在播的节目可从头回放），用节目起止时间替换 catchup-source 模板（`${(b)}`/`${(e)}`，紧凑 yyyyMMddHHmmss）生成 playseek URL 播放；对声明过 catchup 的频道做回放能力探测（复用探测框架），入口仅对确认支持的频道开放；回放流播到窗口末尾（STATE_ENDED）自动重载直播 URL；无 EPG 频道不开放回放入口。PlayerController 增加 live/catchup 播放模式，回放中浮层显示"回放中"标识与进度竖线。

**Considered Options**: 直播中实时后退（切换 playseek 本质是换流重载而非无缝 seek，交互易做坏，留后续增强）；无 EPG 兜底入口（"任意时间回放"窗口边界复杂，且 EPG 匹配率持续提升，弃）。
