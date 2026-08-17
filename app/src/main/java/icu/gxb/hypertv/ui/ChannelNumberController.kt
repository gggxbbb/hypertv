package icu.gxb.hypertv.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 数字键输入状态机（ticket 05，spec 7.2）：
 *
 * - 数字键（0-9）累积频道号，最多 [MAX_DIGITS] 位（新数字顶掉最旧一位）
 * - OK 确认立即跳转，或 2s 无按键自动跳转（[timeoutMs]）
 * - 跳转统一经 [onJump] 回调，参数为输入的频道号（1-based，映射由
 *   [ChannelNumberMapping] 负责）
 * - [clear] 取消挂起的超时并清空输入（返回键/换台等场景）
 *
 * [digits] 暴露当前输入串供 UI 渲染提示；协程注入便于 JVM 单测
 * （生产环境为 composition scope）。
 */
class ChannelNumberController(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    private val _digits = MutableStateFlow("")
    val digits: StateFlow<String> = _digits.asStateFlow()

    /** 输入非空且由 OK/2s 超时提交时触发，参数为输入的频道号 */
    var onJump: ((Int) -> Unit)? = null

    private var timeoutJob: Job? = null

    fun hasDigits(): Boolean = _digits.value.isNotEmpty()

    /** 数字键：累积数字并重启 2s 超时窗口 */
    fun onDigit(digit: Int) {
        if (digit !in 0..9) return
        _digits.value = (_digits.value + digit).takeLast(MAX_DIGITS)
        restartTimeout()
    }

    /** OK 确认：提交当前数字串并清空 */
    fun confirm() = commit()

    /** 清空输入并取消挂起的超时（返回键、上下换台、浮层开关等场景） */
    fun clear() {
        timeoutJob?.cancel()
        timeoutJob = null
        _digits.value = ""
    }

    private fun commit() {
        val number = _digits.value.toIntOrNull()
        clear()
        if (number != null) onJump?.invoke(number)
    }

    private fun restartTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            commit()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
        /** 4 位足够覆盖 5000+ 频道（上限 9999） */
        const val MAX_DIGITS = 4
    }
}
