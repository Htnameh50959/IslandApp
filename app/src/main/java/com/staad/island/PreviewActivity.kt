package com.staad.island

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A button per island mode, so every pill can be checked on-device without
 * waiting for a real song, call or charger — and without rebuilding the UI.
 */
class PreviewActivity : Activity() {

    private val modes = listOf(
        "idle" to "Idle (camera dot)",
        "media" to "Music",
        "video" to "Video",
        "status" to "Earbuds / status",
        "charging" to "Charging",
        "live" to "Timer (live activity)",
        "notification" to "Notification card",
        "actions" to "Copy & Go actions",
        "drop" to "Drop target",
        "face" to "Unlock animation",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        content.addView(TextView(this).apply {
            text = "Preview every pill"
            textSize = 24f
            gravity = Gravity.START
        })
        content.addView(TextView(this).apply {
            text = "The island must be turned on. Each preview disappears after five seconds."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        for ((mode, label) in modes) {
            content.addView(Button(this).apply {
                text = label
                setOnClickListener { IslandBus.postPreview(mode) }
            })
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }
}
