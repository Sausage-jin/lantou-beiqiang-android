package com.lantou;

import java.io.*;
import java.net.*;
import android.os.Handler;
import android.os.Looper;

/**
 * 双人联机通信：局域网主机/客户端，行文本协议。
 * ST:&lt;state&gt; 状态同步（主机 → 客户端：开局布阵/重开/结算）
 * OP:&lt;op&gt;   操作同步（双方各自本地先执行，再发给对方）
 */
public class NetPlay {
    public interface NetListener {
        void onConnected();
        void onStateReceived(String state);
        void onOpReceived(String op);
        /** 聊天功能已移除，保留空回调以兼容现有实现 */
        void onChatReceived(String msg);
        void onDisconnected();
    }

    /** 默认联机端口（主机监听 / 客户端连接） */
    public static final int DEFAULT_PORT = 3721;
    /** 客户端连接超时（毫秒），避免输入错误IP时界面长时间卡死 */
    private static final int CONNECT_TIMEOUT_MS = 4000;

    private Socket socket;
    private ServerSocket serverSocket;
    private BufferedReader in;
    private PrintWriter out;
    private NetListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isHost = false;
    private volatile boolean running = false;
    private volatile boolean accepting = false;

    /** 切换监听器（建连后由游戏窗口接管） */
    public void setListener(NetListener l) { this.listener = l; }

    /** 主机：监听端口，等待 1 个客户端连接（clientCount 参数保留兼容） */
    public void hostMulti(int port, int clientCount, NetListener l) throws IOException {
        isHost = true;
        this.listener = l;
        running = true;
        accepting = true;
        serverSocket = new ServerSocket(port);
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (accepting) {
                    try {
                        Socket s = serverSocket.accept();
                        initStreams(s);
                        notifyConnected();
                        return;   // 双人只需一个客户端
                    } catch (IOException e) {
                        if (accepting) notifyDisconnect();
                        return;
                    }
                }
            }
        }, "net-host");
        t.setDaemon(true);
        t.start();
    }

    /** 客户端：连接主机（带超时；asSpectator 参数保留兼容） */
    public void connect(String ip, int port, NetListener l, boolean asSpectator) throws IOException {
        isHost = false;
        this.listener = l;
        socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        initStreams(socket);
        running = true;
        notifyConnected();
    }

    private void initStreams(Socket s) throws IOException {
        socket = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
        startReceive();
    }

    /** 接收线程：解析 ST/OP 行并回调监听器（EDT线程） */
    private void startReceive() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String line;
                    while (running && (line = in.readLine()) != null) dispatch(line);
                } catch (IOException e) { }
                notifyDisconnect();
            }
        }, "net-recv");
        t.setDaemon(true);
        t.start();
    }

    private void dispatch(String msg) {
        if (msg.startsWith("ST:")) {
            final String body = msg.substring(3);
            mainHandler.post(new Runnable() {
                public void run() { if (listener != null) listener.onStateReceived(body); }
            });
        } else if (msg.startsWith("OP:")) {
            final String body = msg.substring(3);
            mainHandler.post(new Runnable() {
                public void run() { if (listener != null) listener.onOpReceived(body); }
            });
        }
    }

    public void sendState(String state) { send("ST:" + state); }
    public void sendOp(String op) { send("OP:" + op); }

    private synchronized void send(final String msg) {
        if (out == null) return;
        // Android 禁止主线程网络操作：sendState/sendOp 可能在 UI 线程被调用，
        // 必须切到后台线程写 Socket，否则触发 NetworkOnMainThreadException 闪退。
        Thread t = new Thread(new Runnable() {
            public void run() { out.println(msg); }
        }, "net-send");
        t.setDaemon(true);
        t.start();
    }

    /** 停止接受新连接（客户端已连入后调用，不影响已建立的连接） */
    public void stopAccept() {
        accepting = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) { }
    }

    public boolean isHost() { return isHost; }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** 关闭所有连接 */
    public void close() {
        running = false;
        accepting = false;
        try { if (socket != null) socket.close(); } catch (IOException e) { }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) { }
    }

    private void notifyConnected() {
        mainHandler.post(new Runnable() {
            public void run() { if (listener != null) listener.onConnected(); }
        });
    }

    private void notifyDisconnect() {
        mainHandler.post(new Runnable() {
            public void run() { if (listener != null) listener.onDisconnected(); }
        });
    }
}
