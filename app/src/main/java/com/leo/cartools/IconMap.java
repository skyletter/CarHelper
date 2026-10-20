package com.leo.cartools;

import java.util.HashMap;

/**
 * 高德转向 ID（ICON / NEW_ICON）→ 长安仪表协议图标 ID
 * 数据来源：原厂 Auto_Navigation.apk 内 assets/TBT_Navi_id_mapping.properties
 */
public class IconMap {
    private static final HashMap<Integer, Integer> MAP = new HashMap<>();

    static {
        MAP.put(1, 329);   // 自车
        MAP.put(2, 27);    // 左转
        MAP.put(3, 22);    // 右转
        MAP.put(4, 28);    // 左前方
        MAP.put(5, 21);    // 右前方
        MAP.put(6, 26);    // 左后方
        MAP.put(7, 23);    // 右后方
        MAP.put(8, 25);    // 左转掉头
        MAP.put(9, 17);    // 直行
        MAP.put(10, 328);  // 到达途经点
        MAP.put(11, 327);  // 进入环岛
        MAP.put(12, 333);  // 驶出环岛
        MAP.put(13, 15);   // 服务区
        MAP.put(14, 11);   // 收费站
        MAP.put(15, 6);    // 到达目的地
        MAP.put(16, 13);   // 隧道
        MAP.put(17, 330);  // 进入环岛
        MAP.put(18, 331);  // 直行绕左
        MAP.put(19, 24);   // 右调头
        MAP.put(20, 332);  // 顺行

        MAP.put(21, 345);
        MAP.put(22, 344);
        MAP.put(23, 347);
        MAP.put(24, 346);
        MAP.put(25, 348);
        MAP.put(26, 349);
        MAP.put(27, 351);
        MAP.put(28, 350);

        // 环岛左转 1~10 出口
        MAP.put(11121, 235);
        MAP.put(11122, 239);
        MAP.put(11123, 243);
        MAP.put(11124, 247);
        MAP.put(11125, 251);
        MAP.put(11126, 255);
        MAP.put(11127, 259);
        MAP.put(11128, 263);
        MAP.put(11129, 267);
        MAP.put(111210, 271);

        // 环岛右转 1~10 出口
        MAP.put(11171, 334);
        MAP.put(11172, 335);
        MAP.put(11173, 336);
        MAP.put(11174, 337);
        MAP.put(11175, 338);
        MAP.put(11176, 339);
        MAP.put(11177, 340);
        MAP.put(11178, 341);
        MAP.put(11179, 342);
        MAP.put(111710, 343);
    }

    /** 找不到时返回 -1，调用方保留上一次图标 */
    public static int toCluster(int amapIcon) {
        Integer v = MAP.get(amapIcon);
        return v == null ? -1 : v;
    }
}
