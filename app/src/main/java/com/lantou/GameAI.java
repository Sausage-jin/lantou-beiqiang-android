package com.lantou;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 简化版 AI —— 6牌相克环，5行3列棋盘。
 * 相克环：烂头→枪→虎→小孩→鸡→蜜蜂→烂头（箭头方向吃掉）。
 * 没有斩首/合成/中立，胜负只看100回合后的剩牌数。
 *
 * NORMAL：优先能吃的明牌，否则翻牌/随机走，不评估风险。
 * HARD  ：优先安全击杀（吃完后尽量不被反吃）、靠近猎物、避开天敌，再翻牌。
 * 公平性：两档都只看明牌，不偷看暗牌。
 * 随机性：候选动作先收集再随机挑选、评分加随机扰动、困难档有概率提前翻牌，
 *         避免每局走法完全一样被玩家背板。
 *
 * 动作格式：
 *   int[3] = {FLIP, row, col}
 *   int[5] = {MOVE, fromRow, fromCol, toRow, toCol}
 *   int[1] = {PASS}
 */
public class GameAI {
    public static final int NORMAL = 0;
    public static final int HARD   = 1;

    public static final int FLIP = 0, MOVE = 1, PASS = 2;
    private static final int[][] DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private final int aiPlayer;
    private final int enemyPlayer;
    private final int difficulty;
    private final Random rnd = new Random();

    public GameAI(int aiPlayer) { this(aiPlayer, NORMAL); }

    public GameAI(int aiPlayer, int difficulty) {
        this.aiPlayer = aiPlayer;
        this.enemyPlayer = (aiPlayer == 1) ? 2 : 1;
        this.difficulty = difficulty;
    }

    public int[] decide(GameLogic game) {
        int[] action = difficulty == HARD ? decideHard(game) : decideNormal(game);
        return action != null ? action : new int[]{PASS};
    }

    // ==================== 正常难度 ====================
    private int[] decideNormal(GameLogic game) {
        int[] a;
        if ((a = findAnyKill(game)) != null) return a;        // 见明牌就吃（随机挑一个）
        if (rnd.nextInt(100) < 40) {                          // 40% 随手翻牌
            if ((a = findFlip(game)) != null) return a;
        }
        if ((a = findRandomMove(game)) != null) return a;
        if ((a = findFlip(game)) != null) return a;
        return null;
    }

    // ==================== 困难难度 ====================
    private int[] decideHard(GameLogic game) {
        int[] a;
        if ((a = findBestKill(game)) != null) return a;       // 1 收益最高、风险最小的一击
        // 15% 概率提前翻牌，避免行动路线被玩家完全预判
        if (rnd.nextInt(100) < 15) {
            if ((a = findFlip(game)) != null) return a;
        }
        if ((a = findApproach(game)) != null) return a;       // 2 向可吃的猎物靠拢
        if ((a = findEscape(game)) != null) return a;         // 3 躲开相邻天敌
        if ((a = findFlip(game)) != null) return a;           // 4 翻牌寻找机会
        if ((a = findRandomMove(game)) != null) return a;     // 5 调整站位
        return null;
    }

