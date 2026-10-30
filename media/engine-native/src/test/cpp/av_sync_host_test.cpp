/**
 * av_sync.h 的开发机单测（**不需要设备、不需要 FFmpeg**）。
 *
 * 跑法：bash scripts/test-engine-native-host.sh
 * 也在 JVM 单测里被调用（NativeHostLogicTest），所以每次 gradlew test 都会顺带跑一遍。
 */
#include <atomic>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "av_sync.h"

namespace {

int g_checks = 0;
int g_failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) {                                                           \
            ++g_failures;                                                        \
            std::printf("  FAIL 第 %d 行： %s\n", __LINE__, #cond);              \
        }                                                                        \
    } while (0)

#define CHECK_EQ(actual, expected)                                               \
    do {                                                                         \
        ++g_checks;                                                              \
        const long long a_ = static_cast<long long>(actual);                     \
        const long long e_ = static_cast<long long>(expected);                   \
        if (a_ != e_) {                                                          \
            ++g_failures;                                                        \
            std::printf("  FAIL 第 %d 行：期望 %lld，实际 %lld\n", __LINE__, e_, a_); \
        }                                                                        \
    } while (0)

#define CHECK_NEAR(actual, expected, eps)                                        \
    do {                                                                         \
        ++g_checks;                                                              \
        const double a_ = static_cast<double>(actual);                           \
        const double e_ = static_cast<double>(expected);                         \
        if (std::fabs(a_ - e_) > (eps)) {                                        \
            ++g_failures;                                                        \
            std::printf("  FAIL 第 %d 行：期望 %.3f，实际 %.3f\n", __LINE__, e_, a_); \
        }                                                                        \
    } while (0)

void testPtsToMs() {
    std::printf("- pts → 毫秒\n");
    CHECK_EQ(mediagate::ptsToMs(90000, 1, 90000), 1000);      // TS 的 90 kHz
    CHECK_EQ(mediagate::ptsToMs(0, 1, 90000), 0);
    CHECK_EQ(mediagate::ptsToMs(3000, 1, 1000), 3000);        // 已是毫秒
    CHECK_EQ(mediagate::ptsToMs(1001, 1, 30000), 33);        // 29.97 fps 的 1/30000 时间基
    CHECK_EQ(mediagate::ptsToMs(1001, 1001, 30000), 33400);  // 1001 个 1001/30000 秒
    CHECK_EQ(mediagate::ptsToMs(-90000, 1, 90000), -1000);    // 负起点（B 帧/编辑列表）
    CHECK_EQ(mediagate::ptsToMs(1, 1, 0), 0);                 // 时间基非法 → 0，不炸
    CHECK_EQ(mediagate::ptsToMs(INT64_MIN, 1, 90000), 0);     // AV_NOPTS_VALUE 兜底
}

void testTimeline() {
    std::printf("- 时间轴归一\n");
    mediagate::Timeline timeline;
    timeline.bindFromStream(1400, true);
    CHECK_EQ(timeline.normalize(1400), 0);
    CHECK_EQ(timeline.normalize(2400), 1000);
    CHECK_EQ(timeline.toStreamTime(500), 1900);

    mediagate::Timeline fallback;
    fallback.bindFromStream(0, false);      // 元信息没有起点
    CHECK(!fallback.bound);
    fallback.bindFromFirstFrame(9000);      // 用第一帧兜底
    CHECK(fallback.bound);
    CHECK_EQ(fallback.normalize(9000), 0);
    CHECK_EQ(fallback.normalize(9500), 500);

    // 已经绑过就不再被第一帧覆盖（元信息优先）
    mediagate::Timeline preferStream;
    preferStream.bindFromStream(1000, true);
    preferStream.bindFromFirstFrame(9999);
    CHECK_EQ(preferStream.startMs, 1000);
}

