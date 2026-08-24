package com.yourstudio.worldmemory.narrative;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WorldMemory alpha.51 multi-character staged-scene layer.
 * Reflection-only Bukkit interaction keeps this add-on binary-light and isolated
 * from the core narrative runtime.
 */
public final class NarrativeScenes {
    private static final String PREFIX = "§8[§bWorldMemory§8] §7";
    private static volatile Object plugin;
    private static volatile Object commandProxy;
    private static volatile boolean started;
    private static final Map<String, SceneDef> SCENES = new LinkedHashMap<>();
    private static final List<String> ERRORS = new ArrayList<>();
    private static final Map<UUID, SceneRun> RUNS = new ConcurrentHashMap<>();

    private NarrativeScenes() {}

    public static synchronized void startup(Object p) {
        if (p == null) return;
        plugin = p;
        ensureExample();
        reload();
        if (!started) {
            try { registerCommand(); }
            catch (Throwable t) { log("Could not register /scene", t); }
        }
        started = true;
    }

    public static synchronized void shutdown() {
        for (SceneRun run : new ArrayList<>(RUNS.values())) {
            Object owner = online(run.ownerId);
            cleanupCamera(run);
            run.cancelled = true;
            if (owner != null) send(owner, "Scene stopped during plugin shutdown: " + run.def.id);
        }
        RUNS.clear();
        SCENES.clear();
        ERRORS.clear();
        started = false;
        plugin = null;
        commandProxy = null;
    }

    public static synchronized void reload() {
        SCENES.clear();
        ERRORS.clear();
        if (plugin == null) return;
        File dir = scenesDir();
        if (!dir.exists()) dir.mkdirs();
        List<File> files = new ArrayList<>();
        collectYaml(dir, files);
        for (File file : files) {
            try { loadScene(file); }
            catch (Throwable t) { ERRORS.add(file.getName() + ": " + shortError(t)); }
        }
        validateAll();
    }

    /** Hook inserted into NarrativeCore.executeStoryStep in alpha.51. */
    public static Boolean handleStoryStep(Object player, Object storySession, Object step) {
        try {
            String type = lower(str(field(step, "type")));
            if (!type.equals("directed-scene") && !type.equals("staged-scene") && !type.equals("multi-scene")) return null;
            Map<String,Object> data = map(field(step, "data"));
            String id = nonBlank(str(data.get("id")), str(data.get("scene")));
            if (id.isBlank()) {
                send(player, "Directed scene step is missing id.");
                return Boolean.FALSE;
            }
            startScene(player, id, () -> invokeCorePrivateQuiet("advanceStory", player, storySession));
            return Boolean.TRUE;
        } catch (Throwable t) {
            log("Story scene hook failed", t);
            return Boolean.FALSE;
        }
    }

    /** Hook inserted into NarrativeCore.executeCutsceneStep in alpha.51. */
    public static Boolean handleCutsceneStep(Object player, Object cutsceneRun, Object step) {
        try {
            String type = lower(str(field(step, "type")));
            if (!type.equals("directed-scene") && !type.equals("staged-scene") && !type.equals("multi-scene")) return null;
            Map<String,Object> data = map(field(step, "data"));
            String id = nonBlank(str(data.get("id")), str(data.get("scene")));
            if (id.isBlank()) {
                send(player, "Directed scene step is missing id.");
                return Boolean.FALSE;
            }
            startScene(player, id, () -> invokeCorePrivateQuiet("advanceCutscene", player, cutsceneRun));
            return Boolean.TRUE;
        } catch (Throwable t) {
            log("Cutscene scene hook failed", t);
            return Boolean.FALSE;
        }
    }

    public static void startScene(Object owner, String id, Runnable onComplete) {
        if (owner == null || id == null) { if (onComplete != null) onComplete.run(); return; }
        SceneDef def;
        synchronized (NarrativeScenes.class) { def = SCENES.get(id); }
        if (def == null) {
            send(owner, "Unknown staged scene: §f" + id);
            if (onComplete != null) onComplete.run();
            return;
        }
        UUID uid;
        try { uid = uuid(owner); }
        catch (Throwable t) { if (onComplete != null) onComplete.run(); return; }
        SceneRun old = RUNS.get(uid);
        if (old != null && !old.cancelled) {
            send(owner, "A staged scene is already active: §f" + old.def.id);
            if (onComplete != null) onComplete.run();
            return;
        }
        SceneRun run = new SceneRun(uid, def, onComplete);
        run.participants.addAll(collectParticipants(owner, def));
        run.participants.add(uid);
        RUNS.put(uid, run);
        send(owner, "Scene started: §f" + def.id + " §8(" + run.participants.size() + " participant" + (run.participants.size()==1?"":"s") + ")");
        runNext(owner, run);
    }

