package com.lantou;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;

/** 游戏页：顶栏(回合/状态提示) + 棋盘 + 底栏(返回/规则/认输)，支持人机/同屏/联机 */
public class GameActivity extends Activity {
    static NetPlay pendingNet;   // 主页传入的已连接联机实例

    private GameLogic game;
    private GameAI ai;
    private BoardView board;
    private TextView turnLabel, statusLabel, errorLabel;
    private boolean vsAI, net;
    private int myPlayer = 1, diff = 0;
    private int selRow = -1, selCol = -1;
    private boolean aiThinking = false;

    private NetPlay netPlay;
    private boolean applyingNet = false;
    private final Handler handler = new Handler();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        vsAI = getIntent().getBooleanExtra("vsAI", false);
        net = getIntent().getBooleanExtra("net", false);
        diff = getIntent().getIntExtra("diff", 0);
        myPlayer = net ? getIntent().getIntExtra("player", 1) : 1;
        ai = new GameAI(2, diff);
        game = new GameLogic();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF141822);

        // 顶栏：回合 + 状态 + 错误提示（紧凑，把更多空间留给棋盘）
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding((int)(12*dp), (int)(8*dp), (int)(12*dp), (int)(4*dp));
        top.setBackgroundColor(0xFF1E2230);
        turnLabel = new TextView(this);
        turnLabel.setTextSize(14);
        turnLabel.setGravity(Gravity.CENTER);
        turnLabel.setTextColor(UI.GOLD);
        top.addView(turnLabel);
        statusLabel = new TextView(this);
        statusLabel.setTextSize(11);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setTextColor(0xFFB0B6C4);
        top.addView(statusLabel);
        errorLabel = new TextView(this);
        errorLabel.setTextSize(11);
        errorLabel.setGravity(Gravity.CENTER);
        errorLabel.setTextColor(0xFFFF6060);
        top.addView(errorLabel);
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 棋盘
        board = new BoardView(this);
        board.setGame(game);
        board.preloadImages();   // 后台预解码卡牌图，翻牌时直接用缓存，消除首次翻面卡顿
        board.setListener(new BoardView.Listener() {
            public void onCellTap(int r, int c) { GameActivity.this.onCellTap(r, c); }
            public boolean isInteractive() { return canInteract(); }
        });
        root.addView(board, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底栏：返回 / 规则 / 认输（紧凑）
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding((int)(8*dp), (int)(5*dp), (int)(8*dp), (int)(8*dp));
        bottom.setBackgroundColor(0xFF1E2230);
        bottom.addView(UI.topButton(this, "返回", new View.OnClickListener() {
            public void onClick(View v) { GameSound.click(); finish(); }
        }));
        bottom.addView(UI.topButton(this, "规则", new View.OnClickListener() {
            public void onClick(View v) { GameSound.click(); UI.showRules(GameActivity.this); }
        }));
        bottom.addView(UI.topButton(this, "认输", new View.OnClickListener() {
            public void onClick(View v) { GameSound.click(); onSurrender(); }
        }));
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        UI.immersive(this);   // 沉浸式全屏：20:9 长屏铺满整块屏幕
        setupNet();
        refresh();
        maybeAi();
    }

    /** 弹窗等操作会使 IMMERSIVE_STICKY 退出，重新获得焦点时再次隐藏系统栏 */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) UI.immersive(this);
    }

    /** BGM 跟随前后台：进入游戏恢复（与主页共用同一 MediaPlayer，无缝衔接），
     *  退到桌面/返回主页时暂停。 */
    @Override protected void onResume() {
        super.onResume();
        GameSound.startBgm();
    }

    @Override protected void onPause() {
        super.onPause();
        GameSound.stopBgm();
    }

    // ==================== 交互判定 ====================
    private boolean canInteract() {
        if (game.isGameOver() || aiThinking) return false;
        if (net) return game.getCurrentPlayer() == myPlayer;
        return true;   // 单人/同屏都由本地操作
    }

    private void onCellTap(int row, int col) {
        try {
            handleCellTap(row, col);
        } catch (Throwable e) {
            // 任何异常都不允许闪退回主页
            UI.toast(this, "操作异常，请重试");
        }
    }

    private void handleCellTap(int row, int col) {
        if (!canInteract()) return;
        Card card = game.getCard(row, col);
        if (selRow < 0) {
            if (card == null) return;
            if (!card.isFaceUp()) {
                if (game.flip(row, col)) { GameSound.flip(); sendFlip(row, col); afterAction(); }
            } else if (card.getOwner() == game.getCurrentPlayer()) {
                selRow = row; selCol = col;
                board.setSelection(row, col); GameSound.select();
            }
            refresh();
            return;
        }
        if (row == selRow && col == selCol) { cancelSel(); return; }
        Card selCard = game.getCard(selRow, selCol);
        if (selCard == null) { cancelSel(); return; }
        // 点相邻的空格或敌牌 → 移动/对战
        if (Math.abs(row - selRow) + Math.abs(col - selCol) == 1) {
            Card target = game.getCard(row, col);
            if (target == null || target.getOwner() != selCard.getOwner()) {
                boolean wasEnemy = target != null && target.getOwner() != selCard.getOwner();
                if (game.move(selRow, selCol, row, col)) {
                    sendMove(selRow, selCol, row, col);
                    int fr = selRow, fc = selCol;
                    cancelSel();
                    board.pop(row, col);
                    if (wasEnemy) { GameSound.eat(); GameSound.battle(); }
                    else GameSound.move();
                    afterAction();
                } else {
                    GameSound.click();
                    refresh();
                }
                return;
            }
        }
        // 否则重新选择：点己方明牌改选，点暗牌翻牌，其余取消
        if (card != null && !card.isFaceUp()) {
            cancelSel();
            if (game.flip(row, col)) { GameSound.flip(); sendFlip(row, col); afterAction(); }
        } else if (card != null && card.getOwner() == game.getCurrentPlayer()) {
            selRow = row; selCol = col;
            board.setSelection(row, col); GameSound.select();
        } else {
            cancelSel();
        }
        refresh();
    }

    private void cancelSel() { selRow = -1; selCol = -1; board.clearSelection(); }

    /** 认输确认：误触保护，确认后本方认输、对方获胜 */
    private void onSurrender() {
        final int me = net ? myPlayer : 1;
        new AlertDialog.Builder(this).setTitle("认输")
            .setMessage("确定要认输吗？对方将立即获胜。")
            .setPositiveButton("确定认输", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    game.surrender(me);
                    sendOp("SURR:" + me);
                    GameSound.lose();
                    refresh();
                    showResult();
                }
            })
            .setNegativeButton("取消", null).show();
    }

    private void afterAction() {
        refresh();
        if (game.isGameOver()) { showResult(); return; }
        maybeAi();
    }

    // ==================== AI ====================
    private void maybeAi() {
        if (net || vsAI == false || game.isGameOver()) return;
        if (game.getCurrentPlayer() != 2 || aiThinking) return;
        aiThinking = true;
        refresh();
        handler.postDelayed(new Runnable() {
            public void run() {
             try {
                int[] act = ai.decide(game);
                if (act != null) {
                    if (act[0] == GameAI.FLIP) { game.flip(act[1], act[2]); GameSound.flip(); }
                    else if (act[0] == GameAI.PASS) game.pass();
                    else if (game.move(act[1], act[2], act[3], act[4])) {
                        board.pop(act[3], act[4]);
                        GameSound.eat();
                    }
                }
                aiThinking = false;
                refresh();
                if (game.isGameOver()) showResult();
                else maybeAi();
             } catch (Throwable e) {
                aiThinking = false;
                refresh();
             }
            }
        }, 520);
    }

    // ==================== 联机 ====================
    private void setupNet() {
        if (!net) return;
        netPlay = pendingNet;
        pendingNet = null;
        if (netPlay == null) { UI.toast(this, "联机连接丢失"); finish(); return; }
        netPlay.setListener(new NetPlay.NetListener() {
            public void onConnected() { }
            public void onStateReceived(final String s) {
                runOnUiThread(new Runnable() { public void run() {
                    applyingNet = true;
                    game.deserialize(s);
                    applyingNet = false;
                    cancelSel();
                    refresh();
                    if (game.isGameOver()) showResult();
                }});
            }
            public void onOpReceived(final String op) {
                runOnUiThread(new Runnable() { public void run() { applyOp(op); }});
            }
            public void onChatReceived(String s) { }
            public void onDisconnected() {
                runOnUiThread(new Runnable() { public void run() {
                    UI.toast(GameActivity.this, "对手已断开连接");
                }});
            }
        });
        if (netPlay.isHost()) {
            // 主机开局同步状态给客户端
            handler.postDelayed(new Runnable() { public void run() {
                netPlay.sendState(game.serialize());
            }}, 300);
        }
    }

    private void applyOp(String op) {
        if (applyingNet || game.isGameOver()) return;
        String[] p = op.split(":");
        if (p.length == 0) return;
        if ("FLIP".equals(p[0]) && p.length >= 3) {
            game.flip(Integer.parseInt(p[1]), Integer.parseInt(p[2])); GameSound.flip();
        } else if ("MOVE".equals(p[0]) && p.length >= 5) {
            boolean wasEnemy = false;
            Card t = game.getCard(Integer.parseInt(p[3]), Integer.parseInt(p[4]));
            if (t != null) wasEnemy = true;
            if (game.move(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                          Integer.parseInt(p[3]), Integer.parseInt(p[4]))) {
                board.pop(Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                if (wasEnemy) GameSound.eat(); else GameSound.move();
            }
        } else if ("PASS".equals(p[0])) {
            game.pass();
        } else if ("SURR".equals(p[0]) && p.length >= 2) {
            game.surrender(Integer.parseInt(p[1]));
            GameSound.win();
        } else if ("SYNC".equals(p[0])) {
            netPlay.sendState(game.serialize());
        }
        refresh();
        if (game.isGameOver()) showResult();
    }

    private void sendFlip(int r, int c) { if (net) netPlay.sendOp("FLIP:" + r + ":" + c); }
    private void sendMove(int fr, int fc, int tr, int tc) {
        if (net) netPlay.sendOp("MOVE:" + fr + ":" + fc + ":" + tr + ":" + tc);
    }
    private void sendOp(String op) { if (net) netPlay.sendOp(op); }

    // ==================== 刷新与结算 ====================
    private void refresh() {
        String who;
        if (game.isGameOver()) {
            who = game.getWinner() == 0 ? "平局" : (game.getWinner() == 1 ? "红方胜" : "蓝方胜");
            turnLabel.setText(colored(who));
        } else {
            String cur = game.getCurrentPlayer() == 1 ? "红方" : "蓝方";
            String tag;
            if (net) tag = game.getCurrentPlayer() == myPlayer ? "你的回合" : "对方回合";
            else if (vsAI) tag = game.getCurrentPlayer() == 1 ? "你的回合" : "电脑思考中";
            else tag = cur + "回合";
            turnLabel.setText(colored("回合 " + game.getTurnCount() + "/" + GameLogic.MAX_TURNS + " · " + tag
                    + " · 红" + game.countAlive(1) + "/蓝" + game.countAlive(2)));
        }
        statusLabel.setText(game.getLastAction());
        String err = game.getLastError();
        errorLabel.setText(err == null || err.isEmpty() ? " " : "错误：" + err);
        board.invalidate();   // 关键：翻牌/移动后立即重绘棋盘
    }

    /** 给文本中的"红方/红X""蓝方/蓝X"整段染色，当前回合方一眼可辨 */
    private CharSequence colored(String s) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int color;
            if (ch == '红') color = 0xFFFF6B6B;
            else if (ch == '蓝') color = 0xFF6B9BFF;
            else continue;
            // 紧跟的"方"或数字一起染色：红方/蓝方/红6/蓝6 整段同色
            int end = i + 1;
            while (end < s.length()) {
                char nx = s.charAt(end);
                if (nx == '方' || (nx >= '0' && nx <= '9')) end++;
                else break;
            }
            sb.setSpan(new ForegroundColorSpan(color), i, end, 0);
            i = end - 1;
        }
        return sb;
    }

    private void showResult() {
        int w = game.getWinner();
        String title = w == 0 ? "平局" : (w == 1 ? "红方胜" : "蓝方胜");
        boolean iWin = !net ? (w == 1) : (w == myPlayer);
        String sub = game.getLastAction() + "\n红方剩余 " + game.countAlive(1)
                   + " 张 / 蓝方剩余 " + game.countAlive(2) + " 张";
        if (iWin) GameSound.win(); else GameSound.lose();
        new AlertDialog.Builder(this).setTitle(title).setMessage(sub)
            .setPositiveButton("返回主页", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { finish(); }
            })
            .setNegativeButton("再来一局", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { restart(); }
            }).show();
    }

    private void restart() {
        game.reset();
        selRow = -1; selCol = -1;
        board.clearSelection();
        if (net && netPlay != null && netPlay.isHost()) netPlay.sendState(game.serialize());
        GameSound.deal();
        refresh();
        maybeAi();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (net && netPlay != null) netPlay.close();
    }
}
