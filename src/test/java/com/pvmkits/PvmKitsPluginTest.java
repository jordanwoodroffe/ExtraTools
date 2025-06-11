package com.pvmkits;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvmKitsPluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(PvmKitsPlugin.class);
        RuneLite.main(args);
    }
}
