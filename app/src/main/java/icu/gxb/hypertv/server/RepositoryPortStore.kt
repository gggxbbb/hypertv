package icu.gxb.hypertv.server

import icu.gxb.hypertv.data.repository.HypertvRepository

/** [PortStore] 的真实实现：适配 [HypertvRepository] 的 app_config（key/value）读写。 */
class RepositoryPortStore(
    private val repository: HypertvRepository,
) : PortStore {

    override suspend fun get(): Int? =
        repository.getConfig(ServerPortManager.KEY_SERVER_PORT)
            ?.toIntOrNull()
            ?.takeIf { it in ServerPortManager.MIN_PORT..ServerPortManager.MAX_PORT }

    override suspend fun put(port: Int) {
        repository.putConfig(ServerPortManager.KEY_SERVER_PORT, port.toString())
    }
}
