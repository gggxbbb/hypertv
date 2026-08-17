package icu.gxb.hypertv.epg

import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.m3u.EncodingDetector
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
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
    /** 多源刷新时单源失败的警告（部分源失败仍成功，全部失败则抛异常） */
    val warnings: List<String> = emptyList(),
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
 * EPG 刷新服务（v3 多源）：逐个拉取启用源 → 编码识别 → 流式解析 → 合并节目 →
 * 三级匹配 → 事务写库 → 回写频道 epgId（跳过 epgManual）→ 应用匹配规则 →
 * 清理过期 → 更新 epg_last_update（ADR-0005）。
 *
 * - 频道与节目通过「channelEpgId == 频道 epgId（回写后的 XMLTV id）」关联，
 *   查询（now/guide）直接按频道 epgId 走 (channelEpgId, startTime) 复合索引
 * - **多源合并**：全局刷新拉取 epg_sources 中全部 enabled 源（按 orderIndex 顺序）；
 *   各源 XMLTV 频道并集（同 id 首个源保留），节目按 (channelEpgId, startTime) 去重、
 *   后拉取源覆盖（简单一致方案）。单源失败记录警告并继续后续源，全部失败才报错
 * - 全局刷新只匹配「分组未配置独立源」的频道（分组级源覆盖全局源）
 * - 分组刷新只匹配该分组频道，源为其独立源，未配置则回退全部启用全局源
 * - 手动绑定（epgManual=true）的频道 epgId 在回写与规则应用时一律跳过
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

    /** 刷新全局 EPG 源（作用域 = 未配置独立分组源的全部频道；多源拉取合并）。 */
    override suspend fun refreshGlobal(): EpgRefreshResult {
        val urls = store.epgEnabledSources().map { it.url.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) throw EpgException("未配置启用的全局 EPG 源")
        val overriddenGroups = store.groups()
            .asSequence()
            .filter { !it.epgUrl.isNullOrBlank() }
            .map { it.name }
            .toHashSet()
        val channels = store.channels().filter { it.groupName !in overriddenGroups }
        return runRefresh(scope = "global", urls = urls, channels = channels)
    }

    /** 刷新分组级 EPG 源（未配置独立源时回退全部启用全局源；作用域 = 该分组频道）。 */
    override suspend fun refreshGroup(groupName: String): EpgRefreshResult {
        val group = store.groupByName(groupName) ?: throw EpgException("分组不存在：$groupName")
        val ownUrl = group.epgUrl?.trim().orEmpty()
        val urls = if (ownUrl.isNotEmpty()) {
            listOf(ownUrl)
        } else {
            store.epgEnabledSources().map { it.url.trim() }.filter { it.isNotEmpty() }
        }
        if (urls.isEmpty()) throw EpgException("分组「$groupName」未配置 EPG 源，全局源也未设置")
        val channels = store.channels().filter { it.groupName == groupName }
        return runRefresh(scope = "group:$groupName", urls = urls, channels = channels)
    }

    private suspend fun runRefresh(
        scope: String,
        urls: List<String>,
        channels: List<ChannelEntity>,
    ): EpgRefreshResult {
        if (channels.isEmpty()) throw EpgException("作用域 $scope 内没有可匹配的频道")
        status.markRunning(scope, now())
        return try {
            withContext(Dispatchers.IO) {
                val (parsed, warnings) = fetchAndMerge(urls)

                // 持久化 EPG 频道目录（v4）：合并结果的 XMLTV 频道幂等 upsert，与节目解耦，
                // 作用域清旧/过期清理都不删目录；重复刷新同 id 覆盖 displayName/icon。
                store.upsertEpgChannels(
                    parsed.channels.map { ch ->
                        EpgChannelEntity(
                            id = ch.id,
                            displayName = ch.displayNames.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: ch.id,
                            icon = ch.iconUrl,
                        )
                    },
                )

                val match = matcher.match(parsed.channels, channels)

                // 先清该作用域旧数据（按频道当前 epgId），再批量写入新节目
                val oldKeys = channels.mapNotNull { it.epgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
                if (oldKeys.isNotEmpty()) store.deleteProgramsByChannelEpgIds(oldKeys)

                val matchedXmltvIds = match.mapping.values.toHashSet()
                val entities = parsed.programs
                    .filter { it.channelId in matchedXmltvIds }
                    .map { it.toEntity() }
                if (entities.isNotEmpty()) store.upsertPrograms(entities)

                // 回写频道 epgId：epgManual=true 的频道跳过（手动绑定不被覆盖）；
                // 规则命中优先于三级自动匹配（同一频道规则值覆盖自动匹配值）
                val merged = LinkedHashMap<String, String>()
                channels.forEach { ch ->
                    if (ch.epgManual) return@forEach
                    val auto = match.mapping[ch.id]
                    if (auto != null && ch.epgId != auto) merged[ch.id] = auto
                }
                val rules = store.matchRules()
                if (rules.isNotEmpty()) {
                    val ruleResult = applyMatchRules(channels, rules.map { MatchRule(it.epgChannelId, it.keyword, it.ruleType) })
                    for ((id, epgId) in ruleResult.updates) merged[id] = epgId
                }
                if (merged.isNotEmpty()) store.updateChannelEpgIds(merged.toList())

                store.deleteExpiredPrograms(now())
                store.setLastUpdate(now())

                val finishedAt = now()
                status.markSuccess(finishedAt, match.stats)
                EpgRefreshResult(scope = scope, stats = match.stats, programsWritten = entities.size, warnings = warnings)
            }
        } catch (e: Exception) {
            status.markError(now(), e.message ?: (e::class.simpleName ?: "刷新失败"))
            throw e
        }
    }

    /**
     * 拉取并解析全部源；单源失败记录警告继续后续源，全部失败抛 [EpgException]。
     * 返回合并后的解析结果 + 警告列表。
     */
    private suspend fun fetchAndMerge(urls: List<String>): Pair<XmltvParseResult, List<String>> {
        val results = mutableListOf<XmltvParseResult>()
        val warnings = mutableListOf<String>()
        for (url in urls) {
            try {
                val bytes = gunzipIfNeeded(fetcher(url))
                val text = encodingDetector.decode(bytes)
                results += parser.parse(text)
            } catch (e: Exception) {
                warnings += "EPG 源拉取失败：$url（${e.message}）"
            }
        }
        if (results.isEmpty()) {
            throw EpgException(warnings.lastOrNull() ?: "没有可用的 EPG 源")
        }
        return mergeParseResults(results) to warnings
    }

    /**
     * gzip 魔数检测（0x1f 0x8b）后解压；明文直接返回。
     * 兼容两类真实源：服务器直接返回 .gz 压缩流（如 epg.51zmt.top/e1.xml.gz），
     * 以及 Content-Encoding 已解压/明文 XML（同站 e.xml.gz）。
     */
    internal fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }

    /**
     * 多源解析结果合并：
     * - XMLTV 频道并集（同 id 首个源保留，匹配索引确定性）
     * - 节目按 (channelEpgId, startTime) 去重，**后拉取源覆盖**（简单一致方案，
     *   主键 "$channelId|$startTime" 与实体一致，后源胜出语义稳定）
     */
    private fun mergeParseResults(results: List<XmltvParseResult>): XmltvParseResult {
        val channels = LinkedHashMap<String, EpgChannel>()
        val programs = LinkedHashMap<String, EpgProgram>()
        for (result in results) {
            for (ch in result.channels) {
                if (ch.id.isNotEmpty()) channels.putIfAbsent(ch.id, ch)
            }
            for (p in result.programs) {
                programs["${p.channelId}|${p.startTime}"] = p
            }
        }
        return XmltvParseResult(channels.values.toList(), programs.values.toList())
    }

    /**
     * 启动过期即刷（ADR-0005）：距上次成功刷新 >12h 且已配置启用全局源时自动刷新。
     * 供 App 启动路径在后台协程调用；任何失败静默（状态已记录，WebUI 可见），不阻塞启动。
     */
    suspend override fun refreshIfStale(): Boolean {
        if (status.isRunning()) return false
        val last = store.getLastUpdate()
        if (!shouldAutoRefresh(last, now(), staleThresholdMillis)) return false
        if (store.epgEnabledSources().isEmpty()) return false
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