    private static void runNext(Object owner, SceneRun run) {
        if (run.cancelled || RUNS.get(run.ownerId) != run) return;
        try {
            int immediate = 0;
            while (!run.cancelled && run.index < run.def.steps.size() && immediate++ < 64) {
                SceneStep step = run.def.steps.get(run.index++);
                run.currentType = step.type;
                if (execute(owner, run, step)) return; // blocking
            }
            if (run.index >= run.def.steps.size()) finish(owner, run);
            else if (immediate >= 64) abort(owner, run, "possible immediate-step loop");
        } catch (Throwable t) {
            abort(owner, run, shortError(t));
            log("Scene " + run.def.id + " failed", t);
        }
    }

    private static boolean execute(Object owner, SceneRun run, SceneStep step) throws Exception {
        Map<String,Object> d = step.data;
        switch (step.type) {
            case "line", "actor-line" -> {
                broadcastLine(owner, run, d);
                long ticks = longVal(d.get("duration-ticks"), autoLineDuration(str(d.get("text"))));
                schedule(() -> runNext(owner, run), ticks);
                return true;
            }
            case "wait" -> {
                schedule(() -> runNext(owner, run), longVal(d.get("ticks"), longVal(d.get("duration-ticks"), 20L)));
                return true;
            }
            case "dialogue" -> {
                String id = nonBlank(str(d.get("id")), str(d.get("dialogue")));
                NarrativeSessions.startDialogue(owner, id, () -> runNext(owner, run));
                return true;
            }
            case "actor-enter" -> {
                Object actor = actorEntity(owner, run.def, str(d.get("target")));
                if (actor == null) { warn(owner, "Actor not found: " + str(d.get("target"))); return false; }
                tryInvoke(actor, "setInvisible", false);
                if (hasLocation(d)) {
                    invokeDirectorBlocking("moveObject", owner, actor, d, () -> runNext(owner, run));
                    return true;
                }
                return false;
            }
            case "actor-move" -> {
                Object actor = actorEntity(owner, run.def, str(d.get("target")));
                if (actor == null) { warn(owner, "Actor not found: " + str(d.get("target"))); return false; }
                invokeDirectorBlocking("moveObject", owner, actor, d, () -> runNext(owner, run));
                return true;
            }
            case "actor-exit" -> {
                Object actor = actorEntity(owner, run.def, str(d.get("target")));
                if (actor == null) { warn(owner, "Actor not found: " + str(d.get("target"))); return false; }
                Runnable done = () -> {
                    if (bool(d.get("hide"), false)) tryInvoke(actor, "setInvisible", true);
                    runNext(owner, run);
                };
                if (hasLocation(d)) {
                    invokeDirectorBlocking("moveObject", owner, actor, d, done);
                    return true;
                }
                done.run();
                return true;
            }
            case "actor-look", "look" -> {
                Object actor = actorEntity(owner, run.def, str(d.get("target")));
                if (actor == null) { warn(owner, "Actor not found: " + str(d.get("target"))); return false; }
                Map<String,Object> look = new LinkedHashMap<>(d);
                resolveLookTarget(owner, run.def, look);
                invokeDirectorBlocking("lookAt", owner, actor, look, () -> runNext(owner, run));
                return true;
            }
            case "camera", "sync-camera" -> {
                syncCamera(owner, run, d, () -> runNext(owner, run));
                return true;
            }
            case "animation" -> {
                String id = str(d.get("id"));
                String timeline = nonBlank(str(d.get("timeline")), "open");
                invokeCorePrivate("playAnimationBlocking", owner, id, timeline, (Runnable)() -> runNext(owner, run));
                return true;
            }
            case "title" -> {
                forEachParticipant(run, p -> invokeCorePrivateQuiet("showTitle", p, d));
                return false;
            }
            case "sound" -> {
                forEachParticipant(run, p -> invokeCorePrivateQuiet("playSound", p, d));
                return false;
            }
            case "command" -> {
                invokeCorePrivate("executeCommand", owner, str(d.get("command")));
                return false;
            }
            case "refresh-audience", "refresh-participants" -> {
                run.participants.clear();
                run.participants.addAll(collectParticipants(owner, run.def));
                run.participants.add(run.ownerId);
                return false;
            }
            case "end" -> { finish(owner, run); return true; }
            default -> {
                warn(owner, "Unknown scene step type: " + step.type);
                return false;
            }
        }
    }

