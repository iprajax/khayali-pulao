package com.khayali.pulao.ai.data

/**
 * Everything the app downloads or builds — the on-device AI model, market-data packs, the strategy
 * playbook — follows one life cycle. Pure: the managers only feed it events and render its state.
 *
 *  NotInstalled ─Install─▶ Downloading(done/total) ─Downloaded─▶ Verifying ─Verified─▶ Building(done/total) ─Built─▶ Ready
 *  Ready ─Outdated─▶ Stale ─Install─▶ Updating(done/total) ─Built─▶ Ready
 *  any active state ─Pause─▶ Paused(reason) ─Resume─▶ the state it was in;  ─Fail─▶ Failed(msg) ─Install─▶ Downloading
 *  any state ─Delete─▶ NotInstalled
 */
object Artifacts {
    enum class Kind { MODEL, DATA, INDEX }

    sealed class State {
        data object NotInstalled : State()
        data class Downloading(val done: Long, val total: Long) : State()
        data object Verifying : State()
        data class Building(val done: Int, val total: Int) : State()
        data class Ready(val version: String, val bytes: Long, val updatedAt: Long, val note: String = "") : State()
        data class Stale(val version: String, val bytes: Long, val behind: String) : State()
        data class Updating(val done: Int, val total: Int) : State()
        data class Paused(val reason: String, val resume: State) : State()
        data class Failed(val message: String) : State()
    }

    sealed class Ev {
        data object Install : Ev()
        data class Progress(val done: Long, val total: Long) : Ev()
        data object Downloaded : Ev()
        data object Verified : Ev()
        data class Built(val version: String, val bytes: Long, val at: Long, val note: String = "") : Ev()
        data class Outdated(val behind: String) : Ev()
        data class Pause(val reason: String) : Ev()
        data object Resume : Ev()
        data class Fail(val message: String) : Ev()
        data object Delete : Ev()
    }

    val State.active get() = this is State.Downloading || this is State.Verifying || this is State.Building || this is State.Updating
    val State.usable get() = this is State.Ready || this is State.Stale || this is State.Updating

    /** Events that don't fit the current state leave it unchanged (late callbacks can't corrupt it). */
    fun next(s: State, e: Ev): State = when (e) {
        Ev.Delete -> State.NotInstalled
        Ev.Install -> when (s) {
            State.NotInstalled, is State.Failed -> State.Downloading(0, 0)
            is State.Stale -> State.Updating(0, 0)
            is State.Paused -> s.resume
            else -> s
        }
        is Ev.Progress -> when (s) {
            is State.Downloading -> State.Downloading(e.done, e.total)
            is State.Building -> State.Building(e.done.toInt(), e.total.toInt())
            is State.Updating -> State.Updating(e.done.toInt(), e.total.toInt())
            else -> s
        }
        Ev.Downloaded -> if (s is State.Downloading) State.Verifying else s
        Ev.Verified -> if (s is State.Verifying) State.Building(0, 0) else s
        is Ev.Built -> if (s is State.Building || s is State.Updating || s is State.Verifying) State.Ready(e.version, e.bytes, e.at, e.note) else s
        is Ev.Outdated -> if (s is State.Ready) State.Stale(s.version, s.bytes, e.behind) else s
        is Ev.Pause -> if (s.active) State.Paused(e.reason, s) else s
        Ev.Resume -> if (s is State.Paused) s.resume else s
        is Ev.Fail -> if (s.active || s is State.Paused) State.Failed(e.message) else s
    }

    fun describe(s: State): String = when (s) {
        State.NotInstalled -> "Not installed"
        is State.Downloading -> if (s.total > 0) "Downloading ${pct(s.done, s.total)}%" else "Starting download…"
        State.Verifying -> "Checking the download…"
        is State.Building -> if (s.total > 0) "Preparing ${pct(s.done.toLong(), s.total.toLong())}%" else "Preparing…"
        is State.Ready -> "Ready · ${s.version}" + (if (s.note.isNotBlank()) " · ${s.note}" else "")
        is State.Stale -> "Update available · ${s.behind}"
        is State.Updating -> if (s.total > 0) "Updating ${pct(s.done.toLong(), s.total.toLong())}%" else "Updating…"
        is State.Paused -> "Paused · ${s.reason}"
        is State.Failed -> "Failed · ${s.message}"
    }

    /** One or two words for a status column. */
    fun short(s: State): String = when (s) {
        State.NotInstalled -> "Not installed"; is State.Downloading -> if (s.total > 0) "${pct(s.done, s.total)}%" else "Starting"
        State.Verifying -> "Checking"; is State.Building -> "Preparing"; is State.Ready -> "Ready"; is State.Stale -> "Update"
        is State.Updating -> "Updating"; is State.Paused -> "Paused"; is State.Failed -> "Failed"
    }

    private fun pct(a: Long, b: Long) = if (b <= 0) 0 else (a * 100 / b).toInt().coerceIn(0, 100)
}
