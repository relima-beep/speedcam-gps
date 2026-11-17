
package com.speedcam.gps

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class SpeedOverlay {
    @Volatile
    var speedKmh: Int = 0

    fun draw(canvas: Canvas) {
        val scale = canvas.width / 1080f

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 0, 0, 0)
        }

        canvas.drawRoundRect(
            30f * scale, 30f * scale,
            360f * scale, 155f * scale,
            22f * scale, 22f * scale,
            background
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 70f * scale
            isFakeBoldText = true
        }

        canvas.drawText(
            "$speedKmh km/h",
            50f * scale,
            115f * scale,
            textPaint
        )
    }
}
