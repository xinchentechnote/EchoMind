package com.xinchen.echomind.asr

import java.io.File

/**
 * 引用计数的句柄池（纯 Kotlin，JVM 可测）：同一个 key 第一次 `acquire` 真打开，之后只加计数；
 * `release` 减到 0 才真关闭。打开与关闭都在池锁内做——两路任务同时首次要同一个模型时，
 * 第二路等第一路装完直接复用，而不是各装一份。
 *
 * 代价：装载期间（Paraformer 实测 ~5 s）别的 key 的 acquire 也得排队。模型就两三个、装载是秒级的一次性动作，
 * 比引入一把 per-key 锁的复杂度划算。
 */
class RefCountedPool<K, V>(
    private val open: (K) -> V,
    private val close: (V) -> Unit,
) {
    private class Entry<V>(val value: V, var refs: Int)

    private val lock = Any()
    private val entries = HashMap<K, Entry<V>>()

    /** 真打开过几次（诊断与测试：三路并发只该是 1） */
    var opened: Int = 0
        private set

    /** 真关闭过几次 */
    var closed: Int = 0
        private set

    /** 一次持有。`release` 幂等；忘了 release 就是泄漏——调用方必须放在 finally 里（AGENTS.md §5） */
    inner class Lease internal constructor(val key: K, val value: V) {
        private var released = false

        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                val entry = entries[key] ?: return
                entry.refs--
                if (entry.refs <= 0) {
                    entries.remove(key)
                    closed++
                    close(entry.value)
                }
            }
        }
    }

    /** 打开失败（模型不合格等）直接向上抛，不入池、不计数 */
    fun acquire(key: K): Lease = synchronized(lock) {
        val existing = entries[key]
        if (existing != null) {
            existing.refs++
            return Lease(key, existing.value)
        }
        val value = open(key)
        opened++
        entries[key] = Entry(value, 1)
        Lease(key, value)
    }

    fun activeLeases(key: K): Int = synchronized(lock) { entries[key]?.refs ?: 0 }
}

/**
 * 识别引擎共享池（D46，2026-10-24）：**每个模型全 App 只装一份**，多路转录任务共用同一个 `SherpaOnnxEngine`，
 * 最后一路释放时才真 `release()`。
 *
 * 为什么能共享：`SherpaOnnxEngine.recognize` 每次调用新建独立的 stream，底层 onnxruntime 的 `Session::Run`
 * 对并发调用是线程安全的；同一个识别器被多个线程同时解码正是 sherpa-onnx 自己的离线服务端用法。
 * 这把「同一时刻只有一个引擎句柄」（AGENTS.md §3）从「一个任务」改写成「一个模型一份句柄」，
 * 并发任务数由 `AudioFormats.ASR_MAX_CONCURRENT_JOBS` 另行限制。
 */
class SharedAsrEngines(private val modelsRoot: File) {

    private val pool = RefCountedPool<ModelSpec, SherpaOnnxEngine>(
        open = { spec -> SherpaOnnxEngine.open(modelsRoot, spec) },
        close = { engine -> engine.release() },
    )

    /** 装载失败抛 [AsrModelNotReadyException]（与 `SherpaOnnxEngine.open` 一致）；成功后必须在 finally 里 `release()` */
    fun acquire(spec: ModelSpec): RefCountedPool<ModelSpec, SherpaOnnxEngine>.Lease = pool.acquire(spec)

    /** 真装载次数（测试/诊断） */
    val opened: Int get() = pool.opened

    fun activeLeases(spec: ModelSpec): Int = pool.activeLeases(spec)
}
