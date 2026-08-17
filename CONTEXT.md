# HyperTV

HyperTV 是一款面向 Android TV 的 M3U 直播源播放器：电视端只负责播放与换台，所有配置（导入源、频道管理、EPG）通过局域网内的 WebUI 完成。

## Language

**直播源（Playlist Source）**:
一个 M3U/M3U8 来源，可以是 URL 或上传的文件，包含一组频道。App 可同时管理多个直播源。
_Avoid_: M3U 源、播放列表

**频道（Channel）**:
单个可播放的直播流，归属于一个直播源和一个分组，具有全局稳定的排序位置。
_Avoid_: 台、stream

**频道号（Channel Number）**:
频道的全局排序号，等于频道在全局列表中的 orderIndex + 1，与当前查看的分组无关；数字键按其跳转。
_Avoid_: 台号、序号

**分组（Group）**:
频道的分类标签，用于电视端列表的标签页切换与 WebUI 的归类管理。
_Avoid_: 分类、类别

**收藏（Favorite）**:
用户通过电视端星号键或 WebUI 标记的频道集合。

**EPG**:
电子节目指南（XMLTV 数据），为频道提供节目单信息。
_Avoid_: 节目表（"节目表"专指 EPG Guide 页面）

**时移回放（Catch-up）**:
利用频道提供的 catchup 标签，从节目开始时间点回放已播出节目的能力。v2 范围。
_Avoid_: 回放

**WebUI**:
App 内嵌 Ktor 服务托管的网页，是唯一的配置入口，运行在手机/电脑浏览器。
_Avoid_: 管理页面、控制台

**电视端（TV 端）**:
Android TV 上的原生界面，只消费数据，不提供任何配置入口。
_Avoid_: 客户端、App 端
