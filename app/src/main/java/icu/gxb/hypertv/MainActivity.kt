package icu.gxb.hypertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.tv.material3.ExperimentalTvMaterial3Api
import dagger.hilt.android.AndroidEntryPoint
import icu.gxb.hypertv.ui.BootstrapScreen
import icu.gxb.hypertv.ui.theme.HyperTVTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperTVTheme {
                // 本 ticket 阶段无数据层，恒显引导页
                BootstrapScreen()
            }
        }
    }
}
