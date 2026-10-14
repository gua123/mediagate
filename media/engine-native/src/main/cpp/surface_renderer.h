#ifndef MEDIAGATE_SURFACE_RENDERER_H
#define MEDIAGATE_SURFACE_RENDERER_H

#include <android/native_window.h>

#include <cstdint>
#include <mutex>

namespace mediagate {

/**
 * 把像素画到 Surface 上的最小实现（M2 的第一步）。
 *
 * 现在只画测试图案，目的是**先把链路打通**：
 * Kotlin 的 Surface → ANativeWindow → 写入缓冲区 → 屏幕上看得见。
 * 等 FFmpeg 解出真实帧后，把 drawTestPattern() 换成 drawFrame(YUV) 即可，
 * 渲染之外的逻辑（解码、同步）与这里无关。
 *
 * 线程模型：**所有方法都在引擎线程调用**；setWindow 可以在主线程调，内部用锁保护指针。
 */
class SurfaceRenderer {
public:
    SurfaceRenderer() = default;
    ~SurfaceRenderer();

    SurfaceRenderer(const SurfaceRenderer&) = delete;
    SurfaceRenderer& operator=(const SurfaceRenderer&) = delete;

    /** 绑定/解绑 Surface（传 nullptr 解绑）。重复绑定会先释放旧的。 */
    void setWindow(ANativeWindow* window);

    /** 是否已经绑定了可画的 Surface。 */
    bool hasWindow() const;

    /** 当前 Surface 的尺寸（未绑定时为 0）。 */
    void windowSize(int* width, int* height) const;

    /**
     * 画一帧测试图案（横向渐变色 + 一条随时间移动的竖线）。
     *
     * @return 成功返回 true；未绑定 Surface / 锁缓冲失败返回 false（**不抛异常**，
     *         引擎是所有播放的唯一路径，画不出来要能如实报告而不是崩）。
     */
    bool drawTestPattern(int frameIndex);

    /** 用纯色清屏（黑屏用）。 */
    bool clear(uint32_t argb);

private:
    mutable std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
};

}  // namespace mediagate

#endif  // MEDIAGATE_SURFACE_RENDERER_H
