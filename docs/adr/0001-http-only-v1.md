# v1 仅支持 http(s) 流媒体协议

M3U 源中常见的 `udp://`、`rtp://`、`rtsp://` 协议（国内运营商 IPTV 源常用）不在 v1 范围：ExoPlayer 不原生支持这些协议，接入需要自定义 DataSource 或引入 LibVLC 等重依赖。v1 只承诺 http(s) 承载的 HLS（m3u8）、裸 MPEG-TS、MP4 流，覆盖共享 IPTV 源的主流格式；UDP/RTSP 支持推迟到 v2 评估。
