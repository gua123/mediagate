package io.github.gua123.mediagate.feature.player.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.gua123.mediagate.core.common.AppLog

/** 日志 TAG。 */
private const val TAG = "player-audio"

/**
 * 解码后的封面（R5 音频内嵌封面）。
 *
 * 为什么要这层抽象：android.graphics.Bitmap 在 JVM 单测里造不出来（final 类 + 桩方法），
 * 若 UiState 直接持有 Bitmap，「封面加载成功」这条分支就没法单测；
 * 有了接口，单测注入假实现，生产用 [BitmapAudioCover]（沿用 :feature:viewer-image 的做法）。
 */
interface AudioCover {

    /** 解码后的宽（像素）。 */
    val width: Int

    /** 解码后的高（像素）。 */
    val height: Int
}

/** [AudioCover] 的 Android 实现：包装 Bitmap（UI 层 asImageBitmap() 后直接绘制）。 */
class BitmapAudioCover(val bitmap: Bitmap) : AudioCover {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * 封面解码器（R5）：把缩略图流水线给出的字节解成可绘制的封面。
 *
 * 约定：**不抛异常**（除协程取消），解不出来返回 null（页面画渐变底 + 首字母）。
 */
fun interface AudioCoverDecoder {

    /**
     * 解码 [bytes]。
     *
     * @param targetWidth 期望宽度（像素），用于算采样率。
     * @return 解码结果；不是图片 / 内存不足返回 null。
     */
    suspend fun decode(bytes: ByteArray, targetWidth: Int): AudioCover?
}

/**
 * 生产用解码器：BitmapFactory 两步走（先只读文件头拿宽高，再按采样率解码），跑在 [io] 上。
 *
 * 音频内嵌封面通常几百 KB，按目标宽度采样后内存占用很小；解码失败只记日志。
 */
class BitmapAudioCoverDecoder(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AudioCoverDecoder {

    override suspend fun decode(bytes: ByteArray, targetWidth: Int): AudioCover? = withContext(io) {
        try {
            if (bytes.isEmpty()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val sample = sampleSize(bounds.outWidth, targetWidth)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            BitmapAudioCover(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.w(TAG, "封面解码失败：bytes=" + bytes.size, t)
            null
        }
    }

    /** 2 的幂采样率：把 [sourceWidth] 压到不超过 [targetWidth]（至少 1）。 */
    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0 || sourceWidth <= targetWidth) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}
