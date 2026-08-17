# 04 — 探测 API + WebUI

**What to build:** 探测的唯一配置/查看入口：WebUI 频道列表展示每个频道的有效性、支持按状态过滤与手动重测（全部/仅无效）、探测进行中可见进度，页面顶部展示各直播源健康统计——家庭管理员在浏览器里完成全部探测管理，电视端零感知。

**Blocked by:** 03 — 探测触发与隐藏联动

**Status:** ready-for-agent

- [ ] API：`POST /api/channels/probe`（body 支持 `{scope: all|invalid}`）、`GET /api/channels/probe/status`（进行中进度：已完成/总数）
- [ ] `/api/channels` DTO 补 `checkResult/lastCheckedAt/checkDuration/lastError/catchupSupported/hiddenSource`
- [ ] WebUI 频道列表：有效性三态徽标列（未知/有效/无效）+ 按状态过滤
- [ ] WebUI 重测按钮（"重测无效 / 全部重测"）+ 探测进行中进度展示
- [ ] WebUI 页面顶部各直播源健康统计（有效/无效/未测数）
- [ ] Ktor 路由测试（probe 触发/status 契约）+ WebUI 冒烟验证
