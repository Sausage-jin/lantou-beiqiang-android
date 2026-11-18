package com.lantou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 简化版核心逻辑：5行3列棋盘。
 * 第1、2行红方，第4、5行蓝方，第3行空着。
 * 双方各6张暗牌（烂头、枪、虎、小孩、鸡、蜜蜂各1张）。
 * 六种牌构成单向相克环：烂头→枪→虎→小孩→鸡→蜜蜂→烂头。
 * 每次翻牌或行动累计一回合，满100回合后按剩余牌数判胜。
 * 若场上红蓝牌之间已不存在任何克制（互吃）关系，则提前按剩余牌数结算。
 */
public class GameLogic {
    public static final int ROWS = 5;
    public static final int COLS = 3;
    public static final int MAX_TURNS = 100;

    private Card[][] board;
    private int currentPlayer;   // 1 或 2
    private boolean gameOver;
    private int winner;          // 0=平局, 1或2=获胜玩家
    private String lastAction;
    /** 最近一次错误行动的原因（空串表示没有错误）。错误行动不切换回合。 */
    private String lastError = "";
    /** 已累计的行动/回合数（每次翻牌或移动+1） */
    private int turnCount = 0;

    /** 相克环：key 能击杀 value 中的卡（每种恰好克制一种） */
    private static final Map<CardType, Set<CardType>> KILL_MAP = new EnumMap<CardType, Set<CardType>>(CardType.class);
    static {
        KILL_MAP.put(CardType.LANTOU,    EnumSet.of(CardType.QIANG));    // 烂头吃枪
        KILL_MAP.put(CardType.QIANG,     EnumSet.of(CardType.SHARENHU)); // 枪吃老虎
        KILL_MAP.put(CardType.SHARENHU,  EnumSet.of(CardType.XIAOHAI));  // 老虎吃小孩
        KILL_MAP.put(CardType.XIAOHAI,   EnumSet.of(CardType.GONGJI));   // 小孩吃鸡
        KILL_MAP.put(CardType.GONGJI,    EnumSet.of(CardType.MIFENG));   // 鸡吃蜜蜂
        KILL_MAP.put(CardType.MIFENG,    EnumSet.of(CardType.LANTOU));   // 蜜蜂吃烂头
    }

    /** 错误吃牌的趣味文案：key = 进攻方名 + "|" + 防守方名 */
    private static final Map<String, String> ERROR_HINT = new HashMap<String, String>();
    static {
        ERROR_HINT.put(k(CardType.LANTOU, CardType.LANTOU), "两个烂头大眼瞪小眼");
        ERROR_HINT.put(k(CardType.LANTOU, CardType.SHARENHU), "虎哥，头他喝大了");
        ERROR_HINT.put(k(CardType.LANTOU, CardType.XIAOHAI), "这是未成年");
        ERROR_HINT.put(k(CardType.LANTOU, CardType.GONGJI), "烂头追不上鸡");
        ERROR_HINT.put(k(CardType.LANTOU, CardType.MIFENG), "烂头被蜜蜂蜇了");

        ERROR_HINT.put(k(CardType.QIANG, CardType.QIANG), "互架");
        ERROR_HINT.put(k(CardType.QIANG, CardType.XIAOHAI), "枪可不能打小孩");
        ERROR_HINT.put(k(CardType.QIANG, CardType.GONGJI), "打鸟不用枪");
        ERROR_HINT.put(k(CardType.QIANG, CardType.MIFENG), "大炮打蚊子");

        ERROR_HINT.put(k(CardType.SHARENHU, CardType.SHARENHU), "两虎相斗");
        ERROR_HINT.put(k(CardType.SHARENHU, CardType.LANTOU), "太臭了");
        ERROR_HINT.put(k(CardType.SHARENHU, CardType.GONGJI), "老虎不吃鸡");
        ERROR_HINT.put(k(CardType.SHARENHU, CardType.MIFENG), "从牙缝里从容飞出");

        ERROR_HINT.put(k(CardType.XIAOHAI, CardType.XIAOHAI), "小朋友不打架");
        ERROR_HINT.put(k(CardType.XIAOHAI, CardType.LANTOU), "喝大了");
        ERROR_HINT.put(k(CardType.XIAOHAI, CardType.QIANG), "小孩玩不了枪");
        ERROR_HINT.put(k(CardType.XIAOHAI, CardType.MIFENG), "小孩怕蜜蜂");

        ERROR_HINT.put(k(CardType.GONGJI, CardType.GONGJI), "公鸡只爱啄母鸡");
        ERROR_HINT.put(k(CardType.GONGJI, CardType.LANTOU), "鸡啄不动烂头");
        ERROR_HINT.put(k(CardType.GONGJI, CardType.QIANG), "战斗鸡！！");

        ERROR_HINT.put(k(CardType.MIFENG, CardType.MIFENG), "蜜蜂不蜇自己人");
        ERROR_HINT.put(k(CardType.MIFENG, CardType.QIANG), "硬");
        ERROR_HINT.put(k(CardType.MIFENG, CardType.SHARENHU), "皮太厚扎不进去");
        ERROR_HINT.put(k(CardType.MIFENG, CardType.XIAOHAI), "蜜蜂很喜欢小孩");
        ERROR_HINT.put(k(CardType.GONGJI, CardType.SHARENHU), "我的剑也未偿不利");
    }
    private static String k(CardType a, CardType d) { return a.name() + "|" + d.name(); }

