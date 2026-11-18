package com.lantou;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写入 crash_log.txt，
 * 下次启动 MainActivity 时弹窗显示，方便定位闪退原因。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static CrashHandler instance;
    private final Context ctx;
    private final Thread.UncaughtExceptionHandler def;

    private CrashHandler(Context c) {
        ctx = c.getApplicationContext();
        def = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context c) {
        if (instance == null) {
            instance = new CrashHandler(c);
            Thread.setDefaultUncaughtExceptionHandler(instance);
        }
    }

    @Override public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String log = time + " thread=" + t.getName() + "\n" + sw.toString();
            File f = new File(ctx.getFilesDir(), "crash_log.txt");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(log.getBytes("UTF-8"));
            fos.write("\n====\n".getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignore) { }
        // 交给系统默认处理（终止进程）
        if (def != null) def.uncaughtException(t, e);
    }

    /** 读取并删除崩溃日志，返回内容或 null */
    public static String takeCrashLog(Context c) {
        try {
            File f = new File(c.getFilesDir(), "crash_log.txt");
            if (!f.exists() || f.length() == 0) return null;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int r = fis.read(buf);
            fis.close();
            f.delete();
            return new String(buf, 0, r, "UTF-8");
        } catch (Throwable e) { return null; }
    }
}