void testSyncDecision() {
    std::printf("- 音画同步判据\n");
    // 正好到点：立刻送屏
    auto now = mediagate::decideVideoSync(1000, 1000.0);
    CHECK(now.render);
    CHECK(!now.drop);

    // 容差以内的"稍早"：也送屏（不然每帧都要多等一点）
    auto slightEarly = mediagate::decideVideoSync(1008, 1000.0);
    CHECK(slightEarly.render);

    // 早得比较多：等
    auto early = mediagate::decideVideoSync(1100, 1000.0);
    CHECK(!early.render);
    CHECK(!early.drop);
    CHECK(early.waitMs >= 92 && early.waitMs <= 100);

    // 迟到超过阈值：丢帧
    auto late = mediagate::decideVideoSync(800, 1000.0);
    CHECK(!late.render);
    CHECK(late.drop);

    // 迟到但没超过阈值：照常送屏
    auto slack = mediagate::decideVideoSync(950, 1000.0);
    CHECK(slack.render);
    CHECK(!slack.drop);

    // 边界：正好等于阈值不算丢
    CHECK(!mediagate::decideVideoSync(880, 1000.0).drop);
    // 主时钟带小数时也要稳
    CHECK(mediagate::decideVideoSync(1000, 999.4).render);
}

void testAudioClock() {
    std::printf("- 音频时钟\n");
    // 锚点：媒体 0 ms ↔ 设备第 100 帧；播到第 48000+100 帧 ⇒ 正好 1 秒
    CHECK_NEAR(mediagate::audioClockMs(0, 100, 48100, 48000), 1000.0, 0.001);
    // 还没播到锚点（写完还没播出去）：比锚点小
    CHECK_NEAR(mediagate::audioClockMs(500, 1000, 400, 48000), 487.5, 0.001);
    // seek 之后的新锚点
    CHECK_NEAR(mediagate::audioClockMs(60000, 0, 24000, 48000), 60500.0, 0.001);
    // 采样率非法时不炸
    CHECK_NEAR(mediagate::audioClockMs(1234, 0, 999, 0), 1234.0, 0.001);
}

void testSeekPosition() {
    std::printf("- 自定义 AVIO 的 seek 位置\n");
    int64_t out = 0;
    // SEEK_SET：从文件头
    CHECK(mediagate::resolveSeekPosition(1234, 500, 0, 9999, true, &out));
    CHECK_EQ(out, 500);
    // SEEK_CUR：从当前位置
    CHECK(mediagate::resolveSeekPosition(1234, -234, 1, 9999, true, &out));
    CHECK_EQ(out, 1000);
    // SEEK_END：从末尾（要长度）
    CHECK(mediagate::resolveSeekPosition(0, -10, 2, 9999, true, &out));
    CHECK_EQ(out, 9989);
    // 长度未知时 SEEK_END 算不出来（调用方要回 EINVAL，别瞎猜）
    CHECK(!mediagate::resolveSeekPosition(0, -10, 2, -1, false, &out));
    // 越界（负位置）也要拒绝
    CHECK(!mediagate::resolveSeekPosition(0, -1, 0, 9999, true, &out));
    // 不认识的 whence
    CHECK(!mediagate::resolveSeekPosition(0, 0, 7, 9999, true, &out));
    // 越过末尾不做 clamp：数据层读到末尾会返回 EOF
    CHECK(mediagate::resolveSeekPosition(0, 99999, 0, 9999, true, &out));
    CHECK_EQ(out, 99999);
}

void testScaleTarget() {
    std::printf("- 画面缩放档位\n");
    int w = 0;
    int h = 0;
    // 1920x1080 放进 1000x1000 的窗口
    mediagate::scaleTarget(0, 1920, 1080, 1000, 1000, &w, &h);  // FIT：完整可见
    CHECK_EQ(w, 1000);
    CHECK_EQ(h, 562);
    mediagate::scaleTarget(1, 1920, 1080, 1000, 1000, &w, &h);  // CROP：盖住窗口（超出部分由送屏裁掉）
    CHECK_EQ(w, 1777);
    CHECK_EQ(h, 1000);
    mediagate::scaleTarget(2, 1920, 1080, 1000, 1000, &w, &h);  // STRETCH：不保持比例
    CHECK_EQ(w, 1000);
    CHECK_EQ(h, 1000);
    mediagate::scaleTarget(3, 1920, 1080, 1000, 1000, &w, &h);  // ORIGINAL：原样
    CHECK_EQ(w, 1920);
    CHECK_EQ(h, 1080);
    // 不认识的档位退回 FIT（不许算出 0 或负数）
    mediagate::scaleTarget(9, 640, 480, 1280, 720, &w, &h);
    CHECK_EQ(w, 960);
    CHECK_EQ(h, 720);
    // 窗口还没量出来（0）时按原尺寸走，别算出 0 让下游除零
    mediagate::scaleTarget(0, 640, 480, 0, 0, &w, &h);
    CHECK_EQ(w, 640);
    CHECK_EQ(h, 480);
    // 竖屏视频放进横屏窗口
    mediagate::scaleTarget(0, 1080, 1920, 2000, 1000, &w, &h);
    CHECK_EQ(w, 562);
    CHECK_EQ(h, 1000);
}