    public GameLogic() {
        reset();
    }

    /** 初始化：红方铺在第1、2行，蓝方铺在第4、5行，第3行留空，全部牌背朝上 */
    public void reset() {
        board = new Card[ROWS][COLS];
        currentPlayer = 1;
        gameOver = false;
        winner = 0;
        turnCount = 0;
        lastAction = "红方先手 · 点暗牌翻开，或点己方明牌行动";
        lastError = "";

        // 每个阵营一套6张，顺序随机打乱
        CardType[] types = { CardType.LANTOU, CardType.QIANG, CardType.SHARENHU,
                             CardType.XIAOHAI, CardType.GONGJI, CardType.MIFENG };
        for (int p = 1; p <= 2; p++) {
            List<CardType> list = new ArrayList<CardType>();
            Collections.addAll(list, types);
            Collections.shuffle(list);
            int rowStart = (p == 1) ? 0 : 3;   // 红方 0,1 行；蓝方 3,4 行；第2行(index 2)空
            int idx = 0;
            for (int r = rowStart; r < rowStart + 2; r++) {
                for (int c = 0; c < COLS; c++) {
                    board[r][c] = new Card(list.get(idx++), p);
                }
            }
        }
    }

    public Card getCard(int row, int col) { return board[row][col]; }

    public int getCurrentPlayer() { return currentPlayer; }
    public boolean isGameOver() { return gameOver; }
    public int getWinner() { return winner; }
    public String getLastAction() { return lastAction; }
    public String getLastError() { return lastError; }
    public int getTurnCount() { return turnCount; }

