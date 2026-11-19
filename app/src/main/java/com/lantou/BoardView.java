package com.lantou;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 棋盘绘制与触摸交互：5行3列，牌背泛红/蓝光，明牌显示红蓝卡牌图 */
public class BoardView extends View {
    interface Listener {
        void onCellTap(int row, int col);
        boolean isInteractive();
    }

    private GameLogic game;
    private Listener listener;
    private int selRow = -1, selCol = -1;
    private float hoverRow = -1, hoverCol = -1;
    private long popUntil = 0;
    private int popRow = -1, popCol = -1;

    // 线程安全：后台预加载线程与 UI 绘制线程并发读写
    private final ConcurrentHashMap<String, Bitmap> imgCache = new ConcurrentHashMap<String, Bitmap>();
    private final Map<String, RadialGradient> glowCache = new HashMap<String, RadialGradient>();
    // 复用 Paint/Path，避免每帧 new 导致 GC 卡顿
    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintImg = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path pathReuse = new Path();
    private final RectF rectReuse = new RectF();
    private final Rect srcReuse = new Rect();
    // 触摸：记录按下时的格子，抬起时用按下位置判定，手指微移不影响
    private int downRow = -1, downCol = -1;

    static final int HL_SEL = 0xFFFFBE3C, HL_MOVE = 0xFF5AE682, HL_ATK = 0xFFFF5A5A;
    static final int HL_HOVER = 0x2AFFFFFF;
    static final int EDGE_RED = 0xFFC83C37, EDGE_BLU = 0xFF3C5AC8;

    public BoardView(Context c) { super(c); init(); }
    public BoardView(Context c, AttributeSet a) { super(c, a); init(); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Bitmap b : imgCache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        imgCache.clear();
    }

    private void init() {
        setClickable(true);
    }

    public void setGame(GameLogic g) { this.game = g; clearSelection(); invalidate(); }
    public void setListener(Listener l) { this.listener = l; }
    public void clearSelection() { selRow = -1; selCol = -1; invalidate(); }
    public void setSelection(int r, int c) { selRow = r; selCol = c; invalidate(); }
    public void pop(int r, int c) { popRow = r; popCol = c; popUntil = System.currentTimeMillis() + 320; invalidate(); }

    /** 后台预加载全部卡牌图片：避免首次翻牌时 onDraw 在 UI 线程同步解码 JPEG，
     *  导致点击后翻面延迟（每种牌第一次翻都会卡顿）。 */
    public void preloadImages() {
        new Thread(new Runnable() {
            public void run() {
                String[] names = {"kabei",
                    "red_lantou","red_qiang","red_hu","red_xiaohai","red_ji","red_feng",
                    "blue_lantou","blue_qiang","blue_hu","blue_xiaohai","blue_ji","blue_feng"};
                for (String n : names) img(n);
                post(new Runnable() { public void run() { invalidate(); } });
            }
        }).start();
    }

    private Bitmap img(String name) {
        Bitmap b = imgCache.get(name);
        if (b == null) {
            // 降采样到 640x960：原 1280x1932 ARGB_8888 约 10MB/张，
            // 13 张缓存超 130MB 必然 OOM；降采样 + RGB_565 后约 1.2MB/张
            b = UI.assetBitmapScaled(getContext(), "cards/" + name + ".jpg", 640, 960);
            if (b != null) imgCache.put(name, b);   // 解码失败不缓存，下次重试
        }
        return b;
    }

