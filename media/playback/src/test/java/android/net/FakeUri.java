package android.net;

import android.os.Parcel;

import java.util.List;

/**
 * JVM 单测用的 android.net.Uri 替身（只在 src/test 里，不进 APK）。
 *
 * 为什么需要它：Media3 的 DataSpec 构造器对 uri 做了 checkNotNull，而 JVM 单测里 android.jar
 * 是桩（Uri.parse 返回 null、Uri.EMPTY 也是 null），造不出非空 Uri，DataSpec 就构造不出来，
 * 于是在 android.net 包里给一个最小实现（同包才能调用 Uri 的包私有构造器）。
 *
 * 它**只**实现 toString（BackendDataSource 只用它取伪 URI 字符串），其余能力一律抛异常，
 * 这样一旦哪天测试真的用到别的 Uri 方法，会立刻失败而不是悄悄拿到桩返回值。
 */
public final class FakeUri extends Uri {

    private final String value;

    public FakeUri(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    // ---------------------------------------------------------------- Parcelable

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw unsupported();
    }

    // ---------------------------------------------------------------- Uri 抽象方法

    @Override
    public Uri.Builder buildUpon() {
        throw unsupported();
    }

    @Override
    public String getAuthority() {
        throw unsupported();
    }

    @Override
    public String getEncodedAuthority() {
        throw unsupported();
    }

    @Override
    public String getEncodedFragment() {
        throw unsupported();
    }

    @Override
    public String getEncodedPath() {
        throw unsupported();
    }

    @Override
    public String getEncodedQuery() {
        throw unsupported();
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        throw unsupported();
    }

    @Override
    public String getEncodedUserInfo() {
        throw unsupported();
    }

    @Override
    public String getFragment() {
        throw unsupported();
    }

    @Override
    public String getHost() {
        throw unsupported();
    }

    @Override
    public String getLastPathSegment() {
        throw unsupported();
    }

    @Override
    public String getPath() {
        throw unsupported();
    }

    @Override
    public List<String> getPathSegments() {
        throw unsupported();
    }

    @Override
    public int getPort() {
        throw unsupported();
    }

    @Override
    public String getQuery() {
        throw unsupported();
    }

    @Override
    public String getScheme() {
        throw unsupported();
    }

    @Override
    public String getSchemeSpecificPart() {
        throw unsupported();
    }

    @Override
    public String getUserInfo() {
        throw unsupported();
    }

    @Override
    public boolean isHierarchical() {
        throw unsupported();
    }

    @Override
    public boolean isRelative() {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("FakeUri 只实现 toString（JVM 单测替身）");
    }
}
