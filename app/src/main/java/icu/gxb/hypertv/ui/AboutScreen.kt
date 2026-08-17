package icu.gxb.hypertv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 关于页（ticket 11，ADR-0002）：全屏只读信息页——App 版本、局域网 IP、端口、
 * WebUI 地址与二维码（扫码打开 WebUI），外加一句话说明"所有配置请通过 WebUI 完成"。
 * 无任何可修改项；返回键关闭回到播放页（按键语义由 PlayerScreen 分发，本页只渲染）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AboutScreen(
    info: AboutInfo,
    modifier: Modifier = Modifier,
) {
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, info.webUiUrl) {
        value = info.webUiUrl?.let { url ->
            withContext(Dispatchers.Default) { generateQrCode(url)?.asImageBitmap() }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "关于 HyperTV",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "版本 ${info.versionName}",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            val ipText = info.ip ?: "无法获取局域网 IP，请检查网络连接"
            Text(
                text = "局域网 IP：$ipText",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "端口：${info.port}",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val url = info.webUiUrl
            if (url != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "WebUI：$url",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!,
                        contentDescription = "WebUI 地址二维码",
                        modifier = Modifier.size(340.dp),
                    )
                } else {
                    Text(
                        text = "二维码生成失败",
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "所有配置请通过 WebUI 完成",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "返回键退出",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
