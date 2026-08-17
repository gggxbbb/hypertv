package icu.gxb.hypertv.ui

/**
 * 频道号（orderIndex + 1，1-based）→ 列表索引（0-based）映射。
 *
 * 频道号与分组无关，按全局频道列表定位；超出范围自动回绕（取模），
 * 与 [icu.gxb.hypertv.player.PlayerController.switchToIndex] 的 mod 语义一致
 * （spec 7.2）。纯函数，可 JVM 单测。
 */
object ChannelNumberMapping {

    /**
     * 频道号 → 0-based 索引，超出 [totalChannels] 时回绕。
     * @return 有效索引；频道列表为空时返回 null（无可跳转目标）
     */
    fun toIndex(channelNumber: Int, totalChannels: Int): Int? {
        if (totalChannels <= 0) return null
        return (channelNumber - 1).mod(totalChannels)
    }
}
