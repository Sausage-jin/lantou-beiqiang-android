package com.lantou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.InputStream;

/** 界面通用工具：亮黄按钮、图标加载、规则与弹窗 */
public final class UI {
    public static final int GOLD = 0xFFFFD76E;
    public static final int DARK_FG = 0xFF3A2A10;

    private UI() {}

    /** 沉浸式全屏：隐藏状态栏+导航栏，内容铺满整块屏幕。
     *  20:9 长屏手机系统栏占比高，不开全屏会导致游戏上下留出大片系统区域。
     *  IMMERSIVE_STICKY：从边缘滑入临时显示系统栏，松手后自动隐藏，适合游戏。
     *  弹窗等操作可能使 sticky 模式退出，需在 onWindowFocusChanged 里重新调用。 */
    @SuppressWarnings("deprecation")
    public static void immersive(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** 亮黄色圆角按钮（与主菜单同款） */
    public static Button goldButton(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(DARK_FG);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(GOLD);
        gd.setCornerRadius(18);
        gd.setStroke(2, 0xFFFFE9A8);
        b.setBackground(gd);
        b.setPadding(12, 14, 12, 14);
        b.setOnClickListener(l);
        return b;
    }

    /** 顶栏小号亮黄按钮 */
    public static Button topButton(Context c, String text, View.OnClickListener l) {
        Button b = goldButton(c, text, l);
        b.setTextSize(14);
        b.setPadding(6, 10, 6, 10);
        return b;
    }

    /** 从 assets 读取图片为 Bitmap（原尺寸，仅用于小图标） */
    public static Bitmap assetBitmap(Context c, String path) {
        try {
            InputStream is = c.getAssets().open(path);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (Throwable e) { return null; }   // Throwable 捕获 OOM 等 Error
    }

    /**
     * 从 assets 读取图片并降采样到不超过 maxW x maxH。
     * 大图（卡牌/背景）必须用此方法，否则全分辨率解码会 OOM：
     * 1280x1932 ARGB_8888 单张约 10MB，13 张缓存超 130MB。
     * JPG 无透明通道，用 RGB_565 再省一半内存。
     */
    public static Bitmap assetBitmapScaled(Context c, String path, int maxW, int maxH) {
        // 第一次仅读尺寸
        int sample = 1;
        try {
            InputStream is = c.getAssets().open(path);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opt);
            is.close();
            while (opt.outWidth / sample > maxW || opt.outHeight / sample > maxH) {
                sample *= 2;
            }
        } catch (Throwable e) { return null; }
        // 第二次真正解码（降采样 + RGB_565）
        try {
            InputStream is = c.getAssets().open(path);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeStream(is, null, opt);
            is.close();
            return b;
        } catch (Throwable e) { return null; }   // OOM 等 Error 也不能让 Activity 闪退
        // 注意：decodeStream 返回 null 表示解码失败，调用方已判空
    }

    /** 从 assets 读取背景大图：原尺寸 + ARGB_8888 高色深 + 抖动。
     *  RGB_565 只有 16bit 色深，渐变区会产生明显色带，放大后显得"糊"；
     *  32bit + 双线性插值 + 抖动可显著改善观感。 */
    public static Bitmap assetBitmapHq(Context c, String path) {
        try {
            InputStream is = c.getAssets().open(path);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opt.inDither = true;
            Bitmap b = BitmapFactory.decodeStream(is, null, opt);
            is.close();
            return b;
        } catch (Throwable e) { return null; }
    }

    /** 圆形图标按钮（说明/音乐），带金色着色 */
    public static ImageButton iconButton(Context c, String asset, View.OnClickListener l, int sizeDp) {
        ImageButton ib = new ImageButton(c);
        Bitmap src = assetBitmap(c, asset);
        if (src != null) {
            Bitmap tinted = tint(src, GOLD);
            Bitmap scaled = Bitmap.createScaledBitmap(tinted, sizeDp * 3, sizeDp * 3, true);
            ib.setImageBitmap(scaled);
        }
        ib.setBackgroundColor(Color.TRANSPARENT);
        ib.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        ib.setOnClickListener(l);
        return ib;
    }

    /** 把图片非透明像素染成指定颜色（保留 alpha） */
    public static Bitmap tint(Bitmap src, int color) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        cv.drawBitmap(src, 0, 0, null);
        // SRC_IN 需要先用源色覆盖：改为直接以 src 作为 mask
        cv.drawRect(0, 0, src.getWidth(), src.getHeight(), p);
        return out;
    }

    /** 规则文本（与桌面版一致） */
    public static String rulesText() {
        return "【棋盘】5行3列。第1、2行红方，第4、5行蓝方，第3行空着；双方各6张暗牌。\n\n"
             + "【六种牌】烂头、枪、虎、小孩、鸡、蜜蜂，红蓝各一套。\n\n"
             + "【吃牌相克环】箭头方向为“吃”：\n"
             + "  烂头→枪→虎→小孩→鸡→蜜蜂→烂头\n"
             + "  · 我方吃对方 → 吃掉并占位\n"
             + "  · 对方吃我方 → 我方被反吃，对方留原地\n"
             + "  · 互不相克 → 错误行动，回合不切换、棋子不动\n"
             + "  对方暗牌也可主动进攻：翻开应战，按上面判定。\n\n"
             + "【回合与胜利】\n"
             + "  翻牌或行动一次累计1回合（显示 回合 N/100）。\n"
             + "  满100回合按剩余牌数判胜，相同平局。\n"
             + "  某方被吃光 → 对方立即获胜。\n"
             + "  场上红蓝牌无任何相克关系 → 提前结算。\n\n"
             + "【模式】\n"
             + "  单人：与电脑对战，正常/困难两档（你执红方先手）\n"
             + "  双人同屏：同屏轮流操作（红方先手）\n"
             + "  联机对战：创建房间 / 加入房间（同一局域网）";
    }

    public static void showRules(Activity a) {
        TextView tv = new TextView(a);
        tv.setText(rulesText());
        tv.setTextSize(14);
        tv.setTextColor(0xFF222222);
        tv.setPadding(24, 20, 24, 20);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);
        new AlertDialog.Builder(a).setTitle("游戏规则").setView(sv)
                .setPositiveButton("知道了", null).show();
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }
}
