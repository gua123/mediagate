package io.github.gua123.mediagate.data.storage.sftp

/**
 * 指纹落盘接口（**plan 4.9** 的 TOFU 持久化出口）。
 *
 * 刻意用**阻塞**签名：调用方是 JSch 的主机密钥校验回调（跑在连接的 IO 线程上），
 * 用 suspend 反而要在这里 runBlocking；实现可以直接查 Room（**别在主线程调**）。
 */
interface HostKeyPersistence {

    /** 读出全部已记录的指纹（进程启动后第一次校验时调一次）。 */
    fun loadAll(): List<HostKeyFingerprint>

    /** 记下一把指纹（同一 host:port + sha256 重复插入要幂等）。 */
    fun insert(fingerprint: HostKeyFingerprint)

    /** 清掉某台主机的全部记录（用户确认换钥后手工恢复 TOFU）。 */
    fun delete(host: String, port: Int)
}

/**
 * 会落盘的已知主机指纹存储（**R2 / plan 4.9**）：内存缓存 + 写穿（write-through）。
 *
 * 为什么要有内存缓存：JSch 每建一条连接都要校验一次指纹，指纹表却极少变化，
 * 而 [KnownHostsStore] 的读接口是同步的——每次读都打一次数据库没必要。
 *
 * 关键取舍：**第一次访问时同步加载**（在 IO 线程上，几十行数据）。
 * 不能异步预热——否则冷启动后立刻连 SFTP 时缓存还是空的，一个被换过的密钥会被
 * 当成"未知主机"按 TOFU 放行，正好绕过了变更检测。
 */
class PersistentKnownHostsStore(private val persistence: HostKeyPersistence) : KnownHostsStore {

    private val lock = Any()
    private val entries = LinkedHashMap<String, MutableList<HostKeyFingerprint>>()
    private var loaded = false

    override fun find(host: String, port: Int): List<HostKeyFingerprint> = synchronized(lock) {
        ensureLoaded()
        entries[HostKeyFingerprint.key(host, port)]?.toList().orEmpty()
    }

    override fun save(fingerprint: HostKeyFingerprint) {
        synchronized(lock) {
            ensureLoaded()
            val list = entries.getOrPut(fingerprint.storeKey) { mutableListOf() }
            if (list.any { it.sha256 == fingerprint.sha256 }) return
            list.add(fingerprint)
            persistence.insert(fingerprint)
        }
    }

    override fun remove(host: String, port: Int) {
        synchronized(lock) {
            ensureLoaded()
            entries.remove(HostKeyFingerprint.key(host, port))
            persistence.delete(host, port)
        }
    }

    override val size: Int
        get() = synchronized(lock) {
            ensureLoaded()
            entries.values.sumOf { it.size }
        }

    /** 首次访问时把库里的记录读进内存（只做一次）。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        persistence.loadAll().forEach { fingerprint ->
            entries.getOrPut(fingerprint.storeKey) { mutableListOf() }.add(fingerprint)
        }
    }
}
