package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.m3u.EncodingDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** EPG 刷新失败（未配置源、拉取失败、解析/匹配异常等），路由层转 400。 */
class EpgException(message: String) : Exception(message)

/** 单次刷新结果（成功路径返回给调用方）。 */
data class EpgRefreshResult(
    /** 刷新作用域：global 或 group:<name> */
    val scope: String,
    val stats: EpgMatchStats,
    /** 实际写入的节目条数 */
    val programsWritten: Int,
)

/** 刷新状态视图（内存快照，GET /api/epg/source 附带返回）。 */
data class EpgRefreshStatusView(
    val running: Boolean,
    val scope: String?,
    val lastStartedAt: Long?,
    val lastFinishedAt: Long?,
    val lastError: String?,
    val lastStats: EpgMatchStats?,
)

/** 刷新状态持有者（内存态，跨协程同步）。lastUpdate 持久化在 app_config，不在此。 */
class EpgRefreshStatus {

    private var running = false
    private var scope: String? = null
    private var lastStartedAt: Long? = null
    private var lastFinishedAt: Long? = null
    private var lastError: String? = null
    private var lastStats: EpgMatchStats? = null

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun markRunning(scope: String, at: Long) {
        running = true
        this.scope = scope
        lastStartedAt = at
        lastError = null
    }

    @Synchronized
    fun markSuccess(at: Long, stats: EpgMatchStats) {
        running = false
        lastFinishedAt = at
        lastError = null
        lastStats = stats
    }

    @Synchronized
    fun markError(at: Long, message: String) {
        running = false
        lastFinishedAt = at
        lastError = message
    }

    @Synchronized
    fun snapshot(): EpgRefreshStatusView = EpgRefreshStatusView(
        running = running,
        scope = scope,
        lastStartedAt = lastStartedAt,
        lastFinishedAt = lastFinishedAt,
        lastError = lastError,
        lastStats = lastStats,
    )
}

/**
 * EPG 刷新服务：拉取 XMLTV → 编码识别 → 流式解析 → 三级匹配 → 事务写库 →
 * 回写频道 epgId → 清理过期 → 更新 epg_last_update（ADR-0005）。
 *
 * - 频道与节目通过「channelEpgId == 频道 epgId（回写后的 XMLTV id）」关联，
 *   查询（now/guide）直接按频道 epgId 走 (channelEpgId, startTime) 复合索引
 * - 全局刷新只匹配「分组未配置独立源」的频道（分组级源覆盖全局源）
 * - 分组刷新只匹配该分组频道，源为其独立源，未配置则回退全局源
 * - 写库为批量 upsert（一次事务），先按旧 epgId 清该作用域旧数据再写入
 */
