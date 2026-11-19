package com.lantou;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 主页：背景 background001 + 单人/双人/联机菜单，就地切换按钮文字 */
public class MainActivity extends Activity {
    private LinearLayout btnBox;
    private Button[] btns = new Button[4];
    private String localIp = "127.0.0.1";
    private Bitmap bgBitmap;   // 背景图缓存：只解码一次，避免每帧解码导致 OOM

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        CrashHandler.install(this);   // 全局崩溃捕获，写日志方便定位闪退
        GameSound.init(this);
        localIp = detectIp();

        FrameLayout root = new FrameLayout(this);
        root.addView(makeBackground());
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);   // 水平 + 垂直都居中
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 尺寸统一用 dp 换算，高密度手机上不会显示太小
        float dp = getResources().getDisplayMetrics().density;
        TextView title = new TextView(this);
        title.setText("烂头打虎");
        title.setTextSize(40);
        title.setTextColor(UI.GOLD);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(8, 0, 0, 0x99000000);
        overlay.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (56 * dp)));

        int btnW = (int) (280 * dp);      // 280dp 宽
        int btnH = (int) (60 * dp);       // 60dp 高
        int btnMargin = (int) (14 * dp);  // 14dp 间距
        btnBox = new LinearLayout(this);
        btnBox.setOrientation(LinearLayout.VERTICAL);
        btnBox.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int i = 0; i < btns.length; i++) {
            btns[i] = UI.goldButton(this, "", click(i));
            btns[i].setTextSize(20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnW, btnH);
            lp.setMargins(0, btnMargin, 0, btnMargin);
            btnBox.addView(btns[i], lp);
        }
        overlay.addView(btnBox);

        TextView tip = new TextView(this);
        tip.setText("支持人机、同屏双人与局域网联机");
        tip.setTextSize(13);
        tip.setTextColor(0xCCEEEEEE);
        tip.setGravity(Gravity.CENTER);
        tip.setShadowLayer(6, 0, 0, 0x99000000);
        overlay.addView(tip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) tip.getLayoutParams()).topMargin = (int) (16 * dp);

        // 右下角图标：说明 + 音乐
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.RIGHT);
        bottom.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        bottom.setPadding(0, 0, 24, 20);
        bottom.addView(UI.iconButton(this, "shuoming.png", new View.OnClickListener() {
            public void onClick(View v) { GameSound.click(); UI.showRules(MainActivity.this); }
        }, 44));
        final ImageButton musicBtn = UI.iconButton(this, "music.png", new View.OnClickListener() {
            public void onClick(View v) {
                GameSound.setMusicOn(!GameSound.isMusicOn());
                GameSound.click();
                UI.toast(MainActivity.this, GameSound.isMusicOn() ? "音乐开" : "音乐关");
            }
        }, 44);
        bottom.addView(musicBtn);
        root.addView(bottom);

        setContentView(root);
        UI.immersive(this);   // 沉浸式全屏：20:9 长屏铺满整块屏幕
        showMain();
        GameSound.startBgm();
        showCrashLogIfAny();
    }

    /** 弹窗等操作会使 IMMERSIVE_STICKY 退出，重新获得焦点时再次隐藏系统栏 */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) UI.immersive(this);
    }

    /** 上次如果闪退过，弹窗显示崩溃日志方便排查 */
    private void showCrashLogIfAny() {
        final String log = CrashHandler.takeCrashLog(this);
        if (log == null) return;
        TextView tv = new TextView(this);
        tv.setText(log);
        tv.setTextSize(11);
        tv.setTextColor(0xFF333333);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setPadding(24, 20, 24, 20);
        new AlertDialog.Builder(this)
                .setTitle("上次运行异常退出")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 背景：blackground001 铺满 + 顶部/底部渐变保证文字可读 */
    private View makeBackground() {
        View bgView = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                super.onDraw(cv);
                // 懒加载 + 缓存：首次绘制时按屏幕尺寸降采样解码，之后复用
                if (bgBitmap == null) {
                    bgBitmap = UI.assetBitmapScaled(getContext(), "bg.png",
                            getWidth(), getHeight());
                }
                if (bgBitmap != null) {
                    float s = Math.max((float) getWidth() / bgBitmap.getWidth(),
                                       (float) getHeight() / bgBitmap.getHeight());
                    int w = (int) (bgBitmap.getWidth() * s), h = (int) (bgBitmap.getHeight() * s);
                    Rect dst = new Rect((getWidth() - w) / 2, (getHeight() - h) / 2,
                                        (getWidth() + w) / 2, (getHeight() + h) / 2);
                    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                    cv.drawBitmap(bgBitmap, null, dst, p);
                }
                int hh = getHeight();
                Paint gp = new Paint();
                gp.setShader(new LinearGradient(0, 0, 0, hh * 0.18f, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), hh * 0.18f, gp);
                gp.setShader(new LinearGradient(0, hh * 0.85f, 0, hh, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
                cv.drawRect(0, hh * 0.85f, getWidth(), hh, gp);
            }
        };
        bgView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return bgView;
    }

    private View.OnClickListener click(final int idx) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                GameSound.click();
                handleClick(idx);
            }
        };
    }

    private void showButtons(String[] texts) {
        for (int i = 0; i < btns.length; i++) {
            btns[i].setText(i < texts.length ? texts[i] : "");
            btns[i].setVisibility(i < texts.length ? View.VISIBLE : View.GONE);
        }
    }

    private void showMain() {
        showButtons(new String[]{"单人游戏", "双人游戏", "退出游戏"});
    }

    private void handleClick(final int idx) {
        String t = btns[idx].getText().toString();
        if ("单人游戏".equals(t) || "单人".equals(t)) {
            showButtons(new String[]{"正常", "困难", "返回"});
        } else if ("双人游戏".equals(t)) {
            showButtons(new String[]{"双人同屏", "联机对战", "返回"});
        } else if ("退出游戏".equals(t)) {
            finish();
        } else if ("正常".equals(t)) {
            startGame(true, 0);
        } else if ("困难".equals(t)) {
            startGame(true, 1);
        } else if ("双人同屏".equals(t)) {
            startGame(false, 0);
        } else if ("联机对战".equals(t)) {
            showButtons(new String[]{"创建房间", "加入房间", "返回"});
        } else if ("创建房间".equals(t)) {
            hostGame();
        } else if ("加入房间".equals(t)) {
            joinGame();
        } else if ("返回".equals(t)) {
            // 从任意子菜单退回：判断当前处于哪一级
            if (btns[0].getText().toString().equals("正常") || btns[0].getText().toString().equals("双人同屏"))
                showMain();
            else if (btns[0].getText().toString().equals("创建房间"))
                showButtons(new String[]{"双人同屏", "联机对战", "返回"});
            else showMain();
        }
    }

    private void startGame(boolean vsAI, int diff) {
        android.content.Intent it = new android.content.Intent(this, GameActivity.class);
        it.putExtra("vsAI", vsAI);
        it.putExtra("diff", diff);
        startActivity(it);
    }

    // ==================== 联机 ====================
    private void hostGame() {
        final NetPlay np = new NetPlay();
        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setTitle("创建房间");
        dlg.setMessage("本机IP：" + localIp + "\n等待对手加入…（端口 " + NetPlay.DEFAULT_PORT + "）");
        dlg.setCancelable(false);
        dlg.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { np.close(); d.dismiss(); }
        });
        dlg.show();
        try {
            np.hostMulti(NetPlay.DEFAULT_PORT, 1, new NetPlay.NetListener() {
                public void onConnected() { runOnUiThread(new Runnable() {
                    public void run() { dlg.dismiss(); launchNet(np, 1); }
                }); }
                public void onStateReceived(String s) { }
                public void onOpReceived(String s) { }
                public void onChatReceived(String s) { }
                public void onDisconnected() { }
            });
        } catch (Exception e) {
            dlg.dismiss();
            UI.toast(this, "创建房间失败：" + e.getMessage());
        }
    }

    private void joinGame() {
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("加入房间");
        b.setMessage("输入主机IP（同一局域网）：");
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(localIp);
        b.setView(et);
        b.setPositiveButton("连接", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                final String ip = et.getText().toString().trim().isEmpty() ? "127.0.0.1" : et.getText().toString().trim();
                final ProgressDialog pd = new ProgressDialog(MainActivity.this);
                pd.setTitle("加入房间");
                pd.setMessage("正在连接 " + ip + " …");
                pd.setCancelable(false);
                pd.show();
                new Thread(new Runnable() {
                    public void run() {
                        final NetPlay np = new NetPlay();
                        try {
                            np.connect(ip, NetPlay.DEFAULT_PORT, new NetPlay.NetListener() {
                                public void onConnected() { }
                                public void onStateReceived(String s) { }
                                public void onOpReceived(String s) { }
                                public void onChatReceived(String s) { }
                                public void onDisconnected() { }
                            }, false);
                            runOnUiThread(new Runnable() {
                                public void run() { pd.dismiss(); launchNet(np, 2); }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    pd.dismiss(); np.close();
                                    UI.toast(MainActivity.this, "连接失败：" + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void launchNet(NetPlay np, int player) {
        android.content.Intent it = new android.content.Intent(this, GameActivity.class);
        it.putExtra("net", true);
        it.putExtra("player", player);
        GameActivity.pendingNet = np;   // 传递已连接的 NetPlay 实例
        startActivity(it);
    }

    private String detectIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> adds = ni.getInetAddresses();
                while (adds.hasMoreElements()) {
                    InetAddress a = adds.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) { }
        return "127.0.0.1";
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        GameSound.stopBgm();
        if (bgBitmap != null) { bgBitmap.recycle(); bgBitmap = null; }
    }
}
