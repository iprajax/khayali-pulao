package com.khayali.pulao.ai

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import com.khayali.pulao.Kit
import com.khayali.pulao.R

/** Outlined line icons (Material Icons, Apache 2.0) for the drawer, the input bar and each answer's action row. */
object Icons {
    private val RES = mapOf(
        "thumb_up" to R.drawable.ic_thumb_up, "thumb_down" to R.drawable.ic_thumb_down, "copy" to R.drawable.ic_copy, "refresh" to R.drawable.ic_refresh,
        "share" to R.drawable.ic_share, "volume" to R.drawable.ic_volume, "more" to R.drawable.ic_more, "menu" to R.drawable.ic_menu, "add" to R.drawable.ic_add,
        "search" to R.drawable.ic_search, "mic" to R.drawable.ic_mic, "live" to R.drawable.ic_live, "edit" to R.drawable.ic_edit, "delete" to R.drawable.ic_delete,
        "pin" to R.drawable.ic_pin, "expand" to R.drawable.ic_expand, "close" to R.drawable.ic_close, "check" to R.drawable.ic_check, "stop" to R.drawable.ic_stop,
        "send" to R.drawable.ic_send, "sparkle" to R.drawable.ic_sparkle,
    )

    fun res(name: String) = RES.getValue(name)

    /** A tinted icon of [size] dp inside a [box] dp touch target, with a soft round press state. */
    fun button(ctx: Context, k: Kit, name: String, color: Int, desc: String, size: Int = 20, box: Int = 40, onClick: (ImageView) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res(name)); imageTintList = ColorStateList.valueOf(color)
            val pad = k.dp((box - size) / 2)
            setPadding(pad, pad, pad, pad); scaleType = ImageView.ScaleType.FIT_CENTER
            background = k.press(null, 999f); contentDescription = desc
            setOnClickListener { onClick(this) }
        }

    fun tint(v: ImageView, color: Int) { v.imageTintList = ColorStateList.valueOf(color) }
}