class EpgRefresher(
    private val store: EpgStore,
    private val fetcher: suspend (String) -> ByteArray,
    private val encodingDetector: EncodingDetector = EncodingDetector,
    private val parser: XmltvParser = XmltvParser(),
    private val matcher: EpgChannelMatcher = EpgChannelMatcher(),
    private val now: () -> Long = System::currentTimeMillis,
) : EpgRefreshService {
    override val status = EpgRefreshStatus()

    /** 启动过期即刷阈值（ADR-0005）：距上次成功刷新超过 12h 触发。 */
    val staleThresholdMillis: Long = STALE_THRESHOLD_MILLIS

    /** 刷新全局 EPG 源（作用域 = 未配置独立分组源的全部频道）。 */
    override suspend fun refreshGlobal(): EpgRefreshResult {
        val url = store.getGlobalSourceUrl()?.trim().orEmpty()
        if (url.isEmpty()) throw EpgException("未配置全局 EPG 源")
        val overriddenGroups = store.groups()
            .asSequence()
            .filter { !it.epgUrl.isNullOrBlank() }
            .map { it.name }
            .toHashSet()
        val channels = store.channels().filter { it.groupName !in overriddenGroups }
        return runRefresh(scope = "global", url = url, channels = channels)
    }

    /** 刷新分组级 EPG 源（未配置独立源时回退全局源；作用域 = 该分组频道）。 */
    override suspend fun refreshGroup(groupName: String): EpgRefreshResult {
        val group = store.groupByName(groupName) ?: throw EpgException("分组不存在：$groupName")
        val url = group.epgUrl?.trim().orEmpty().ifEmpty { store.getGlobalSourceUrl()?.trim().orEmpty() }
        if (url.isEmpty()) throw EpgException("分组「$groupName」未配置 EPG 源，全局源也未设置")
        val channels = store.channels().filter { it.groupName == groupName }
        return runRefresh(scope = "group:$groupName", url = url, channels = channels)
    }

    private suspend fun runRefresh(
        scope: String,
        url: String,
        channels: List<icu.gxb.hypertv.data.entity.ChannelEntity>,
    ): EpgRefreshResult {
        if (channels.isEmpty()) throw EpgException("作用域 $scope 内没有可匹配的频道")
        status.markRunning(scope, now())
        return try {
            withContext(Dispatchers.IO) {
                val bytes = fetcher(url)
                val text = encodingDetector.decode(bytes)
                val parsed = parser.parse(text)
                val match = matcher.match(parsed.channels, channels)

                // 先清该作用域旧数据（按频道当前 epgId），再批量写入新节目
                val oldKeys = channels.mapNotNull { it.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
                if (oldKeys.isNotEmpty()) store.deleteProgramsByChannelEpgIds(oldKeys)

                val matchedXmltvIds = match.mapping.values.toHashSet()
                val entities = parsed.programs
                    .filter { it.channelId in matchedXmltvIds }
                    .map { it.toEntity() }
                if (entities.isNotEmpty()) store.upsertPrograms(entities)

                // 回写频道 epgId（比对内存中的当前值，只更新变化的）
                val updates = channels.mapNotNull { ch ->
                    val xmltvId = match.mapping[ch.id]
                    if (xmltvId != null && ch.epgId != xmltvId) ch.id to xmltvId else null
                }
                if (updates.isNotEmpty()) store.updateChannelEpgIds(updates)

                store.deleteExpiredPrograms(now())
                store.setLastUpdate(now())

                val finishedAt = now()
                status.markSuccess(finishedAt, match.stats)
                EpgRefreshResult(scope = scope, stats = match.stats, programsWritten = entities.size)
            }
        } catch (e: Exception) {
            status.markError(now(), e.message ?: (e::class.simpleName ?: "刷新失败"))
            throw e
        }
    }

    /**
     * 启动过期即刷（ADR-0005）：距上次成功刷新 >12h 且已配置全局源时自动刷新。
     * 供 App 启动路径在后台协程调用；任何失败静默（状态已记录，WebUI 可见），不阻塞启动。
     */
    suspend override fun refreshIfStale(): Boolean {
        if (status.isRunning()) return false
        val last = store.getLastUpdate()
        if (!shouldAutoRefresh(last, now(), staleThresholdMillis)) return false
        val url = store.getGlobalSourceUrl()?.trim().orEmpty()
        if (url.isEmpty()) return false
        return try {
            refreshGlobal()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val STALE_THRESHOLD_MILLIS = 12L * 60 * 60 * 1000

        /** 是否需要自动刷新：从未刷新、距上次成功刷新超过阈值，或时钟异常（未来时间戳）时触发。 */
        fun shouldAutoRefresh(
            lastUpdate: Long?,
            now: Long,
            thresholdMillis: Long = STALE_THRESHOLD_MILLIS,
        ): Boolean = lastUpdate == null || lastUpdate > now || now - lastUpdate > thresholdMillis
    }
}

/** XMLTV 节目 → Room 实体；主键由 xmltvId + startTime 决定（同一源内唯一，重复则 REPLACE）。 */
internal fun EpgProgram.toEntity(): EpgProgramEntity = EpgProgramEntity(
    id = "$channelId|$startTime",
    channelEpgId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    category = category,
)