void testRingBasics() {
    std::printf("- PCM 环形缓冲：基本读写\n");
    mediagate::PcmRing ring;
    CHECK(!ring.ready());
    ring.configure(2, 16);
    CHECK(ring.ready());
    CHECK_EQ(ring.channels(), 2);
    CHECK_EQ(ring.capacityFrames(), 16);
    CHECK_EQ(ring.spaceFrames(), 16);

    std::vector<float> block(4 * 2);
    for (int i = 0; i < 4; ++i) {
        block[i * 2] = static_cast<float>(i);
        block[i * 2 + 1] = static_cast<float>(i) + 0.5f;
    }
    CHECK_EQ(ring.write(block.data(), 4), 4);
    CHECK_EQ(ring.queuedFrames(), 4);
    CHECK_EQ(ring.spaceFrames(), 12);
    CHECK_EQ(ring.producedFrames(), 4);

    float out[16 * 2] = {0};
    CHECK_EQ(ring.read(out, 4), 4);
    CHECK_EQ(ring.queuedFrames(), 0);
    for (int i = 0; i < 4; ++i) {
        CHECK_NEAR(out[i * 2], static_cast<float>(i), 0.0001);
        CHECK_NEAR(out[i * 2 + 1], static_cast<float>(i) + 0.5f, 0.0001);
    }
    // 读空之后再读：拿不到数据，但不越界
    CHECK_EQ(ring.read(out, 8), 0);

    // 写满：超出容量的那部分写不进去（由调用方等设备取走再补写）
    std::vector<float> big(20 * 2, 1.0f);
    CHECK_EQ(ring.write(big.data(), 20), 16);
    CHECK_EQ(ring.spaceFrames(), 0);
    CHECK_EQ(ring.write(big.data(), 20), 0);
}

void testRingWrapAround() {
    std::printf("- PCM 环形缓冲：绕回与顺序\n");
    mediagate::PcmRing ring;
    ring.configure(1, 8);   // 单声道容量 8，方便手算

    std::vector<float> first(6);
    for (int i = 0; i < 6; ++i) first[i] = static_cast<float>(i);   // 0..5
    CHECK_EQ(ring.write(first.data(), 6), 6);

    float out[16] = {0};
    CHECK_EQ(ring.read(out, 4), 4);
    CHECK_EQ(static_cast<int>(out[0]), 0);
    CHECK_EQ(static_cast<int>(out[3]), 3);
    CHECK_EQ(ring.queuedFrames(), 2);   // 还剩 4、5

    std::vector<float> second(5);
    for (int i = 0; i < 5; ++i) second[i] = static_cast<float>(6 + i);   // 6..10
    CHECK_EQ(ring.write(second.data(), 5), 5);   // 绕回写在下标 6,7,0,1,2

    CHECK_EQ(ring.read(out, 7), 7);
    for (int i = 0; i < 7; ++i) {
        CHECK_EQ(static_cast<int>(out[i]), 4 + i);   // 4,5,6,7,8,9,10
    }
    CHECK_EQ(ring.queuedFrames(), 0);
}

void testRingFlush() {
    std::printf("- PCM 环形缓冲：seek 丢弃\n");
    mediagate::PcmRing ring;
    ring.configure(1, 64);
    std::vector<float> src(40, 1.0f);
    CHECK_EQ(ring.write(src.data(), 40), 40);

    ring.requestFlush();
    CHECK(ring.flushPending());
    float out[16] = {0};
    // 消费者一进来就把"还没播的"全丢掉，于是这次读不到任何数据
    CHECK_EQ(ring.read(out, 16), 0);
    CHECK(!ring.flushPending());
    CHECK_EQ(ring.queuedFrames(), 0);
    CHECK_EQ(ring.flushes(), 1);
    // 丢弃是"跳过"，不是"清零"：生产者的计数继续往前走
    CHECK_EQ(ring.producedFrames(), 40);
    CHECK_EQ(ring.spaceFrames(), 64);

    // 回调没在跑时（未启动/已暂停），生产者可以自己代劳
    std::vector<float> more(10, 2.0f);
    CHECK_EQ(ring.write(more.data(), 10), 10);
    ring.requestFlush();
    ring.applyFlushIfRequested();
    CHECK(!ring.flushPending());
    CHECK_EQ(ring.queuedFrames(), 0);
    CHECK_EQ(ring.flushes(), 2);

    // 丢弃之后写进去的才是新位置的内容
    std::vector<float> fresh(3, 3.0f);
    CHECK_EQ(ring.write(fresh.data(), 3), 3);
    CHECK_EQ(ring.read(out, 3), 3);
    CHECK_NEAR(out[0], 3.0f, 0.0001);
}

