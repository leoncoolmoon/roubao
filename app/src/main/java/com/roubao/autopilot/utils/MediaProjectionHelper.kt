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
    @Volatile
    private var mediaProjection: MediaProjection? = null

    /**
     * 保存 MediaProjection 授权结果并初始化 MediaProjection 实例
     */
    fun setProjectionData(context: Context, resultCode: Int, data: Intent) {
        this.projectionResultCode = resultCode
        this.projectionIntentData = data.clone() as Intent

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping previous MediaProjection", e)
        }

        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager != null) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data.clone() as Intent)
                Log.d(TAG, "MediaProjection instance created successfully")
                CrashHandler.getInstance().log(TAG, "MediaProjection 授权成功并创建实例")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaProjection instance in setProjectionData", e)
            CrashHandler.getInstance().log(TAG, "MediaProjection 实例创建失败: ${e.message}", e)
        }
    }

    /**
     * 检查是否已经获得 MediaProjection 授权且 MediaProjection 可用
     */
    fun hasProjectionPermission(): Boolean {
        return mediaProjection != null || (projectionIntentData != null && projectionResultCode != 0)
    }

    /**
     * 使用 MediaProjection 截取屏幕 Bitmap
     */
    suspend fun captureScreen(context: Context): Bitmap? = withContext(Dispatchers.IO) {
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

        var mp = mediaProjection
        if (mp == null && projectionIntentData != null && projectionResultCode != 0) {
            try {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mp = projectionManager?.getMediaProjection(projectionResultCode, projectionIntentData!!.clone() as Intent)
                mediaProjection = mp
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recreate MediaProjection instance", e)
                CrashHandler.getInstance().log(TAG, "重新获取 MediaProjection 实例失败: ${e.message}", e)
            }
        }

        if (mp == null) {
            Log.e(TAG, "No valid MediaProjection instance available")
            CrashHandler.getInstance().log(TAG, "MediaProjection 实例为空，无法截屏")
            return@withContext null
        }

        try {
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
                CrashHandler.getInstance().log(TAG, "ImageReader 获取图像超时")
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

            val croppedBitmap = if (rowPadding > 0) {
                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cleanBitmap
            } else {
                bitmap
            }

            Log.d(TAG, "Successfully captured screenshot via MediaProjection: ${croppedBitmap.width}x${croppedBitmap.height}")
            CrashHandler.getInstance().log(TAG, "MediaProjection 截屏成功 (${croppedBitmap.width}x${croppedBitmap.height})")
            return@withContext croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screenshot via MediaProjection", e)
            CrashHandler.getInstance().log(TAG, "MediaProjection 截屏异常: ${e.message}", e)
            return@withContext null
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
        }
    }
}
