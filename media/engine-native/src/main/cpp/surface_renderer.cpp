#include "surface_renderer.h"

#include <android/log.h>

#include <algorithm>

namespace mediagate {
namespace {

constexpr const char* kTag = "mediagate-engine";

void logW(const char* what) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", what);
}

}  // namespace

SurfaceRenderer::~SurfaceRenderer() {
    setWindow(nullptr);
}

void SurfaceRenderer::setWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == window) return;
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = window;
    if (window_ != nullptr) {
        // 统一用 RGBA_8888：软件路径最省事（GLES 路径以后再说，接口不用改）
        ANativeWindow_setBuffersGeometry(window_, 0, 0, WINDOW_FORMAT_RGBA_8888);
    }
}

bool SurfaceRenderer::hasWindow() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return window_ != nullptr;
}

void SurfaceRenderer::setBufferSize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return;
    ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
}

void SurfaceRenderer::windowSize(int* width, int* height) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) {
        if (width) *width = 0;
        if (height) *height = 0;
        return;
    }
    if (width) *width = ANativeWindow_getWidth(window_);
    if (height) *height = ANativeWindow_getHeight(window_);
}

bool SurfaceRenderer::blitRgba(const uint8_t* rgba, int width, int height) {
    if (rgba == nullptr || width <= 0 || height <= 0) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（送帧）");
        return false;
    }
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    const int windowW = buffer.width;
    const int windowH = buffer.height;
    // 居中 + 等比（FIT）：先把整屏刷黑，再把画面贴到中间
    const int count = buffer.stride * windowH;
    for (int i = 0; i < count; ++i) pixels[i] = 0xFF000000u;
    const int offsetX = (windowW - width) / 2;
    const int offsetY = (windowH - height) / 2;
    for (int y = 0; y < height; ++y) {
        const int dstY = offsetY + y;
        if (dstY < 0 || dstY >= windowH) continue;
        uint32_t* dstRow = pixels + static_cast<size_t>(dstY) * buffer.stride;
        const uint8_t* srcRow = rgba + static_cast<size_t>(y) * width * 4;
        for (int x = 0; x < width; ++x) {
            const int dstX = offsetX + x;
            if (dstX < 0 || dstX >= windowW) continue;
            const uint8_t r = srcRow[x * 4 + 0];
            const uint8_t g = srcRow[x * 4 + 1];
            const uint8_t b = srcRow[x * 4 + 2];
            const uint8_t a = srcRow[x * 4 + 3];
            dstRow[dstX] = (static_cast<uint32_t>(a) << 24) | (static_cast<uint32_t>(r) << 16) |
                           (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
        }
    }
    ANativeWindow_unlockAndPost(window_);
    return true;
}

bool SurfaceRenderer::clear(uint32_t argb) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（清屏）");
        return false;
    }
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    const int count = buffer.stride * buffer.height;
    for (int i = 0; i < count; ++i) pixels[i] = argb;
    ANativeWindow_unlockAndPost(window_);
    return true;
}

bool SurfaceRenderer::drawTestPattern(int frameIndex) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return false;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        logW("ANativeWindow_lock 失败（测试图案）");
        return false;
    }
    const int width = buffer.width;
    const int height = buffer.height;
    auto* pixels = static_cast<uint32_t*>(buffer.bits);
    // 一条随时间横向移动的竖线，用来确认"帧确实在更新"（不是卡住的静态画面）
    const int barX = (frameIndex * 7) % std::max(1, width);
    for (int y = 0; y < height; ++y) {
        uint32_t* row = pixels + static_cast<size_t>(y) * buffer.stride;
        for (int x = 0; x < width; ++x) {
            if (std::abs(x - barX) < 4) {
                row[x] = 0xFFFFFFFFu;  // 白线
            } else {
                // 横向渐变（红→蓝），每行加一点绿，方便看出画面朝向
                const uint32_t r = static_cast<uint32_t>(255.0 * x / std::max(1, width));
                const uint32_t b = 255u - r;
                const uint32_t g = static_cast<uint32_t>(96.0 * y / std::max(1, height));
                row[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
            }
        }
    }
    ANativeWindow_unlockAndPost(window_);
    return true;
}

}  // namespace mediagate