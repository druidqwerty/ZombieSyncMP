package com.druid.zombiesync;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** Console helpers: ZombieSync_status(), ZombieSync_set("mode", "smooth"). */
public class LuaApi {
    @LuaMethod(name = "ZombieSync_status", global = true)
    public static String status() {
        return Engine.status();
    }

    @LuaMethod(name = "ZombieSync_set", global = true)
    public static boolean set(String key, String value) {
        boolean ok = Config.set(key, value);
        Engine.log("set " + key + "=" + value + (ok ? "" : " (rejected)"));
        return ok;
    }
}
