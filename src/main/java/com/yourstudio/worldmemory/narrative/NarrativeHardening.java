package com.yourstudio.worldmemory.narrative;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NarrativeHardening {
    private static final String PREFIX = "§8[§cNGuard§8] §7";
    private static final String ADMIN = "worldmemory.narrative.admin";
    private static final long RENDER_WINDOW_MS = 5000L;
    private static final int RENDER_LIMIT = 80;
    private static final long STUCK_WARN_MS = 120_000L;
    private static final long OFFLINE_CLEANUP_MS = 60_000L;
    private static final String STATE_SCHEMA = "1";
    private static volatile Object plugin;
    private static volatile Object proxy;
    private static volatile Object watchdogTask;
    private static volatile boolean started;
    private static final Map<UUID, RenderBudget> RENDER_BUDGETS = new ConcurrentHashMap<>();
    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> OFFLINE_SINCE = new ConcurrentHashMap<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private NarrativeHardening() {}

    public static synchronized void startup(Object p) {
        if (started) return;
        plugin = p;
        try {
            registerCommand();
            ensureStateSchemaBackup();
            armWatchdog();
            started = true;
            List<Finding> fs = scanStatic();
            long errors = fs.stream().filter(f -> f.severity.equals("ERROR")).count();
            long warns = fs.stream().filter(f -> f.severity.equals("WARN")).count();
            logger().info("Narrative hardening online: " + errors + " errors, " + warns + " warnings.");
        } catch (Throwable t) {
            logger().warning("Narrative hardening could not start fully: " + shortError(t));
        }
    }

    public static synchronized void shutdown() {
        try { if (watchdogTask != null) call(watchdogTask, "cancel"); } catch (Throwable ignored) {}
        watchdogTask = null;
        RENDER_BUDGETS.clear(); WATCHES.clear(); OFFLINE_SINCE.clear();
        proxy = null; plugin = null; started = false;
    }

    public static synchronized void reload() {
        RENDER_BUDGETS.clear(); WATCHES.clear();
    }

    /** Called before NarrativeCore resolves the dialogue node. */
    public static void onRender(Object player, Object session) {
        try {
            UUID id = uuid(player);
            long now = System.currentTimeMillis();
            RenderBudget b = RENDER_BUDGETS.computeIfAbsent(id, k -> new RenderBudget(now, 0));
            synchronized (b) {
                if (now - b.windowStart > RENDER_WINDOW_MS) { b.windowStart = now; b.count = 0; }
                b.count++;
                if (b.count > RENDER_LIMIT) {
                    setField(session, "nodeId", "__worldmemory_guard_abort__");
                    b.windowStart = now; b.count = 0;
                    send(player, "§cNarrative stopped an excessively fast dialogue loop. Check /nguard doctor.");
                    logger().severe("Narrative render-loop guard tripped for " + playerName(player) + ". A dialogue exceeded " + RENDER_LIMIT + " node renders in 5 seconds.");
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void onDialogueFinish(Object player, Object session) {
        try { RENDER_BUDGETS.remove(uuid(player)); } catch (Throwable ignored) {}
    }

    public static Boolean safeReload(Object sender) {
        try {
            requireAdmin(sender);
            RuntimeCounts c = counts();
            if (c.totalActive() > 0) {
                send(sender, "§cSafe reload refused: narrative runtime is active.");
                send(sender, "§7dialogues="+c.dialogues+" stories="+c.stories+" cutscenes="+c.cutscenes+" scenes="+c.scenes+" cameras="+c.cameras);
                send(sender, "§7Finish or stop the active narrative first. This prevents mid-session definition swaps.");
                return true;
            }
            List<Finding> pre = preflightFiles();
            long preErr = pre.stream().filter(f -> f.severity.equals("ERROR")).count();
            if (preErr > 0) {
                send(sender, "§cSafe reload aborted during YAML preflight ("+preErr+" errors).");
                for (Finding f : pre.stream().filter(x->x.severity.equals("ERROR")).limit(10).toList()) send(sender, "§c"+f.code+" §7"+f.message);
                return true;
            }
            File backup = backupAuthored("safe-reload");
            StaticSnapshot snap = StaticSnapshot.capture();
            try {
                invokeCoreReload();
                NarrativeComposition.reload();
                NarrativeActors.reload();
                NarrativeFlow.reload();
                NarrativeSessions.reload();
                NarrativeScenes.reload();
                NarrativeStudio.reload();
                List<Finding> after = scanStatic();
                long errors = after.stream().filter(f->f.severity.equals("ERROR")).count();
                if (errors > 0) throw new IllegalStateException("reloaded definitions reported " + errors + " validation errors");
                reload();
                send(sender, "§aNarrative safe reload completed successfully.");
                send(sender, "§7Backup: §f" + relative(dataFolder(), backup));
                long warns = after.stream().filter(f->f.severity.equals("WARN")).count();
                if (warns > 0) send(sender, "§eHardening warnings after reload: " + warns + " §7(use /nguard doctor)");
            } catch (Throwable t) {
                snap.restore();
                send(sender, "§cReload failed; previous in-memory narrative definitions were restored.");
                send(sender, "§7Reason: §f" + shortError(t));
                logger().warning("Narrative safe reload rolled back: " + shortError(t));
            }
            return true;
        } catch (Throwable t) {
            send(sender, "§cSafe reload error: " + shortError(t));
            return true;
        }
    }

    private static void registerCommand() throws Exception {
        Object cmd = call(plugin, "getCommand", "narrativeguard");
        if (cmd == null) throw new IllegalStateException("plugin.yml is missing narrativeguard");
        ClassLoader cl = plugin.getClass().getClassLoader();
        Class<?> exec = Class.forName("org.bukkit.command.CommandExecutor", true, cl);
        Class<?> tab = Class.forName("org.bukkit.command.TabCompleter", true, cl);
        proxy = Proxy.newProxyInstance(cl, new Class<?>[]{exec,tab}, (o,m,a)->{
            String n=m.getName();
            if(n.equals("onCommand")){try{return command(a[0],(String[])a[3]);}catch(Throwable t){send(a[0],"§cGuard error: "+shortError(t));return true;}}
            if(n.equals("onTabComplete")){String[] x=(String[])a[3]; if(x.length==1)return match(x[0],List.of("help","status","doctor","scan","reload","repair","backup"));return List.of();}
            if(n.equals("toString"))return "WorldMemoryNarrativeGuardProxy";
            if(n.equals("hashCode"))return System.identityHashCode(o);
            if(n.equals("equals"))return o==a[0];
            return primitiveDefault(m.getReturnType());
        });
        call(cmd,"setExecutor",proxy); call(cmd,"setTabCompleter",proxy);
    }

    private static boolean command(Object s,String[] a)throws Exception{
        requireAdmin(s);
        if(a.length==0||eq(a[0],"help")){help(s);return true;}
        switch(lower(a[0])){
            case "status" -> status(s);
            case "doctor","scan" -> doctor(s);
            case "reload" -> safeReload(s);
            case "repair" -> repair(s);
            case "backup" -> {File b=backupAll("manual");send(s,"§aNarrative backup created: §f"+relative(dataFolder(),b));}
            default -> send(s,"§cUnknown command. Use /nguard help.");
        }
        return true;
    }

    private static void help(Object s){
        send(s,"§c§lWorldMemory Narrative Guard");
        send(s,"§f/nguard status §8- §7live runtime safety state");
        send(s,"§f/nguard doctor §8- §7structural + runtime diagnostics");
        send(s,"§f/nguard reload §8- §7safe reload with preflight, backup and rollback");
        send(s,"§f/nguard repair §8- §7clean offline sessions and orphan cameras");
        send(s,"§f/nguard backup §8- §7snapshot authored content + narrative state");
    }

    private static void status(Object s)throws Exception{
        RuntimeCounts c=counts();
        send(s,"§c§l--- Narrative Guard Status ---");
        send(s,"§7Active: §fdialogues="+c.dialogues+" stories="+c.stories+" cutscenes="+c.cutscenes+" scenes="+c.scenes+" cameras="+c.cameras);
        send(s,"§7Offline tracked: §f"+OFFLINE_SINCE.size()+" §8• §7Watch entries: §f"+WATCHES.size());
        send(s,"§7Render guards: §f"+RENDER_BUDGETS.size()+" §8• §7Orphan cameras: §f"+orphanCameras(false));
        send(s,"§7State schema: §f"+STATE_SCHEMA);
    }

    private static void doctor(Object s)throws Exception{
        List<Finding> fs=new ArrayList<>(); fs.addAll(preflightFiles()); fs.addAll(scanStatic()); fs.addAll(runtimeFindings());
        long e=fs.stream().filter(f->f.severity.equals("ERROR")).count();
        long w=fs.stream().filter(f->f.severity.equals("WARN")).count();
        send(s,"§c§l--- Narrative Doctor ---");
        send(s,"§7Result: "+(e>0?"§cERRORS":"§aREADY")+" §8• §7errors=§f"+e+" §7warnings=§f"+w);
        int shown=0;
        for(Finding f:fs){if(shown++>=25)break;String col=f.severity.equals("ERROR")?"§c":f.severity.equals("WARN")?"§e":"§a";send(s,col+f.severity+" §8["+f.code+"] §7"+f.message);}
        if(fs.size()>25)send(s,"§8… "+(fs.size()-25)+" more findings not shown.");
        if(fs.isEmpty())send(s,"§aNo hardening findings.");
    }

    private static void repair(Object s)throws Exception{
        int offline=cleanupOffline(true);
        int cameras=orphanCameras(true);
        send(s,"§aNarrative repair complete.");
        send(s,"§7Offline sessions cleaned: §f"+offline);
        send(s,"§7Orphan cameras repaired: §f"+cameras);
    }

    private static List<Finding> preflightFiles(){
        List<Finding> out=new ArrayList<>();
        try{
            for(File f:yamlFiles()){
                try{
                    Class<?> y=Class.forName("org.bukkit.configuration.file.YamlConfiguration",true,plugin.getClass().getClassLoader());
                    Object cfg=y.getDeclaredConstructor().newInstance();
                    call(cfg,"load",f);
                }catch(Throwable t){out.add(new Finding("ERROR","YAML",relative(contentRoot(),f)+": "+shortError(t)));}
                try{
                    List<String> lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);
                    for(int i=0;i<lines.size();i++) if(lines.get(i).startsWith("\t")||lines.get(i).contains("\t")) {out.add(new Finding("WARN","TAB",relative(contentRoot(),f)+":"+(i+1)+" contains a tab; use spaces in YAML."));break;}
                    if(f.length()>512*1024)out.add(new Finding("WARN","LARGE_FILE",relative(contentRoot(),f)+" is larger than 512 KiB."));
                }catch(Throwable ignored){}
            }
            Map<String,List<String>> ids=new TreeMap<>();
            for(File f:yamlFiles()){
                String id=firstId(f); if(id!=null)ids.computeIfAbsent(id,k->new ArrayList<>()).add(relative(contentRoot(),f));
            }
            for(var e:ids.entrySet())if(e.getValue().size()>1)out.add(new Finding("ERROR","DUPLICATE_ID",e.getKey()+" appears in "+e.getValue()));
        }catch(Throwable t){out.add(new Finding("ERROR","PREFLIGHT",shortError(t)));}
        return out;
    }

    private static List<Finding> scanStatic(){
        List<Finding> out=new ArrayList<>();
        try{for(Object x:staticList("com.yourstudio.worldmemory.narrative.NarrativeCore","ERRORS"))out.add(new Finding("ERROR","CORE",String.valueOf(x)));}catch(Throwable ignored){}
        try{for(Object x:staticList("com.yourstudio.worldmemory.narrative.NarrativeCore","WARNINGS"))out.add(new Finding("WARN","CORE",String.valueOf(x)));}catch(Throwable ignored){}
        try{for(String x:NarrativeComposition.errors())out.add(new Finding("ERROR","COMPOSITION",x));for(String x:NarrativeComposition.warnings())out.add(new Finding("WARN","COMPOSITION",x));}catch(Throwable ignored){}
        try{for(Object x:staticList("com.yourstudio.worldmemory.narrative.NarrativeFlow","ERRORS"))out.add(new Finding("ERROR","FLOW",String.valueOf(x)));for(Object x:staticList("com.yourstudio.worldmemory.narrative.NarrativeFlow","WARNINGS"))out.add(new Finding("WARN","FLOW",String.valueOf(x)));}catch(Throwable ignored){}
        try{for(Object x:staticList("com.yourstudio.worldmemory.narrative.NarrativeScenes","ERRORS"))out.add(new Finding("ERROR","SCENE",String.valueOf(x)));}catch(Throwable ignored){}
        try{out.addAll(dialogueCycleFindings());}catch(Throwable t){out.add(new Finding("WARN","GRAPH_SCAN",shortError(t)));}
        try{out.addAll(storyCycleFindings());}catch(Throwable ignored){}
        return out;
    }

    private static List<Finding> dialogueCycleFindings()throws Exception{
        List<Finding> out=new ArrayList<>();
        Map<?,?> ds=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","DIALOGUES");
        for(var e:ds.entrySet()){
            String id=String.valueOf(e.getKey()); Object def=e.getValue(); Map<?,?> nodes=(Map<?,?>)call0(def,"nodes");
            Map<String,String> auto=new HashMap<>();
            for(var ne:nodes.entrySet()){
                Object node=ne.getValue(); List<?> choices=(List<?>)call0(node,"choices"); String next=str(call0(node,"next"));
                if((choices==null||choices.isEmpty())&&!next.isBlank())auto.put(String.valueOf(ne.getKey()),next);
            }
            Set<String> done=new HashSet<>();
            for(String start:auto.keySet()){
                if(done.contains(start))continue;
                LinkedHashMap<String,Integer> path=new LinkedHashMap<>(); String cur=start;
                while(cur!=null&&auto.containsKey(cur)&&!done.contains(cur)){
                    if(path.containsKey(cur)){
                        List<String> xs=new ArrayList<>(path.keySet()); int from=path.get(cur); List<String> cyc=xs.subList(from,xs.size());
                        out.add(new Finding("ERROR","AUTO_CYCLE",id+" has an automatic dialogue cycle: "+String.join(" -> ",cyc)+" -> "+cur));
                        break;
                    }
                    path.put(cur,path.size()); cur=auto.get(cur);
                }
                done.addAll(path.keySet());
            }
        }
        return out;
    }

    private static List<Finding> storyCycleFindings()throws Exception{
        List<Finding> out=new ArrayList<>(); Map<?,?> stories=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","STORIES");
        for(var e:stories.entrySet()){
            String id=String.valueOf(e.getKey()); Map<?,?> scenes=(Map<?,?>)call0(e.getValue(),"scenes"); Map<String,String> edges=new HashMap<>();
            for(var se:scenes.entrySet()){String n=str(call0(se.getValue(),"nextScene"));if(!n.isBlank())edges.put(String.valueOf(se.getKey()),n);}
            Set<String> done=new HashSet<>();
            for(String st:edges.keySet()){
                LinkedHashMap<String,Integer> path=new LinkedHashMap<>(); String cur=st;
                while(cur!=null&&edges.containsKey(cur)&&!done.contains(cur)){
                    if(path.containsKey(cur)){List<String> xs=new ArrayList<>(path.keySet());int from=path.get(cur);out.add(new Finding("WARN","STORY_CYCLE",id+" scene chain cycles: "+String.join(" -> ",xs.subList(from,xs.size()))+" -> "+cur));break;}
                    path.put(cur,path.size());cur=edges.get(cur);
                } done.addAll(path.keySet());
            }
        } return out;
    }

    private static List<Finding> runtimeFindings(){
        List<Finding> out=new ArrayList<>();
        try{
            RuntimeCounts c=counts(); if(c.totalActive()>0)out.add(new Finding("INFO","ACTIVE","Narrative is active: dialogues="+c.dialogues+", stories="+c.stories+", cutscenes="+c.cutscenes+", scenes="+c.scenes+"."));
            int orphans=orphanCameras(false);if(orphans>0)out.add(new Finding("WARN","ORPHAN_CAMERA",orphans+" camera state(s) have no owning story/cutscene/scene."));
            long now=System.currentTimeMillis(); for(var e:WATCHES.entrySet())if(now-e.getValue().lastChange>STUCK_WARN_MS)out.add(new Finding("WARN","STUCK",e.getKey()+" has not advanced for "+((now-e.getValue().lastChange)/1000)+"s."));
            if(!OFFLINE_SINCE.isEmpty())out.add(new Finding("WARN","OFFLINE_SESSION",OFFLINE_SINCE.size()+" offline player(s) still have narrative runtime state inside the cleanup grace window."));
        }catch(Throwable t){out.add(new Finding("WARN","RUNTIME_SCAN",shortError(t)));}
        return out;
    }

    private static void armWatchdog()throws Exception{
        Object server=call(plugin,"getServer"); Object scheduler=call(server,"getScheduler");
        watchdogTask=call(scheduler,"runTaskTimer",plugin,(Runnable)NarrativeHardening::watchdog,20L,20L);
    }

    private static void watchdog(){
        try{
            watchMap("story",staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","STORY_SESSIONS"),v->str(field(v,"sceneId"))+":"+field(v,"stepIndex"));
            watchMap("cutscene",staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","CUTSCENE_RUNS"),v->String.valueOf(field(v,"stepIndex")));
            watchMap("scene",staticMap("com.yourstudio.worldmemory.narrative.NarrativeScenes","RUNS"),v->String.valueOf(field(v,"index"))+":"+str(field(v,"currentType")));
            cleanupOffline(false);
        }catch(Throwable t){logger().fine("Narrative watchdog tick skipped: "+shortError(t));}
    }

    private static void watchMap(String type,Map<?,?> map,Fingerprint f)throws Exception{
        long now=System.currentTimeMillis(); Set<String> live=new HashSet<>(); Map<?,?> dialogs=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","DIALOGUE_SESSIONS");
        for(var e:map.entrySet()){
            UUID id=(UUID)e.getKey(); String key=type+":"+id; live.add(key); String fp=f.of(e.getValue());
            Watch w=WATCHES.get(key); if(w==null||!Objects.equals(w.fingerprint,fp)){WATCHES.put(key,new Watch(fp,now,false));continue;}
            if(!w.warned&&now-w.lastChange>STUCK_WARN_MS&&!dialogs.containsKey(id)){
                w.warned=true; logger().warning("Narrative watchdog: "+key+" has not advanced for >"+(STUCK_WARN_MS/1000)+"s ("+fp+").");
                Object p=online(id); if(p!=null)send(p,"§eA narrative sequence appears stalled. An admin can inspect /nguard doctor.");
            }
        }
        WATCHES.keySet().removeIf(k->k.startsWith(type+":")&&!live.contains(k));
    }

    private static int cleanupOffline(boolean immediate)throws Exception{
        Set<UUID> ids=new LinkedHashSet<>();
        for(String[] cf:new String[][]{{"com.yourstudio.worldmemory.narrative.NarrativeCore","DIALOGUE_SESSIONS"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","STORY_SESSIONS"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","CUTSCENE_RUNS"},{"com.yourstudio.worldmemory.narrative.NarrativeScenes","RUNS"}})for(Object k:staticMap(cf[0],cf[1]).keySet())if(k instanceof UUID)ids.add((UUID)k);
        long now=System.currentTimeMillis();int cleaned=0;
        for(UUID id:ids){
            if(online(id)!=null){OFFLINE_SINCE.remove(id);continue;}
            long since=OFFLINE_SINCE.computeIfAbsent(id,k->now);
            if(immediate||now-since>=OFFLINE_CLEANUP_MS){cleanupUuid(id);OFFLINE_SINCE.remove(id);cleaned++;logger().warning("Cleaned stale offline narrative session for "+id);}
        }
        OFFLINE_SINCE.keySet().removeIf(id->!ids.contains(id)); return cleaned;
    }

    private static void cleanupUuid(UUID id){
        for(String[] cf:new String[][]{
            {"com.yourstudio.worldmemory.narrative.NarrativeCore","DIALOGUE_SESSIONS"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","STORY_SESSIONS"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","CUTSCENE_RUNS"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","CAMERA_STATES"},{"com.yourstudio.worldmemory.narrative.NarrativeCore","CAMERA_LOCKS"},
            {"com.yourstudio.worldmemory.narrative.NarrativeScenes","RUNS"},{"com.yourstudio.worldmemory.narrative.NarrativeSessions","ACTIVE"},{"com.yourstudio.worldmemory.narrative.NarrativeSessions","QUEUES"},{"com.yourstudio.worldmemory.narrative.NarrativeSessions","PAUSED"}
        })try{staticMap(cf[0],cf[1]).remove(id);}catch(Throwable ignored){}
        RENDER_BUDGETS.remove(id); WATCHES.keySet().removeIf(k->k.endsWith(id.toString()));
    }

    private static int orphanCameras(boolean repair)throws Exception{
        Map<?,?> cameras=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","CAMERA_STATES");
        Map<?,?> cuts=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","CUTSCENE_RUNS");
        Map<?,?> stories=staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","STORY_SESSIONS");
        Map<?,?> sceneRuns=staticMap("com.yourstudio.worldmemory.narrative.NarrativeScenes","RUNS");
        Set<UUID> sceneCam=new HashSet<>(); for(Object r:sceneRuns.values())try{Object x=field(r,"cameraParticipants");if(x instanceof Set<?> ss)for(Object o:ss)if(o instanceof UUID)sceneCam.add((UUID)o);}catch(Throwable ignored){}
        List<UUID> orphan=new ArrayList<>();for(Object k:new ArrayList<>(cameras.keySet()))if(k instanceof UUID id&&!cuts.containsKey(id)&&!stories.containsKey(id)&&!sceneCam.contains(id))orphan.add(id);
        if(repair)for(UUID id:orphan){Object p=online(id);if(p!=null){try{invokeCorePrivate("restoreCamera",new Class<?>[]{Object.class},p);}catch(Throwable t){cameras.remove(id);}}else cameras.remove(id);}
        return orphan.size();
    }

    private static RuntimeCounts counts()throws Exception{
        return new RuntimeCounts(staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","DIALOGUE_SESSIONS").size(),staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","STORY_SESSIONS").size(),staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","CUTSCENE_RUNS").size(),staticMap("com.yourstudio.worldmemory.narrative.NarrativeScenes","RUNS").size(),staticMap("com.yourstudio.worldmemory.narrative.NarrativeCore","CAMERA_STATES").size());
    }

    private static void ensureStateSchemaBackup()throws Exception{
        File narrative=new File(dataFolder(),"narrative"); narrative.mkdirs(); File marker=new File(narrative,".hardening-schema"); String old=marker.exists()?Files.readString(marker.toPath(),StandardCharsets.UTF_8).trim():"";
        if(!STATE_SCHEMA.equals(old)){
            boolean hasState=narrative.listFiles()!=null&&Arrays.stream(Objects.requireNonNull(narrative.listFiles())).anyMatch(f->!f.getName().equals(".hardening-schema"));
            if(hasState){File b=new File(dataFolder(),"backups/narrative-state-pre-schema-"+STATE_SCHEMA+"-"+TS.format(new Date()));copyTree(narrative.toPath(),b.toPath());logger().info("Backed up narrative runtime state before schema marker update: "+relative(dataFolder(),b));}
            Files.writeString(marker.toPath(),STATE_SCHEMA,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static File backupAuthored(String reason)throws Exception{
        File out=new File(dataFolder(),"backups/narrative-"+reason+"-"+TS.format(new Date()));out.mkdirs();
        for(String p:List.of("dialogue","narrative","cutscenes","quests")){File src=new File(contentRoot(),p);if(src.exists())copyTree(src.toPath(),new File(out,"content/"+p).toPath());}
        return out;
    }
    private static File backupAll(String reason)throws Exception{File out=backupAuthored(reason);File state=new File(dataFolder(),"narrative");if(state.exists())copyTree(state.toPath(),new File(out,"runtime-state").toPath());return out;}

    private static void invokeCoreReload()throws Exception{Class<?> c=core();Method m=c.getDeclaredMethod("reloadDefinitions");m.setAccessible(true);m.invoke(null);}
    private static Object invokeCorePrivate(String n,Class<?>[] p,Object...a)throws Exception{Method m=core().getDeclaredMethod(n,p);m.setAccessible(true);return m.invoke(null,a);}
    private static Class<?> core()throws Exception{return Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore",false,plugin.getClass().getClassLoader());}

    private static Map<?,?> staticMap(String c,String f)throws Exception{Field x=Class.forName(c,false,plugin.getClass().getClassLoader()).getDeclaredField(f);x.setAccessible(true);Object v=x.get(null);return v instanceof Map<?,?>?(Map<?,?>)v:Map.of();}
    private static List<?> staticList(String c,String f)throws Exception{Field x=Class.forName(c,false,plugin.getClass().getClassLoader()).getDeclaredField(f);x.setAccessible(true);Object v=x.get(null);return v instanceof List<?>?(List<?>)v:List.of();}

    private static List<File> yamlFiles(){List<File> out=new ArrayList<>();for(String p:List.of("dialogue","narrative","cutscenes","quests"))collectYaml(new File(contentRoot(),p),out);return out;}
    private static void collectYaml(File f,List<File> o){if(!f.exists())return;File[] xs=f.listFiles();if(xs==null)return;for(File x:xs){if(x.isDirectory())collectYaml(x,o);else if(x.getName().endsWith(".yml")||x.getName().endsWith(".yaml"))o.add(x);}}
    private static String firstId(File f){try{for(String line:Files.readAllLines(f.toPath(),StandardCharsets.UTF_8)){String t=line.trim();if(t.startsWith("id:")&&!t.startsWith("#")){String s=t.substring(3).trim();if((s.startsWith("\"")&&s.endsWith("\""))||(s.startsWith("'")&&s.endsWith("'")))s=s.substring(1,s.length()-1);return s;}}}catch(Throwable ignored){}return null;}

    private static Object online(UUID id){try{Object server=call(plugin,"getServer");return call(server,"getPlayer",id);}catch(Throwable t){return null;}}
    private static UUID uuid(Object player)throws Exception{return (UUID)call(player,"getUniqueId");}
    private static String playerName(Object p){try{return String.valueOf(call(p,"getName"));}catch(Throwable t){return "player";}}
    private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void setField(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private static Object call0(Object o,String n)throws Exception{return call(o,n);}

    private static File dataFolder(){try{return (File)call(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");}}
    private static File contentRoot(){return new File(dataFolder(),"content");}
    private static void copyTree(Path src,Path dst)throws IOException{Files.walkFileTree(src,new SimpleFileVisitor<>(){public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(dst.resolve(src.relativize(d)));return FileVisitResult.CONTINUE;}public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.createDirectories(dst.resolve(src.relativize(f)).getParent());Files.copy(f,dst.resolve(src.relativize(f)),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;}});}
    private static String relative(File b,File f){try{return b.toPath().toAbsolutePath().normalize().relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\','/');}catch(Throwable t){return f.getPath();}}

    private static void requireAdmin(Object s)throws Exception{Object v=call(s,"hasPermission",ADMIN);if(!(v instanceof Boolean)||!((Boolean)v))throw new IllegalStateException("You do not have permission to use Narrative Guard.");}
    private static void send(Object t,String m){try{call(t,"sendMessage",PREFIX+m);}catch(Throwable ignored){}}
    private static java.util.logging.Logger logger(){try{return (java.util.logging.Logger)call(plugin,"getLogger");}catch(Throwable t){return java.util.logging.Logger.getLogger("WorldMemory");}}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static boolean eq(String a,String b){return a!=null&&a.equalsIgnoreCase(b);}
    private static String lower(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static List<String> match(String p,List<String>x){String q=lower(p);return x.stream().filter(v->lower(v).startsWith(q)).toList();}
    private static String shortError(Throwable t){while(t instanceof InvocationTargetException&&((InvocationTargetException)t).getCause()!=null)t=((InvocationTargetException)t).getCause();String m=t.getMessage();return t.getClass().getSimpleName()+(m==null?"":": "+m);}
    private static Object call(Object target,String name,Object...args)throws Exception{Method m=findCompatible(target.getClass(),name,args);if(m==null)throw new NoSuchMethodException(target.getClass().getName()+"."+name);m.setAccessible(true);return m.invoke(target,args);}
    private static Method findCompatible(Class<?> c,String n,Object[] a){for(Class<?> x=c;x!=null;x=x.getSuperclass())for(Method m:x.getDeclaredMethods())if(m.getName().equals(n)&&compat(m.getParameterTypes(),a))return m;for(Method m:c.getMethods())if(m.getName().equals(n)&&compat(m.getParameterTypes(),a))return m;return null;}
    private static boolean compat(Class<?>[]p,Object[]a){if(p.length!=a.length)return false;for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;}else if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?> wrap(Class<?>c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==float.class)return Float.class;if(c==double.class)return Double.class;if(c==char.class)return Character.class;return c;}
    private static Object primitiveDefault(Class<?>c){if(!c.isPrimitive())return null;if(c==boolean.class)return false;if(c==char.class)return '\0';if(c==byte.class)return(byte)0;if(c==short.class)return(short)0;if(c==int.class)return 0;if(c==long.class)return 0L;if(c==float.class)return 0f;if(c==double.class)return 0d;return null;}

    private interface Fingerprint{String of(Object o)throws Exception;}
    private static final class RenderBudget{long windowStart;int count;RenderBudget(long w,int c){windowStart=w;count=c;}}
    private static final class Watch{final String fingerprint;final long lastChange;volatile boolean warned;Watch(String f,long l,boolean w){fingerprint=f;lastChange=l;warned=w;}}
    private record Finding(String severity,String code,String message){}
    private record RuntimeCounts(int dialogues,int stories,int cutscenes,int scenes,int cameras){int totalActive(){return dialogues+stories+cutscenes+scenes;}}

    private static final class StaticSnapshot {
        private final List<Entry> entries=new ArrayList<>();
        static StaticSnapshot capture(){StaticSnapshot s=new StaticSnapshot();for(String c:List.of("com.yourstudio.worldmemory.narrative.NarrativeCore","com.yourstudio.worldmemory.narrative.NarrativeComposition","com.yourstudio.worldmemory.narrative.NarrativeActors","com.yourstudio.worldmemory.narrative.NarrativeFlow","com.yourstudio.worldmemory.narrative.NarrativeSessions","com.yourstudio.worldmemory.narrative.NarrativeScenes"))s.captureClass(c);return s;}
        void captureClass(String cn){try{Class<?> c=Class.forName(cn,false,plugin.getClass().getClassLoader());for(Field f:c.getDeclaredFields()){if(!Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object v=f.get(null);Object cp=null;if(v instanceof Map<?,?>m)cp=new LinkedHashMap<>(m);else if(v instanceof List<?>l)cp=new ArrayList<>(l);else if(v instanceof Set<?>ss)cp=new LinkedHashSet<>(ss);if(cp!=null)entries.add(new Entry(f,v,cp));}}catch(Throwable ignored){}}
        @SuppressWarnings({"unchecked","rawtypes"}) void restore(){for(Entry e:entries)try{if(e.target instanceof Map m&&e.copy instanceof Map c){m.clear();m.putAll(c);}else if(e.target instanceof List l&&e.copy instanceof List c){l.clear();l.addAll(c);}else if(e.target instanceof Set s&&e.copy instanceof Set c){s.clear();s.addAll(c);}}catch(Throwable ignored){}}
        private record Entry(Field field,Object target,Object copy){}
    }
}
