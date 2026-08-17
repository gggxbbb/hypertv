# 08 — WebUI 导入页与多源管理

**What to build:** WebUI 直播源页：M3U URL 输入 + 本地文件拖拽上传（均经编码识别解析）、解析预览（频道数/分组）、导入确认与冲突提示；多源列表（新增、重命名、刷新、删除）；删除直播源弹确认后级联删除其全部频道（含收藏记录）；重复导入同源走增量合并。

**Blocked by:** 03 — M3U 导入与增量合并；07 — WebUI 基础与频道/分组管理

**Status:** resolved

- [x] URL 导入与文件上传两条路径均可用，预览后确认导入
- [x] 多源列表：重命名/刷新/删除操作完整
- [x] 删除源前确认，确认后频道级联消失
- [x] 重复导入同源增量合并，收藏与自定义顺序保留（与 03 语义一致）

## Answer（2026-08-17）

Commit `3cf8cd8` 实现并验收通过：后端补充 GET/PUT/DELETE /api/playlists、POST /api/playlists/{id}/refresh、POST /api/playlist/upload/preview；PlaylistImporter 支持 saveFile/readFile（文件源 url 存落盘路径，refresh 读回重解析）+ 可选 sourceName；同源匹配：URL 源按归一化 URL，文件源按 (type=file, name)；删源走外键 CASCADE（含收藏）；前端 /sources 页（URL 导入/文件拖拽上传 → 预览卡（频道数/分组/编码/冲突预测 imported/updated/hidden）→ 确认导入；多源列表重命名/刷新/删除/空态）。构建成功，133 个单测全过（PlaylistManagementRouteTest 13 个）。遗留：URL 源存归一化值导致大小写敏感 URL refresh 可能失败（03 遗留）；沙箱拦截 vite emptyDir，用 cleanWebuiDist + emptyOutDir:false 规避。
