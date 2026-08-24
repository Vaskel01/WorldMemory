package com.yourstudio.worldmemory.narrative;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public final class NarrativeDirector {
    private static final String PREFIX = "§6[WorldMemory] §r";
    private static final String ADMIN = "worldmemory.admin";
    private static volatile Object plugin;
    private static volatile Object commandProxy;
    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();
    private static final Set<UUID> ACTIVE_CUSTOM = ConcurrentHashMap.newKeySet();
    private static final DecimalFormat DF = new DecimalFormat("0.##");
    private static final DateTimeFormatter BACKUP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile boolean started;
    private static volatile boolean warnedLetterbox;

    private NarrativeDirector() {}

    public static synchronized void startup(Object pluginObj) {
        if (started || pluginObj == null) return;
        plugin = pluginObj;
        started = true;
        try {
            Files.createDirectories(new File(dataFolder(), "narrative/editor").toPath());
            registerCommand();
            logger().info("NarrativeDirector alpha.47 cinematic editor ready.");
        } catch (Throwable t) {
            logger().warning("Could not start NarrativeDirector: " + shortError(t));
        }
    }

    public static synchronized void shutdown() {
        started = false;
        DRAFTS.clear();
        ACTIVE_CUSTOM.clear();
        commandProxy = null;
        plugin = null;
    }

    private static void registerCommand() throws Exception {
        Object cmd = call(plugin, "getCommand", "cinematic");
        if (cmd == null) {
            logger().warning("/cinematic is missing from plugin.yml; editor command not registered.");
            return;
        }
        ClassLoader cl = plugin.getClass().getClassLoader();
        Class<?> exec = Class.forName("org.bukkit.command.CommandExecutor", false, cl);
        Class<?> tab = Class.forName("org.bukkit.command.TabCompleter", false, cl);
        commandProxy = Proxy.newProxyInstance(cl, new Class<?>[]{exec, tab}, (p, m, a) -> {
            if (m.getName().equals("onCommand")) {
                Object sender = a[0];
                String[] args = (String[]) a[3];
                try { return handleCommand(sender, args); }
                catch (Throwable t) { send(sender, "§cCinematic command failed: " + shortError(t)); return true; }
            }
            if (m.getName().equals("onTabComplete")) {
                try { return tabComplete(a[0], (String[]) a[3]); }
                catch (Throwable t) { return List.of(); }
            }
            if (m.getName().equals("toString")) return "WorldMemoryNarrativeDirector";
            return primitiveDefault(m.getReturnType());
        });
        call(cmd, "setExecutor", commandProxy);
        call(cmd, "setTabCompleter", commandProxy);
    }

    private static boolean handleCommand(Object sender, String[] args) throws Exception {
        requireAdmin(sender);
        if (args.length == 0 || eq(args[0], "help")) { help(sender); return true; }
        String sub = lower(args[0]);
        switch (sub) {
            case "list" -> listCutscenes(sender);
            case "validate" -> validateCinematics(sender);
            case "timeline", "inspect" -> {
                if (args.length < 2) { send(sender, "§eUsage: /cinematic timeline <cutscene-id>"); return true; }
                timeline(sender, args[1]);
            }
            case "play", "preview" -> {
                Object player = requirePlayer(sender);
                if (args.length < 2) { send(sender, "§eUsage: /cinematic play <cutscene-id> [step]"); return true; }
                int step = args.length >= 3 ? parseInt(args[2], 1) : 1;
                playFrom(player, args[1], step);
            }
            case "stop" -> stop(sender);
            case "mark" -> {
                Object player = requirePlayer(sender);
                if (args.length < 2) { send(sender, "§eUsage: /cinematic mark <name>"); return true; }
                putMarker(player, args[1]);
            }
            case "marks" -> showMarkers(requirePlayer(sender));
            case "unmark" -> {
                Object player = requirePlayer(sender);
                if (args.length < 2) { send(sender, "§eUsage: /cinematic unmark <name>"); return true; }
                removeMarker(player, args[1]);
            }
            case "camera" -> {
                Object player = requirePlayer(sender);
                if (args.length < 2) { send(sender, "§eUsage: /cinematic camera <marker> [duration-ticks] [easing]"); return true; }
                Marker m = marker(player, args[1]);
                if (m == null) { send(sender, "§cUnknown marker: " + args[1]); return true; }
                long ticks = args.length >= 3 ? Math.max(0, parseLong(args[2], 40)) : 40;
                String easing = args.length >= 4 ? args[3] : "smooth";
                emitCameraYaml(sender, m, ticks, easing);
            }
            case "lookat" -> {
                Object player = requirePlayer(sender);
                if (args.length < 3) { send(sender, "§eUsage: /cinematic lookat <camera-marker> <target-marker> [duration-ticks] [easing]"); return true; }
                Marker cam = marker(player, args[1]);
                Marker target = marker(player, args[2]);
                if (cam == null || target == null) { send(sender, "§cBoth markers must exist."); return true; }
                long ticks = args.length >= 4 ? Math.max(0, parseLong(args[3], 40)) : 40;
                String easing = args.length >= 5 ? args[4] : "smooth";
                emitLookAtYaml(sender, cam, target, ticks, easing);
            }
            case "record" -> record(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private static void help(Object sender) {
        send(sender, "§6--- WorldMemory Cinematic Editor ---");
        send(sender, "§e/cinematic list | validate");
        send(sender, "§e/cinematic timeline <id>");
        send(sender, "§e/cinematic play <id> [step]");
        send(sender, "§e/cinematic stop");
        send(sender, "§e/cinematic mark <name> §7- save your camera position");
        send(sender, "§e/cinematic marks | unmark <name>");
        send(sender, "§e/cinematic camera <marker> [ticks] [easing]");
        send(sender, "§e/cinematic lookat <camera> <target> [ticks] [easing]");
        send(sender, "§e/cinematic record start <id>");
        send(sender, "§e/cinematic record add camera|wait|dialogue|animation|sound|title|player-move|npc-move|npc-look ...");
        send(sender, "§e/cinematic record show|undo|export|cancel");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> cutscenes() throws Exception {
        Field f = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore").getDeclaredField("CUTSCENES");
        f.setAccessible(true);
        return (Map<String,Object>) f.get(null);
    }

    private static void validateCinematics(Object sender) throws Exception {
        int errors = 0, custom = 0;
        send(sender, "§6--- Cinematic Validation ---");
        for (Map.Entry<String,Object> en : cutscenes().entrySet()) {
            List<Object> ss = steps(en.getValue());
            for (int i=0;i<ss.size();i++) {
                String t = lower(stepType(ss.get(i)));
                Map<String,Object> d = stepData(ss.get(i));
                String problem = null;
                switch (t) {
                    case "player-move" -> { custom++; if (!(d.containsKey("x")&&d.containsKey("y")&&d.containsKey("z"))) problem="requires x, y and z"; }
                    case "npc-move" -> { custom++; if (str(d.getOrDefault("target",d.get("npc"))).isBlank()) problem="requires target"; else if (!(d.containsKey("x")&&d.containsKey("y")&&d.containsKey("z"))) problem="requires destination x, y and z"; }
                    case "npc-look" -> { custom++; if (str(d.getOrDefault("target",d.get("npc"))).isBlank()) problem="requires target"; else if (!(d.containsKey("look-target") || (d.containsKey("look-x")&&d.containsKey("look-y")&&d.containsKey("look-z")))) problem="requires look-target or look-x/look-y/look-z"; }
                    case "look-at" -> { custom++; if (!(d.containsKey("look-target") || d.containsKey("target") || (d.containsKey("look-x")&&d.containsKey("look-y")&&d.containsKey("look-z")) || (d.containsKey("x")&&d.containsKey("y")&&d.containsKey("z")))) problem="requires target coordinates or target entity"; }
                    case "fade", "letterbox" -> custom++;
                }
                if (problem != null) { errors++; send(sender,"§c"+en.getKey()+" step "+(i+1)+" ["+t+"]: "+problem); }
            }
        }
        if (errors==0) send(sender,"§aCinematic extensions healthy. §7Custom steps checked: §f"+custom);
        else send(sender,"§cValidation failed with "+errors+" cinematic extension error(s).");
        send(sender,"§7Use §e/narrative validate §7for core story/dialogue/reference validation.");
    }

    private static void listCutscenes(Object sender) throws Exception {
        Map<String,Object> map = cutscenes();
        send(sender, "§6--- Cutscenes (" + map.size() + ") ---");
        map.keySet().stream().sorted().limit(50).forEach(id -> send(sender, "§e" + id));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> steps(Object def) throws Exception {
        return (List<Object>) call(def, "steps");
    }

    private static String stepType(Object step) throws Exception { return String.valueOf(call(step, "type")); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> stepData(Object step) throws Exception { return (Map<String,Object>) call(step, "data"); }

    private static void timeline(Object sender, String id) throws Exception {
        Object def = cutscenes().get(id);
        if (def == null) { send(sender, "§cUnknown cutscene: " + id); return; }
        List<Object> steps = steps(def);
        send(sender, "§6--- Timeline: " + id + " ---");
        long knownTicks = 0;
        boolean unknown = false;
        for (int i = 0; i < steps.size(); i++) {
            Object s = steps.get(i);
            String type = stepType(s);
            Map<String,Object> data = stepData(s);
            DurationInfo di = durationInfo(type, data);
            String at = unknown ? "?" : fmtTicks(knownTicks);
            String dur = di.blockingUnknown ? "blocking" : (di.ticks > 0 ? fmtTicks(di.ticks) : "instant");
            send(sender, "§7" + (i+1) + ". §e" + type + " §8@ " + at + " §7(" + dur + ") §f" + summary(type, data));
            if (di.blockingUnknown) unknown = true;
            else if (!unknown) knownTicks += di.ticks;
        }
        send(sender, "§7Steps: §f" + steps.size() + " §8| §7Known minimum: §f" + fmtTicks(knownTicks) + (unknown ? " §8(+ blocking steps)" : ""));
        send(sender, "§7Preview from a step: §e/cinematic play " + id + " <step>");
    }

    private static DurationInfo durationInfo(String type, Map<String,Object> data) {
        String t = lower(type);
        if (t.equals("dialogue") || t.equals("animation")) return new DurationInfo(0, true);
        if (t.equals("wait") || t.equals("camera") || t.equals("player-move") || t.equals("npc-move") || t.equals("npc-look") || t.equals("look-at") || t.equals("fade")) {
            long ticks = Math.max(0, parseLong(data.getOrDefault("duration-ticks", data.getOrDefault("ticks", 0)), 0));
            return new DurationInfo(ticks, false);
        }
        return new DurationInfo(0, false);
    }

    private static String summary(String type, Map<String,Object> d) {
        String t = lower(type);
        if (t.equals("dialogue") || t.equals("animation")) return str(d.get("id"));
        if (t.equals("camera")) return str(d.getOrDefault("action", "move"));
        if (t.equals("npc-move") || t.equals("npc-look")) return str(d.getOrDefault("target", d.get("npc")));
        if (t.equals("sound")) return str(d.getOrDefault("sound", ""));
        if (t.equals("title")) return str(d.getOrDefault("title", ""));
        return "";
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static void playFrom(Object player, String id, int oneBasedStep) throws Exception {
        Object def = cutscenes().get(id);
        if (def == null) { send(player, "§cUnknown cutscene: " + id); return; }
        List<Object> steps = steps(def);
        if (steps.isEmpty()) { send(player, "§cCutscene has no steps."); return; }
        int idx = Math.max(0, Math.min(steps.size()-1, oneBasedStep - 1));
        invokeCorePrivate("stopPlayer", new Class<?>[]{Object.class, boolean.class}, player, false);
        Class<?> defClass = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore$CutsceneDef");
        Class<?> runClass = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore$CutsceneRun");
        Constructor<?> ctor = runClass.getDeclaredConstructor(defClass, int.class, Runnable.class);
        ctor.setAccessible(true);
        Runnable done = () -> { try { send(player, "§aCinematic preview finished."); } catch (Throwable ignored) {} };
        Object run = ctor.newInstance(def, idx, done);
        Field runsField = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore").getDeclaredField("CUTSCENE_RUNS");
        runsField.setAccessible(true);
        Map runs = (Map) runsField.get(null);
        runs.put(uuid(player), run);
        send(player, "§7Previewing §e" + id + " §7from step §f" + (idx+1) + "/" + steps.size() + ".");
        invokeCorePrivate("runCutscene", new Class<?>[]{Object.class, runClass}, player, run);
    }

    private static void stop(Object sender) throws Exception {
        Object player = requirePlayer(sender);
        invokeCorePrivate("stopPlayer", new Class<?>[]{Object.class, boolean.class}, player, false);
        ACTIVE_CUSTOM.remove(uuid(player));
        send(sender, "§aCinematic stopped and player state restored.");
    }

    private static void putMarker(Object player, String name) throws Exception {
        Marker m = markerFromLocation(call(player, "getLocation"));
        Properties p = loadMarkerProperties(uuid(player));
        String key = safeName(name);
        p.setProperty(key + ".world", m.world);
        p.setProperty(key + ".x", String.valueOf(m.x));
        p.setProperty(key + ".y", String.valueOf(m.y));
        p.setProperty(key + ".z", String.valueOf(m.z));
        p.setProperty(key + ".yaw", String.valueOf(m.yaw));
        p.setProperty(key + ".pitch", String.valueOf(m.pitch));
        saveMarkerProperties(uuid(player), p);
        send(player, "§aSaved camera marker §e" + key + "§a at §f" + m.world + " " + DF.format(m.x) + " " + DF.format(m.y) + " " + DF.format(m.z));
    }

    private static void showMarkers(Object player) throws Exception {
        Properties p = loadMarkerProperties(uuid(player));
        TreeSet<String> names = new TreeSet<>();
        for (String k : p.stringPropertyNames()) if (k.endsWith(".world")) names.add(k.substring(0, k.length()-6));
        send(player, "§6--- Camera Markers (" + names.size() + ") ---");
        if (names.isEmpty()) { send(player, "§7No markers. Use §e/cinematic mark <name>§7."); return; }
        for (String n : names) {
            Marker m = marker(player, n);
            send(player, "§e" + n + " §8- §7" + m.world + " " + DF.format(m.x) + " " + DF.format(m.y) + " " + DF.format(m.z));
        }
    }

    private static void removeMarker(Object player, String name) throws Exception {
        Properties p = loadMarkerProperties(uuid(player));
        String n = safeName(name);
        boolean removed = false;
        for (String suffix : List.of(".world", ".x", ".y", ".z", ".yaw", ".pitch")) removed |= p.remove(n + suffix) != null;
        saveMarkerProperties(uuid(player), p);
        send(player, removed ? "§aRemoved marker §e" + n : "§cMarker not found: " + n);
    }

    private static Marker marker(Object player, String name) throws Exception {
        Properties p = loadMarkerProperties(uuid(player));
        String n = safeName(name);
        String world = p.getProperty(n + ".world");
        if (world == null) return null;
        return new Marker(world,
                parseDouble(p.getProperty(n + ".x"), 0), parseDouble(p.getProperty(n + ".y"), 0), parseDouble(p.getProperty(n + ".z"), 0),
                (float)parseDouble(p.getProperty(n + ".yaw"), 0), (float)parseDouble(p.getProperty(n + ".pitch"), 0));
    }

    private static Marker markerFromLocation(Object loc) throws Exception {
        Object world = call(loc, "getWorld");
        return new Marker(String.valueOf(call(world, "getName")),
                num(call(loc, "getX")), num(call(loc, "getY")), num(call(loc, "getZ")),
                ((Number)call(loc, "getYaw")).floatValue(), ((Number)call(loc, "getPitch")).floatValue());
    }

    private static void emitCameraYaml(Object sender, Marker m, long ticks, String easing) {
        sendRaw(sender, "§7- type: camera");
        sendRaw(sender, "§7  action: move");
        emitLoc(sender, m, "§7  ");
        sendRaw(sender, "§7  duration-ticks: " + ticks);
        sendRaw(sender, "§7  easing: " + easing);
    }

    private static void emitLookAtYaml(Object sender, Marker cam, Marker target, long ticks, String easing) {
        float[] yp = lookAngles(cam.x, cam.y, cam.z, target.x, target.y, target.z);
        Marker aimed = new Marker(cam.world, cam.x, cam.y, cam.z, yp[0], yp[1]);
        emitCameraYaml(sender, aimed, ticks, easing);
    }

    private static void emitLoc(Object sender, Marker m, String pre) {
        sendRaw(sender, pre + "world: " + yaml(m.world));
        sendRaw(sender, pre + "x: " + DF.format(m.x));
        sendRaw(sender, pre + "y: " + DF.format(m.y));
        sendRaw(sender, pre + "z: " + DF.format(m.z));
        sendRaw(sender, pre + "yaw: " + DF.format(m.yaw));
        sendRaw(sender, pre + "pitch: " + DF.format(m.pitch));
    }

    private static void record(Object sender, String[] args) throws Exception {
        Object player = requirePlayer(sender);
        UUID u = uuid(player);
        if (args.length < 2) { send(player, "§e/cinematic record start|add|show|undo|export|cancel"); return; }
        String action = lower(args[1]);
        if (action.equals("start")) {
            if (args.length < 3) { send(player, "§eUsage: /cinematic record start <id>"); return; }
            String id = safeId(args[2]);
            DRAFTS.put(u, new Draft(id));
            send(player, "§aStarted cinematic draft §e" + id + "§a.");
            return;
        }
        Draft d = DRAFTS.get(u);
        if (d == null) { send(player, "§cNo active draft. Use /cinematic record start <id>."); return; }
        switch (action) {
            case "show" -> showDraft(player, d);
            case "undo" -> {
                if (d.steps.isEmpty()) send(player, "§7Draft is already empty.");
                else { Map<String,Object> removed = d.steps.remove(d.steps.size()-1); send(player, "§aRemoved last step: §e" + removed.get("type")); }
            }
            case "cancel" -> { DRAFTS.remove(u); send(player, "§aCancelled draft §e" + d.id); }
            case "export" -> exportDraft(player, d, args.length >= 3 && eq(args[2], "force"));
            case "add" -> addDraftStep(player, d, args);
            default -> send(player, "§e/cinematic record start|add|show|undo|export|cancel");
        }
    }

    private static void addDraftStep(Object player, Draft d, String[] args) throws Exception {
        if (args.length < 3) { send(player, "§eUsage: /cinematic record add <type> ..."); return; }
        String type = lower(args[2]);
        LinkedHashMap<String,Object> s = new LinkedHashMap<>();
        s.put("type", type);
        switch (type) {
            case "camera" -> {
                Marker m = markerFromLocation(call(player, "getLocation"));
                s.put("action", d.steps.stream().noneMatch(x -> "camera".equals(x.get("type"))) ? "enter" : "move");
                putLoc(s, m);
                s.put("duration-ticks", args.length >= 4 ? Math.max(0, parseLong(args[3], 40)) : 40);
                s.put("easing", args.length >= 5 ? args[4] : "smooth");
            }
            case "wait" -> s.put("ticks", args.length >= 4 ? Math.max(0, parseLong(args[3], 20)) : 20);
            case "dialogue" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add dialogue <dialogue-id>"); return; }
                s.put("id", args[3]);
            }
            case "animation" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add animation <animation-id> [timeline]"); return; }
                s.put("id", args[3]);
                s.put("timeline", args.length >= 5 ? args[4] : "open");
            }
            case "sound" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add sound <sound> [volume] [pitch]"); return; }
                s.put("sound", args[3]);
                s.put("volume", args.length >= 5 ? parseDouble(args[4], 1.0) : 1.0);
                s.put("pitch", args.length >= 6 ? parseDouble(args[5], 1.0) : 1.0);
            }
            case "title" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add title <text>"); return; }
                s.put("title", join(args, 3));
            }
            case "player-move" -> {
                Marker m = markerFromLocation(call(player, "getLocation"));
                putLoc(s, m);
                s.put("duration-ticks", args.length >= 4 ? Math.max(0, parseLong(args[3], 20)) : 20);
                s.put("easing", args.length >= 5 ? args[4] : "smooth");
            }
            case "npc-move" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add npc-move <npc-name-or-tag> [ticks]"); return; }
                s.put("target", args[3]);
                Marker m = markerFromLocation(call(player, "getLocation"));
                putLoc(s, m);
                s.put("duration-ticks", args.length >= 5 ? Math.max(0, parseLong(args[4], 20)) : 20);
                s.put("easing", args.length >= 6 ? args[5] : "smooth");
            }
            case "npc-look" -> {
                if (args.length < 4) { send(player, "§eUsage: ... add npc-look <npc-name-or-tag> [ticks]"); return; }
                s.put("target", args[3]);
                Marker at = markerFromLocation(call(player, "getLocation"));
                s.put("look-x", at.x); s.put("look-y", at.y); s.put("look-z", at.z);
                s.put("duration-ticks", args.length >= 5 ? Math.max(0, parseLong(args[4], 10)) : 10);
            }
            case "fade" -> {
                s.put("mode", args.length >= 4 ? args[3] : "out");
                s.put("duration-ticks", args.length >= 5 ? Math.max(0, parseLong(args[4], 20)) : 20);
            }
            case "letterbox" -> s.put("action", args.length >= 4 ? args[3] : "on");
            default -> { send(player, "§cUnsupported recorder step type: " + type); return; }
        }
        d.steps.add(s);
        send(player, "§aAdded step §f" + d.steps.size() + "§a: §e" + type);
    }

    private static void putLoc(Map<String,Object> s, Marker m) {
        s.put("world", m.world); s.put("x", m.x); s.put("y", m.y); s.put("z", m.z); s.put("yaw", m.yaw); s.put("pitch", m.pitch);
    }

    private static void showDraft(Object player, Draft d) {
        send(player, "§6--- Draft: " + d.id + " ---");
        if (d.steps.isEmpty()) { send(player, "§7No steps recorded yet."); return; }
        for (int i=0;i<d.steps.size();i++) {
            Map<String,Object> s = d.steps.get(i);
            send(player, "§7" + (i+1) + ". §e" + s.get("type") + " §f" + summary(String.valueOf(s.get("type")), s));
        }
    }

    private static void exportDraft(Object player, Draft d, boolean force) throws Exception {
        File dir = new File(dataFolder(), "content/cutscenes");
        Files.createDirectories(dir.toPath());
        File target = new File(dir, d.id.replace('.', '_') + ".yml");
        if (target.exists() && !force) {
            send(player, "§cThat file already exists: " + target.getName());
            send(player, "§7Use §e/cinematic record export force §7to back it up and replace it.");
            return;
        }
        if (target.exists()) {
            File backupDir = new File(dataFolder(), "backups/narrative-editor/" + BACKUP_FMT.format(LocalDateTime.now()));
            Files.createDirectories(backupDir.toPath());
            Files.copy(target.toPath(), new File(backupDir, target.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String yaml = draftYaml(d);
        Path tmp = new File(dir, "." + target.getName() + ".tmp").toPath();
        Files.writeString(tmp, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        send(player, "§aExported §e" + d.id + " §ato §f" + target.getName());
        send(player, "§7Run §e/narrative validate §7then §e/narrative reload§7.");
    }

    private static String draftYaml(Draft d) {
        StringBuilder b = new StringBuilder();
        b.append("id: ").append(yaml(d.id)).append("\n\nsteps:\n");
        for (Map<String,Object> s : d.steps) {
            boolean first = true;
            for (Map.Entry<String,Object> e : s.entrySet()) {
                if (first) { b.append("  - ").append(e.getKey()).append(": ").append(yamlValue(e.getValue())).append("\n"); first=false; }
                else b.append("    ").append(e.getKey()).append(": ").append(yamlValue(e.getValue())).append("\n");
            }
            b.append("\n");
        }
        if (d.steps.isEmpty() || !"end".equals(d.steps.get(d.steps.size()-1).get("type"))) b.append("  - type: end\n");
        return b.toString();
    }

    private static String yamlValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return yaml(String.valueOf(v));
    }

    // Called from NarrativeCore.executeCutsceneStep via alpha.47 bytecode hook.
    // null = not handled; false = handled immediate; true = handled blocking.
    public static Boolean handleCutsceneStep(Object player, Object run, Object step) {
        try {
            String type = lower(String.valueOf(call(step, "type")));
            @SuppressWarnings("unchecked") Map<String,Object> data = (Map<String,Object>) call(step, "data");
            return switch (type) {
                case "player-move" -> { moveObject(player, player, data, () -> advanceCutscene(player, run)); yield Boolean.TRUE; }
                case "look-at" -> { lookAt(player, player, data, () -> advanceCutscene(player, run)); yield Boolean.TRUE; }
                case "npc-move" -> {
                    Object ent = findEntity(player, str(data.getOrDefault("target", data.get("npc"))));
                    if (ent == null) throw new IllegalStateException("NPC/entity not found: " + data.get("target"));
                    moveObject(player, ent, data, () -> advanceCutscene(player, run)); yield Boolean.TRUE;
                }
                case "npc-look" -> {
                    Object ent = findEntity(player, str(data.getOrDefault("target", data.get("npc"))));
                    if (ent == null) throw new IllegalStateException("NPC/entity not found: " + data.get("target"));
                    lookAt(player, ent, data, () -> advanceCutscene(player, run)); yield Boolean.TRUE;
                }
                case "fade" -> { fade(player, data, () -> advanceCutscene(player, run)); yield Boolean.TRUE; }
                case "letterbox" -> { letterbox(player, data); yield Boolean.FALSE; }
                default -> null;
            };
        } catch (Throwable t) {
            logger().warning("Cinematic custom step failed: " + shortError(t));
            try { send(player, "§cCinematic step failed: " + shortError(t)); } catch (Throwable ignored) {}
            return null; // let core surface unknown-step error instead of silently hanging
        }
    }

    private static void advanceCutscene(Object player, Object run) {
        try {
            Class<?> runClass = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore$CutsceneRun");
            invokeCorePrivate("advanceCutscene", new Class<?>[]{Object.class, runClass}, player, run);
        } catch (Throwable t) { logger().warning("Could not advance cinematic: " + shortError(t)); }
    }

    private static void moveObject(Object player, Object entity, Map<String,Object> data, Runnable done) throws Exception {
        Object start = call(call(entity, "getLocation"), "clone");
        Object dest = destination(entity, data, start);
        long ticks = Math.max(0, parseLong(data.getOrDefault("duration-ticks", data.getOrDefault("ticks", 20)), 20));
        String easing = lower(str(data.getOrDefault("easing", "smooth")));
        if (ticks <= 0) { call(entity, "teleport", dest); done.run(); return; }
        UUID pu = uuid(player); ACTIVE_CUSTOM.add(pu);
        tweenTeleport(player, entity, start, dest, 1, ticks, easing, () -> { ACTIVE_CUSTOM.remove(pu); done.run(); });
    }

    private static void tweenTeleport(Object player, Object entity, Object start, Object dest, long tick, long total, String easing, Runnable done) {
        if (!ACTIVE_CUSTOM.contains(safeUuid(player))) return;
        try {
            double p = Math.min(1.0, tick / (double)total);
            double e = easing.equals("linear") ? p : p*p*(3.0 - 2.0*p);
            Object loc = call(start, "clone");
            call(loc, "setX", lerp(num(call(start,"getX")), num(call(dest,"getX")), e));
            call(loc, "setY", lerp(num(call(start,"getY")), num(call(dest,"getY")), e));
            call(loc, "setZ", lerp(num(call(start,"getZ")), num(call(dest,"getZ")), e));
            float yaw = (float)lerpAngle(num(call(start,"getYaw")), num(call(dest,"getYaw")), e);
            float pitch = (float)lerp(num(call(start,"getPitch")), num(call(dest,"getPitch")), e);
            call(loc, "setYaw", yaw); call(loc, "setPitch", pitch);
            call(entity, "teleport", loc);
            if (tick >= total) { done.run(); return; }
            schedule(() -> tweenTeleport(player, entity, start, dest, tick+1, total, easing, done), 1);
        } catch (Throwable t) { ACTIVE_CUSTOM.remove(safeUuid(player)); logger().warning("Cinematic movement failed: " + shortError(t)); }
    }

    private static void lookAt(Object player, Object entity, Map<String,Object> data, Runnable done) throws Exception {
        Object loc = call(entity, "getLocation");
        double tx, ty, tz;
        if (data.containsKey("look-x") || data.containsKey("x")) {
            tx = parseDouble(data.getOrDefault("look-x", data.get("x")), num(call(loc,"getX")));
            ty = parseDouble(data.getOrDefault("look-y", data.get("y")), num(call(loc,"getY")));
            tz = parseDouble(data.getOrDefault("look-z", data.get("z")), num(call(loc,"getZ")));
        } else {
            Object target = findEntity(player, str(data.getOrDefault("look-target", data.get("target"))));
            if (target == null) throw new IllegalStateException("look target not found");
            Object tl = call(target,"getLocation"); tx=num(call(tl,"getX")); ty=num(call(tl,"getY")); tz=num(call(tl,"getZ"));
        }
        double sx=num(call(loc,"getX")), sy=num(call(loc,"getY")), sz=num(call(loc,"getZ"));
        float[] yp = lookAngles(sx,sy,sz,tx,ty,tz);
        long ticks = Math.max(0, parseLong(data.getOrDefault("duration-ticks", data.getOrDefault("ticks", 0)), 0));
        if (ticks <= 0) { call(entity,"setRotation", yp[0], yp[1]); done.run(); return; }
        Map<String,Object> move = new LinkedHashMap<>();
        move.put("x",sx); move.put("y",sy); move.put("z",sz); move.put("yaw",yp[0]); move.put("pitch",yp[1]); move.put("duration-ticks",ticks); move.put("easing",data.getOrDefault("easing","smooth"));
        moveObject(player, entity, move, done);
    }

    private static Object destination(Object entity, Map<String,Object> data, Object fallback) throws Exception {
        Object dest = call(fallback, "clone");
        if (data.containsKey("world")) {
            Object server = call(plugin, "getServer");
            Object world = call(server, "getWorld", str(data.get("world")));
            if (world != null) call(dest, "setWorld", world);
        }
        if (data.containsKey("x")) call(dest,"setX",parseDouble(data.get("x"),num(call(dest,"getX"))));
        if (data.containsKey("y")) call(dest,"setY",parseDouble(data.get("y"),num(call(dest,"getY"))));
        if (data.containsKey("z")) call(dest,"setZ",parseDouble(data.get("z"),num(call(dest,"getZ"))));
        if (data.containsKey("yaw")) call(dest,"setYaw",(float)parseDouble(data.get("yaw"),num(call(dest,"getYaw"))));
        if (data.containsKey("pitch")) call(dest,"setPitch",(float)parseDouble(data.get("pitch"),num(call(dest,"getPitch"))));
        return dest;
    }

    private static Object findEntity(Object player, String target) throws Exception {
        if (target == null || target.isBlank()) return null;
        Object world = call(player, "getWorld");
        @SuppressWarnings("unchecked") List<Object> entities = (List<Object>) call(world, "getEntities");
        String wanted = normalize(target);
        Object best = null; double bestDist = Double.MAX_VALUE;
        Object pl = call(player,"getLocation");
        for (Object e : entities) {
            if (e == player) continue;
            boolean match = false;
            for (String candidate : entityNames(e)) if (normalize(candidate).equals(wanted) || normalize(candidate).endsWith(wanted)) { match=true; break; }
            if (!match) continue;
            try {
                Object el=call(e,"getLocation");
                double dx=num(call(el,"getX"))-num(call(pl,"getX")); double dy=num(call(el,"getY"))-num(call(pl,"getY")); double dz=num(call(el,"getZ"))-num(call(pl,"getZ"));
                double d=dx*dx+dy*dy+dz*dz; if (d<bestDist) {bestDist=d; best=e;}
            } catch (Throwable ignored) { if (best==null) best=e; }
        }
        return best;
    }

    private static List<String> entityNames(Object e) {
        ArrayList<String> out=new ArrayList<>();
        for (String method : List.of("getName","getCustomName")) try { Object v=call(e,method); if(v!=null)out.add(String.valueOf(v)); } catch(Throwable ignored){}
        try { Object tags=call(e,"getScoreboardTags"); if(tags instanceof Iterable<?> it) for(Object t:it) out.add(String.valueOf(t)); } catch(Throwable ignored){}
        return out;
    }

    private static void fade(Object player, Map<String,Object> data, Runnable done) {
        String mode = lower(str(data.getOrDefault("mode", data.getOrDefault("action","out"))));
        long ticks = Math.max(0, parseLong(data.getOrDefault("duration-ticks", data.getOrDefault("ticks",20)),20));
        try {
            if (mode.equals("in") || mode.equals("clear")) {
                runConsole("effect clear " + playerName(player) + " minecraft:blindness");
                if (ticks > 0) schedule(done,ticks); else done.run();
            } else {
                int seconds = (int)Math.max(1, Math.ceil((ticks+10)/20.0));
                runConsole("effect give " + playerName(player) + " minecraft:blindness " + seconds + " 0 true");
                if (ticks > 0) schedule(done,ticks); else done.run();
            }
        } catch (Throwable t) { done.run(); }
    }

    private static void letterbox(Object player, Map<String,Object> data) {
        String action = lower(str(data.getOrDefault("action", data.getOrDefault("mode","on"))));
        try {
            File f = new File(dataFolder(), "content/narrative/presentation.yml");
            String cmd = readSimpleYamlValue(f, action.equals("off") ? "cinematic.letterbox.command-off" : "cinematic.letterbox.command-on");
            if (cmd != null && !cmd.isBlank()) runConsole(cmd.replace("%player%", playerName(player)));
            else if (!warnedLetterbox) {
                warnedLetterbox=true;
                logger().info("Letterbox step is active but no presentation command hook is configured; step is a safe no-op.");
            }
        } catch(Throwable ignored){}
    }

    private static String readSimpleYamlValue(File f, String dotted) {
        if (!f.isFile()) return null;
        // Minimal nested-key reader for our own presentation hook; supports two levels below cinematic.
        try {
            String[] parts=dotted.split("\\."); int depth=0; String path="";
            for(String raw:Files.readAllLines(f.toPath(),StandardCharsets.UTF_8)){
                if(raw.trim().isEmpty()||raw.trim().startsWith("#"))continue;
                int spaces=0; while(spaces<raw.length()&&raw.charAt(spaces)==' ')spaces++;
                String t=raw.trim(); int c=t.indexOf(':'); if(c<0)continue;
                String key=t.substring(0,c).trim(); String val=t.substring(c+1).trim();
                int level=spaces/2;
                if(level==0) path=key;
                else if(level==1) path=path.split("\\.")[0]+"."+key;
                else if(level==2) {String[] ps=path.split("\\."); path=ps[0]+"."+(ps.length>1?ps[1]:"")+"."+key;}
                if(path.equals(dotted)&&!val.isEmpty()) return unquote(val);
            }
        }catch(Throwable ignored){}
        return null;
    }

    private static String unquote(String s){ if(s.length()>=2&&((s.startsWith("\"")&&s.endsWith("\""))||(s.startsWith("'")&&s.endsWith("'"))))return s.substring(1,s.length()-1);return s;}

    private static void runConsole(String command) throws Exception {
        Object server=call(plugin,"getServer"); Object console=call(server,"getConsoleSender"); call(server,"dispatchCommand",console,command);
    }

    private static void schedule(Runnable r, long ticks) {
        try { Object server=call(plugin,"getServer"); Object scheduler=call(server,"getScheduler"); call(scheduler,"runTaskLater",plugin,r,Math.max(0,ticks)); }
        catch(Throwable t){ logger().warning("Could not schedule cinematic task: "+shortError(t)); }
    }

    private static Object invokeCorePrivate(String name, Class<?>[] sig, Object... args) throws Exception {
        Class<?> c=Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore"); Method m=c.getDeclaredMethod(name,sig); m.setAccessible(true); return m.invoke(null,args);
    }

    private static void requireAdmin(Object sender) throws Exception {
        if (!hasPermission(sender, ADMIN)) throw new IllegalStateException("You do not have permission.");
    }
    private static Object requirePlayer(Object sender) throws Exception {
        Class<?> p=Class.forName("org.bukkit.entity.Player",false,plugin.getClass().getClassLoader());
        if(!p.isInstance(sender))throw new IllegalStateException("This command must be run by a player."); return sender;
    }
    private static boolean hasPermission(Object sender,String perm){ try{return Boolean.TRUE.equals(call(sender,"hasPermission",perm));}catch(Throwable t){return true;} }
    private static UUID uuid(Object p) throws Exception { return (UUID)call(p,"getUniqueId"); }
    private static UUID safeUuid(Object p){ try{return uuid(p);}catch(Throwable t){return new UUID(0,0);} }
    private static String playerName(Object p){ try{return String.valueOf(call(p,"getName"));}catch(Throwable t){return "@s";} }
    private static void send(Object sender,String msg){ try{call(sender,"sendMessage",PREFIX+msg);}catch(Throwable ignored){} }
    private static void sendRaw(Object sender,String msg){ try{call(sender,"sendMessage",msg);}catch(Throwable ignored){} }
    private static Logger logger(){ try{return (Logger)call(plugin,"getLogger");}catch(Throwable t){return Logger.getLogger("WorldMemory");} }
    private static File dataFolder(){ try{return (File)call(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");} }

    private static Properties loadMarkerProperties(UUID u) throws IOException {
        Properties p=new Properties(); File f=markerFile(u); if(f.isFile())try(InputStream in=new FileInputStream(f)){p.load(in);} return p;
    }
    private static void saveMarkerProperties(UUID u,Properties p)throws IOException{
        File f=markerFile(u); Files.createDirectories(f.getParentFile().toPath()); Path tmp=new File(f.getParentFile(),"."+f.getName()+".tmp").toPath();
        try(OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){p.store(out,"WorldMemory cinematic camera markers");}
        try{Files.move(tmp,f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,f.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static File markerFile(UUID u){return new File(dataFolder(),"narrative/editor/markers-"+u+".properties");}

    private static List<String> tabComplete(Object sender,String[] args)throws Exception{
        if(args.length==1)return match(args[0],List.of("help","list","validate","timeline","play","preview","stop","mark","marks","unmark","camera","lookat","record"));
        if(args.length==2 && List.of("timeline","play","preview","inspect").contains(lower(args[0]))) return match(args[1],new ArrayList<>(cutscenes().keySet()));
        if(args.length==2 && lower(args[0]).equals("record")) return match(args[1],List.of("start","add","show","undo","export","cancel"));
        if(args.length==3 && lower(args[0]).equals("record") && lower(args[1]).equals("add")) return match(args[2],List.of("camera","wait","dialogue","animation","sound","title","player-move","npc-move","npc-look","fade","letterbox"));
        if(args.length==2 && List.of("camera","unmark").contains(lower(args[0]))) return match(args[1],markerNames(requirePlayer(sender)));
        if(args.length==2 && lower(args[0]).equals("lookat")) return match(args[1],markerNames(requirePlayer(sender)));
        if(args.length==3 && lower(args[0]).equals("lookat")) return match(args[2],markerNames(requirePlayer(sender)));
        return List.of();
    }
    private static List<String> markerNames(Object p)throws Exception{Properties pr=loadMarkerProperties(uuid(p));TreeSet<String>s=new TreeSet<>();for(String k:pr.stringPropertyNames())if(k.endsWith(".world"))s.add(k.substring(0,k.length()-6));return new ArrayList<>(s);}
    private static List<String> match(String q,List<String> vals){String l=lower(q);return vals.stream().filter(x->lower(x).startsWith(l)).sorted().limit(50).toList();}

    private static Object call(Object target,String name,Object...args)throws Exception{
        if(target==null)throw new NullPointerException(name+" target is null"); Method m=findCompatible(target.getClass(),name,args); if(m==null)throw new NoSuchMethodException(target.getClass().getName()+"."+name); m.setAccessible(true); return m.invoke(target,args);
    }
    private static Method findCompatible(Class<?> c,String name,Object[]args){for(Class<?> k=c;k!=null;k=k.getSuperclass()){for(Method m:k.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;}for(Method m:c.getMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;return null;}
    private static boolean compatible(Class<?>[]p,Object[]a){if(p.length!=a.length)return false;for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;continue;}if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?> wrap(Class<?> c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;if(c==char.class)return Character.class;return c;}
    private static Object primitiveDefault(Class<?> c){if(!c.isPrimitive())return null;if(c==boolean.class)return false;if(c==char.class)return '\0';if(c==byte.class)return(byte)0;if(c==short.class)return(short)0;if(c==int.class)return 0;if(c==long.class)return 0L;if(c==float.class)return 0f;if(c==double.class)return 0d;return null;}

    private static float[] lookAngles(double sx,double sy,double sz,double tx,double ty,double tz){double dx=tx-sx,dy=ty-sy,dz=tz-sz;double flat=Math.sqrt(dx*dx+dz*dz);float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));float pitch=(float)-Math.toDegrees(Math.atan2(dy,flat));return new float[]{yaw,pitch};}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double lerpAngle(double a,double b,double t){double d=((b-a+540)%360)-180;return a+d*t;}
    private static double num(Object o){return o instanceof Number n?n.doubleValue():parseDouble(o,0);}
    private static int parseInt(Object o,int d){try{return Integer.parseInt(String.valueOf(o));}catch(Throwable t){return d;}}
    private static long parseLong(Object o,long d){try{return Long.parseLong(String.valueOf(o));}catch(Throwable t){try{return (long)Double.parseDouble(String.valueOf(o));}catch(Throwable x){return d;}}}
    private static double parseDouble(Object o,double d){try{return Double.parseDouble(String.valueOf(o));}catch(Throwable t){return d;}}
    private static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static boolean eq(String a,String b){return a!=null&&a.equalsIgnoreCase(b);}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static String normalize(String s){return lower(s).replaceAll("§.","").replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");}
    private static String safeName(String s){String n=normalize(s);return n.isBlank()?"marker":n;}
    private static String safeId(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static String join(String[]a,int from){StringBuilder b=new StringBuilder();for(int i=from;i<a.length;i++){if(i>from)b.append(' ');b.append(a[i]);}return b.toString();}
    private static String fmtTicks(long t){return t+"t/"+DF.format(t/20.0)+"s";}
    private static String yaml(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
    private static String shortError(Throwable t){Throwable x=t instanceof InvocationTargetException ite && ite.getCause()!=null?ite.getCause():t;String m=x.getMessage();return x.getClass().getSimpleName()+(m==null?"":": "+m);}

    private record Marker(String world,double x,double y,double z,float yaw,float pitch){}
    private record DurationInfo(long ticks,boolean blockingUnknown){}
    private static final class Draft{final String id;final List<Map<String,Object>>steps=new ArrayList<>();Draft(String id){this.id=id;}}
}
