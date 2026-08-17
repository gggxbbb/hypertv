package icu.gxb.hypertv.server

import kotlin.random.Random

/**
 * 端口持久化存储（app_config 读写）：get 返回上次 listen 成功的端口，put 保存新端口。
 * 生产实现 [RepositoryPortStore] 适配 Room app_config；测试用内存 fake。
 */
interface PortStore {
    /** 读取已保存端口；从未保存或值非法时为 null */
    suspend fun get(): Int?

    /** 保存端口；写入失败由调用方吞掉（不影响功能，下次重新随机） */
    suspend fun put(port: Int)
}

/**
 * 动态端口选择器（核心逻辑，纯 JVM 可单测）。
 *
 * 语义（动态端口改造需求）：
 * 1. 启动时优先复用 app_config 已保存的端口（key = [KEY_SERVER_PORT]）；
 * 2. 无保存端口、或保存端口绑定失败（[binder] 返回 false）时，在 IANA 动态端口范围
 *    [MIN_PORT]..[MAX_PORT] 随机生成新端口重试，最多 [MAX_RANDOM_ATTEMPTS] 次；
 * 3. 绑定成功即把实际端口写回存储（幂等；写入失败不影响功能，下次重新随机）；
 * 4. 全部候选失败返回 null —— 调用方沿用现有降级路径（服务不崩溃）。
 *
 * [binder] 由调用方提供：对给定端口执行真实 bind（如 Ktor embeddedServer），
 * 成功返回 true 并记录 engine，失败返回 false。
 */
class ServerPortManager(
    private val store: PortStore,
    private val random: Random = Random.Default,
    private val maxRandomAttempts: Int = MAX_RANDOM_ATTEMPTS,
) {

    /**
     * 尝试获取一个可用的监听端口。
     * @return 绑定成功且已持久化的端口；全部候选失败返回 null
     */
    suspend fun acquirePort(binder: suspend (candidate: Int) -> Boolean): Int? {
        store.get()?.let { saved ->
            if (bindAndPersist(binder, saved)) return saved
        }
        repeat(maxRandomAttempts) {
            val candidate = random.nextInt(MIN_PORT, MAX_PORT_EXCLUSIVE)
            if (bindAndPersist(binder, candidate)) return candidate
        }
        return null
    }

    private suspend fun bindAndPersist(binder: suspend (Int) -> Boolean, port: Int): Boolean {
        if (!binder(port)) return false
        try {
            store.put(port)
        } catch (_: Exception) {
            // 写入失败不影响功能：端口已成功监听，下次启动重新随机即可
        }
        return true
    }

    companion object {
        /** IANA 动态/私有端口段下界（含）。 */
        const val MIN_PORT = 49152

        /** IANA 动态/私有端口段上界（含）。 */
        const val MAX_PORT = 65535

        /** Random.nextInt 上限（不含）。 */
        const val MAX_PORT_EXCLUSIVE = MAX_PORT + 1

        /** 随机端口重试次数上限。 */
        const val MAX_RANDOM_ATTEMPTS = 10

        /** app_config 持久化 key（与现有 key/value 风格一致）。 */
        const val KEY_SERVER_PORT = "server_port"
    }
}