    // ==================== 击杀 ====================
    /** 正常：收集所有能吃的明牌目标，随机挑一个（不再固定从左上角扫到右下角） */
    private int[] findAnyKill(GameLogic game) {
        List<int[]> kills = new ArrayList<int[]>();
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (!isMine(card)) continue;
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (!inBoard(nr, nc)) continue;
                    Card target = game.getCard(nr, nc);
                    if (target == null || !target.isFaceUp() || target.getOwner() != enemyPlayer) continue;
                    if (GameLogic.canKillType(card.getType(), target.getType())) {
                        kills.add(new int[]{MOVE, r, c, nr, nc});
                    }
                }
            }
        }
        if (kills.isEmpty()) return null;
        return kills.get(rnd.nextInt(kills.size()));
    }

    /** 困难：遍历所有可吃目标，挑"吃完后被天敌威胁最少"的一击；
     *  评分加随机扰动，同分选项不再固定选第一个；无安全选项时仍会出手 */
    private int[] findBestKill(GameLogic game) {
        int bestScore = Integer.MIN_VALUE;
        int[] best = null;
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (!isMine(card)) continue;
                CardType myType = card.getType();
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (!inBoard(nr, nc)) continue;
                    Card target = game.getCard(nr, nc);
                    if (target == null || !target.isFaceUp() || target.getOwner() != enemyPlayer) continue;
                    if (!GameLogic.canKillType(myType, target.getType())) continue;
                    // 落到 (nr,nc) 后，周围有几个敌方明牌能吃掉我
                    int risk = threatCount(game, nr, nc, myType);
                    int score = 100 - risk * 40 + rnd.nextInt(10);   // 扰动打破平局
                    if (score > bestScore) { bestScore = score; best = new int[]{MOVE, r, c, nr, nc}; }
                }
            }
        }
        return best;
    }

    /** 统计 (r,c) 周围有几个敌方明牌能吃掉给定牌型 */
    private int threatCount(GameLogic game, int r, int c, CardType myType) {
        int n = 0;
        for (int[] d : DIRS) {
            int nr = r + d[0], nc = c + d[1];
            if (!inBoard(nr, nc)) continue;
            Card e = game.getCard(nr, nc);
            if (e == null || !e.isFaceUp() || e.getOwner() != enemyPlayer) continue;
            if (GameLogic.canKillType(e.getType(), myType)) n++;
        }
        return n;
    }

    /** 向"我能吃的明牌猎物"靠拢，且落点尽量安全；代价加随机扰动 */
    private int[] findApproach(GameLogic game) {
        int bestCost = Integer.MAX_VALUE;
        int[] best = null;
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (!isMine(card)) continue;
                CardType type = card.getType();
                int[] prey = nearestPrey(game, r, c, type);
                if (prey == null) continue;
                int dist = Math.abs(r - prey[0]) + Math.abs(c - prey[1]);
                if (dist < 2) continue; // 已相邻会走击杀分支
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (!inBoard(nr, nc) || game.getCard(nr, nc) != null) continue;
                    int newDist = Math.abs(nr - prey[0]) + Math.abs(nc - prey[1]);
                    if (newDist >= dist) continue;
                    int cost = newDist * 10 + threatCount(game, nr, nc, type) * 25 + rnd.nextInt(5);
                    if (cost < bestCost) { bestCost = cost; best = new int[]{MOVE, r, c, nr, nc}; }
                }
            }
        }
        return best;
    }

    /** 找最近的、当前牌能吃掉的敌方明牌 */
    private int[] nearestPrey(GameLogic game, int r, int c, CardType type) {
        int bestDist = Integer.MAX_VALUE;
        int[] best = null;
        for (int tr = 0; tr < GameLogic.ROWS; tr++) {
            for (int tc = 0; tc < GameLogic.COLS; tc++) {
                Card t = game.getCard(tr, tc);
                if (t == null || !t.isFaceUp() || t.getOwner() != enemyPlayer) continue;
                if (!GameLogic.canKillType(type, t.getType())) continue;
                int dist = Math.abs(r - tr) + Math.abs(c - tc);
                if (dist < bestDist) { bestDist = dist; best = new int[]{tr, tc}; }
            }
        }
        return best;
    }

    /** 躲开：被天敌相邻的己方明牌，移到没有天敌威胁的空格（收集全部安全点随机选） */
    private int[] findEscape(GameLogic game) {
        List<int[]> escapes = new ArrayList<int[]>();
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (!isMine(card)) continue;
                CardType type = card.getType();
                if (threatCount(game, r, c, type) == 0) continue;
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (!inBoard(nr, nc) || game.getCard(nr, nc) != null) continue;
                    if (threatCount(game, nr, nc, type) == 0) {
                        escapes.add(new int[]{MOVE, r, c, nr, nc});
                    }
                }
            }
        }
        if (escapes.isEmpty()) return null;
        return escapes.get(rnd.nextInt(escapes.size()));
    }

    /** 翻一张暗牌：优先翻己方暗牌（让战力尽早可用），其次翻敌方暗牌侦察 */
    private int[] findFlip(GameLogic game) {
        List<int[]> own = new ArrayList<int[]>();
        List<int[]> other = new ArrayList<int[]>();
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (card == null || card.isFaceUp()) continue;
                if (card.getOwner() == aiPlayer) own.add(new int[]{r, c});
                else other.add(new int[]{r, c});
            }
        }
        List<int[]> pool;
        if (difficulty == HARD) {
            pool = !own.isEmpty() ? own : other;   // 困难档总是先翻己方
        } else {
            // 正常档：有己方暗牌优先，否则侦察敌方（各留一点随机）
            if (own.isEmpty()) pool = other;
            else if (other.isEmpty()) pool = own;
            else pool = rnd.nextBoolean() ? own : other;
        }
        if (pool.isEmpty()) return null;
        int[] p = pool.get(rnd.nextInt(pool.size()));
        return new int[]{FLIP, p[0], p[1]};
    }

    /** 随机把一张己方明牌移到相邻空格 */
    private int[] findRandomMove(GameLogic game) {
        List<int[]> moves = new ArrayList<int[]>();
        for (int r = 0; r < GameLogic.ROWS; r++) {
            for (int c = 0; c < GameLogic.COLS; c++) {
                Card card = game.getCard(r, c);
                if (!isMine(card)) continue;
                for (int[] d : DIRS) {
                    int nr = r + d[0], nc = c + d[1];
                    if (inBoard(nr, nc) && game.getCard(nr, nc) == null) {
                        moves.add(new int[]{MOVE, r, c, nr, nc});
                    }
                }
            }
        }
        if (moves.isEmpty()) return null;
        return moves.get(rnd.nextInt(moves.size()));
    }

    // ==================== 工具 ====================
    private boolean isMine(Card card) {
        return card != null && card.isFaceUp() && card.getOwner() == aiPlayer;
    }
    private boolean inBoard(int r, int c) {
        return r >= 0 && r < GameLogic.ROWS && c >= 0 && c < GameLogic.COLS;
    }
}