    /**
     * 指定玩家当前是否有合法动作可做。
     * 有暗牌可翻、有相邻的空格可走、或有相邻敌方牌可形成有效克制，都算有动作。
     * 暗牌全翻开且棋盘被挤满、相邻牌又互不相克时，会返回 false（需跳过回合）。
     */
    public boolean hasAnyAction(int player) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Card card = board[r][c];
                if (card == null || !card.isFaceUp()) {
                    // 任意暗牌都可翻（不限归属）
                    if (card != null && !card.isFaceUp()) return true;
                    continue;
                }
                if (card.getOwner() != player) continue;
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    int nr = r + d[0], nc = c + d[1];
                    if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue;
                    Card t = board[nr][nc];
                    if (t == null) return true;                                  // 可移动到空格
                    // 只有"我能吃掉对方"才算有效动作，与 AI/玩家的攻击判定保持一致
                    if (t.getOwner() != player && GameLogic.canKillType(card.getType(), t.getType())) return true;
                }
            }
        }
        return false;
    }

    /** 跳过回合：仅在确实无任何合法动作时允许，消耗一回合并换手 */
    public boolean pass() {
        lastError = "";
        if (gameOver || hasAnyAction(currentPlayer)) return false;
        lastAction = playerName(currentPlayer) + "无子可动，跳过回合";
        endTurn();
        return true;
    }

    /** 认输：指定玩家认输，对方立即获胜 */
    public void surrender(int player) {
        if (gameOver) return;
        gameOver = true;
        winner = player == 1 ? 2 : 1;
        lastError = "";
        lastAction = playerName(player) + "认输，" + playerName(winner) + "胜";
    }

    /** 统计某玩家存活卡牌数（暗牌也算存活） */
    public int countAlive(int player) {
        int count = 0;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (board[r][c] != null && board[r][c].getOwner() == player) count++;
        return count;
    }

    /** 翻牌：翻开任意暗牌，消耗一回合 */
    public boolean flip(int row, int col) {
        lastError = "";
        if (gameOver) return false;
        Card card = board[row][col];
        if (card == null || card.isFaceUp()) return false;
        card.setFaceUp(true);
        String ownerPrefix = card.getOwner() != currentPlayer ? playerName(card.getOwner()) : "";
        lastAction = playerName(currentPlayer) + "翻开 " + ownerPrefix + card.getType().getName();
        endTurn();
        return true;
    }

    /**
     * 移动/对战：己方明牌向相邻一格行动。
     * 空格→移动；敌方牌→对战（相克环判定，双方都不克则错误行动）。
     */
    public boolean move(int fromRow, int fromCol, int toRow, int toCol) {
        lastError = "";
        if (gameOver) return false;
        if (!isAdjacent(fromRow, fromCol, toRow, toCol)) return false;
        Card card = board[fromRow][fromCol];
        if (card == null || !card.isFaceUp() || card.getOwner() != currentPlayer) return false;
        Card target = board[toRow][toCol];
        if (target != null && target.getOwner() == currentPlayer) return false; // 不能移到己方牌上

        if (target == null) {
            board[toRow][toCol] = card;
            board[fromRow][fromCol] = null;
            lastAction = playerName(currentPlayer) + "移动 " + card.getType().getName();
        } else {
            // 敌方牌 → 对战；未翻开的应战牌先翻开
            StringBuilder sb = new StringBuilder();
            boolean targetWasFaceUp = target.isFaceUp();
            if (!targetWasFaceUp) {
                target.setFaceUp(true);
                sb.append(target.getType().getName()).append("应战，");
            }
            boolean attKills = canKill(card.getType(), target.getType());
            boolean defKills = canKill(target.getType(), card.getType());

            // 双方都无法击杀对方 → 错误行动：撤销翻牌、不移动、回合仍归错误方
            if (!attKills && !defKills) {
                if (!targetWasFaceUp) target.setFaceUp(false);
                lastError = invalidAttackHint(card.getType(), target.getType());
                return false;
            }

            if (attKills) {
                board[toRow][toCol] = card;
                board[fromRow][fromCol] = null;
                sb.append(card.getType().getName()).append(" 吃掉了 ").append(target.getType().getName());
            } else {
                // 防守方反杀：进攻方消失，防守方留在原地
                board[fromRow][fromCol] = null;
                sb.append(card.getType().getName()).append(" 被 ").append(target.getType().getName()).append(" 反吃");
            }
            lastAction = sb.toString();
        }

        endTurn();
        return true;
    }

    /** 无效攻击提示：查趣味文案表，表外兜底 */
    private String invalidAttackHint(CardType attacker, CardType defender) {
        String hint = ERROR_HINT.get(k(attacker, defender));
        if (hint != null) return hint;
        return attacker.getName() + "吃不了" + defender.getName();
    }

    private boolean canKill(CardType attacker, CardType defender) {
        Set<CardType> kills = KILL_MAP.get(attacker);
        return kills != null && kills.contains(defender);
    }

    /** 公共查询：attacker能否击杀defender（供AI与界面使用） */
    public static boolean canKillType(CardType attacker, CardType defender) {
        Set<CardType> kills = KILL_MAP.get(attacker);
        return kills != null && kills.contains(defender);
    }

    /** 两张牌之间是否存在有效克制（任一方能吃掉另一方） */
    public static boolean canBattle(CardType a, CardType b) {
        return canKillType(a, b) || canKillType(b, a);
    }

    private boolean isAdjacent(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2) == 1;
    }

    /** 回合结束：计数+1，判断胜负与提前结束，否则换手 */
    private void endTurn() {
        turnCount++;
        if (settleIfWipedOut()) return;
        // 场上已没有任何一组可互吃的红蓝牌 → 无法再通过吃牌分胜负，提前结算
        if (!hasBattleRelation()) {
            settleByCount("场上牌已无相克，提前结算");
            return;
        }
        if (turnCount >= MAX_TURNS) {
            settleByCount(MAX_TURNS + "回合结束");
            return;
        }
        currentPlayer = (currentPlayer == 1) ? 2 : 1;
    }

    /** 某方牌被吃光则对方立即获胜 */
    private boolean settleIfWipedOut() {
        int red = countAlive(1), blue = countAlive(2);
        if (red == 0 || blue == 0) {
            gameOver = true;
            winner = (red == 0) ? 2 : 1;
            lastAction = playerName(winner) + "胜，" + playerName(red == 0 ? 1 : 2) + "的牌已被吃光";
            return true;
        }
        return false;
    }

    /**
     * 场上红蓝双方任意两张牌之间是否还存在克制（互吃）关系。
     * 只考虑牌的类型，与朝向、位置无关：只要还存在一组可互吃的红蓝牌，
     * 对局理论上就还能继续通过吃牌分出胜负。
     */
    public boolean hasBattleRelation() {
        for (int r1 = 0; r1 < ROWS; r1++) {
            for (int c1 = 0; c1 < COLS; c1++) {
                Card a = board[r1][c1];
                if (a == null) continue;
                for (int r2 = 0; r2 < ROWS; r2++) {
                    for (int c2 = 0; c2 < COLS; c2++) {
                        Card b = board[r2][c2];
                        if (b == null || a.getOwner() == b.getOwner()) continue;
                        if (canBattle(a.getType(), b.getType())) return true;
                    }
                }
            }
        }
        return false;
    }

    /** 满回合 / 场上无相克时的结算：剩余牌多者获胜 */
    private void settleByCount(String reason) {
        gameOver = true;
        int red = countAlive(1), blue = countAlive(2);
        if (red > blue) winner = 1;
        else if (blue > red) winner = 2;
        else winner = 0;
        lastAction = reason + " · 红方" + red + "张 / 蓝方" + blue + "张，"
                + (winner == 0 ? "平局" : playerName(winner) + "胜");
    }

    /** 序列化完整状态（用于联机同步） */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(currentPlayer).append('|').append(gameOver).append('|').append(winner).append('|')
          .append(turnCount).append('|')
          .append(lastAction == null ? "" : lastAction.replace("|", "\u4e28"));
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                sb.append('|');
                Card card = board[r][c];
                if (card == null) sb.append('-');
                else sb.append(card.getOwner()).append(':').append(card.getType().ordinal())
                       .append(':').append(card.isFaceUp() ? 1 : 0);
            }
        }
        return sb.toString();
    }

    /** 反序列化恢复状态 */
    public void deserialize(String s) {
        lastError = "";
        String[] parts = s.split("\\|", -1);
        currentPlayer = Integer.parseInt(parts[0]);
        gameOver = Boolean.parseBoolean(parts[1]);
        winner = Integer.parseInt(parts[2]);
        turnCount = parts.length > 4 ? Integer.parseInt(parts[3]) : 0;
        int cellStart = 5;
        lastAction = parts[4].replace("\u4e28", "|");
        board = new Card[ROWS][COLS];
        int idx = cellStart;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                String cell = parts[idx++];
                if (!"-".equals(cell)) {
                    String[] f = cell.split(":");
                    Card card = new Card(CardType.values()[Integer.parseInt(f[1])], Integer.parseInt(f[0]));
                    card.setFaceUp("1".equals(f[2]));
                    board[r][c] = card;
                }
            }
        }
    }

    private String playerName(int p) { return p == 1 ? "红方" : "蓝方"; }
}