    private String imageName(Card card) {
        String side = card.getOwner() == 1 ? "red_" : "blue_";
        switch (card.getType()) {
            case LANTOU:    return side + "lantou";
            case QIANG:     return side + "qiang";
            case SHARENHU:  return side + "hu";
            case XIAOHAI:   return side + "xiaohai";
            case GONGJI:    return side + "ji";
            case MIFENG:    return side + "feng";
        }
        return side + "lantou";
    }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (game == null) return;
        try {
            drawBoard(cv);
        } catch (Throwable e) {
            // 绘制异常（OOM 等）不能让 Activity 闪退，静默跳过本帧
        }
    }

    private void drawBoard(Canvas cv) {
        int w = getWidth(), h = getHeight();
        // 棋盘区域：极小边距，尽量铺满（卡牌大小主要由屏幕高度决定）
        float padX = w * 0.02f, padY = h * 0.005f;
        float availW = w - padX * 2, availH = h - padY * 2;
        float cellW = Math.min(availW / GameLogic.COLS, availH / GameLogic.ROWS * 0.66f);
        float cellH = cellW * 1.5f;
        float gridW = cellW * GameLogic.COLS, gridH = cellH * GameLogic.ROWS;
        float ox = (w - gridW) / 2, oy = (h - gridH) / 2;
        if (popUntil > System.currentTimeMillis()) invalidate();

        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                float x = ox + c * cellW, y = oy + r * cellH;
                Card card = game.getCard(r, c);
                if (card == null) {
                    drawEmptyCell(cv, x, y, cellW, cellH);
                } else if (!card.isFaceUp()) {
                    drawCardBack(cv, card, x, y, cellW, cellH);
                } else {
                    drawCardFace(cv, card, x, y, cellW, cellH);
                }
            }
        }
        drawHighlights(cv, ox, oy, cellW, cellH);
    }

    private void drawEmptyCell(Canvas cv, float x, float y, float cw, float ch) {
        paintFill.setColor(0x18FFFFFF);
        paintFill.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(x + 3, y + 3, x + cw - 3, y + ch - 3, 12, 12, paintFill);
    }

    private void drawCardBack(Canvas cv, Card card, float x, float y, float cw, float ch) {
        float cx = x + cw / 2, cy = y + ch / 2;
        // 泛红光/蓝光：径向渐变柔光（缓存复用，避免每帧 new RadialGradient）
        boolean red = card.getOwner() == 1;
        int edge = red ? EDGE_RED : EDGE_BLU;
        String key = red + "_" + cw + "_" + ch;
        RadialGradient rg = glowCache.get(key);
        if (rg == null) {
            int glow = red ? 0x40FF4B4B : 0x404B7BFF;   // 减弱泛光（原 0x66 过浓）
            rg = new RadialGradient(cx, cy, Math.max(cw, ch) * 0.55f,
                    new int[]{glow, 0x00000000}, null, Shader.TileMode.CLAMP);
            glowCache.put(key, rg);
        }
        paintFill.setShader(rg);
        paintFill.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(x - 2, y - 2, x + cw + 2, y + ch + 2, 14, 14, paintFill);
        paintFill.setShader(null);

        Bitmap img = img("kabei");
        rectReuse.set(x + 3, y + 3, x + cw - 3, y + ch - 3);
        if (img != null) {
            cv.save();
            pathReuse.reset();
            pathReuse.addRoundRect(rectReuse, 11, 11, Path.Direction.CW);
            cv.clipPath(pathReuse);
            cv.drawBitmap(img, null, rectReuse, paintImg);
            cv.restore();
        }
        paintStroke.setColor(edge);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(2);   // 细边框（原 4 过粗）
        cv.drawRoundRect(rectReuse, 11, 11, paintStroke);
    }

    private void drawCardFace(Canvas cv, Card card, float x, float y, float cw, float ch) {
        Bitmap img = img(imageName(card));
        rectReuse.set(x + 2, y + 2, x + cw - 2, y + ch - 2);
        // 阴影
        paintFill.setColor(0x55000000);
        paintFill.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(rectReuse.left + 2, rectReuse.top + 3, rectReuse.right + 2, rectReuse.bottom + 3, 11, 11, paintFill);
        if (img != null) {
            cv.save();
            pathReuse.reset();
            pathReuse.addRoundRect(rectReuse, 11, 11, Path.Direction.CW);
            cv.clipPath(pathReuse);
            // 裁掉卡牌图四周约12%的浅色装饰边，主体内容放大约1.3倍填满格子
            int iw = img.getWidth(), ih = img.getHeight();
            int cx = (int)(iw * 0.12f), cy = (int)(ih * 0.12f);
            srcReuse.set(cx, cy, iw - cx, ih - cy);
            cv.drawBitmap(img, srcReuse, rectReuse, paintImg);
            cv.restore();
        }
        paintStroke.setColor(card.getOwner() == 1 ? EDGE_RED : EDGE_BLU);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(2);   // 细边框（原 4 过粗）
        cv.drawRoundRect(rectReuse, 11, 11, paintStroke);

        // 弹出动画缩放高亮
        if (popUntil > System.currentTimeMillis() && popRow >= 0) {
            paintStroke.setColor(0xAAFFE9A8);
            paintStroke.setStrokeWidth(8);
            cv.drawRoundRect(rectReuse, 11, 11, paintStroke);
        }
    }

    private void strokeCell(Canvas cv, float ox, float oy, float cw, float ch, int r, int c, int color, float w) {
        float x = ox + c * cw, y = oy + r * ch;
        paintStroke.setColor(color);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(w);
        cv.drawRoundRect(x + 3, y + 3, x + cw - 3, y + ch - 3, 12, 12, paintStroke);
    }

    private void drawHighlights(Canvas cv, float ox, float oy, float cw, float ch) {
        if (game.isGameOver()) return;
        if (hoverRow >= 0) strokeCell(cv, ox, oy, cw, ch, (int) hoverRow, (int) hoverCol, HL_HOVER, 3);
        if (selRow < 0) return;
        glowCell(cv, ox, oy, cw, ch, selRow, selCol, HL_SEL);
        Card sel = game.getCard(selRow, selCol);
        if (sel == null) return;
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] d : dirs) {
            int nr = selRow + d[0], nc = selCol + d[1];
            if (nr < 0 || nr >= GameLogic.ROWS || nc < 0 || nc >= GameLogic.COLS) continue;
            Card t = game.getCard(nr, nc);
            if (t == null) strokeCell(cv, ox, oy, cw, ch, nr, nc, HL_MOVE, 4);
            else if (t.getOwner() != sel.getOwner()) strokeCell(cv, ox, oy, cw, ch, nr, nc, HL_ATK, 4);
        }
    }

    private void glowCell(Canvas cv, float ox, float oy, float cw, float ch, int r, int c, int color) {
        strokeCell(cv, ox, oy, cw, ch, r, c, color, 7);
    }

    /** 由手势坐标换算行列 */
    private int[] cellAt(float px, float py) {
        int w = getWidth(), h = getHeight();
        float padX = w * 0.02f, padY = h * 0.005f;
        float availW = w - padX * 2, availH = h - padY * 2;
        float cellW = Math.min(availW / GameLogic.COLS, availH / GameLogic.ROWS * 0.66f);
        float cellH = cellW * 1.5f;
        float gridW = cellW * GameLogic.COLS, gridH = cellH * GameLogic.ROWS;
        float ox = (w - gridW) / 2, oy = (h - gridH) / 2;
        int c = (int) ((px - ox) / cellW);
        int r = (int) ((py - oy) / cellH);
        if (r < 0 || r >= GameLogic.ROWS || c < 0 || c >= GameLogic.COLS) return null;
        return new int[]{r, c};
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (listener == null || !listener.isInteractive()) return true;
        int action = e.getAction();
        int[] cell = cellAt(e.getX(), e.getY());
        if (action == MotionEvent.ACTION_DOWN) {
            // 记录按下格子，抬起时用它判定，手指微移不影响点击
            downRow = cell == null ? -1 : cell[0];
            downCol = cell == null ? -1 : cell[1];
            if (cell != null) { hoverRow = cell[0]; hoverCol = cell[1]; invalidate(); }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            // hover 只在格子变化时才重绘，减少无效帧
            if (cell != null && (cell[0] != hoverRow || cell[1] != hoverCol)) {
                hoverRow = cell[0]; hoverCol = cell[1]; invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP && downRow >= 0) {
            int r = downRow, c = downCol;
            downRow = -1; downCol = -1;
            hoverRow = -1; hoverCol = -1;
            invalidate();
            listener.onCellTap(r, c);
        }
        return true;
    }
}