    private static void broadcastLine(Object owner, SceneRun run, Map<String,Object> d) {
        String speakerToken = nonBlank(str(d.get("speaker")), "narrator");
        String actorId = resolveActor(run.def, speakerToken);
        String speaker = speakerName(actorId, speakerToken);
        String text = resolveSceneText(owner, d);
        run.currentSpeaker = speaker;
        String chat = "§6" + speaker + " §8» §f" + text;
        boolean subtitle = bool(d.get("subtitle"), true);
        forEachParticipant(run, p -> {
            sendRaw(p, chat);
            if (subtitle) {
                try {
                    Method m = find(p.getClass(), "sendTitle", new Object[]{speaker, text, 5, (int)Math.min(80, autoLineDuration(text)), 10});
                    if (m != null) m.invoke(p, speaker, text, 5, (int)Math.min(80, autoLineDuration(text)), 10);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String resolveSceneText(Object player, Map<String,Object> d) {
        String text = str(d.get("text"));
        String key = str(d.get("text-key"));
        if (!key.isBlank()) {
            try { text = str(invokeCorePrivate("resolveText", player, key, key)); }
            catch (Throwable ignored) { if (text.isBlank()) text = key; }
        }
        try { return NarrativeBridge.interpolate(player, text); }
        catch (Throwable ignored) { return text; }
    }

    private static void syncCamera(Object owner, SceneRun run, Map<String,Object> d, Runnable done) {
        String action = lower(nonBlank(str(d.get("action")), "move"));
        List<Object> players = onlineParticipants(run);
        if (players.isEmpty()) { done.run(); return; }
        if (action.equals("exit") || action.equals("restore")) {
            for (Object p : players) {
                invokeCorePrivateQuiet("restoreCamera", p);
                try { run.cameraParticipants.remove(uuid(p)); } catch (Throwable ignored) {}
            }
            done.run();
            return;
        }
        long duration = longVal(d.get("duration-ticks"), longVal(d.get("ticks"), 0));
        AtomicInteger remaining = new AtomicInteger(players.size());
        for (Object p : players) {
            try {
                invokeCorePrivate("ensureCamera", p);
                run.cameraParticipants.add(uuid(p));
                invokeCorePrivate("moveCamera", p, d, duration, (Runnable)() -> {
                    if (remaining.decrementAndGet() == 0) done.run();
                });
            } catch (Throwable t) {
                log("Synchronized camera failed for " + playerName(p), t);
                if (remaining.decrementAndGet() == 0) done.run();
            }
        }
    }

    private static void resolveLookTarget(Object owner, SceneDef def, Map<String,Object> d) {
        if (d.containsKey("look-x") && d.containsKey("look-y") && d.containsKey("look-z")) return;
        String token = str(d.get("look-at"));
        Object target = null;
        try {
            if (token.equalsIgnoreCase("player") || token.equalsIgnoreCase("owner")) target = owner;
            else if (!token.isBlank()) target = actorEntity(owner, def, token);
            if (target == null) return;
            Object loc = call0(target, "getLocation");
            d.put("look-x", num(call0(loc, "getX"), 0));
            d.put("look-y", num(call0(loc, "getY"), 0) + num(d.get("look-y-offset"), 1.4));
            d.put("look-z", num(call0(loc, "getZ"), 0));
        } catch (Throwable ignored) {}
    }

    private static void finish(Object owner, SceneRun run) {
        if (RUNS.remove(run.ownerId, run)) {
            cleanupCamera(run);
            send(owner, "Scene complete: §f" + run.def.id);
            if (run.onComplete != null) run.onComplete.run();
        }
    }

    private static void abort(Object owner, SceneRun run, String reason) {
        run.cancelled = true;
        RUNS.remove(run.ownerId, run);
        cleanupCamera(run);
        send(owner, "§cScene failed: §f" + run.def.id + " §8- §7" + reason);
        // Avoid leaving a parent story/cutscene permanently blocked after an authoring/runtime error.
        if (run.onComplete != null) run.onComplete.run();
    }

    private static void cleanupCamera(SceneRun run) {
        for (UUID id : new ArrayList<>(run.cameraParticipants)) {
            Object p = online(id);
            if (p != null) invokeCorePrivateQuiet("restoreCamera", p);
        }
        run.cameraParticipants.clear();
    }

    private static Set<UUID> collectParticipants(Object owner, SceneDef def) {
        LinkedHashSet<UUID> out = new LinkedHashSet<>();
        try {
            UUID ownerId = uuid(owner);
            out.add(ownerId);
            if (def.ownerOnly || def.audienceRadius <= 0) return out;
            Object world = call0(owner, "getWorld");
            Object oloc = call0(owner, "getLocation");
            Object list = call0(world, "getPlayers");
            if (list instanceof Iterable<?> it) {
                double r2 = def.audienceRadius * def.audienceRadius;
                for (Object p : it) {
                    try {
                        Object ploc = call0(p, "getLocation");
                        double dist = num(call(oloc, "distanceSquared", ploc), Double.MAX_VALUE);
                        if (dist <= r2) out.add(uuid(p));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static List<Object> onlineParticipants(SceneRun run) {
        ArrayList<Object> list = new ArrayList<>();
        for (UUID id : run.participants) { Object p = online(id); if (p != null) list.add(p); }
        return list;
    }

    private interface PlayerAction { void apply(Object player); }
    private static void forEachParticipant(SceneRun run, PlayerAction action) {
        for (UUID id : run.participants) {
            Object p = online(id);
            if (p != null) try { action.apply(p); } catch (Throwable ignored) {}
        }
    }

    private static Object actorEntity(Object owner, SceneDef def, String token) {
        String actor = resolveActor(def, token);
        try {
            Method m = declaredMethod(NarrativeActors.class, "findActorEntity", Object.class, String.class);
            Object found = m.invoke(null, owner, actor);
            if (found != null) return found;
            if (actor.contains(".")) {
                String shortName = actor.substring(actor.lastIndexOf('.')+1).replace('_',' ');
                found = m.invoke(null, owner, shortName);
                if (found != null) return found;
            }
        } catch (Throwable t) { log("Actor lookup failed: " + actor, t); }
        return null;
    }

    private static String resolveActor(SceneDef def, String token) {
        if (token == null) return "";
        String mapped = def.cast.get(token);
        return mapped == null ? token : mapped;
    }

    private static String speakerName(String actorId, String fallback) {
        if (actorId.equalsIgnoreCase("narrator")) return "Narrator";
        try {
            Method m = declaredMethod(NarrativeActors.class, "profile", String.class);
            Object p = m.invoke(null, actorId);
            if (p != null) {
                String n = str(field(p, "displayName"));
                if (!n.isBlank()) return n;
            }
        } catch (Throwable ignored) {}
        String s = actorId.isBlank() ? fallback : actorId;
        if (s.contains(".")) s = s.substring(s.lastIndexOf('.')+1);
        return human(s);
    }

    private static void invokeDirectorBlocking(String method, Object owner, Object actor, Map<String,Object> data, Runnable done) throws Exception {
        Method m = declaredMethod(NarrativeDirector.class, method, Object.class, Object.class, Map.class, Runnable.class);
        m.invoke(null, owner, actor, data, done);
    }

    // -------- Commands --------

    private static void registerCommand() throws Exception {
        Object cmd = call(plugin, "getCommand", "scene");
        if (cmd == null) return;
        ClassLoader cl = cl();
        Class<?> executor = Class.forName("org.bukkit.command.CommandExecutor", true, cl);
        Class<?> tab = Class.forName("org.bukkit.command.TabCompleter", true, cl);
        commandProxy = Proxy.newProxyInstance(cl, new Class<?>[]{executor, tab}, (proxy, method, args) -> {
            String n = method.getName();
            if (n.equals("onCommand")) {
                Object sender = args[0];
                String[] a = args[3] instanceof String[] x ? x : new String[0];
                try { return handleCommand(sender, a); }
                catch (Throwable t) { send(sender, "§cScene command failed: " + shortError(t)); log("/scene failed", t); return true; }
            }
            if (n.equals("onTabComplete")) {
                String[] a = args[3] instanceof String[] x ? x : new String[0];
                return tabComplete(a);
            }
            if (method.getReturnType() == boolean.class) return false;
            return null;
        });
        call(cmd, "setExecutor", commandProxy);
        call(cmd, "setTabCompleter", commandProxy);
    }

    private static boolean handleCommand(Object sender, String[] args) throws Exception {
        if (args.length == 0 || eq(args[0], "help")) { help(sender); return true; }
        String sub = lower(args[0]);
        switch (sub) {
            case "list" -> {
                send(sender, "--- Staged Scenes ---");
                synchronized (NarrativeScenes.class) {
                    if (SCENES.isEmpty()) send(sender, "No staged scenes loaded.");
                    for (SceneDef d : SCENES.values()) send(sender, "§f" + d.id + " §8- §7" + d.steps.size() + " steps, " + d.cast.size() + " actors");
                }
            }
            case "inspect", "timeline" -> {
                if (args.length < 2) { send(sender, "Usage: /scene " + sub + " <id>"); return true; }
                inspect(sender, args[1]);
            }
            case "validate" -> validate(sender);
            case "reload" -> { reload(); send(sender, "Staged scenes reloaded: §f" + SCENES.size() + "§7, errors=§f" + ERRORS.size()); }
            case "play" -> {
                if (args.length < 2) { send(sender, "Usage: /scene play <id> [player]"); return true; }
                Object player = args.length >= 3 ? onlineByName(args[2]) : requirePlayer(sender);
                if (player == null) { send(sender, "Player not found."); return true; }
                startScene(player, args[1], null);
            }
            case "stop" -> {
                Object player = args.length >= 2 ? onlineByName(args[1]) : requirePlayer(sender);
                if (player == null) { send(sender, "Player not found."); return true; }
                UUID id = uuid(player); SceneRun run = RUNS.remove(id);
                if (run == null) send(sender, "No active staged scene.");
                else { run.cancelled = true; cleanupCamera(run); send(player, "Scene stopped: §f" + run.def.id); if (run.onComplete != null) run.onComplete.run(); }
            }
            case "status" -> {
                Object player = args.length >= 2 ? onlineByName(args[1]) : requirePlayer(sender);
                if (player == null) { send(sender, "Player not found."); return true; }
                status(sender, player);
            }
            case "participants" -> {
                Object player = args.length >= 2 ? onlineByName(args[1]) : requirePlayer(sender);
                if (player == null) { send(sender, "Player not found."); return true; }
                SceneRun run = RUNS.get(uuid(player));
                if (run == null) send(sender, "No active staged scene.");
                else {
                    send(sender, "Participants for §f" + run.def.id + "§7:");
                    for (UUID id : run.participants) { Object p = online(id); send(sender, "- " + (p == null ? id.toString() : playerName(p))); }
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private static void help(Object s) {
        send(s, "--- WorldMemory Scene Director ---");
        send(s, "/scene list");
        send(s, "/scene inspect <id>");
        send(s, "/scene timeline <id>");
        send(s, "/scene play <id> [player]");
        send(s, "/scene stop [player]");
        send(s, "/scene status [player]");
        send(s, "/scene participants [player]");
        send(s, "/scene validate | reload");
        send(s, "Story/cutscene step: §f- type: directed-scene  id: <scene-id>");
    }

    private static void inspect(Object sender, String id) {
        SceneDef d;
        synchronized (NarrativeScenes.class) { d = SCENES.get(id); }
        if (d == null) { send(sender, "Unknown staged scene: " + id); return; }
        send(sender, "--- Scene: §f" + d.id + " §7---");
        send(sender, "Audience: radius=§f" + trim(d.audienceRadius) + "§7 ownerOnly=§f" + d.ownerOnly);
        send(sender, "Cast: §f" + (d.cast.isEmpty() ? "none" : d.cast));
        int i=1;
        for (SceneStep s : d.steps) send(sender, "§8" + (i++) + ". §f" + s.type + " §7" + summary(s));
    }

    private static String summary(SceneStep s) {
        Map<String,Object> d=s.data;
        if (s.type.contains("actor")) return nonBlank(str(d.get("target")), "");
        if (s.type.equals("line") || s.type.equals("actor-line")) return nonBlank(str(d.get("speaker")), "narrator") + ": " + ellipsis(str(d.get("text")), 48);
        if (s.type.equals("dialogue") || s.type.equals("animation")) return str(d.get("id"));
        if (s.type.contains("camera")) return nonBlank(str(d.get("action")), "move") + " " + longVal(d.get("duration-ticks"),0) + "t";
        if (s.type.equals("wait")) return longVal(d.get("ticks"), longVal(d.get("duration-ticks"),20)) + "t";
        return "";
    }

    private static void status(Object sender, Object player) throws Exception {
        SceneRun r = RUNS.get(uuid(player));
        if (r == null) { send(sender, "No active staged scene for " + playerName(player) + "."); return; }
        send(sender, "--- Scene Status: §f" + playerName(player) + " §7---");
        send(sender, "Scene: §f" + r.def.id);
        send(sender, "Step: §f" + r.index + "/" + r.def.steps.size() + " §8(" + r.currentType + ")");
        send(sender, "Speaker: §f" + nonBlank(r.currentSpeaker, "-") );
        send(sender, "Participants: §f" + r.participants.size());
        send(sender, "Camera participants: §f" + r.cameraParticipants.size());
    }

    private static void validate(Object sender) {
        synchronized (NarrativeScenes.class) {
            send(sender, "--- Staged Scene Validation ---");
            send(sender, "Scenes: §f" + SCENES.size() + "§7 | Errors: §f" + ERRORS.size());
            if (ERRORS.isEmpty()) send(sender, "§aResult: HEALTHY");
            else for (int i=0;i<Math.min(20,ERRORS.size());i++) send(sender, "§c" + (i+1) + ". §7" + ERRORS.get(i));
        }
    }

    private static List<String> tabComplete(String[] args) {
        if (args.length <= 1) return match(args.length==0?"":args[0], List.of("help","list","inspect","timeline","play","stop","status","participants","validate","reload"));
        if (args.length == 2 && Set.of("inspect","timeline","play").contains(lower(args[0]))) {
            synchronized (NarrativeScenes.class) { return match(args[1], new ArrayList<>(SCENES.keySet())); }
        }
        return List.of();
    }

    // -------- Loading / validation --------

    private static void loadScene(File file) throws Exception {
        Object yaml = loadYaml(file);
        String id = yamlString(yaml, "id", stripExt(file.getName()));
        if (id.isBlank()) throw new IllegalArgumentException("missing id");
        SceneDef d = new SceneDef(id);
        d.audienceRadius = yamlDouble(yaml, "audience.radius", 10.0);
        d.ownerOnly = yamlBool(yaml, "audience.owner-only", false);
        Object cast = section(yaml, "cast");
        if (cast != null) {
            for (String key : keys(cast)) {
                String value = yamlString(cast, key, "");
                if (value.isBlank()) {
                    Object child = section(cast, key);
                    if (child != null) value = yamlString(child, "actor", yamlString(child, "target", ""));
                }
                if (!value.isBlank()) d.cast.put(key, value);
            }
        }
        for (Map<String,Object> raw : mapList(yaml, "steps")) {
            String type = lower(str(raw.get("type")));
            if (type.isBlank()) { ERRORS.add(id + ": step missing type"); continue; }
            d.steps.add(new SceneStep(type, new LinkedHashMap<>(raw)));
        }
        if (SCENES.put(id, d) != null) ERRORS.add(id + ": duplicate scene id");
    }

    private static void validateAll() {
        Set<String> supported = Set.of("line","actor-line","wait","dialogue","actor-enter","actor-move","actor-exit","actor-look","look","camera","sync-camera","animation","title","sound","command","refresh-audience","refresh-participants","end");
        for (SceneDef d : SCENES.values()) {
            if (d.steps.isEmpty()) ERRORS.add(d.id + ": no steps");
            for (int i=0;i<d.steps.size();i++) {
                SceneStep s=d.steps.get(i); Map<String,Object> x=s.data;
                if (!supported.contains(s.type)) ERRORS.add(d.id + " step " + (i+1) + ": unsupported type " + s.type);
                if (s.type.startsWith("actor-") || s.type.equals("look")) {
                    String t=str(x.get("target"));
                    if (t.isBlank()) ERRORS.add(d.id + " step " + (i+1) + ": missing actor target");
                    else if (!d.cast.containsKey(t) && !t.contains(".")) ERRORS.add(d.id + " step " + (i+1) + ": cast alias not found: " + t);
                }
                if (s.type.equals("dialogue")) {
                    String q=nonBlank(str(x.get("id")),str(x.get("dialogue")));
                    if (q.isBlank()) ERRORS.add(d.id + " step " + (i+1) + ": missing dialogue id");
                    else if (!coreContains("DIALOGUES",q)) ERRORS.add(d.id + " step " + (i+1) + ": unknown dialogue " + q);
                }
                if ((s.type.equals("actor-move") || s.type.equals("actor-enter") || s.type.equals("actor-exit")) && hasAnyCoord(x) && !hasLocation(x)) {
                    ERRORS.add(d.id + " step " + (i+1) + ": movement requires x, y and z");
                }
            }
        }
    }

    private static boolean coreContains(String field, String key) {
        try { Object m=coreField(field); return m instanceof Map<?,?> map && map.containsKey(key); }
        catch (Throwable ignored) { return true; }
    }

    private static void ensureExample() {
        try {
            File f = new File(scenesDir(), "example_multi_actor.yml");
            if (f.exists()) return;
            Object in = plugin.getClass().getMethod("getResource", String.class).invoke(plugin, "content/narrative/scenes/example_multi_actor.yml");
            if (!(in instanceof java.io.InputStream stream)) return;
            f.getParentFile().mkdirs();
            Files.copy(stream, f.toPath());
            stream.close();
        } catch (Throwable ignored) {}
    }

    // -------- Reflection / utility --------

    private static Object coreField(String name) throws Exception {
        Field f = NarrativeCore.class.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    private static Object invokeCorePrivate(String name, Object... args) throws Exception {
        Method m = findCompatibleDeclared(NarrativeCore.class, name, args);
        if (m == null) throw new NoSuchMethodException("NarrativeCore."+name);
        return m.invoke(null,args);
    }
    private static void invokeCorePrivateQuiet(String name, Object... args) { try { invokeCorePrivate(name,args); } catch(Throwable t){ log("NarrativeCore."+name+" failed",t);} }
    private static Method declaredMethod(Class<?> c, String n, Class<?>... types) throws Exception { Method m=c.getDeclaredMethod(n,types); m.setAccessible(true); return m; }
    private static Method findCompatibleDeclared(Class<?> c,String n,Object[] args){
        for(Method m:c.getDeclaredMethods()) if(m.getName().equals(n)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);return m;} return null;
    }
    private static Object field(Object o,String n) throws Exception {
        if(o==null)return null;
        Class<?> c=o.getClass();
        while(c!=null){ try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();} }
        try{Method m=o.getClass().getMethod(n);return m.invoke(o);}catch(Throwable ignored){}
        return null;
    }
    private static Object call0(Object o,String n) throws Exception { return call(o,n); }
    private static Object call(Object o,String n,Object... args) throws Exception { if(o==null)return null; Method m=find(o.getClass(),n,args); if(m==null)throw new NoSuchMethodException(o.getClass().getName()+"."+n); return m.invoke(o,args); }
    private static Object tryInvoke(Object o,String n,Object... args){try{return call(o,n,args);}catch(Throwable ignored){return null;}}
    private static Method find(Class<?> c,String n,Object[] args){ for(Method m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args))return m; for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args)){m.setAccessible(true);return m;} return null; }
    private static boolean compatible(Class<?>[] p,Object[] a){ if(p.length!=a.length)return false; for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;continue;} if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;} return true; }
    private static Class<?> wrap(Class<?> c){ if(!c.isPrimitive())return c; if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;if(c==char.class)return Character.class;return c; }
    private static ClassLoader cl(){ return plugin==null?NarrativeScenes.class.getClassLoader():plugin.getClass().getClassLoader(); }
    private static Object loadYaml(File f)throws Exception{Class<?> y=Class.forName("org.bukkit.configuration.file.YamlConfiguration",true,cl());return y.getMethod("loadConfiguration",File.class).invoke(null,f);}
    private static Object section(Object y,String p){return tryInvoke(y,"getConfigurationSection",p);}
    @SuppressWarnings("unchecked") private static Set<String> keys(Object s){Object k=tryInvoke(s,"getKeys",false);return k instanceof Set<?> set?(Set<String>)set:Set.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> mapList(Object y,String p){Object v=tryInvoke(y,"getMapList",p);if(!(v instanceof List<?> l))return List.of();List<Map<String,Object>>o=new ArrayList<>();for(Object e:l)if(e instanceof Map<?,?>m){LinkedHashMap<String,Object>x=new LinkedHashMap<>();m.forEach((k,val)->x.put(String.valueOf(k),val));o.add(x);}return o;}
    private static String yamlString(Object y,String p,String d){Object v=tryInvoke(y,"getString",p,d);return v==null?d:String.valueOf(v);}
    private static double yamlDouble(Object y,String p,double d){Object v=tryInvoke(y,"getDouble",p,d);return num(v,d);}
    private static boolean yamlBool(Object y,String p,boolean d){Object v=tryInvoke(y,"getBoolean",p,d);return bool(v,d);}
    private static File dataFolder(){try{Object f=call0(plugin,"getDataFolder");return f instanceof File x?x:new File("plugins/WorldMemory");}catch(Throwable t){return new File("plugins/WorldMemory");}}
    private static File scenesDir(){return new File(dataFolder(),"content/narrative/scenes");}
    private static void collectYaml(File d,List<File> out){File[] fs=d.listFiles();if(fs==null)return;Arrays.sort(fs,Comparator.comparing(File::getName));for(File f:fs){if(f.isDirectory())collectYaml(f,out);else if(f.getName().endsWith(".yml")||f.getName().endsWith(".yaml"))out.add(f);}}
    private static Object online(UUID id){try{Object server=call0(plugin,"getServer");return call(server,"getPlayer",id);}catch(Throwable t){return null;}}
    private static Object onlineByName(String n){try{Object server=call0(plugin,"getServer");Object p=tryInvoke(server,"getPlayerExact",n);return p!=null?p:tryInvoke(server,"getPlayer",n);}catch(Throwable t){return null;}}
    private static Object requirePlayer(Object s){try{Class<?> p=Class.forName("org.bukkit.entity.Player",true,cl());return p.isInstance(s)?s:null;}catch(Throwable t){return null;}}
    private static UUID uuid(Object p)throws Exception{Object v=call0(p,"getUniqueId");return (UUID)v;}
    private static String playerName(Object p){try{return str(call0(p,"getName"));}catch(Throwable t){return "player";}}
    private static void schedule(Runnable r,long ticks){if(ticks<=0){r.run();return;}try{Object server=call0(plugin,"getServer");Object sched=call0(server,"getScheduler");call(sched,"runTaskLater",plugin,r,ticks);}catch(Throwable t){log("Schedule failed",t);r.run();}}
    private static void send(Object s,String m){sendRaw(s,PREFIX+m);} private static void warn(Object s,String m){send(s,"§e"+m);}
    private static void sendRaw(Object s,String m){try{call(s,"sendMessage",m);}catch(Throwable ignored){}}
    private static Logger logger(){try{Object l=call0(plugin,"getLogger");if(l instanceof Logger x)return x;}catch(Throwable ignored){}return Logger.getLogger("WorldMemory");}
    private static void log(String m,Throwable t){logger().log(Level.WARNING,"[NarrativeScenes] "+m,t);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){return o instanceof Map<?,?>m?(Map<String,Object>)m:Map.of();}
    private static boolean hasLocation(Map<String,Object>d){return d.containsKey("x")&&d.containsKey("y")&&d.containsKey("z");}
    private static boolean hasAnyCoord(Map<String,Object>d){return d.containsKey("x")||d.containsKey("y")||d.containsKey("z");}
    private static String str(Object o){return o==null?"":String.valueOf(o).trim();}
    private static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static boolean eq(String a,String b){return a!=null&&a.equalsIgnoreCase(b);}
    private static String nonBlank(String a,String b){return a!=null&&!a.isBlank()?a:(b==null?"":b);}
    private static boolean bool(Object o,boolean d){if(o==null)return d;if(o instanceof Boolean b)return b;String s=str(o);return s.equalsIgnoreCase("true")?true:s.equalsIgnoreCase("false")?false:d;}
    private static double num(Object o,double d){if(o instanceof Number n)return n.doubleValue();try{return Double.parseDouble(str(o));}catch(Exception e){return d;}}
    private static long longVal(Object o,long d){if(o instanceof Number n)return n.longValue();try{return Long.parseLong(str(o));}catch(Exception e){return d;}}
    private static long autoLineDuration(String s){return Math.max(35,Math.min(120,25+(s==null?0:s.length()*2L)));}
    private static String trim(double d){if(d==(long)d)return Long.toString((long)d);return String.format(Locale.ROOT,"%.1f",d);}
    private static String human(String s){if(s==null||s.isBlank())return "Unknown";String[]p=s.replace('-','_').split("_");StringBuilder b=new StringBuilder();for(String x:p){if(x.isBlank())continue;if(b.length()>0)b.append(' ');b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));}return b.toString();}
    private static String ellipsis(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,Math.max(0,n-1))+"…";}
    private static String stripExt(String s){int i=s.lastIndexOf('.');return i>0?s.substring(0,i):s;}
    private static String shortError(Throwable t){Throwable x=t;while(x instanceof InvocationTargetException ite&&ite.getCause()!=null)x=ite.getCause();String m=x.getMessage();return x.getClass().getSimpleName()+(m==null?"":": "+m);}
    private static List<String> match(String q,List<String> opts){String n=lower(q);ArrayList<String>o=new ArrayList<>();for(String s:opts)if(lower(s).startsWith(n))o.add(s);return o;}

    private static final class SceneDef {
        final String id; final Map<String,String> cast=new LinkedHashMap<>(); final List<SceneStep> steps=new ArrayList<>();
        double audienceRadius=10; boolean ownerOnly=false;
        SceneDef(String id){this.id=id;}
    }
    private record SceneStep(String type, Map<String,Object> data) {}
    private static final class SceneRun {
        final UUID ownerId; final SceneDef def; final Runnable onComplete;
        final LinkedHashSet<UUID> participants=new LinkedHashSet<>(); final Set<UUID> cameraParticipants=ConcurrentHashMap.newKeySet();
        int index=0; volatile boolean cancelled=false; volatile String currentType=""; volatile String currentSpeaker="";
        SceneRun(UUID ownerId,SceneDef def,Runnable onComplete){this.ownerId=ownerId;this.def=def;this.onComplete=onComplete;}
    }
}
