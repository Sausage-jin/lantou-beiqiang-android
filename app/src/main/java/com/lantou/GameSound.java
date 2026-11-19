package com.lantou;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** 音效与背景音乐（从 assets/music 读取，对应桌面版 SoundManager 的 wav）
 *  注意：assets 在 APK 中必须以 ZIP_STORED 存储，openFd() 才能用；
 *  这里同时内置了拷贝到缓存目录的回退方案，兼容被压缩的资源。 */
public final class GameSound {
    private static Context ctx;
    private static SoundPool sp;
    private static Map<String, Integer> sfxIds = new HashMap<String, Integer>();
    private static MediaPlayer bgm;
    private static boolean musicOn = true;
    private static boolean sfxOn = true;

    public static void init(Context c) {
        if (ctx != null) return;
        ctx = c.getApplicationContext();
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        sp = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attr).build();
        String[] names = {"ui-click", "card-flip", "card-move", "card-eat", "card-select",
                          "card-deal", "battle-attack", "game-win", "game-lose", "net-disconnect"};
        for (String n : names) {
            try {
                int id = loadSfx(n);
                if (id > 0) sfxIds.put(n, id);
            } catch (Exception e) { }
        }
    }

    /** 加载音效：优先 openFd（未压缩），失败则拷贝到缓存文件再加载 */
    private static int loadSfx(String name) {
        String assetPath = "music/" + name + ".wav";
        try {
            AssetFileDescriptor afd = ctx.getAssets().openFd(assetPath);
            int id = sp.load(afd, 1);
            afd.close();
            return id;
        } catch (Exception e) { }
        // 回退：资源被压缩时 openFd 会失败，拷贝到缓存目录后用路径加载
        try {
            File tmp = cacheAsset(assetPath, name + ".wav");
            return sp.load(tmp.getAbsolutePath(), 1);
        } catch (Exception e) { return 0; }
    }

    /** 把 assets 中的文件拷贝到缓存目录，返回缓存文件 */
    private static File cacheAsset(String assetPath, String cacheName) throws Exception {
        File tmp = new File(ctx.getCacheDir(), cacheName);
        if (!tmp.exists() || tmp.length() == 0) {
            InputStream is = ctx.getAssets().open(assetPath);
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) fos.write(buf, 0, r);
            fos.close();
            is.close();
        }
        return tmp;
    }

    public static void play(String name) {
        if (!sfxOn || sp == null) return;
        Integer id = sfxIds.get(name);
        if (id != null) sp.play(id, 1f, 1f, 1, 0, 1f);
    }

    public static void click()    { play("ui-click"); }
    public static void flip()     { play("card-flip"); }
    public static void move()     { play("card-move"); }
    public static void eat()      { play("card-eat"); }
    public static void select()   { play("card-select"); }
    public static void deal()     { play("card-deal"); }
    public static void battle()   { play("battle-attack"); }
    public static void win()      { play("game-win"); }
    public static void lose()     { play("game-lose"); }

    /** 播放背景音乐（循环）。若已有实例则直接恢复（pause→start 无缝衔接），
     *  避免每次 Activity 切换都重新加载 22MB 文件造成 0.5~1s 断音。
     *  首次加载仍用异步准备，不阻塞主线程。 */
    public static void startBgm() {
        if (!musicOn || ctx == null) return;
        if (bgm != null) {
            try {
                if (!bgm.isPlaying()) bgm.start();
                return;
            } catch (Exception e) { stopBgm(); }   // 实例已失效则走重建流程
        }
        try {
            final MediaPlayer mp = new MediaPlayer();
            boolean loaded = false;
            // 优先 openFd
            try {
                AssetFileDescriptor afd = ctx.getAssets().openFd("music/background-music.wav");
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                loaded = true;
            } catch (Exception e) { }
            // 回退：拷贝到缓存文件
            if (!loaded) {
                File tmp = cacheAsset("music/background-music.wav", "background-music.wav");
                mp.setDataSource(tmp.getAbsolutePath());
            }
            mp.setLooping(true);
            mp.setVolume(0.5f, 0.5f);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer p) {
                    // 仅当仍是当前 BGM 且音乐开着时才播放
                    if (bgm == mp && musicOn) p.start();
                }
            });
            bgm = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            if (bgm != null) { try { bgm.release(); } catch (Exception ex) { } }
            bgm = null;
        }
    }

    /** 暂停背景音乐（保留实例，下次 startBgm 可立即恢复，无需重新加载） */
    public static void stopBgm() {
        if (bgm != null) {
            try { bgm.pause(); } catch (Exception e) { }
        }
    }

    public static boolean isMusicOn() { return musicOn; }
    public static void setMusicOn(boolean on) {
        musicOn = on;
        if (on) startBgm(); else stopBgm();
    }
}