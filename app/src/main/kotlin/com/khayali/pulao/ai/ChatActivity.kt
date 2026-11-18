package com.khayali.pulao.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.khayali.pulao.DemoActivity
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import com.khayali.pulao.Prefs

/** Full-screen chat ("Ask Khayali Pulao"). Optionally seeded with the demo portfolio. */
class ChatActivity : Activity() {

    private lateinit var chat: ChatView
    private var drawer: ChatDrawer? = null
    private var drawerBack: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val k = Kit(this)
        val prefs = Prefs(this)
        chat = ChatView(this, prefs, { null }, ::open)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.BG) }
        val bar = k.row().apply { setPadding(k.dp(4), k.dp(6), k.dp(8), k.dp(6)) }
        bar.addView(Icons.button(this, k, "menu", Pal.TEXT, "Chats and more", 22, 44) { openDrawer() })
        val titles = k.column()
        titles.addView(k.text("Khayali Pulao", 17f, Pal.TEXT, 500))
        titles.addView(k.text("${com.khayali.pulao.ai.Persona.of(prefs.personaName).title} · ${ModelSpec.DISPLAY_NAME} on your phone", 12f, Pal.MUTED))
        bar.addView(titles, k.lp(0, WRAP_CONTENT, 1f))
        bar.addView(Icons.button(this, k, "edit", Pal.TEXT, "New chat", 20, 44) { chat.newChat() })
        bar.addView(k.iconButton("⋮", 22f, Pal.TEXT) { menu() }.apply { contentDescription = "More" })
        root.addView(bar, k.lp(MATCH_PARENT))
        root.addView(chat, k.lp(MATCH_PARENT, 0, 1f))

        // Edge-to-edge + keyboard: pad for system bars and the IME so the input stays visible.
        root.setOnApplyWindowInsetsListener { v, insets ->
            com.khayali.pulao.Insets.pad(v, insets)
            insets
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(Pal.BG); addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        chat.library?.let { lib ->
            val memory = MemoryStore.get(this)
            drawer = ChatDrawer(this, k, lib, { chat.currentChat }, onNew = { chat.newChat() }, onOpen = { chat.openChat(it) }, onDelete = { chat.deleteChat(it) },
                places = listOf(
                    "Commands & @mentions" to { chat.showHelp() },
                    "Memory · ${memory.playbook.bullets.size} notes" to { startActivity(Intent(this, MemoryActivity::class.java)) },
                    "Setup" to { chat.showSetup() },
                    "Models & data" to { startActivity(Intent(this, ArtifactsActivity::class.java)) },
                    "Home" to { finish() },
                )).also { d ->
                d.onClosed = { if (android.os.Build.VERSION.SDK_INT >= 33) (drawerBack as? android.window.OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }; drawerBack = null }
                frame.addView(d, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                d.setOnApplyWindowInsetsListener { v, insets -> com.khayali.pulao.Insets.pad(v, insets); insets }
            }
        }
        setContentView(frame)
    }

    /** The drawer: chats on this phone, then the app's places. Back closes it (Android 13+). */
    private fun openDrawer() {
        val d = drawer ?: return
        d.open()
        if (android.os.Build.VERSION.SDK_INT >= 33 && drawerBack == null) {
            val cb = android.window.OnBackInvokedCallback { closeDrawer() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb); drawerBack = cb
        }
    }

    private fun closeDrawer() = drawer?.close()

    private fun open(target: String): Boolean {
        runOnUiThread {
            when (target) {
                "demo" -> startActivity(Intent(this, DemoActivity::class.java))
                "home", "settings" -> finish()
                else -> startActivity(Intent(this, DemoActivity::class.java))
            }
        }
        return target in AppTools.Lists.TARGETS
    }

    private fun menu() {
        val mm = ModelManager.get(this)
        val memory = MemoryStore.get(this)
        val items = arrayOf("New chat", "Commands & @mentions", "Memory (${memory.playbook.bullets.size} notes)", "Setup", "Models & data", "Delete model (free ${DownloadText.bytes(ModelSpec.SIZE_BYTES)})")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setItems(items) { _, i ->
            when (i) {
                0 -> chat.newChat()
                1 -> chat.showHelp()
                2 -> startActivity(Intent(this, MemoryActivity::class.java))
                3 -> chat.showSetup()
                4 -> startActivity(Intent(this, ArtifactsActivity::class.java))
                5 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Delete the on-device model? You can download it again later.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> mm.delete() }.show()
            }
        }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ChatView.MIC_REQUEST) chat.onMicPermission(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onPause() { chat.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); ModelManager.get(this).refresh(); chat.onResume() }
    override fun onDestroy() { chat.release(); super.onDestroy() }

    companion object { const val EXTRA_DEMO = "demo" }
}

