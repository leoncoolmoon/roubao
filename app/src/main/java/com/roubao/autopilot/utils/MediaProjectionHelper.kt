package com.roubao.autopilot.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * MediaProjection 截屏辅助类，用于当 Shizuku/Shell 截图不可用时的 Fallback 机制
 */
object MediaProjectionHelper {

    private const val TAG = "MediaProjectionHelper"

    private var projectionIntentData: Intent? = null
    private var projectionResultCode: Int = 0
    private var mediaProjection: MediaProjection? = null

    /**
     * 保存 MediaProjection 授权结果
     */
    fun setProjectionData(resultCode: Int, data: Intent) {
        this.projectionResultCode = resultCode
        this.projectionIntentData = data.clone() as Intent
        // 如果有旧的 mediaProjection，停止它以重新创建
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "Projection data set successfully")
    }

    /**
     * 检查是否已经获得 MediaProjection 授权
     */
    fun hasProjectionPermission(): Boolean {
        return projectionIntentData != null && projectionResultCode != 0
    }

    /**
     * 使用 MediaProjection 截取屏幕 Bitmap
     */
    suspend fun captureScreen(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val intentData = projectionIntentData ?: run {
            Log.w(TAG, "No MediaProjection permission intent data available")
            return@withContext null
        }

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return@withContext null

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return@withContext null

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var mp: MediaProjection? = mediaProjection

        try {
            if (mp == null) {
                mp = projectionManager.getMediaProjection(projectionResultCode, intentData.clone() as Intent)
                mediaProjection = mp
            }

            if (mp == null) {
                Log.e(TAG, "Failed to get MediaProjection instance")
                return@withContext null
            }

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "AutopilotFallbackCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            // 等待绘制帧写入 ImageReader
            var image: Image? = null
            for (i in 0 until 10) {
                kotlinx.coroutines.delay(100)
                image = imageReader.acquireLatestImage()
                if (image != null) break
            }

            if (image == null) {
                Log.e(TAG, "Failed to acquire image from ImageReader")
                return@withContext null
            }

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // 裁切掉右侧 padding 部分（如果 rowPadding > 0）
            val croppedBitmap = if (rowPadding > 0) {
                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cleanBitmap
            } else {
                bitmap
            }

            Log.d(TAG, "Successfully captured screenshot via MediaProjection: ${croppedBitmap.width}x${croppedBitmap.height}")
            return@withContext croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot via MediaProjection", e)
            // 出现异常时重置 mediaProjection
            mediaProjection?.stop()
            mediaProjection = null
            return@withContext null
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
        }
    }
}
