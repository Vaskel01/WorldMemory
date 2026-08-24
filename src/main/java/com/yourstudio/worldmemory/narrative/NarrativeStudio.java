package com.yourstudio.worldmemory.narrative;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public final class NarrativeStudio {
    private static final String PREFIX = "§8[§dStudio§8] §7";
    private static final String ADMIN = "worldmemory.narrative.admin";
    private static volatile Object plugin;
    private static volatile Object proxy;
    private static volatile boolean started;
    private static final Pattern ID_LINE = Pattern.compile("^\\s*id\\s*:\\s*(.+?)\\s*$");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private NarrativeStudio() {}

    public static synchronized void startup(Object p) {
        if (started) return;
        plugin = p;
        try {
            registerCommand();
            started = true;
            logger().info("WorldMemory Narrative Studio authoring workspace ready.");
        } catch (Throwable t) {
            logger().warning("Narrative Studio could not start: " + shortError(t));
        }
    }

    public static synchronized void shutdown() {
        started = false;
        proxy = null;
        plugin = null;
    }

    public static synchronized void reload() {
        // Studio intentionally keeps no content cache. Every command reads current files.
    }

    private static void registerCommand() throws Exception {
        Object cmd = call(plugin, "getCommand", "studio");
        if (cmd == null) throw new IllegalStateException("plugin.yml is missing the studio command");
        ClassLoader cl = plugin.getClass().getClassLoader();
        Class<?> exec = Class.forName("org.bukkit.command.CommandExecutor", true, cl);
        Class<?> tab = Class.forName("org.bukkit.command.TabCompleter", true, cl);
        proxy = Proxy.newProxyInstance(cl, new Class<?>[]{exec, tab}, (obj, method, args) -> {
            String n = method.getName();
            if (n.equals("onCommand")) {
                try { return handle(args[0], (String[]) args[3]); }
                catch (Throwable t) { send(args[0], "§cStudio error: " + shortError(t)); return true; }
            }
            if (n.equals("onTabComplete")) {
                try { return tabComplete((String[]) args[3]); }
                catch (Throwable t) { return List.of(); }
            }
            if (n.equals("toString")) return "WorldMemoryNarrativeStudioProxy";
            if (n.equals("hashCode")) return System.identityHashCode(obj);
            if (n.equals("equals")) return obj == args[0];
            return primitiveDefault(method.getReturnType());
        });
        call(cmd, "setExecutor", proxy);
        call(cmd, "setTabCompleter", proxy);
    }

    private static boolean handle(Object sender, String[] args) throws Exception {
        requireAdmin(sender);
        if (args.length == 0 || eq(args[0], "help")) { help(sender); return true; }
        String sub = lower(args[0]);
        switch (sub) {
            case "status" -> status(sender);
            case "index" -> index(sender);
            case "new" -> {
                if (args.length < 3) send(sender, "§cUsage: /studio new <dialogue|story|cutscene|scene|actor|speaker|quest> <id>");
                else create(sender, args[1], args[2]);
            }
            case "duplicate", "clone" -> {
                if (args.length < 4) send(sender, "§cUsage: /studio duplicate <type> <source-id> <new-id>");
                else duplicate(sender, args[1], args[2], args[3]);
            }
            case "where" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio where <id>");
                else where(sender, args[1]);
            }
            case "find" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio find <text>");
                else find(sender, join(args, 1), false);
            }
            case "refs" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio refs <id>");
                else find(sender, args[1], true);
            }
            case "outline" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio outline <id>");
                else outline(sender, args[1]);
            }
            case "recent" -> recent(sender, args.length >= 2 ? parseInt(args[1], 10) : 10);
            case "backup" -> backup(sender);
            case "validate" -> validate(sender);
            case "reload" -> reloadContent(sender);
            case "play" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio play <id>");
                else play(sender, args[1]);
            }
            case "inspect" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio inspect <id>");
                else inspect(sender, args[1]);
            }
            case "graph" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio graph <dialogue-id>");
                else dispatch(sender, "conversation graph " + args[1]);
            }
            case "timeline" -> {
                if (args.length < 2) send(sender, "§cUsage: /studio timeline <scene-or-cutscene-id>");
                else timeline(sender, args[1]);
            }
            case "tools" -> tools(sender);
            default -> send(sender, "§cUnknown Studio command. Use /studio help.");
        }
        return true;
    }

    private static void help(Object s) {
        send(s, "§d§lWorldMemory Narrative Studio");
        send(s, "§f/studio status §8- §7authoring/runtime overview");
        send(s, "§f/studio index §8- §7content counts + duplicate IDs");
        send(s, "§f/studio new <type> <id> §8- §7create a safe starter definition");
        send(s, "§f/studio duplicate <type> <source> <new> §8- §7clone a definition");
        send(s, "§f/studio where <id> §8- §7locate its source file");
        send(s, "§f/studio refs <id> §8- §7find everything referencing an ID");
        send(s, "§f/studio find <text> §8- §7search narrative content");
        send(s, "§f/studio outline <id> §8- §7quick structure summary");
        send(s, "§f/studio recent [count] §8- §7recently edited files");
        send(s, "§f/studio validate §8- §7run the complete narrative validation suite");
        send(s, "§f/studio reload §8- §7reload narrative content (safe reload when hardening is installed)");
        send(s, "§f/studio play|inspect|timeline|graph <id> §8- §7route to the correct existing tool");
        send(s, "§f/studio backup §8- §7snapshot authored narrative/quest files");
        send(s, "§f/studio tools §8- §7show the specialized editor commands");
    }

    private static void tools(Object s) {
        send(s, "§d§lSpecialized authoring tools");
        send(s, "§f/cinematic §7camera markers, recording, timeline previews");
        send(s, "§f/scene §7multi-character scene inspection and playback");
        send(s, "§f/conversation §7dialogue graphs, flow traces, sessions");
        send(s, "§f/actor §7relationships, flags, emotion and history");
        send(s, "§f/narrative §7stories, variables and raw runtime controls");
        send(s, "§f/wm builder §7world coordinates, selections, nearby content and templates");
        send(s, "§f/wm quest §7quest validation and progression tools");
    }

    private static void status(Object s) throws Exception {
        List<Def> defs = definitions();
        Map<String, Long> counts = new TreeMap<>();
        for (Def d : defs) counts.merge(d.type, 1L, Long::sum);
        send(s, "§d§l--- Narrative Studio ---");
        send(s, "§7Definitions: §f" + defs.size() + " §8" + counts);
        int dialogue = mapSize("com.yourstudio.worldmemory.narrative.NarrativeCore", "DIALOGUE_SESSIONS");
        int stories = mapSize("com.yourstudio.worldmemory.narrative.NarrativeCore", "STORY_SESSIONS");
        int cutscenes = mapSize("com.yourstudio.worldmemory.narrative.NarrativeCore", "CUTSCENE_RUNS");
        int scenes = mapSize("com.yourstudio.worldmemory.narrative.NarrativeScenes", "RUNS");
        send(s, "§7Runtime: §fdialogues=" + dialogue + " stories=" + stories + " cutscenes=" + cutscenes + " scenes=" + scenes);
        send(s, "§7Content root: §f" + relative(dataFolder(), contentRoot()));
        send(s, "§7Tip: §f/studio recent §7and §f/studio refs <id> §7are the fastest way to navigate a large project.");
    }

    private static void index(Object s) throws Exception {
        List<Def> defs = definitions();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, List<Def>> byId = new TreeMap<>();
        for (Def d : defs) {
            counts.merge(d.type, 1, Integer::sum);
            byId.computeIfAbsent(d.id, k -> new ArrayList<>()).add(d);
        }
        send(s, "§d§l--- Narrative Content Index ---");
        for (Map.Entry<String,Integer> e : counts.entrySet()) send(s, "§7" + e.getKey() + ": §f" + e.getValue());
        int dup = 0;
        for (Map.Entry<String,List<Def>> e : byId.entrySet()) if (e.getValue().size() > 1) {
            dup++;
            send(s, "§cDuplicate ID: §f" + e.getKey());
            for (Def d : e.getValue()) send(s, "  §8- §7" + relative(contentRoot(), d.file));
        }
        send(s, dup == 0 ? "§aNo duplicate narrative IDs found." : "§cDuplicate IDs: " + dup);
    }

    private static void create(Object s, String typeRaw, String id) throws Exception {
        String type = normalizeType(typeRaw);
        if (type == null) { send(s, "§cUnknown type: " + typeRaw); return; }
        if (!validId(id)) { send(s, "§cIDs may only contain letters, numbers, _, -, and periods."); return; }
        List<Def> existing = findDefinitions(id);
        if (!existing.isEmpty()) { send(s, "§cID already exists in " + relative(contentRoot(), existing.get(0).file)); return; }
        File dir = dirFor(type);
        dir.mkdirs();
        File out = new File(dir, safeFile(id) + ".yml");
        if (out.exists()) { send(s, "§cFile already exists: " + relative(contentRoot(), out)); return; }
        atomicWrite(out, template(type, id));
        send(s, "§aCreated §f" + type + " §d" + id);
        send(s, "§7File: §f" + relative(contentRoot(), out));
        send(s, "§7Next: edit the definition, then run §f/studio validate§7.");
    }

    private static void duplicate(Object s, String typeRaw, String sourceId, String newId) throws Exception {
        String type = normalizeType(typeRaw);
        if (type == null || !validId(newId)) { send(s, "§cInvalid type or new ID."); return; }
        Def source = findDefinition(sourceId, type);
        if (source == null) { send(s, "§cCould not find " + type + " " + sourceId); return; }
        if (!findDefinitions(newId).isEmpty()) { send(s, "§cNew ID already exists: " + newId); return; }
        String text = Files.readString(source.file.toPath(), StandardCharsets.UTF_8);
        String replaced = replaceFirstId(text, newId);
        File out = new File(dirFor(type), safeFile(newId) + ".yml");
        if (out.exists()) { send(s, "§cDestination file already exists."); return; }
        atomicWrite(out, replaced);
        send(s, "§aCloned §f" + sourceId + " §7→ §d" + newId);
        send(s, "§7File: §f" + relative(contentRoot(), out));
        send(s, "§eInternal references are intentionally unchanged. Run §f/studio refs " + sourceId + " §eand §f/studio validate§e.");
    }

    private static void where(Object s, String id) throws Exception {
        List<Def> ds = findDefinitions(id);
        if (ds.isEmpty()) { send(s, "§cNo definition found for: " + id); return; }
        send(s, "§d§l--- " + id + " ---");
        for (Def d : ds) send(s, "§7" + d.type + " §8→ §f" + relative(contentRoot(), d.file) + "§8:" + d.line);
    }

    private static void find(Object s, String query, boolean refsOnly) throws Exception {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        Set<String> definitionLines = new HashSet<>();
        if (refsOnly) for (Def d : findDefinitions(query)) definitionLines.add(d.file.getCanonicalPath() + ":" + d.line);
        for (File f : yamlFiles()) {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i=0;i<lines.size();i++) {
                String line = lines.get(i);
                if (!line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                String key = f.getCanonicalPath() + ":" + (i+1);
                if (refsOnly && definitionLines.contains(key)) continue;
                hits.add(new Hit(f, i+1, line.trim()));
            }
        }
        send(s, "§d§l--- " + (refsOnly ? "References" : "Search") + ": " + query + " ---");
        if (hits.isEmpty()) { send(s, "§7No matches."); return; }
        int limit = Math.min(20, hits.size());
        for (int i=0;i<limit;i++) {
            Hit h = hits.get(i);
            send(s, "§f" + relative(contentRoot(), h.file) + "§8:" + h.line + " §7" + ellipsis(h.text, 72));
        }
        if (hits.size() > limit) send(s, "§8… " + (hits.size()-limit) + " more matches not shown.");
        send(s, "§7Matches: §f" + hits.size());
    }

    private static void outline(Object s, String id) throws Exception {
        Def d = firstDefinition(id);
        if (d == null) { send(s, "§cDefinition not found: " + id); return; }
        List<String> lines = Files.readAllLines(d.file.toPath(), StandardCharsets.UTF_8);
        int choices=0, steps=0, imports=0, objectives=0;
        List<String> topKeys = new ArrayList<>();
        boolean inNodes=false, inScenes=false, inStages=false;
        int baseIndent = -1;
        for (String line : lines) {
            String t=line.trim();
            if (t.equals("nodes:")) { inNodes=true; inScenes=inStages=false; baseIndent=indent(line); continue; }
            if (t.equals("scenes:")) { inScenes=true; inNodes=inStages=false; baseIndent=indent(line); continue; }
            if (t.equals("stages:")) { inStages=true; inNodes=inScenes=false; baseIndent=indent(line); continue; }
            if (t.startsWith("- type:")) steps++;
            if (t.startsWith("- id:") && line.contains("      -")) choices++;
            if (t.startsWith("- dialogue:")) imports++;
            if (t.startsWith("type:") && line.contains("        ")) objectives++;
            if ((inNodes||inScenes||inStages) && indent(line)==baseIndent+2 && t.endsWith(":")) {
                String k=t.substring(0,t.length()-1).trim();
                if (!k.isEmpty() && topKeys.size()<20) topKeys.add(k);
            }
        }
        send(s, "§d§l--- Outline: " + id + " ---");
        send(s, "§7Type: §f" + d.type + " §8• §7File: §f" + relative(contentRoot(), d.file));
        if (!topKeys.isEmpty()) send(s, "§7Sections: §f" + String.join(", ", topKeys));
        send(s, "§7Approx: §fsteps=" + steps + " choices=" + choices + " imports=" + imports + " objectives=" + objectives);
        int refs = referenceCount(id);
        send(s, "§7External textual references: §f" + refs);
    }

    private static void recent(Object s, int n) throws Exception {
        n = Math.max(1, Math.min(30, n));
        List<File> files = yamlFiles();
        files.sort(Comparator.comparingLong(File::lastModified).reversed());
        send(s, "§d§l--- Recently Edited Narrative Files ---");
        for (int i=0;i<Math.min(n, files.size());i++) {
            File f=files.get(i);
            long age=Math.max(0,System.currentTimeMillis()-f.lastModified());
            send(s, "§f" + relative(contentRoot(), f) + " §8• §7" + age(age));
        }
    }

    private static void backup(Object s) throws Exception {
        File out = new File(dataFolder(), "backups/narrative-authoring-" + TS.format(new Date()));
        out.mkdirs();
        for (String path : List.of("dialogue", "narrative", "cutscenes", "quests")) {
            File src = new File(contentRoot(), path);
            if (src.exists()) copyTree(src.toPath(), new File(out, path).toPath());
        }
        send(s, "§aNarrative authoring backup created.");
        send(s, "§7Path: §f" + relative(dataFolder(), out));
    }

    private static void validate(Object s) throws Exception {
        send(s, "§dRunning WorldMemory narrative validation suite…");
        dispatch(s, "narrative validate");
        dispatch(s, "conversation validate");
        dispatch(s, "actor validate");
        dispatch(s, "scene validate");
        dispatch(s, "cinematic validate");
        dispatch(s, "wm quest validate");
    }

    private static void reloadContent(Object s) throws Exception {
        try {
            Class<?> h = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeHardening", false, plugin.getClass().getClassLoader());
            Method m = h.getMethod("safeReload", Object.class);
            Object result = m.invoke(null, s);
            if (!(result instanceof Boolean) || (Boolean) result) return;
        } catch (ClassNotFoundException ignored) {
        }
        send(s, "§eHardening safe-reload is not present in this build; using the current narrative reload path.");
        dispatch(s, "narrative reload");
        dispatch(s, "conversation reload");
    }

    private static void play(Object s, String id) throws Exception {
        Def d = firstDefinition(id);
        if (d == null) { send(s, "§cDefinition not found: " + id); return; }
        switch (d.type) {
            case "dialogue" -> dispatch(s, "narrative play dialogue " + id);
            case "story" -> dispatch(s, "narrative play story " + id);
            case "cutscene" -> dispatch(s, "cinematic play " + id);
            case "scene" -> dispatch(s, "scene play " + id);
            case "quest" -> dispatch(s, "wm quest start " + id);
            default -> send(s, "§e" + d.type + " definitions do not have a direct play action.");
        }
    }

    private static void inspect(Object s, String id) throws Exception {
        Def d = firstDefinition(id);
        if (d == null) { send(s, "§cDefinition not found: " + id); return; }
        switch (d.type) {
            case "dialogue", "story", "cutscene" -> dispatch(s, "narrative inspect " + id);
            case "scene" -> dispatch(s, "scene inspect " + id);
            case "actor" -> dispatch(s, "actor inspect " + id);
            case "quest" -> dispatch(s, "wm quest status");
            default -> where(s, id);
        }
    }

    private static void timeline(Object s, String id) throws Exception {
        Def d = firstDefinition(id);
        if (d == null) { send(s, "§cDefinition not found: " + id); return; }
        if (d.type.equals("scene")) dispatch(s, "scene timeline " + id);
        else if (d.type.equals("cutscene")) dispatch(s, "cinematic timeline " + id);
        else send(s, "§eTimeline is available for scenes and cutscenes.");
    }

    private static List<String> tabComplete(String[] args) throws Exception {
        if (args.length == 1) return match(args[0], List.of("help","status","index","new","duplicate","where","find","refs","outline","recent","backup","validate","reload","play","inspect","graph","timeline","tools"));
        if (args.length == 2 && (eq(args[0],"new")||eq(args[0],"duplicate"))) return match(args[1], List.of("dialogue","story","cutscene","scene","actor","speaker","quest"));
        if (args.length == 2 && Set.of("where","refs","outline","play","inspect","graph","timeline").contains(lower(args[0]))) return match(args[1], ids());
        if (args.length == 3 && eq(args[0],"duplicate")) {
            String t=normalizeType(args[1]);
            List<String> out=new ArrayList<>();
            for (Def d:definitions()) if (t==null||d.type.equals(t)) out.add(d.id);
            return match(args[2],out);
        }
        return List.of();
    }

    private static List<String> ids() throws Exception {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for (Def d:definitions()) out.add(d.id);
        return new ArrayList<>(out);
    }

    private static List<Def> definitions() throws Exception {
        List<Def> out=new ArrayList<>();
        for (File f:yamlFiles()) {
            List<String> lines=Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i=0;i<Math.min(lines.size(),80);i++) {
                Matcher m=ID_LINE.matcher(lines.get(i));
                if (m.matches()) {
                    String id=unquote(m.group(1));
                    if (!id.isBlank()) out.add(new Def(typeFor(f),id,f,i+1));
                    break;
                }
            }
        }
        return out;
    }

    private static List<Def> findDefinitions(String id) throws Exception {
        List<Def> out=new ArrayList<>();
        for (Def d:definitions()) if (d.id.equalsIgnoreCase(id)) out.add(d);
        return out;
    }
    private static Def firstDefinition(String id) throws Exception { List<Def> l=findDefinitions(id); return l.isEmpty()?null:l.get(0); }
    private static Def findDefinition(String id,String type) throws Exception { for(Def d:findDefinitions(id)) if(d.type.equals(type)) return d; return null; }

    private static String typeFor(File f) throws IOException {
        String p=relative(contentRoot(),f).replace('\\','/').toLowerCase(Locale.ROOT);
        if(p.startsWith("dialogue/")) return "dialogue";
        if(p.startsWith("cutscenes/")) return "cutscene";
        if(p.startsWith("quests/")) return "quest";
        if(p.startsWith("narrative/scenes/")) return "scene";
        if(p.startsWith("narrative/actors/")) return "actor";
        if(p.startsWith("narrative/speakers/")) return "speaker";
        if(p.startsWith("narrative/")) return "story";
        return "content";
    }

    private static String normalizeType(String t) {
        t=lower(t);
        if(t.endsWith("s")) t=t.substring(0,t.length()-1);
        if(t.equals("dialog"))t="dialogue";
        if(Set.of("dialogue","story","cutscene","scene","actor","speaker","quest").contains(t)) return t;
        return null;
    }

    private static File dirFor(String type) {
        return switch(type){
            case "dialogue" -> new File(contentRoot(),"dialogue");
            case "story" -> new File(contentRoot(),"narrative");
            case "cutscene" -> new File(contentRoot(),"cutscenes");
            case "scene" -> new File(contentRoot(),"narrative/scenes");
            case "actor" -> new File(contentRoot(),"narrative/actors");
            case "speaker" -> new File(contentRoot(),"narrative/speakers");
            case "quest" -> new File(contentRoot(),"quests");
            default -> contentRoot();
        };
    }

    private static String template(String type,String id) {
        String h="# Created by WorldMemory Narrative Studio\n# Edit this file, then run /studio validate before reloading.\n";
        return switch(type){
            case "dialogue" -> h+"id: "+id+"\nentry: start\nnodes:\n  start:\n    speaker: narrator\n    text: \"TODO: Write this line.\"\n    choices:\n      - id: continue\n        text: \"Continue.\"\n        end: true\n";
            case "story" -> h+"id: "+id+"\nentry: intro\nscenes:\n  intro:\n    steps:\n      - type: message\n        text: \"TODO: Story begins here.\"\n      - type: checkpoint\n      - type: end\n";
            case "cutscene" -> h+"id: "+id+"\nsteps:\n  - type: title\n    title: \"TODO\"\n    subtitle: \"Cutscene\"\n    stay: 30\n  - type: end\n";
            case "scene" -> h+"id: "+id+"\naudience:\n  radius: 12\n  owner-only: false\ncast: {}\nsteps:\n  - type: line\n    speaker: narrator\n    text: \"TODO: Directed scene line.\"\n    duration-ticks: 40\n  - type: end\n";
            case "actor" -> h+"id: "+id+"\ndisplay-name: \"TODO Actor\"\naliases: []\nrelationships:\n  trust: 0\n  respect: 0\n  fear: 0\n  affinity: 0\nstate:\n  default-emotion: neutral\n  default-pose: standing\npresentation:\n  look-at-player: true\nambient:\n  enabled: false\n  radius: 8\n  cooldown-seconds: 45\n  chance: 0.25\n  priority: 10\n  lines: []\n";
            case "speaker" -> h+"id: "+id+"\ndisplay-name: \"TODO Speaker\"\nsubtitle-prefix: \"\"\nvoice:\n  sound: minecraft:block.amethyst_block.chime\n  volume: 0.25\n  pitch: 1.0\n";
            case "quest" -> h+"id: "+id+"\nscope: owner\nroute: default\nstages:\n  start:\n    objectives:\n      - id: begin\n        type: narrative_event\n        target: "+id+".begin\n";
            default -> h+"id: "+id+"\n";
        };
    }

    private static int referenceCount(String id) throws Exception {
        int c=0; String n=id.toLowerCase(Locale.ROOT);
        for(File f:yamlFiles()) for(String l:Files.readAllLines(f.toPath(),StandardCharsets.UTF_8)) if(l.toLowerCase(Locale.ROOT).contains(n) && !l.trim().matches("id\\s*:\\s*[\"']?"+Pattern.quote(id)+"[\"']?")) c++;
        return c;
    }

    private static List<File> yamlFiles() throws IOException {
        List<File> out=new ArrayList<>();
        for(String p:List.of("dialogue","narrative","cutscenes","quests")) collectYaml(new File(contentRoot(),p),out);
        return out;
    }
    private static void collectYaml(File f,List<File> out){
        if(!f.exists())return;
        File[] xs=f.listFiles(); if(xs==null)return;
        for(File x:xs){ if(x.isDirectory())collectYaml(x,out); else if(x.getName().endsWith(".yml")||x.getName().endsWith(".yaml"))out.add(x); }
    }

    private static void dispatch(Object sender,String command) throws Exception {
        Object server=call(plugin,"getServer");
        Object ok=call(server,"dispatchCommand",sender,command);
        if(ok instanceof Boolean && !((Boolean)ok)) send(sender,"§eCommand did not report success: /"+command);
    }

    private static int mapSize(String className,String fieldName){
        try{ Class<?> c=Class.forName(className,false,plugin.getClass().getClassLoader()); Field f=c.getDeclaredField(fieldName); f.setAccessible(true); Object v=f.get(null); return v instanceof Map<?,?> m?m.size():0;}catch(Throwable t){return 0;}
    }

    private static File dataFolder(){ try{return (File)call(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");} }
    private static File contentRoot(){return new File(dataFolder(),"content");}

    private static void copyTree(Path src,Path dst)throws IOException{
        Files.walkFileTree(src,new SimpleFileVisitor<>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(dst.resolve(src.relativize(d)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.copy(f,dst.resolve(src.relativize(f)),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;}
        });
    }
    private static void atomicWrite(File f,String text)throws IOException{
        f.getParentFile().mkdirs(); Path target=f.toPath(); Path tmp=target.resolveSibling(target.getFileName()+".tmp-"+System.nanoTime());
        Files.writeString(tmp,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);} }
    private static String replaceFirstId(String text,String id){String[] lines=text.split("\\R",-1);for(int i=0;i<lines.length;i++){if(ID_LINE.matcher(lines[i]).matches()){String prefix=lines[i].substring(0,lines[i].indexOf('i'));lines[i]=prefix+"id: "+id;break;}}return String.join(System.lineSeparator(),lines);}
    private static boolean validId(String id){return id!=null&&SAFE_ID.matcher(id).matches();}
    private static String safeFile(String id){return id.replace('.','_').replace('-','_');}
    private static String unquote(String s){s=s.trim();if(s.length()>=2&&((s.startsWith("\"")&&s.endsWith("\""))||(s.startsWith("'")&&s.endsWith("'"))))return s.substring(1,s.length()-1);return s;}
    private static String relative(File base,File f){try{return base.toPath().toAbsolutePath().normalize().relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\','/');}catch(Throwable t){return f.getPath();}}
    private static int indent(String s){int i=0;while(i<s.length()&&s.charAt(i)==' ')i++;return i;}
    private static String join(String[] a,int from){StringBuilder b=new StringBuilder();for(int i=from;i<a.length;i++){if(i>from)b.append(' ');b.append(a[i]);}return b.toString();}
    private static int parseInt(String s,int d){try{return Integer.parseInt(s);}catch(Exception e){return d;}}
    private static String ellipsis(String s,int n){return s.length()<=n?s:s.substring(0,Math.max(0,n-1))+"…";}
    private static String age(long ms){long sec=ms/1000;if(sec<60)return sec+"s ago";long min=sec/60;if(min<60)return min+"m ago";long hr=min/60;if(hr<48)return hr+"h ago";return (hr/24)+"d ago";}
    private static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static boolean eq(String a,String b){return a!=null&&a.equalsIgnoreCase(b);}
    private static List<String> match(String p,List<String> xs){String q=lower(p);return xs.stream().filter(x->lower(x).startsWith(q)).sorted().limit(50).toList();}

    private static void requireAdmin(Object s)throws Exception{Object v=call(s,"hasPermission",ADMIN);if(!(v instanceof Boolean)||!((Boolean)v))throw new IllegalStateException("You do not have permission to use Narrative Studio.");}
    private static void send(Object target,String msg){try{call(target,"sendMessage",PREFIX+msg);}catch(Throwable ignored){}}
    private static java.util.logging.Logger logger(){try{return (java.util.logging.Logger)call(plugin,"getLogger");}catch(Throwable t){return java.util.logging.Logger.getLogger("WorldMemory");}}
    private static String shortError(Throwable t){while(t instanceof InvocationTargetException && ((InvocationTargetException)t).getCause()!=null)t=((InvocationTargetException)t).getCause();String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}
    private static Object call(Object target,String name,Object...args)throws Exception{Method m=findCompatible(target.getClass(),name,args);if(m==null)throw new NoSuchMethodException(target.getClass().getName()+"."+name);m.setAccessible(true);return m.invoke(target,args);}
    private static Method findCompatible(Class<?> c,String name,Object[] args){for(Class<?> x=c;x!=null;x=x.getSuperclass())for(Method m:x.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;for(Method m:c.getMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;return null;}
    private static boolean compatible(Class<?>[] p,Object[] a){if(p.length!=a.length)return false;for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;}else if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?> wrap(Class<?> c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;if(c==char.class)return Character.class;return c;}
    private static Object primitiveDefault(Class<?> c){if(!c.isPrimitive())return null;if(c==boolean.class)return false;if(c==char.class)return '\0';if(c==byte.class)return (byte)0;if(c==short.class)return (short)0;if(c==int.class)return 0;if(c==long.class)return 0L;if(c==float.class)return 0f;if(c==double.class)return 0d;return null;}

    private record Def(String type,String id,File file,int line){}
    private record Hit(File file,int line,String text){}
}
