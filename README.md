# Lantou Beiqiang (烂头背枪) — Android Card Strategy Game

**English** | [中文](#中文)

---

## English

### Overview

**Lantou Beiqiang** (烂头背枪) is a native Android implementation of a Chinese folk-inspired card strategy board game. Two players (Red and Blue) each command 6 face-down cards on a 5×3 grid, battling through a single-direction counter ring until one side prevails.

Built with **100% native Android Java** — no third-party libraries, no XML layouts, no Gradle build dependencies. The entire UI is programmatic, the board is custom-rendered via Canvas, and the game ships as a single APK.

- **Package**: `com.lantou`
- **Version**: 1.14 (versionCode 15)
- **Orientation**: Portrait, immersive fullscreen
- **Permissions**: `INTERNET`, `ACCESS_WIFI_STATE` (LAN play only)

---

### Game Rules

#### Board Setup
- **5 rows × 3 columns** grid
- Red occupies rows 1–2, Blue occupies rows 4–5, row 3 is empty
- Each side holds 6 face-down cards, shuffled randomly at game start

#### The Six Cards
Both sides hold exactly one of each:

| Card | Chinese | 
|------|---------|
| Lantou (Rotten Head) | 烂头 |
| Gun | 枪 |
| Tiger | 虎 |
| Kid | 小孩 |
| Rooster | 鸡 |
| Bee | 蜜蜂 |

#### Counter Ring (吃牌相克环)
The six cards form a **single-direction cycle** — each card kills exactly one other, and is killed by exactly one other:

```
Lantou → Gun → Tiger → Kid → Rooster → Bee → Lantou
 (烂头)   (枪)   (虎)   (小孩)   (鸡)    (蜜蜂)  (烂头)
```

#### Actions
1. **Flip** — reveal any face-down card (yours or opponent's). Consumes a turn.
2. **Move** — move your face-up card one step to an adjacent empty cell.
3. **Attack** — move your face-up card onto an adjacent enemy card.

#### Battle Resolution
When attacking an enemy card:

| Situation | Result |
|-----------|--------|
| Attacker kills defender | Attacker eats defender and occupies the cell |
| Defender kills attacker | Attacker is removed; defender stays in place |
| Neither kills the other | **Invalid action**: turn NOT consumed, board unchanged, humorous hint shown |

- Attacking a **face-down** card: it flips to reveal first, then battle resolution applies normally.
- Invalid actions do **not** switch turns — the offending player must try a different move.

#### Win Conditions
| Condition | Result |
|-----------|--------|
| One side's cards all eaten | Other side wins immediately |
| 100 turns reached | Side with more remaining cards wins (equal = draw) |
| No counter relationships remain between any red/blue cards | Early settlement by card count |
| Surrender | Opponent wins immediately |

#### Pass Rule
A player may only **pass** when they truly have no legal action (no flippable cards, no moves, no valid attacks). Passing consumes a turn.

---

### Game Modes

| Mode | Description |
|------|-------------|
| **Single Player — Normal** | vs AI: eats any killable face-up card, flips and moves semi-randomly |
| **Single Player — Hard** | vs AI: safe-kill scoring, prey approach, predator avoidance |
| **Local Two-Player** | Same screen, alternating turns (Red moves first) |
| **LAN Multiplayer** | Host/Join over local Wi-Fi (TCP socket, port 3721) |

---

### AI Mechanism Design

Both difficulties are **fair** — the AI only ever reads face-up cards and never peeks at face-down ones.

#### Normal Difficulty
Priority chain:
1. **Eat** any killable enemy face-up card (random pick among all candidates)
2. 40% chance: **flip** a random card
3. **Random move** to an adjacent empty cell
4. **Flip** if nothing else available

#### Hard Difficulty
Priority chain:
1. **Best Kill** — scores every possible kill: `score = 100 − threat×40 + noise(0–9)`, where `threat` = number of adjacent enemy face-up cards that could eat the attacker after it lands. Picks the highest-scoring kill.
2. 15% chance: **early flip** (prevents move-pattern memorization)
3. **Approach** — move toward the nearest killable prey, minimizing `distance×10 + threat×25 + noise`
4. **Escape** — if a friendly card is adjacent to a predator, move it to a safe empty cell
5. **Flip** (own face-down cards first, then enemy recon)
6. **Random repositioning**

#### Anti-Repetition Measures
- Candidate actions are **collected then randomly selected** — no fixed scan-order bias
- Scoring includes **random perturbation** to break ties
- Hard mode has a **15% early-flip probability** to stay unpredictable
- Flip priority: own face-down cards first (get forces combat-ready), then enemy cards (recon)

---

### Architecture

```
app/src/main/java/com/lantou/
├── MainActivity.java   — Main menu, IP detection, host/join flow
├── GameActivity.java   — Game screen, interaction routing, AI scheduling, net sync
├── BoardView.java      — Custom View: Canvas board rendering + touch handling
├── GameLogic.java      — Core rules, board state, win checks, serialization
├── GameAI.java         — AI decision engine (Normal / Hard)
├── NetPlay.java        — TCP networking (host/client, line-text protocol)
├── GameSound.java      — SoundPool SFX + MediaPlayer BGM
├── UI.java             — Shared UI utilities (buttons, bitmaps, dialogs, immersive mode)
├── CrashHandler.java   — Global uncaught exception handler
├── Card.java           — Card data model (type, owner, faceUp)
└── CardType.java       — Card type enum (6 types)
```

**Data flow**: `BoardView` (touch) → `GameActivity` (routing) → `GameLogic` (rules) → `BoardView.invalidate()` (redraw). In AI mode, `GameActivity.maybeAi()` schedules `GameAI.decide()` on a 520 ms delay. In LAN mode, `NetPlay` receives ops and `GameActivity.applyOp()` replays them locally.

---

### Technologies Used

| Area | Technology |
|------|-----------|
| Language | Java (Android SDK, no Kotlin) |
| UI | 100% programmatic — zero XML layouts |
| Rendering | Custom `View` subclass + `Canvas` 2D drawing |
| Audio | `SoundPool` (10 SFX) + `MediaPlayer` (looping BGM) |
| Networking | Raw `Socket` / `ServerSocket` (TCP, UTF-8 line-text protocol) |
| Data structures | `EnumMap` / `EnumSet` (counter ring), `ConcurrentHashMap` (image cache) |
| State sync | Custom string serialization (`serialize()` / `deserialize()`) |
| Error handling | Global `Thread.UncaughtExceptionHandler` |
| Theme | `Theme.Material.Light.NoActionBar` |
| Fullscreen | `IMMERSIVE_STICKY` (status bar + nav bar hidden) |

**Zero third-party dependencies.** The entire game is self-contained.

---

### Key Technical Implementations

#### 1. Memory Management (OOM Prevention)
Card images are 1280×1932 JPGs. Decoded at full `ARGB_8888`, each consumes ~10 MB; 13 cached cards would exceed 130 MB — guaranteed OOM on any device.

**Solution**: two-pass decode with `inSampleSize` downsampling to 640×960 + `RGB_565` config:
- Pass 1: `inJustDecodeBounds = true` — read dimensions only
- Pass 2: decode with computed `inSampleSize` + `RGB_565` (no alpha needed for JPGs)
- Result: ~1.2 MB per card, ~15 MB total cache — safe on low-RAM devices
- Background image: downsampled to screen size, decoded once, recycled in `onDestroy()`

#### 2. Rendering Performance
- `Paint`, `Path`, `RectF` instances are **reused across frames** — no per-frame allocations, no GC stutter
- `RadialGradient` (card-back glow) is **cached by key** (side + cell size), never recreated per draw
- Card images are **preloaded on a background thread** at game start — eliminates the first-flip decode jank entirely
- Image cache uses `ConcurrentHashMap` — safe concurrent access between preload thread and UI thread

#### 3. Touch Handling
- Tap detection uses the **down-position** cell, not the up-position — slight finger drift doesn't misfire
- Hover highlight only triggers `invalidate()` when the hovered cell actually changes

#### 4. Network Protocol
Line-based UTF-8 text over TCP (port 3721):

| Message | Direction | Meaning |
|---------|-----------|---------|
| `ST:<state>` | Host → Client | Full state sync (game start, restart, settlement) |
| `OP:FLIP:r:c` | Both | Flip card at (r,c) |
| `OP:MOVE:fr:fc:tr:tc` | Both | Move from (fr,fc) to (tr,tc) |
| `OP:PASS` | Both | Skip turn |
| `OP:SURR:<player>` | Both | Player surrenders |
| `OP:SYNC` | Client → Host | Request state resync |

- **Optimistic execution**: both sides execute operations locally first, then send to the peer
- Sends run on **daemon threads** — avoids `NetworkOnMainThreadException`
- **4-second connect timeout** prevents UI freeze on wrong IPs
- All callbacks are marshaled to the main thread via `Handler(Looper.getMainLooper())`
- State serialization: pipe-delimited string encoding the full board (player, gameOver, winner, turnCount, lastAction, 15 cells)

#### 5. Audio with Compression Fallback
- `SoundPool` SFX prefer `AssetFileDescriptor.openFd()` (works when assets are stored uncompressed in APK)
- **Fallback**: if the asset was ZIP-compressed, copy it to the cache directory and load by file path
- BGM (22 MB WAV) loads asynchronously via `prepareAsync()` — never blocks the main thread
- BGM volume capped at 50%, loops indefinitely, toggled from the main menu

#### 6. Crash Diagnostics
- `CrashHandler` installs as the global `UncaughtExceptionHandler`
- Writes full stack trace + timestamp + thread name to `crash_log.txt` in internal storage
- On next launch, `MainActivity` reads the log, displays it in a scrollable dialog, then deletes it
- All drawing and interaction code is wrapped in `try/catch(Throwable)` — OOM or edge cases never crash mid-game

#### 7. Error-Action Flavor Text
Invalid attacks show humorous Chinese hints from a curated 30+ entry table, for example:
- Gun attacks Bee → "大炮打蚊子" (using a cannon to swat a mosquito)
- Rooster attacks Gun → "战斗鸡！！" (BATTLE CHICKEN!!)
- Lantou attacks Kid → "这是未成年" (that's a minor)
- Tiger attacks Lantou → "太臭了" (too smelly)

---

### Build & Run

#### Prerequisites
- Android SDK (build-tools 34.0.0, platform android-34)
- JDK 8+

#### Install Prebuilt APK
```bash
adb install lantou.apk
```

#### Play
- **Single player**: launch → 单人游戏 → 正常 / 困难
- **LAN play**: both devices on the same Wi-Fi → one taps 创建房间 (shows host IP), the other taps 加入房间 and enters that IP

---

### Project Structure

```
lantou-beiqiang-android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── bg.png                    # Main menu background
│   │   ├── music.png / shuoming.png  # Icon assets (music toggle / rules)
│   │   ├── cards/                    # 13 images: red/blue × 6 + card back
│   │   └── music/                    # 10 SFX + 1 BGM (WAV)
│   ├── java/com/lantou/              # 11 source files (see Architecture)
│   └── res/                          # Launcher icons (mipmap-*) + values
├── lantou.apk                        # Prebuilt signed APK
└── .gitignore
```

---

## 中文

### 项目简介

**烂头背枪**是一款基于中国民间棋牌玩法的 Android 原生卡牌策略游戏。红蓝双方各持 6 张暗牌,在 5×3 的棋盘上排兵布阵,通过单向相克环吃掉对方棋子,直至一方获胜。

项目采用 **100% 原生 Android Java** 开发——无任何第三方依赖库、无 XML 布局、无 Gradle 构建依赖。所有 UI 均由代码动态创建,棋盘通过 Canvas 自定义绘制,最终打包为单个 APK。

- **包名**:`com.lantou`
- **版本**:1.14(versionCode 15)
- **屏幕方向**:竖屏,沉浸式全屏
- **权限**:`INTERNET`、`ACCESS_WIFI_STATE`(仅联机模式需要)

---

### 游戏规则

#### 棋盘布局
- **5 行 × 3 列**棋盘
- 红方占据第 1–2 行,蓝方占据第 4–5 行,第 3 行为空
- 双方各持 6 张暗牌,开局随机洗牌布阵

#### 六种棋子
双方各持以下棋子一张:

| 棋子 | 说明 |
|------|------|
| 烂头 | 烂头 |
| 枪 | 枪 |
| 虎 | 虎 |
| 小孩 | 小孩 |
| 鸡 | 鸡 |
| 蜜蜂 | 蜜蜂 |

#### 相克环(吃牌关系)
六种棋子构成**单向循环相克**——每种棋子恰好克制一种、被一种克制:

```
烂头 → 枪 → 虎 → 小孩 → 鸡 → 蜜蜂 → 烂头
```

#### 可执行操作
1. **翻牌**——翻开任意一张暗牌(己方或敌方均可),消耗一回合。
2. **移动**——将己方明牌移动到相邻空格。
3. **攻击**——将己方明牌移动到相邻敌方棋子所在格。

#### 战斗结算

| 情况 | 结果 |
|------|------|
| 攻击方克制防守方 | 攻击方吃掉防守方,占据该格 |
| 防守方克制攻击方 | 攻击方被吃掉,防守方原地不动 |
| 双方无相克关系 | **无效操作**:不切换回合,棋盘不变,显示趣味提示 |

- 攻击**暗牌**时:先翻开该牌,再按上述规则正常结算。
- 无效操作**不消耗回合**——玩家必须换一种走法。

#### 胜负条件

| 条件 | 结果 |
|------|------|
| 一方棋子被全部吃光 | 另一方立即获胜 |
| 达到 100 回合 | 剩余棋子多的一方获胜(相等为平局) |
| 红蓝双方所有明牌之间已无相克关系 | 提前结算,按剩余棋子数判定 |
| 认输 | 对方立即获胜 |

#### 跳过规则
只有当玩家确实无任何合法操作(无可翻之牌、无可走之步、无可打之敌)时才允许**跳过**,跳过消耗一回合。

---

### 游戏模式

| 模式 | 说明 |
|------|------|
| **单人游戏——正常** | 对战 AI:见明牌可吃就吃,翻牌和移动偏随机 |
| **单人游戏——困难** | 对战 AI:最优击杀评分、靠近猎物、逃离天敌 |
| **双人同屏** | 同一屏幕轮流操作,红方先手 |
| **局域网联机** | 同一 Wi-Fi 下创建/加入房间(TCP Socket,端口 3721) |

---

### AI 机制设计

两档难度均保证**公平**——AI 只读取场上明牌信息,绝不偷看暗牌。

#### 正常难度
优先级链:
1. **吃牌**——吃掉任意可吃的敌方明牌(从所有候选中随机挑选)
2. 40% 概率:**翻牌**(随机翻一张)
3. **随机移动**到相邻空格
4. 实在无事可做时**翻牌**

#### 困难难度
优先级链:
1. **最优击杀**——对每个可能的击杀评分:`评分 = 100 − 威胁×40 + 随机扰动(0–9)`,其中"威胁"=击杀落点后周围能反吃自己的敌方明牌数量。选评分最高的击杀。
2. 15% 概率:**提前翻牌**(防止走位被玩家背板)
3. **靠近猎物**——向最近的可吃目标移动,使 `距离×10 + 威胁×25 + 随机扰动` 最小
4. **逃离天敌**——若己方明牌紧邻能吃自己的敌人,将其移到安全空格
5. **翻牌**(先翻己方暗牌备战,再翻敌方暗牌侦察)
6. **随机走位**

#### 防背板措施
- 候选操作**先收集再随机抽取**——消除固定扫描顺序带来的偏好
- 评分中加入**随机扰动**,打破平局僵化
- 困难模式有 **15% 提前翻牌概率**,保持不可预测性
- 翻牌优先级:先翻己方暗牌(让战力尽快就位),再翻敌方暗牌(侦察敌情)

---

### 技术架构

```
app/src/main/java/com/lantou/
├── MainActivity.java   — 主菜单、IP 自动检测、创建/加入房间
├── GameActivity.java   — 游戏页、交互路由、AI 调度、联机同步
├── BoardView.java      — 自定义 View:Canvas 棋盘绘制 + 触摸处理
├── GameLogic.java      — 核心规则、棋盘状态、胜负判定、序列化
├── GameAI.java         — AI 决策引擎(正常/困难)
├── NetPlay.java        — TCP 网络通信(主机/客户端、行文本协议)
├── GameSound.java      — SoundPool 音效 + MediaPlayer 背景音乐
├── UI.java             — 通用 UI 工具(按钮、位图、弹窗、沉浸式全屏)
├── CrashHandler.java   — 全局未捕获异常处理器
├── Card.java           — 棋子数据模型(类型、归属、是否翻开)
└── CardType.java       — 棋子类型枚举(6 种)
```

**数据流**:`BoardView`(触摸)→ `GameActivity`(路由)→ `GameLogic`(规则)→ `BoardView.invalidate()`(重绘)。AI 模式下,`GameActivity.maybeAi()` 延迟 520ms 调度 `GameAI.decide()`。联机模式下,`NetPlay` 接收操作指令,`GameActivity.applyOp()` 在本地重放执行。

---

### 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Java(Android SDK,无 Kotlin) |
| UI | 100% 代码动态创建——零 XML 布局 |
| 渲染 | 自定义 `View` 子类 + `Canvas` 2D 绘制 |
| 音频 | `SoundPool`(10 种音效)+ `MediaPlayer`(循环 BGM) |
| 网络 | 原生 `Socket` / `ServerSocket`(TCP,UTF-8 行文本协议) |
| 数据结构 | `EnumMap` / `EnumSet`(相克环)、`ConcurrentHashMap`(图片缓存) |
| 状态同步 | 自定义字符串序列化(`serialize()` / `deserialize()`) |
| 异常处理 | 全局 `Thread.UncaughtExceptionHandler` |
| 主题 | `Theme.Material.Light.NoActionBar` |
| 全屏 | `IMMERSIVE_STICKY`(隐藏状态栏和导航栏) |

**零第三方依赖**,整个游戏完全自包含。

---

### 关键技术实现

#### 1. 内存管理(防 OOM)
卡牌原图为 1280×1932 JPG。若按 `ARGB_8888` 全尺寸解码,每张约 10MB,13 张缓存超 130MB——任何设备必 OOM。

**解决方案**:两遍解码 + `inSampleSize` 降采样至 640×960 + `RGB_565` 格式:
- 第一遍:`inJustDecodeBounds = true`,仅读取尺寸
- 第二遍:按计算出的 `inSampleSize` 解码 + `RGB_565`(JPG 无透明通道,无需 ARGB)
- 效果:每张约 1.2MB,总缓存约 15MB——低内存设备也安全
- 背景图:降采样至屏幕尺寸,只解码一次,`onDestroy()` 中回收

#### 2. 渲染性能
- `Paint`、`Path`、`RectF` 实例**跨帧复用**——无每帧分配,无 GC 卡顿
- `RadialGradient`(牌背泛光)**按 key 缓存**(方+格尺寸),不会每次绘制都重建
- 卡牌图片在游戏开始时**后台线程预加载**——彻底消除首次翻牌的解码卡顿
- 图片缓存使用 `ConcurrentHashMap`——预加载线程与 UI 线程并发访问安全

#### 3. 触摸处理
- 点击判定基于**按下位置**所在格,而非抬起位置——手指轻微滑动不会误触
- 悬停高亮仅在悬停格**真正变化时**才触发 `invalidate()`

#### 4. 网络协议
基于 TCP(端口 3721)的 UTF-8 行文本协议:

| 消息 | 方向 | 含义 |
|------|------|------|
| `ST:<状态>` | 主机 → 客户端 | 全量状态同步(开局、重开、结算) |
| `OP:FLIP:r:c` | 双向 | 翻开 (r,c) 处的牌 |
| `OP:MOVE:fr:fc:tr:tc` | 双向 | 从 (fr,fc) 移动到 (tr,tc) |
| `OP:PASS` | 双向 | 跳过回合 |
| `OP:SURR:<player>` | 双向 | 某方认输 |
| `OP:SYNC` | 客户端 → 主机 | 请求状态重新同步 |

- **乐观执行**:双方先在本地执行操作,再发送给对方
- 发送在**守护线程**上进行——避免 `NetworkOnMainThreadException`
- **4 秒连接超时**——输入错误 IP 不会卡死界面
- 所有回调通过 `Handler(Looper.getMainLooper())` 切回主线程
- 状态序列化:竖线分隔的字符串,编码完整棋盘(当前玩家、游戏结束、胜者、回合数、上次操作、15 个格子)

#### 5. 音频加载与压缩回退
- `SoundPool` 音效优先使用 `AssetFileDescriptor.openFd()`(资产在 APK 中未压缩时可用)
- **回退方案**:若资产被 ZIP 压缩导致 openFd 失败,则拷贝到缓存目录后按文件路径加载
- BGM(22MB WAV)通过 `prepareAsync()` 异步加载——绝不阻塞主线程
- BGM 音量上限 50%,无限循环,主菜单可开关

#### 6. 崩溃诊断
- `CrashHandler` 注册为全局 `UncaughtExceptionHandler`
- 将完整堆栈 + 时间戳 + 线程名写入内部存储的 `crash_log.txt`
- 下次启动时 `MainActivity` 读取日志,弹窗展示后删除
- 所有绘制和交互代码均包裹 `try/catch(Throwable)`——OOM 或边界情况不会中途崩溃

#### 7. 错误操作趣味文案
无效攻击会从 30 余条趣味文案表中随机展示,例如:
- 枪打蜜蜂 → "大炮打蚊子"
- 鸡打枪 → "战斗鸡!!"
- 烂头打小孩 → "这是未成年"
- 虎打烂头 → "太臭了"

---

### 构建与运行

#### 环境要求
- Android SDK(build-tools 34.0.0、platform android-34)
- JDK 8+

#### 安装预编译 APK
```bash
adb install lantou.apk
```

#### 开始游戏
- **单人**:启动 → 单人游戏 → 正常 / 困难
- **联机**:两台设备连同一 Wi-Fi → 一方点"创建房间"(显示主机 IP),另一方点"加入房间"并输入该 IP

---

### 项目结构

```
lantou-beiqiang-android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── bg.png                    # 主菜单背景图
│   │   ├── music.png / shuoming.png  # 图标素材(音乐开关 / 规则说明)
│   │   ├── cards/                    # 13 张图片:红蓝各 6 + 牌背
│   │   └── music/                    # 10 种音效 + 1 首 BGM(WAV)
│   ├── java/com/lantou/              # 11 个源文件(见技术架构)
│   └── res/                          # 启动图标(mipmap-*)+ values
├── lantou.apk                        # 预编译签名 APK
└── .gitignore
```