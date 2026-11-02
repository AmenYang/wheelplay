package com.shilapi.xcertplay.web

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The image fallback costs one GL read-back and one JPEG per published frame. On a weak SoC that
 * decides whether a browser on JPEG leaves the phone responsive, so measure it on the real device
 * instead of guessing: `glReadPixels` of the same surface plus the compress below is the whole
 * per-frame cost.
 */
@RunWith(AndroidJUnit4::class)
class ImageEncodeCostTest {
    private val width = 1184
    private val height = 798

    /** A CarPlay screen: large flat areas, a bright nav strip, some text-like strokes. */
    private fun screen(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(14, 18, 22))
        val paint = Paint()
        paint.color = Color.rgb(28, 36, 42)
        canvas.drawRect(0f, 0f, width.toFloat(), 120f, paint)
        paint.color = Color.rgb(200, 210, 205)
        for (i in 0 until 6) {
            canvas.drawRect(60f + i * 170f, 40f, 120f + i * 170f, 80f, paint)
        }
        paint.color = Color.rgb(60, 90, 120)
        for (i in 0 until 40) {
            val y = 200f + i * 14f
            canvas.drawRect(80f, y, 80f + (i % 7) * 90f, y + 6f, paint)
        }
        return bitmap
    }

    @Test fun perFrameCost() {
        val bitmap = screen()
        val encoder = ImageFrameEncoder()
        try {
            for (quality in listOf(75, 55)) {
                val options = ImageEncodingOptions(ImageFormat.JPEG, quality)
                repeat(3) { compressOnce(bitmap, encoder, options) }
                val samples = (0 until 20).map { compressOnce(bitmap, encoder, options) }
                val bytes = (0 until 3).map { compressOnce(bitmap, encoder, options).second }.average().toInt()
                val sorted = samples.map { it.first }.sorted()
                Log.i(
                    "EncodeCost",
                    "jpeg q=$quality frame=${width}x$height bytes=$bytes " +
                        "avg=${sorted.average().toInt()}ms p50=${sorted[sorted.size / 2]}ms " +
                        "max=${sorted.last()}ms",
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns the encode time and the resulting size. */
    private fun compressOnce(
        bitmap: Bitmap,
        encoder: ImageFrameEncoder,
        options: ImageEncodingOptions,
    ): Pair<Long, Int> {
        val started = System.nanoTime()
        val (bytes, _) = encoder.encode(options) { format, quality, output ->
            bitmap.compress(format.compression, quality, output)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        return ms to bytes.size
    }
}
