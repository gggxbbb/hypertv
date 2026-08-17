# 07 — WebUI 基础与频道/分组管理

**What to build:** Vue 3 + Vite + TypeScript WebUI 项目初始化，构建产物由 Gradle 自动打进 APK assets 并由 Ktor 托管；频道列表页（即时搜索、拖拽排序、改名、隐藏/恢复、删除、改分组、编辑台标）；分组管理页（增删改、排序、频道拖拽入组）；即时自动保存（500ms 防抖）、无保存按钮；多标签页 5 秒轮询同步；所有改动经 Repository 写入后由 Room Flow 推送到电视端，电视端 1 秒内刷新。

**Blocked by:** 03 — M3U 导入与增量合并

**Status:** resolved

- [x] WebUI 静态资源随 APK 打包托管，手机浏览器可访问
- [x] 频道搜索 5000 条 <200ms
- [x] 拖拽排序、改名、隐藏/恢复、删除、改分组、改台标全部可用
- [x] 分组增删改与频道拖拽入组可用
- [x] WebUI 修改后电视端 1 秒内刷新（Room Flow）
- [x] 多标签页状态一致（5s 轮询 + 操作后立即拉取）

## Answer（2026-08-17）

Commit `0202e16` 实现并验收通过：webui/ Vue3+Vite+TS 项目（Pinia + Element Plus 中文 UI）；频道列表页（250ms 防抖搜索、分组/隐藏筛选、多选批量、行内编辑、vue-draggable-plus 拖拽排序、content-visibility 优化 5000+ 行）；分组管理页（分组 CRUD + 双向拖拽入组）；即时自动保存（500ms 防抖）+ 5s 轮询（ADR-0003）；Gradle task 链 npmInstallWebui→buildWebui（增量缓存）→copyWebuiToAssets→preBuild，产物进 assets/webui；Ktor webAssetLoader 托管（防路径穿越 + ContentType 映射）；后端 9 个管理 API 全量落地（DTO 分离、400/404 统一错误、删分组单事务归置频道）。构建成功，117 个单测全过（ManagementRouteTest 22 个）；vue-tsc 0 错误。遗留：删除频道不压实 orderIndex（重拖即规整）；content-visibility 依赖 Chrome/Edge。