void testRingDeliveredAndUnderrun() {
    std::printf("- PCM 环形缓冲：交付计数与欠载\n");
    mediagate::PcmRing ring;
    ring.configure(1, 32);
    std::vector<float> src(10, 1.0f);
    CHECK_EQ(ring.write(src.data(), 10), 10);

    float out[32] = {0};
    CHECK_EQ(ring.read(out, 16), 10);      // 只拿到 10，还差 6 ⇒ 调用方补静音
    ring.noteUnderrun();
    CHECK_EQ(ring.deliveredFrames(), 16);  // 交付计数按"设备要的帧数"走
    CHECK_EQ(ring.underruns(), 1);
}

void testRingThreaded() {
    std::printf("- PCM 环形缓冲：真并发（生产者线程 / 消费者线程）\n");
    mediagate::PcmRing ring;
    ring.configure(2, 1024);

    constexpr long long kTotalFrames = 200000;
    constexpr int kPattern = 997;
    std::atomic<bool> producerDone{false};
    long long mismatches = 0;

    std::thread producer([&] {
        long long frame = 0;
        float block[8 * 2];
        while (frame < kTotalFrames) {
            const int count = static_cast<int>(std::min<long long>(8, kTotalFrames - frame));
            for (int i = 0; i < count; ++i) {
                const float value = static_cast<float>((frame + i) % kPattern);
                block[i * 2] = value;
                block[i * 2 + 1] = value;
            }
            const size_t written = ring.write(block, static_cast<size_t>(count));
            frame += static_cast<long long>(written);
            if (written == 0) std::this_thread::yield();
        }
        producerDone.store(true);
    });

    std::thread consumer([&] {
        long long expected = 0;
        float out[64 * 2];
        while (expected < kTotalFrames) {
            const size_t got = ring.read(out, 64);
            if (got == 0) {
                std::this_thread::yield();
                continue;
            }
            for (size_t i = 0; i < got; ++i) {
                const float want = static_cast<float>((expected + static_cast<long long>(i)) % kPattern);
                if (out[i * 2] != want || out[i * 2 + 1] != want) {
                    ++mismatches;
                    break;
                }
            }
            expected += static_cast<long long>(got);
        }
    });

    producer.join();
    consumer.join();
    CHECK(producerDone.load());
    CHECK_EQ(mismatches, 0);
    CHECK_EQ(ring.producedFrames(), static_cast<uint64_t>(kTotalFrames));
    CHECK_EQ(ring.queuedFrames(), 0);
}

void testShouldStartPlayback() {
    std::printf("- 播放线程启动条件（path / 数据源至少一个）\n");
    CHECK(mediagate::shouldStartPlayback(true, false));   // open(path)
    CHECK(mediagate::shouldStartPlayback(false, true));   // openSource(JNI)
    CHECK(mediagate::shouldStartPlayback(true, true));
    CHECK(!mediagate::shouldStartPlayback(false, false));  // 两者都没有才不启动
}

}  // namespace

int main() {
    std::printf("自研引擎纯逻辑单测（开发机直跑）\n");
    testPtsToMs();
    testTimeline();
    testSyncDecision();
    testAudioClock();
    testSeekPosition();
    testScaleTarget();
    testShouldStartPlayback();
    testRingBasics();
    testRingWrapAround();
    testRingFlush();
    testRingDeliveredAndUnderrun();
    testRingThreaded();

    if (g_failures == 0) {
        std::printf("全部通过（%d 项断言）\n", g_checks);
        return 0;
    }
    std::printf("失败 %d 项 / 共 %d 项断言\n", g_failures, g_checks);
    return 1;
}
