-- ZombieSync MP settings.
--   server    the host's sandbox options, page ZombieSync MP (SandboxVars.ZombieSyncMP): Enabled, Mode,
--             SendIntervalMs, SendBudgetKBps, StallBufferMs, SyncPlayers, FollowAttacks, FixEating. The same for
--             everyone; the host (or an admin in a running game, Admin > Sandbox options) changes them, the server
--             sends them to every client, and this file applies them within 2 s (B42 has no Lua event for a sandbox
--             change, so it looks every 2 s).
--   personal  Options > Mods > ZombieSync MP: only this player's diagnostics (stats lines in console.txt).
-- Outside a server (main menu, single player), or on a server without the ZombieSync MP page (an older version of
-- the mod there), the global values come from Zomboid/ZombieSyncMP.properties or the built-in defaults.
-- Values go to the Java side (ZombieSync_set) and apply at once. After the first apply only values that changed are
-- sent again, so a change of one setting touches nothing else. Keys saved by older versions of the personal page
-- (enabled, mode, use, sendIntervalMs, ... in Zomboid/Lua/ModOptions.ini) are not read by anything any more.
if not PZAPI or not PZAPI.ModOptions then return end

local MODES = { "balanced", "smooth", "realtime" }
local ORDER = { "enabled", "mode", "sendBoost", "sendIntervalMs", "sendBudgetKBps", "maxDelayMs", "syncPlayers",
    "followAttacks", "fixEating", "logStats", "statsIntervalSec" }

local function javaLoaded()
    return type(ZombieSync_get) == "function" and type(ZombieSync_set) == "function"
end

local function javaGet(key)
    if not javaLoaded() then return nil end
    local ok, value = pcall(ZombieSync_get, key)
    if ok and value ~= nil then return tostring(value) end
    return nil
end

local function javaSet(key, value)
    if javaGet(key) == value then return true end
    local ok, accepted = pcall(ZombieSync_set, key, value)
    return ok and accepted == true
end

local function intString(v)
    return string.format("%d", math.floor((tonumber(v) or 0) + 0.5))
end

local function clamp(v, lo, hi, default)
    v = tonumber(v) or default
    return math.max(lo, math.min(hi, v))
end

local function startupNumber(key, default)
    return tonumber(javaGet("startup." .. key)) or default
end

local options = PZAPI.ModOptions:create("ZombieSyncMP", "ZombieSync MP")
options:addDescription(javaLoaded() and "UI_ZS_Info" or "UI_ZS_NotLoaded")
-- defaults: the file's values (Zomboid/ZombieSyncMP.properties), until the player saves this page
local logStats = options:addTickBox("logStats", "UI_ZS_LogStats", javaGet("startup.logStats") ~= "false", "UI_ZS_LogStats_tt")
local statsInterval = options:addSlider("statsIntervalSec", "UI_ZS_StatsInterval", 5, 600, 5,
    clamp(startupNumber("statsIntervalSec", 30), 5, 600, 30))

local function bool(v, default)
    if v == true or v == "true" then return true end
    if v == false or v == "false" then return false end
    return default
end

-- the host's values on a server; nil in single player / main menu or if the server has no ZombieSync MP page
local function serverValues()
    if not isClient() then return nil end
    local sv = SandboxVars and SandboxVars.ZombieSyncMP
    if type(sv) ~= "table" then return nil end
    local sendIntervalMs = clamp(sv.SendIntervalMs, 20, 200, 50)
    return {
        enabled = bool(sv.Enabled, true),
        mode = MODES[tonumber(sv.Mode)] or "balanced",
        -- 200 ms is vanilla's own rate: no boost needed
        sendBoost = sendIntervalMs < 200,
        sendIntervalMs = sendIntervalMs,
        sendBudgetKBps = clamp(sv.SendBudgetKBps, 8, 4096, 256),
        stallBufferMs = clamp(sv.StallBufferMs, 300, 4000, 1500),
        syncPlayers = bool(sv.SyncPlayers, true),
        followAttacks = bool(sv.FollowAttacks, true),
        fixEating = bool(sv.FixEating, true),
    }
end

local function globalValues()
    local srv = serverValues()
    if srv then return srv, "server" end
    return {
        enabled = javaGet("startup.enabled") ~= "false",
        mode = javaGet("startup.mode") or "balanced",
        sendBoost = javaGet("startup.sendBoost") ~= "false",
        sendIntervalMs = startupNumber("sendIntervalMs", 50),
        sendBudgetKBps = startupNumber("sendBudgetKBps", 256),
        stallBufferMs = startupNumber("maxDelayMs", 1500),
        syncPlayers = javaGet("startup.syncPlayers") ~= "false",
        followAttacks = javaGet("startup.followAttacks") ~= "false",
        fixEating = javaGet("startup.fixEating") ~= "false",
    }, "local"
end

local sent = {}  -- key -> value last sent to the Java side
local lastLine

local function applySettings(all)
    if not javaLoaded() then return end
    local g, source = globalValues()
    local v = {
        enabled = tostring(g.enabled),
        mode = g.mode,
        sendBoost = tostring(g.sendBoost),
        sendIntervalMs = intString(g.sendIntervalMs),
        sendBudgetKBps = intString(g.sendBudgetKBps),
        maxDelayMs = intString(g.stallBufferMs),
        syncPlayers = tostring(g.syncPlayers),
        followAttacks = tostring(g.followAttacks),
        fixEating = tostring(g.fixEating),
        logStats = tostring(logStats:getValue()),
        statsIntervalSec = intString(statsInterval:getValue()),
    }
    for _, k in ipairs(ORDER) do
        if all or sent[k] ~= v[k] then
            javaSet(k, v[k])
            sent[k] = v[k]
        end
    end
    local line = "[ZombieSync] settings (" .. source .. "): enabled=" .. v.enabled .. " mode=" .. v.mode
        .. " sendIntervalMs=" .. v.sendIntervalMs .. " sendBudgetKBps=" .. v.sendBudgetKBps
        .. " stallBufferMs=" .. v.maxDelayMs .. " syncPlayers=" .. v.syncPlayers .. " followAttacks=" .. v.followAttacks
        .. " fixEating=" .. v.fixEating .. " | personal: logStats=" .. v.logStats .. " statsIntervalSec=" .. v.statsIntervalSec
    if line ~= lastLine then
        lastLine = line
        print(line)
    end
end

options.apply = function() applySettings(false) end
Events.OnMainMenuEnter.Add(function() applySettings(true) end)
Events.OnGameStart.Add(function() applySettings(true) end)

-- no event fires when an admin changes sandbox options in a running game: look every 2 s
local nextCheck = 0
Events.OnTick.Add(function()
    local now = getTimestampMs()
    if now < nextCheck then return end
    nextCheck = now + 2000
    applySettings(false)
end)
