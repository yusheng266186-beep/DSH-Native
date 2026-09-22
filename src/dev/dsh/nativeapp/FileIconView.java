package dev.dsh.nativeapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 文件图标：用 Canvas 自绘，不用图片资源、不用字体符号。
 *
 * <p>为什么不用 emoji / 图形字符：
 * <ul>
 *   <li>emoji 由系统字体渲染，**各机型外观差异很大**，且自带彩色，
 *       与本应用克制的单色设计语言冲突，观感廉价；</li>
 *   <li>图形字符（对勾、警告符之类）同样受字体影响，字重与图标不匹配。</li>
 * </ul>
 *
 * <p>自绘的好处：描边粗细、圆角、配色全部可控，缩放不糊，且**零资源文件**——
 * 本项目的资源 id 是构建时注入的，少一个资源就少一处可能失配的地方。
 *
 * <p>三种形态：目录、普通文件、符号链接。线条统一 1.4dp、圆角端点，
 * 颜色取自 {@link DshUi#TEXT_3}（弱化），目录用 {@link DshUi#TEXT_2} 稍重，
 * 形成层级但不喧宾夺主。
 */
public final class FileIconView extends View {

    public static final int FOLDER = 0;
    public static final int FILE = 1;
    public static final int LINK = 2;

    private int type = FILE;
    private int color = DshUi.TEXT_3;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();

    public FileIconView(Context c, int type) {
        super(c);
        setType(type);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(DshUi.dp(c, 1.4f));
    }

    /** 设置图标形态（{@link #FOLDER} / {@link #FILE} / {@link #LINK}）。 */
    public void setType(int t) {
        this.type = t;
        this.color = t == FOLDER ? DshUi.TEXT_2 : DshUi.TEXT_3;
        stroke.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int size = DshUi.dp(getContext(), 18);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = w * 0.14f;                       // 四周留白，避免贴边
        float gap = w * 0.20f;                       // 右上折角的大小
        box.set(pad, pad, w - pad, h - pad);

        if (type == FOLDER) {
            drawFolder(cv, box);
        } else {
            drawFile(cv, box);
            if (type == LINK) drawLinkBadge(cv, box);
        }
    }

    /** 目录：带顶部小凸起的圆角矩形。 */
    private void drawFolder(Canvas cv, RectF b) {
        float r = b.width() * 0.14f;
        float bodyTop = b.top + b.height() * 0.18f;

        // 顶部凸起（标签页）
        path.reset();
        path.moveTo(b.left, bodyTop);
        path.lineTo(b.left, b.top + b.height() * 0.04f);
        path.lineTo(b.left + b.width() * 0.42f, b.top + b.height() * 0.04f);
        path.lineTo(b.left + b.width() * 0.54f, bodyTop);
        path.close();
        cv.drawPath(path, stroke);

        // 主体
        RectF body = new RectF(b.left, bodyTop, b.right, b.bottom);
        cv.drawRoundRect(body, r, r, stroke);
    }

    /** 文件：圆角矩形 + 右上折角。 */
    private void drawFile(Canvas cv, RectF b) {
        float r = b.width() * 0.12f;
        float cx = b.right - b.width() * 0.34f;      // 折角起点
        float cy = b.top + b.height() * 0.34f;

        path.reset();
        path.moveTo(cx, b.top);
        path.lineTo(b.right - r, b.top);
        path.quadTo(b.right, b.top, b.right, b.top + r);
        path.lineTo(b.right, b.bottom - r);
        path.quadTo(b.right, b.bottom, b.right - r, b.bottom);
        path.lineTo(b.left + r, b.bottom);
        path.quadTo(b.left, b.bottom, b.left, b.bottom - r);
        path.lineTo(b.left, b.top + r);
        path.quadTo(b.left, b.top, b.left + r, b.top);
        path.lineTo(cx, b.top);
        cv.drawPath(path, stroke);

        // 折角的两条线
        path.reset();
        path.moveTo(cx, b.top);
        path.lineTo(cx, cy);
        path.lineTo(b.right, cy);
        cv.drawPath(path, stroke);
    }

    /**
     * 符号链接的角标：右下一个小箭头，表示「指向别处」。
     *
     * <p>不用链条图形 —— 18dp 下链条会糊成一团，箭头在同样尺寸下更易辨认。
     */
    private void drawLinkBadge(Canvas cv, RectF b) {
        float s = b.width() * 0.42f;
        float x = b.right - s * 0.55f;
        float y = b.bottom - s * 0.55f;

        // 用底色盖掉原图右下角，让角标清晰
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFFFFFFF);
        cv.drawCircle(x - s * 0.15f, y - s * 0.15f, s * 0.72f, bg);

        Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        arrow.setStrokeWidth(stroke.getStrokeWidth());
        arrow.setColor(DshUi.ACCENT);

        path.reset();
        path.moveTo(x - s * 0.5f, y + s * 0.5f);      // 从左下
        path.lineTo(x + s * 0.5f, y - s * 0.5f);      // 指向右上
        cv.drawPath(path, arrow);
        // 箭头两翼
        path.reset();
        path.moveTo(x + s * 0.5f, y - s * 0.5f);
        path.lineTo(x - s * 0.06f, y - s * 0.5f);
        path.moveTo(x + s * 0.5f, y - s * 0.5f);
        path.lineTo(x + s * 0.5f, y + s * 0.06f);
        cv.drawPath(path, arrow);
    }
}
