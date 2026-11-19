package com.lantou;

/**
 * 卡牌类型枚举（简化版：仅 6 种对战牌）
 * 红蓝双方各持一套 6 张：烂头、枪、虎、小孩、鸡、蜜蜂。
 * 六种牌构成单向相克环：
 *   烂头→枪→虎→小孩→鸡→蜜蜂→烂头
 * 无中立牌、无合成、无烂头打虎。
 */
public enum CardType {
    LANTOU("烂头"),
    QIANG("枪"),
    SHARENHU("虎"),
    XIAOHAI("小孩"),
    GONGJI("鸡"),
    MIFENG("蜜蜂");

    private final String name;

    CardType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
