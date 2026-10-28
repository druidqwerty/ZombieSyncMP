package com.druid.zombiesync;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** Lua side: ZombieSync_status(), ZombieSync_get("mode"), ZombieSync_set("mode", "smooth"); the in-game options use get/set. */
public class LuaApi {
    @LuaMethod(name = "ZombieSync_status", global = true)
    public static String status() {
        return Engine.status();
    }

    @LuaMethod(name = "ZombieSync_get", global = true)
    public static String get(String key) {
        return key == null ? null : Config.get(key);
    }

    @LuaMethod(name = "ZombieSync_set", global = true)
    public static boolean set(String key, String value) {
        boolean ok = Config.set(key, value);
        Engine.log("set " + key + "=" + value + (ok ? "" : " (rejected)"));
        return ok;
    }
}
