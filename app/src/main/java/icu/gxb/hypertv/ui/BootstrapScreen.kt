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
import icu.gxb.hypertv.net.getLocalIpv4
import icu.gxb.hypertv.server.SERVER_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 引导页：无直播源时恒显。
 *
 * 电视端零配置（ADR 0002）：此处只展示 WebUI 地址（IP + 端口 + 二维码），
 * 所有配置都在手机/电脑浏览器完成。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BootstrapScreen(modifier: Modifier = Modifier) {
    val ip = getLocalIpv4()
    val webuiUrl = ip?.let { "http://$it:$SERVER_PORT" }
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, webuiUrl) {
        value = webuiUrl?.let { url ->
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
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "HyperTV",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "请用手机或电脑浏览器打开下方地址，在 WebUI 中配置直播源",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(40.dp))

            if (webuiUrl != null) {
                Text(
                    text = webuiUrl,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "端口 $SERVER_PORT",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!,
                        contentDescription = "WebUI 地址二维码",
                        modifier = Modifier.size(420.dp),
                    )
                } else {
                    Text(
                        text = "二维码生成失败",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "无法获取局域网 IP，请检查网络连接",
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
