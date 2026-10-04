package org.qa602906.bedrockminerMaster;

import net.fabricmc.api.ModInitializer;

public class BedrockminerMaster implements ModInitializer {

    /**调试日志总开关*/
    public static boolean debugLog =
            Boolean.parseBoolean(System.getProperty("bedrockminer.debug", "False"));

    @Override
    public void onInitialize() {
    }

    public static void log(String msg) {
        if (debugLog) {
            System.out.println("[bedrockminer] " + msg);
        }
    }

    public static void log(String msg, Throwable t) {
        if (debugLog) {
            System.out.println("[bedrockminer] " + msg);
            t.printStackTrace();
        }
    }
}
