package com.khayali.pulao.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.khayali.pulao.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide owner of the LiteRT-LM engine (one ~1 GB model in memory at most).
 * Loads lazily on first chat, tries GPU then CPU, and unloads after [IDLE_MS]
 * without use or when Android is short on memory.
 */
object AiEngine {
    sealed class State {
        data object Unloaded : State()
        data class Loading(val startedAt: Long, val backend: String) : State()
        data class Ready(val backend: String, val loadMs: Long) : State()
        data class Failed(val message: String) : State()
    }

    private const val IDLE_MS = 5 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state

    @Volatile private var engine: Engine? = null
    /** Set when the GPU loaded but failed at inference time on this device; we stay on CPU after that. */
    @Volatile var gpuBroken = false
        private set

    /** The GPU path failed mid-generation: drop the engine and use CPU from now on. */
    suspend fun fallBackToCpu() = lock.withLock {
        gpuBroken = true
        engine?.let { runCatching { it.close() } }
        engine = null
        _state.value = State.Unloaded
    }

    val onGpu: Boolean get() = (state.value as? State.Ready)?.backend == "GPU"
    private var idleJob: Job? = null

    /** Returns a ready engine, loading it if needed. Throws with a readable message on failure. */
    suspend fun acquire(ctx: Context): Engine = lock.withLock {
        touch()
        engine?.let { return it }
        val mm = ModelManager.get(ctx)
        check(mm.isReady()) { "The AI model isn't downloaded yet." }
        val cache = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.path
        val t0 = SystemClock.elapsedRealtime()
        var lastError: Throwable? = null
        val order = listOf<Pair<String, () -> Backend>>("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
        for ((name, backend) in if (gpuBroken) order.drop(1) else order) {
            _state.value = State.Loading(t0, name)
            val e = runCatching {
                withContext(Dispatchers.IO) {
                    Engine(EngineConfig(mm.file.path, backend(), null, null, 4096, null, cache)).also { it.initialize() }
                }
            }.onFailure { lastError = it; if (BuildConfig.DEBUG) Log.w("KhayaliAI", "$name backend failed", it) }.getOrNull()
            if (e != null) {
                engine = e
                _state.value = State.Ready(name, SystemClock.elapsedRealtime() - t0)
                touch()
                return e
            }
        }
        _state.value = State.Failed(lastError?.message ?: "Couldn't start the model on this phone")
        throw IllegalStateException(lastError?.message ?: "Couldn't start the model", lastError)
    }

    /** Mark activity; resets the idle-unload timer. */
    fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch { delay(IDLE_MS); unload() }
    }

    fun unload() {
        scope.launch {
            lock.withLock {
                idleJob?.cancel()
                engine?.let { runCatching { it.close() } }
                engine = null
                _state.value = State.Unloaded
            }
        }
    }
}
