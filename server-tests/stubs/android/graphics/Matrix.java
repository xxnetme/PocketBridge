package android.graphics;

import java.util.ArrayList;
import java.util.List;

/** Заглушка android.graphics.Matrix для автономных JVM-тестов (повороты на 90° и отражения). */
public final class Matrix {
    static final class Op {
        final boolean rotate;
        final float a, b;
        Op(boolean rotate, float a, float b) { this.rotate = rotate; this.a = a; this.b = b; }
    }

    final List<Op> ops = new ArrayList<>();

    public boolean postRotate(float degrees) {
        ops.add(new Op(true, degrees, 0f));
        return true;
    }

    public boolean postScale(float sx, float sy) {
        ops.add(new Op(false, sx, sy));
        return true;
    }
}
