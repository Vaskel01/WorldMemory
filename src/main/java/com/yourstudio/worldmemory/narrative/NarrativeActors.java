package com.yourstudio.worldmemory.narrative;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WorldMemory alpha.48 actor/dialogue state layer.
 * Uses reflection so this class remains binary-light against Paper and the existing NarrativeCore.
 */
public final class NarrativeActors {
    private static final String PREFIX = "§6[WorldMemory] §r";
    private static final String ADMIN = "worldmemory.narrative.admin";
    private static volatile Object plugin;
    private static volatile Object commandProxy;
    private static volatile boolean started;

    private static final Map<String, ActorProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, DialogueMeta> DIALOGUE_META = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_NODE = new ConcurrentHashMap<>();
    private static final ThreadLocal<UUID> CURRENT_RENDER = new ThreadLocal<>();
    private static final Pattern ACTOR_TOKEN = Pattern.compile("%actor:([^:%]+):([^%]+)%");
    private static final int HISTORY_LIMIT = 500;

    private NarrativeActors() {}

    public static synchronized void startup(Object pluginObj) {
        if (started || pluginObj == null) return;
        plugin = pluginObj;
        started = true;
        try {
            Files.createDirectories(stateDir().toPath());
            Files.createDirectories(historyDir().toPath());
            File actors = actorsDir();
            if (!actors.exists()) actors.mkdirs();
            reload();
            registerCommand();
            logger().info("NarrativeActors alpha.48 actor/dialogue state layer ready. profiles=" + PROFILES.size() + ", dialogueMeta=" + DIALOGUE_META.size());
        } catch (Throwable t) {
            logger().warning("Could not start NarrativeActors: " + shortError(t));
        }
    }

    public static synchronized void shutdown() {
        for (Map.Entry<UUID, PlayerState> e : new ArrayList<>(STATES.entrySet())) {
            try { saveState(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
        }
        STATES.clear();
        LAST_NODE.clear();
        CURRENT_RENDER.remove();
        commandProxy = null;
        plugin = null;
        started = false;
    }

    public static synchronized void reload() {
        if (plugin == null) return;
        PROFILES.clear();
        DIALOGUE_META.clear();
        loadProfiles();
        loadDialogueMetadata();
    }

    // ---- hooks injected into NarrativeCore ----

    public static void enterRender(Object player, Object dialogueSession) {
        try {
            UUID id = uuid(player);
            CURRENT_RENDER.set(id);
            String dialogueId = dialogueId(dialogueSession);
            String nodeId = str(field(dialogueSession, "nodeId"));
            String key = dialogueId + "|" + nodeId;
            if (key.equals(LAST_NODE.get(id))) return;
            LAST_NODE.put(id, key);

            Object node = currentNode(dialogueSession);
            if (node == null) return;
            String speaker = str(call0(node, "speaker"));
            if (speaker.isBlank()) speaker = "narrator";
            DialogueMeta meta = DIALOGUE_META.get(dialogueId);
            NodeMeta nm = meta == null ? null : meta.nodes.get(nodeId);

            if (!speaker.equalsIgnoreCase("narrator")) {
                ensureActorDefaults(id, speaker);
                ActorState as = actorState(id, speaker);
                if (nm != null) applyNodeMeta(player, speaker, as, dialogueId, nodeId, nm);
                else applyActorCue(player, speaker, as.emotion, as.pose, profile(speaker).lookAtPlayer);
            }
            history(id, speaker, "node", dialogueId, nodeId, "");
            if (nm != null && !nm.event.isBlank()) emitEvent(player, nm.event);
        } catch (Throwable t) {
            logFine("Actor render hook failed", t);
        }
    }

    public static void exitRender() {
        CURRENT_RENDER.remove();
    }

    public static void onDialogueFinish(Object player, Object dialogueSession) {
        try {
            UUID id = uuid(player);
            String dialogueId = dialogueId(dialogueSession);
            String nodeId = str(field(dialogueSession, "nodeId"));
            Object node = currentNode(dialogueSession);
            String speaker = node == null ? "" : str(call0(node, "speaker"));
            history(id, speaker, "end", dialogueId, nodeId, "");
            LAST_NODE.remove(id);
        } catch (Throwable ignored) {
        } finally {
            CURRENT_RENDER.remove();
        }
    }

    public static void onChoice(Object player, Object choice) {
        try {
            UUID id = uuid(player);
            Object session = currentDialogueSession(id);
            if (session == null) return;
            String dialogueId = dialogueId(session);
            String nodeId = str(field(session, "nodeId"));
            String choiceId = str(call0(choice, "id"));
            Object node = currentNode(session);
            String speaker = node == null ? "" : str(call0(node, "speaker"));
            DialogueMeta dm = DIALOGUE_META.get(dialogueId);
            ChoiceMeta cm = dm == null ? null : dm.choice(nodeId, choiceId);

            history(id, speaker, "choice", dialogueId, nodeId, choiceId);
            if (cm == null) return;
            String actor = nonBlank(cm.actor, speaker);
            if (!actor.isBlank() && !actor.equalsIgnoreCase("narrator")) {
                ensureActorDefaults(id, actor);
                ActorState as = actorState(id, actor);
                for (Map.Entry<String, Double> e : cm.add.entrySet()) as.relationships.merge(norm(e.getKey()), e.getValue(), Double::sum);
                for (Map.Entry<String, Double> e : cm.set.entrySet()) as.relationships.put(norm(e.getKey()), e.getValue());
                for (Map.Entry<String, Boolean> e : cm.flags.entrySet()) as.flags.put(norm(e.getKey()), e.getValue());
                if (!cm.emotion.isBlank()) as.emotion = cm.emotion;
                if (!cm.pose.isBlank()) as.pose = cm.pose;
                saveState(id, state(id));
                applyActorCue(player, actor, as.emotion, as.pose, cm.lookAtPlayer || profile(actor).lookAtPlayer);
            }
            for (String cmd : cm.commands) executeConsole(cmd, player, actor);
            if (!cm.event.isBlank()) emitEvent(player, cm.event);
        } catch (Throwable t) {
            logFine("Actor choice hook failed", t);
        }
    }

    /** Actor-aware portion of Choice.when. Core story-variable conditions continue separately. */
    public static boolean actorConditionsMatch(Object storySession, Map<String,Object> condition) {
        try {
            if (condition == null || condition.isEmpty()) return true;
            Map<String,Object> ac = actorCondition(condition);
            if (ac == null || ac.isEmpty()) return true;
            UUID playerId = storySession == null ? CURRENT_RENDER.get() : (UUID)field(storySession, "playerId");
            if (playerId == null) return false;
            String actor = str(ac.get("actor"));
            if (actor.isBlank()) actor = str(ac.get("id"));
            if (actor.isBlank()) return true;
            ensureActorDefaults(playerId, actor);
            ActorState as = actorState(playerId, actor);

            String relationship = str(ac.get("relationship"));
            if (!relationship.isBlank()) {
                double value = as.relationships.getOrDefault(norm(relationship), 0.0);
                if (ac.containsKey("minimum") && value < num(ac.get("minimum"), 0)) return false;
                if (ac.containsKey("min") && value < num(ac.get("min"), 0)) return false;
                if (ac.containsKey("maximum") && value > num(ac.get("maximum"), 0)) return false;
                if (ac.containsKey("max") && value > num(ac.get("max"), 0)) return false;
                if (ac.containsKey("equals") && Double.compare(value, num(ac.get("equals"), 0)) != 0) return false;
            }
            String flag = str(ac.get("flag"));
            if (!flag.isBlank() && !as.flags.getOrDefault(norm(flag), false)) return false;
            String flagNot = str(ac.get("flag-not"));
            if (flagNot.isBlank()) flagNot = str(ac.get("not-flag"));
            if (!flagNot.isBlank() && as.flags.getOrDefault(norm(flagNot), false)) return false;
            String emotion = str(ac.get("emotion"));
            if (!emotion.isBlank() && !emotion.equalsIgnoreCase(as.emotion)) return false;
            String pose = str(ac.get("pose"));
            if (!pose.isBlank() && !pose.equalsIgnoreCase(as.pose)) return false;
            return true;
        } catch (Throwable t) {
            logFine("Actor condition failed", t);
            return false;
        }
    }

    /** Removes actor-only keys so NarrativeCore can evaluate its normal variable condition unchanged. */
    public static Map<String,Object> stripActorConditions(Map<String,Object> original) {
        if (original == null || original.isEmpty()) return original;
        if (original.containsKey("actor-condition")) {
            Map<String,Object> copy = new LinkedHashMap<>(original);
            copy.remove("actor-condition");
            return copy;
        }
        if (!original.containsKey("actor")) return original;
        // Root actor syntax is exclusively actor state; don't let core treat min/max as a story var condition.
        LinkedHashMap<String,Object> copy = new LinkedHashMap<>(original);
        for (String k : List.of("actor","id","relationship","minimum","min","maximum","max","equals","flag","flag-not","not-flag","emotion","pose")) copy.remove(k);
        return copy;
    }

    public static String interpolate(Object player, String input) {
        if (input == null || input.indexOf("%actor:") < 0) return input;
        try {
            UUID id = uuid(player);
            Matcher m = ACTOR_TOKEN.matcher(input);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String actor = m.group(1);
                String key = m.group(2);
                ensureActorDefaults(id, actor);
                ActorState as = actorState(id, actor);
                String value = actorValue(actor, as, key);
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            return input;
        }
    }

    // ---- metadata application ----

    private static void applyNodeMeta(Object player, String actor, ActorState as, String dialogueId, String nodeId, NodeMeta nm) throws Exception {
        boolean changed = false;
        if (!nm.emotion.isBlank()) { as.emotion = nm.emotion; changed = true; }
        if (!nm.pose.isBlank()) { as.pose = nm.pose; changed = true; }
        for (Map.Entry<String, Double> e : nm.add.entrySet()) { as.relationships.merge(norm(e.getKey()), e.getValue(), Double::sum); changed = true; }
        for (Map.Entry<String, Double> e : nm.set.entrySet()) { as.relationships.put(norm(e.getKey()), e.getValue()); changed = true; }
        for (Map.Entry<String, Boolean> e : nm.flags.entrySet()) { as.flags.put(norm(e.getKey()), e.getValue()); changed = true; }
        if (changed) saveState(uuid(player), state(uuid(player)));
        applyActorCue(player, actor, as.emotion, as.pose, nm.lookAtPlayer || profile(actor).lookAtPlayer);
        for (String cmd : nm.commands) executeConsole(cmd, player, actor);
    }

    private static void applyActorCue(Object player, String actor, String emotion, String pose, boolean lookAtPlayer) {
        try {
            Object entity = findActorEntity(player, actor);
            if (entity == null) return;
            if (!pose.isBlank()) trySetPose(entity, pose);
            if (lookAtPlayer) lookAt(entity, player);
            ActorProfile p = profile(actor);
            ActorCue cue = p.cues.get(norm(emotion));
            if (cue != null) {
                if (cue.glowing != null) tryInvoke(entity, "setGlowing", cue.glowing);
                if (!cue.pose.isBlank()) trySetPose(entity, cue.pose);
                if (!cue.sound.isBlank()) playSound(player, cue.sound, cue.volume, cue.pitch);
            }
        } catch (Throwable t) {
            logFine("Actor cue failed", t);
        }
    }

    private static Object findActorEntity(Object player, String actor) throws Exception {
        ActorProfile p = profile(actor);
        List<String> needles = new ArrayList<>();
        needles.add(actor);
        needles.addAll(p.aliases);
        if (!p.displayName.isBlank()) needles.add(p.displayName);
        Object nearby = call(player, "getNearbyEntities", 16.0, 8.0, 16.0);
        if (!(nearby instanceof Iterable<?> it)) return null;
        for (Object e : it) {
            Set<String> candidates = new HashSet<>();
            try { candidates.add(str(call(e, "getName"))); } catch (Throwable ignored) {}
            try { candidates.add(str(call(e, "getCustomName"))); } catch (Throwable ignored) {}
            try {
                Object tags = call(e, "getScoreboardTags");
                if (tags instanceof Iterable<?> ti) for (Object tag : ti) candidates.add(str(tag));
            } catch (Throwable ignored) {}
            for (String n : needles) for (String c : candidates) if (matches(n,c)) return e;
        }
        return null;
    }

    private static void lookAt(Object entity, Object player) throws Exception {
        Object from = call(entity, "getLocation");
        Object to = call(player, "getEyeLocation");
        double dx = num(call(to,"getX"),0)-num(call(from,"getX"),0);
        double dy = num(call(to,"getY"),0)-num(call(from,"getY"),0);
        double dz = num(call(to,"getZ"),0)-num(call(from,"getZ"),0);
        double horiz = Math.sqrt(dx*dx+dz*dz);
        float yaw = (float)Math.toDegrees(Math.atan2(-dx,dz));
        float pitch = (float)-Math.toDegrees(Math.atan2(dy,horiz));
        tryInvoke(entity,"setRotation",yaw,pitch);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static void trySetPose(Object entity, String pose) {
        try {
            Class<?> pc = Class.forName("org.bukkit.entity.Pose", false, plugin.getClass().getClassLoader());
            Object pv = Enum.valueOf((Class<? extends Enum>)pc.asSubclass(Enum.class), pose.trim().toUpperCase(Locale.ROOT).replace('-','_'));
            tryInvoke(entity,"setPose",pv);
        } catch (Throwable ignored) {}
    }

    private static void playSound(Object player, String sound, double volume, double pitch) {
        try {
            Object loc = call(player,"getLocation");
            tryInvoke(player,"playSound",loc,sound,(float)volume,(float)pitch);
        } catch (Throwable ignored) {}
    }

    private static void executeConsole(String command, Object player, String actor) {
        if (command == null || command.isBlank()) return;
        try {
            String cmd = command.replace("%player%", playerName(player)).replace("%actor%", actor == null ? "" : actor);
            Object server = call(plugin,"getServer");
            Object console = call(server,"getConsoleSender");
            tryInvoke(server,"dispatchCommand",console,cmd.startsWith("/")?cmd.substring(1):cmd);
        } catch (Throwable t) { logFine("Actor command failed", t); }
    }

    private static void emitEvent(Object player, String event) {
        if (event == null || event.isBlank()) return;
        try {
            Class<?> qr = Class.forName("com.yourstudio.worldmemory.quest.QuestRuntime", false, plugin.getClass().getClassLoader());
            Method m = qr.getMethod("signal", Object.class, String.class, String.class);
            m.invoke(null, player, "narrative_event", event);
        } catch (Throwable ignored) {}
        try { history(uuid(player), "", "event", "", "", event); } catch (Throwable ignored) {}
    }

    // ---- persistence ----

    private static PlayerState state(UUID playerId) {
        return STATES.computeIfAbsent(playerId, NarrativeActors::loadState);
    }

    private static ActorState actorState(UUID playerId, String actor) {
        return state(playerId).actors.computeIfAbsent(actor, k -> new ActorState());
    }

    private static void ensureActorDefaults(UUID playerId, String actor) {
        PlayerState ps = state(playerId);
        ActorState as = ps.actors.computeIfAbsent(actor, k -> new ActorState());
        ActorProfile p = profile(actor);
        boolean changed = false;
        for (Map.Entry<String,Double> e : p.defaults.entrySet()) if (!as.relationships.containsKey(e.getKey())) { as.relationships.put(e.getKey(),e.getValue()); changed=true; }
        if (as.emotion.isBlank() && !p.defaultEmotion.isBlank()) { as.emotion=p.defaultEmotion; changed=true; }
        if (as.pose.isBlank() && !p.defaultPose.isBlank()) { as.pose=p.defaultPose; changed=true; }
        if (changed) try { saveState(playerId, ps); } catch (Throwable ignored) {}
    }

    private static PlayerState loadState(UUID id) {
        PlayerState ps = new PlayerState();
        File f = new File(stateDir(), id + ".properties");
        if (!f.isFile()) return ps;
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { props.load(r); }
        catch (Throwable t) { return ps; }
        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot <= 0) continue;
            String actor = decActor(key.substring(0,dot));
            String rest = key.substring(dot+1);
            ActorState as = ps.actors.computeIfAbsent(actor,k->new ActorState());
            String v = props.getProperty(key,"");
            if (rest.startsWith("rel.")) as.relationships.put(rest.substring(4), num(v,0));
            else if (rest.startsWith("flag.")) as.flags.put(rest.substring(5), Boolean.parseBoolean(v));
            else if (rest.equals("emotion")) as.emotion=v;
            else if (rest.equals("pose")) as.pose=v;
        }
        return ps;
    }

    private static synchronized void saveState(UUID id, PlayerState ps) throws IOException {
        Properties props = new Properties();
        for (Map.Entry<String,ActorState> ae : ps.actors.entrySet()) {
            String a = encActor(ae.getKey()); ActorState as = ae.getValue();
            for (Map.Entry<String,Double> e : as.relationships.entrySet()) props.setProperty(a+".rel."+e.getKey(), trim(e.getValue()));
            for (Map.Entry<String,Boolean> e : as.flags.entrySet()) props.setProperty(a+".flag."+e.getKey(), String.valueOf(e.getValue()));
            if (!as.emotion.isBlank()) props.setProperty(a+".emotion",as.emotion);
            if (!as.pose.isBlank()) props.setProperty(a+".pose",as.pose);
        }
        File f = new File(stateDir(), id + ".properties");
        File tmp = new File(stateDir(), id + ".properties.tmp");
        try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) { props.store(w,"WorldMemory alpha.48 actor state"); }
        try { Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    }

    private static synchronized void history(UUID id, String actor, String event, String dialogue, String node, String detail) {
        try {
            File f = new File(historyDir(), id + ".log");
            String line = Instant.now()+"\t"+esc(actor)+"\t"+esc(event)+"\t"+esc(dialogue)+"\t"+esc(node)+"\t"+esc(detail)+System.lineSeparator();
            Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            List<String> lines = Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);
            if (lines.size() > HISTORY_LIMIT) {
                List<String> tail = lines.subList(lines.size()-HISTORY_LIMIT,lines.size());
                Path tmp = new File(historyDir(), id + ".log.tmp").toPath();
                Files.write(tmp,tail,StandardCharsets.UTF_8);
                try { Files.move(tmp,f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp,f.toPath(),StandardCopyOption.REPLACE_EXISTING); }
            }
        } catch (Throwable ignored) {}
    }

    // ---- loading ----

    private static void loadProfiles() {
        File dir = actorsDir();
        File[] files = dir.listFiles((d,n)->n.endsWith(".yml")||n.endsWith(".yaml"));
        if (files == null) return;
        for (File f : files) {
            try {
                Object y = loadYaml(f);
                String id = yamlString(y,"id",stripExt(f.getName()));
                ActorProfile p = new ActorProfile(id);
                p.displayName = yamlString(y,"display-name",human(id));
                p.aliases.addAll(stringList(y,"aliases"));
                p.aliases.addAll(stringList(y,"entity-match"));
                p.defaultEmotion = yamlString(y,"state.default-emotion",yamlString(y,"default-emotion","neutral"));
                p.defaultPose = yamlString(y,"state.default-pose",yamlString(y,"default-pose","standing"));
                p.lookAtPlayer = yamlBool(y,"presentation.look-at-player",false);
                Object rel = section(y,"relationships");
                if (rel != null) for (String k : keys(rel)) p.defaults.put(norm(k), yamlDouble(rel,k,0));
                Object cues = section(y,"cues");
                if (cues != null) for (String emotion : keys(cues)) {
                    Object s = section(cues,emotion); if (s==null) continue;
                    ActorCue c = new ActorCue();
                    c.pose=yamlString(s,"pose",""); c.sound=yamlString(s,"sound","");
                    c.volume=yamlDouble(s,"volume",0.35); c.pitch=yamlDouble(s,"pitch",1.0);
                    if (hasPath(s,"glowing")) c.glowing=yamlBool(s,"glowing",false);
                    p.cues.put(norm(emotion),c);
                }
                PROFILES.put(id,p);
            } catch (Throwable t) { logger().warning("Actor profile load failed " + f.getName()+": "+shortError(t)); }
        }
    }

    private static void loadDialogueMetadata() {
        File dir = new File(dataFolder(),"content/dialogue");
        File[] files = dir.listFiles((d,n)->n.endsWith(".yml")||n.endsWith(".yaml"));
        if (files == null) return;
        for (File f : files) {
            try {
                Object y=loadYaml(f); String id=yamlString(y,"id",stripExt(f.getName()));
                DialogueMeta dm=new DialogueMeta(id); Object nodes=section(y,"nodes");
                if (nodes!=null) for (String nodeId: keys(nodes)) {
                    Object ns=section(nodes,nodeId); if(ns==null)continue;
                    NodeMeta nm=new NodeMeta();
                    nm.emotion=yamlString(ns,"emotion",""); nm.pose=yamlString(ns,"pose",""); nm.event=yamlString(ns,"event","");
                    nm.lookAtPlayer=yamlBool(ns,"look-at-player",false);
                    readRelationshipAction(ns,"relationship-on-enter",nm.add,nm.set);
                    nm.flags.putAll(boolMap(section(ns,"actor-flags-on-enter")));
                    nm.commands.addAll(stringList(ns,"commands-on-enter"));
                    List<Map<String,Object>> choices=mapList(ns,"choices");
                    for(Map<String,Object> cm:choices){
                        String cid=str(cm.get("id")); if(cid.isBlank())continue;
                        ChoiceMeta meta=new ChoiceMeta(); meta.actor=str(cm.get("actor"));
                        meta.emotion=str(cm.get("emotion")); meta.pose=str(cm.get("pose")); meta.event=str(cm.get("event"));
                        meta.lookAtPlayer=bool(cm.get("look-at-player"));
                        readRelationshipActionMap(cm.get("relationship"),meta.add,meta.set);
                        meta.flags.putAll(boolMapObject(cm.get("actor-flags")));
                        meta.commands.addAll(stringListObject(cm.get("commands")));
                        nm.choices.put(cid,meta);
                    }
                    dm.nodes.put(nodeId,nm);
                }
                DIALOGUE_META.put(id,dm);
            } catch(Throwable t){ logger().warning("Dialogue actor metadata load failed "+f.getName()+": "+shortError(t)); }
        }
    }

    private static void readRelationshipAction(Object section, String path, Map<String,Double> add, Map<String,Double> set){
        try { Object x=section(section,path); readRelationshipActionMap(x,add,set); } catch(Throwable ignored){}
    }
    private static void readRelationshipActionMap(Object obj, Map<String,Double> add, Map<String,Double> set){
        if(obj==null)return;
        try {
            Object a = obj instanceof Map<?,?> m ? m.get("add") : section(obj,"add");
            Object s = obj instanceof Map<?,?> m ? m.get("set") : section(obj,"set");
            add.putAll(doubleMapObject(a)); set.putAll(doubleMapObject(s));
        } catch(Throwable ignored){}
    }

    // ---- /actor command ----

    private static void registerCommand() throws Exception {
        Object cmd=call(plugin,"getCommand","actor");
        if(cmd==null){logger().warning("/actor missing from plugin.yml");return;}
        ClassLoader cl=plugin.getClass().getClassLoader();
        Class<?> exec=Class.forName("org.bukkit.command.CommandExecutor",false,cl);
        Class<?> tab=Class.forName("org.bukkit.command.TabCompleter",false,cl);
        commandProxy=Proxy.newProxyInstance(cl,new Class<?>[]{exec,tab},(p,m,a)->{
            if(m.getName().equals("onCommand")){ try{return handleCommand(a[0],(String[])a[3]);}catch(Throwable t){send(a[0],"§cActor command failed: "+shortError(t));return true;} }
            if(m.getName().equals("onTabComplete")){ try{return tabComplete((String[])a[3]);}catch(Throwable t){return List.of();} }
            if(m.getName().equals("toString"))return "WorldMemoryNarrativeActors";
            return primitiveDefault(m.getReturnType());
        });
        call(cmd,"setExecutor",commandProxy); call(cmd,"setTabCompleter",commandProxy);
    }

    private static boolean handleCommand(Object sender,String[] args)throws Exception{
        Object self=isPlayer(sender)?sender:null;
        if(args.length==0||eq(args[0],"help")){help(sender);return true;}
        String sub=norm(args[0]);
        switch(sub){
            case "list" -> listActors(sender);
            case "inspect" -> { if(args.length<2){send(sender,"§eUsage: /actor inspect <actor-id>");break;} inspectActor(sender,args[1]); }
            case "status" -> {
                Object player=args.length>=3?requireOnlineAdmin(sender,args[2]):requirePlayer(sender);
                String actor=args.length>=2?args[1]:inferActor(player);
                if(actor.isBlank()){send(sender,"§eUsage: /actor status <actor-id> [player]");break;}
                status(sender,player,actor);
            }
            case "history" -> {
                Object player=args.length>=3?requireOnlineAdmin(sender,args[2]):requirePlayer(sender);
                String actor=args.length>=2?args[1]:""; showHistory(sender,player,actor);
            }
            case "set","add" -> {
                requireAdmin(sender); if(args.length<4){send(sender,"§eUsage: /actor "+sub+" <actor> <relationship> <value> [player]");break;}
                Object player=args.length>=5?requireOnline(sender,args[4]):requirePlayer(sender);
                mutateRelationship(sender,player,args[1],args[2],num(args[3],0),sub.equals("add"));
            }
            case "flag" -> {
                requireAdmin(sender); if(args.length<4){send(sender,"§eUsage: /actor flag <actor> <flag> <true|false> [player]");break;}
                Object player=args.length>=5?requireOnline(sender,args[4]):requirePlayer(sender);
                mutateFlag(sender,player,args[1],args[2],Boolean.parseBoolean(args[3]));
            }
            case "emotion","pose" -> {
                requireAdmin(sender); if(args.length<3){send(sender,"§eUsage: /actor "+sub+" <actor> <value> [player]");break;}
                Object player=args.length>=4?requireOnline(sender,args[3]):requirePlayer(sender);
                mutateCue(sender,player,args[1],sub,args[2]);
            }
            case "reset" -> {
                requireAdmin(sender); if(args.length<2){send(sender,"§eUsage: /actor reset <actor> [player]");break;}
                Object player=args.length>=3?requireOnline(sender,args[2]):requirePlayer(sender);
                UUID id=uuid(player); state(id).actors.remove(args[1]); ensureActorDefaults(id,args[1]); saveState(id,state(id)); send(sender,"§aReset actor state for §e"+args[1]+" §aon §f"+playerName(player));
            }
            case "reload" -> { requireAdmin(sender); reload(); send(sender,"§aActor profiles/dialogue metadata reloaded. §7profiles="+PROFILES.size()+" dialogueMeta="+DIALOGUE_META.size()); }
            case "validate" -> validate(sender);
            default -> help(sender);
        }
        return true;
    }

    private static void help(Object s){
        send(s,"§6--- WorldMemory Actor/Dialogue ---");
        send(s,"§e/actor list §7- actor profiles");
        send(s,"§e/actor inspect <actor> §7- profile/defaults/cues");
        send(s,"§e/actor status <actor> [player] §7- relationship/state");
        send(s,"§e/actor history [actor] [player] §7- recent conversation history");
        send(s,"§e/actor set|add <actor> <relationship> <value> [player]");
        send(s,"§e/actor flag <actor> <flag> <true|false> [player]");
        send(s,"§e/actor emotion|pose <actor> <value> [player]");
        send(s,"§e/actor reset <actor> [player] §8| §e/actor validate §8| §e/actor reload");
    }

    private static void listActors(Object s){send(s,"§6--- Actors ("+PROFILES.size()+") ---");PROFILES.keySet().stream().sorted().forEach(id->send(s,"§e"+id+" §8- §7"+profile(id).displayName));}
    private static void inspectActor(Object s,String id){ActorProfile p=profile(id);send(s,"§6--- Actor: "+id+" ---");send(s,"§7Name: §f"+p.displayName);send(s,"§7Aliases: §f"+p.aliases);send(s,"§7Defaults: §f"+p.defaults);send(s,"§7Emotion/Pose: §f"+p.defaultEmotion+" / "+p.defaultPose);send(s,"§7Look at player: §f"+p.lookAtPlayer);send(s,"§7Emotion cues: §f"+p.cues.keySet());}
    private static void status(Object s,Object player,String actor)throws Exception{UUID id=uuid(player);ensureActorDefaults(id,actor);ActorState as=actorState(id,actor);send(s,"§6--- Actor State: "+actor+" ---");send(s,"§7Player: §f"+playerName(player));send(s,"§7Relationships: §f"+as.relationships);send(s,"§7Flags: §f"+as.flags);send(s,"§7Emotion: §f"+as.emotion+" §8| §7Pose: §f"+as.pose);}
    private static void mutateRelationship(Object s,Object player,String actor,String rel,double value,boolean add)throws Exception{UUID id=uuid(player);ensureActorDefaults(id,actor);ActorState as=actorState(id,actor);String k=norm(rel);if(add)as.relationships.merge(k,value,Double::sum);else as.relationships.put(k,value);saveState(id,state(id));send(s,"§a"+actor+"."+k+" = §f"+trim(as.relationships.get(k))+" §7("+playerName(player)+")");}
    private static void mutateFlag(Object s,Object player,String actor,String flag,boolean value)throws Exception{UUID id=uuid(player);ensureActorDefaults(id,actor);actorState(id,actor).flags.put(norm(flag),value);saveState(id,state(id));send(s,"§a"+actor+" flag "+norm(flag)+" = §f"+value);}
    private static void mutateCue(Object s,Object player,String actor,String kind,String value)throws Exception{UUID id=uuid(player);ensureActorDefaults(id,actor);ActorState as=actorState(id,actor);if(kind.equals("emotion"))as.emotion=value;else as.pose=value;saveState(id,state(id));applyActorCue(player,actor,as.emotion,as.pose,profile(actor).lookAtPlayer);send(s,"§a"+actor+" "+kind+" = §f"+value);}

    private static void showHistory(Object s,Object player,String actor)throws Exception{
        File f=new File(historyDir(),uuid(player)+".log"); send(s,"§6--- Conversation History: "+playerName(player)+" ---");
        if(!f.isFile()){send(s,"§7No history yet.");return;} List<String> lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8); int shown=0;
        for(int i=lines.size()-1;i>=0&&shown<15;i--){String[] p=lines.get(i).split("\\t",-1);if(p.length<6)continue;if(!actor.isBlank()&&!p[1].equalsIgnoreCase(actor))continue;send(s,"§8"+shortTime(p[0])+" §e"+p[2]+" §7"+p[1]+" §8"+p[3]+"/"+p[4]+(p[5].isBlank()?"":" §f"+p[5]));shown++;}
        if(shown==0)send(s,"§7No matching history entries.");
    }

    private static void validate(Object s){
        int errors=0,warnings=0; send(s,"§6--- Actor/Dialogue Validation ---");
        for(DialogueMeta dm:DIALOGUE_META.values())for(Map.Entry<String,NodeMeta> ne:dm.nodes.entrySet()){
            NodeMeta nm=ne.getValue(); for(ChoiceMeta cm:nm.choices.values()) if(!cm.actor.isBlank()&&!PROFILES.containsKey(cm.actor)){warnings++;send(s,"§eWarning: §7"+dm.id+"/"+ne.getKey()+" choice references actor without profile: §f"+cm.actor);}
        }
        send(s,(errors==0?"§a":"§c")+"Result: "+(errors==0?"READY":"FAILED")+" §8| §7profiles=§f"+PROFILES.size()+" §7dialogueMeta=§f"+DIALOGUE_META.size()+" §7warnings=§f"+warnings);
    }

    private static List<String> tabComplete(String[] a){
        if(a.length==1)return match(a[0],List.of("help","list","inspect","status","history","set","add","flag","emotion","pose","reset","validate","reload"));
        if(a.length==2&&List.of("inspect","status","set","add","flag","emotion","pose","reset","history").contains(norm(a[0])))return match(a[1],new ArrayList<>(PROFILES.keySet()));
        if(a.length==3&&(eq(a[0],"set")||eq(a[0],"add")))return match(a[2],List.of("trust","respect","fear","affinity"));
        return List.of();
    }

    // ---- utility/reflection ----

    @SuppressWarnings("unchecked") private static Map<String,Object> actorCondition(Map<String,Object> c){Object n=c.get("actor-condition");if(n instanceof Map<?,?> m){LinkedHashMap<String,Object>x=new LinkedHashMap<>();m.forEach((k,v)->x.put(str(k),v));return x;}if(c.containsKey("actor")){LinkedHashMap<String,Object>x=new LinkedHashMap<>();for(String k:List.of("actor","id","relationship","minimum","min","maximum","max","equals","flag","flag-not","not-flag","emotion","pose"))if(c.containsKey(k))x.put(k,c.get(k));return x;}return null;}
    private static String actorValue(String actor,ActorState as,String key){String k=norm(key);if(k.equals("name"))return profile(actor).displayName;if(k.equals("emotion"))return as.emotion;if(k.equals("pose"))return as.pose;if(k.startsWith("flag."))return String.valueOf(as.flags.getOrDefault(k.substring(5),false));return trim(as.relationships.getOrDefault(k,0.0));}
    private static ActorProfile profile(String id){return PROFILES.getOrDefault(id,new ActorProfile(id));}
    private static Object currentDialogueSession(UUID id)throws Exception{Field f=Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore").getDeclaredField("DIALOGUE_SESSIONS");f.setAccessible(true);Object m=f.get(null);return m instanceof Map<?,?> map?map.get(id):null;}
    private static String dialogueId(Object session)throws Exception{Object def=field(session,"definition");return str(call0(def,"id"));}
    private static Object currentNode(Object session)throws Exception{Object def=field(session,"definition");Object nodes=call0(def,"nodes");String node=str(field(session,"nodeId"));return nodes instanceof Map<?,?>m?m.get(node):null;}
    private static String inferActor(Object player)throws Exception{Object s=currentDialogueSession(uuid(player));if(s==null)return"";Object n=currentNode(s);return n==null?"":str(call0(n,"speaker"));}
    private static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static Object call0(Object o,String n)throws Exception{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}

    private static File dataFolder(){try{return (File)call(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");}}
    private static File stateDir(){return new File(dataFolder(),"narrative/actors/state");}
    private static File historyDir(){return new File(dataFolder(),"narrative/actors/history");}
    private static File actorsDir(){return new File(dataFolder(),"content/narrative/actors");}
    private static Object loadYaml(File f)throws Exception{Class<?> y=Class.forName("org.bukkit.configuration.file.YamlConfiguration",false,plugin.getClass().getClassLoader());return y.getMethod("loadConfiguration",File.class).invoke(null,f);}
    private static Object section(Object o,String p){try{return call(o,"getConfigurationSection",p);}catch(Throwable t){return null;}}
    @SuppressWarnings("unchecked") private static Set<String> keys(Object o){try{Object r=call(o,"getKeys",false);return r instanceof Set<?>s?(Set<String>)s:Set.of();}catch(Throwable t){return Set.of();}}
    private static boolean hasPath(Object o,String p){try{return bool(call(o,"contains",p));}catch(Throwable t){return false;}}
    private static String yamlString(Object o,String p,String d){try{Object r=call(o,"getString",p,d);return r==null?d:str(r);}catch(Throwable t){return d;}}
    private static boolean yamlBool(Object o,String p,boolean d){try{return bool(call(o,"getBoolean",p,d));}catch(Throwable t){return d;}}
    private static double yamlDouble(Object o,String p,double d){try{return num(call(o,"getDouble",p,d),d);}catch(Throwable t){return d;}}
    private static List<String> stringList(Object o,String p){try{return stringListObject(call(o,"getStringList",p));}catch(Throwable t){return List.of();}}
    private static List<String> stringListObject(Object o){if(o instanceof Iterable<?>it){ArrayList<String>x=new ArrayList<>();for(Object v:it)x.add(str(v));return x;}if(o instanceof String s&&!s.isBlank())return List.of(s);return List.of();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> mapList(Object o,String p){try{Object r=call(o,"getMapList",p);if(r instanceof List<?> l){ArrayList<Map<String,Object>>x=new ArrayList<>();for(Object v:l)if(v instanceof Map<?,?>m){LinkedHashMap<String,Object>y=new LinkedHashMap<>();m.forEach((k,z)->y.put(str(k),z));x.add(y);}return x;}}catch(Throwable ignored){}return List.of();}
    private static Map<String,Boolean> boolMap(Object o){LinkedHashMap<String,Boolean>x=new LinkedHashMap<>();if(o!=null)for(String k:keys(o))try{x.put(norm(k),bool(call(o,"get",k)));}catch(Throwable ignored){}return x;}
    private static Map<String,Boolean> boolMapObject(Object o){LinkedHashMap<String,Boolean>x=new LinkedHashMap<>();if(o instanceof Map<?,?>m)m.forEach((k,v)->x.put(norm(str(k)),bool(v)));return x;}
    private static Map<String,Double> doubleMapObject(Object o){LinkedHashMap<String,Double>x=new LinkedHashMap<>();if(o instanceof Map<?,?>m)m.forEach((k,v)->x.put(norm(str(k)),num(v,0)));else if(o!=null)for(String k:keys(o))try{x.put(norm(k),num(call(o,"get",k),0));}catch(Throwable ignored){}return x;}

    private static Object requirePlayer(Object s){if(!isPlayer(s))throw new IllegalArgumentException("This command requires a player.");return s;}
    private static Object requireOnlineAdmin(Object s,String name)throws Exception{requireAdmin(s);return requireOnline(s,name);}
    private static Object requireOnline(Object s,String name)throws Exception{Object server=call(plugin,"getServer");Object p=call(server,"getPlayerExact",name);if(p==null)throw new IllegalArgumentException("Player is not online: "+name);return p;}
    private static void requireAdmin(Object s){if(!hasPermission(s,ADMIN))throw new IllegalArgumentException("You need "+ADMIN+".");}
    private static boolean hasPermission(Object s,String p){try{return bool(call(s,"hasPermission",p));}catch(Throwable t){return false;}}
    private static boolean isPlayer(Object o){try{Class<?> p=Class.forName("org.bukkit.entity.Player",false,plugin.getClass().getClassLoader());return p.isInstance(o);}catch(Throwable t){return o!=null&&o.getClass().getName().endsWith("Player");}}
    private static UUID uuid(Object p)throws Exception{return (UUID)call(p,"getUniqueId");}
    private static String playerName(Object p){try{return str(call(p,"getName"));}catch(Throwable t){return"player";}}
    private static void send(Object s,String m){try{call(s,"sendMessage",PREFIX+m);}catch(Throwable ignored){}}
    private static Logger logger(){try{return (Logger)call(plugin,"getLogger");}catch(Throwable t){return Logger.getLogger("WorldMemory");}}
    private static void logFine(String m,Throwable t){try{logger().fine(m+": "+shortError(t));}catch(Throwable ignored){}}
    private static Object call(Object target,String name,Object...args)throws Exception{Method m=findCompatible(target.getClass(),name,args);if(m==null)throw new NoSuchMethodException(target.getClass().getName()+"."+name);m.setAccessible(true);return m.invoke(target,args);}
    private static Object tryInvoke(Object target,String name,Object...args){try{return call(target,name,args);}catch(Throwable t){return null;}}
    private static Method findCompatible(Class<?> c,String name,Object[] args){for(Class<?> x=c;x!=null;x=x.getSuperclass())for(Method m:x.getDeclaredMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;for(Method m:c.getMethods())if(m.getName().equals(name)&&compatible(m.getParameterTypes(),args))return m;return null;}
    private static boolean compatible(Class<?>[] p,Object[] a){if(p.length!=a.length)return false;for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;continue;}if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?> wrap(Class<?> c){if(!c.isPrimitive())return c;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==char.class)return Character.class;return c;}
    private static Object primitiveDefault(Class<?> c){if(!c.isPrimitive())return null;if(c==boolean.class)return false;if(c==char.class)return'\0';return 0;}
    private static boolean matches(String a,String b){String x=norm(a),y=norm(b);return !x.isBlank()&&!y.isBlank()&&(x.equals(y)||y.contains(x)||x.contains(y));}
    private static String norm(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT).replace(' ','_');}
    private static boolean eq(String a,String b){return a!=null&&a.equalsIgnoreCase(b);}
    private static String nonBlank(String a,String b){return a!=null&&!a.isBlank()?a:(b==null?"":b);}
    private static String str(Object o){return o==null?"":String.valueOf(o);}
    private static boolean bool(Object o){if(o instanceof Boolean b)return b;return Boolean.parseBoolean(str(o));}
    private static double num(Object o,double d){if(o instanceof Number n)return n.doubleValue();try{return Double.parseDouble(str(o));}catch(Throwable t){return d;}}
    private static String trim(double d){if(Math.rint(d)==d)return Long.toString((long)d);return String.format(Locale.ROOT,"%.2f",d).replaceAll("0+$","").replaceAll("\\.$","");}
    private static String encActor(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(str(s).getBytes(StandardCharsets.UTF_8));}
    private static String decActor(String s){try{return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}catch(Throwable t){return s;}}
    private static String esc(String s){return str(s).replace("\t"," ").replace("\r"," ").replace("\n"," ");}
    private static String shortTime(String iso){try{return iso.length()>=19?iso.substring(11,19):iso;}catch(Throwable t){return iso;}}
    private static String shortError(Throwable t){Throwable x=t instanceof InvocationTargetException&&t.getCause()!=null?t.getCause():t;return x.getClass().getSimpleName()+": "+str(x.getMessage());}
    private static String stripExt(String n){int i=n.lastIndexOf('.');return i>0?n.substring(0,i):n;}
    private static String human(String s){String x=s;int i=x.lastIndexOf('.');if(i>=0)x=x.substring(i+1);StringBuilder b=new StringBuilder();for(String p:x.replace('_',' ').split(" "))if(!p.isBlank())b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');return b.toString().trim();}
    private static List<String> match(String q,List<String> values){String n=norm(q);return values.stream().filter(v->norm(v).startsWith(n)).sorted().limit(50).toList();}

    private static final class PlayerState { final Map<String,ActorState> actors=new LinkedHashMap<>(); }
    private static final class ActorState { final Map<String,Double> relationships=new LinkedHashMap<>(); final Map<String,Boolean> flags=new LinkedHashMap<>(); String emotion=""; String pose=""; }
    private static final class ActorProfile { final String id; String displayName; final List<String> aliases=new ArrayList<>(); final Map<String,Double> defaults=new LinkedHashMap<>(); String defaultEmotion="neutral",defaultPose="standing"; boolean lookAtPlayer; final Map<String,ActorCue> cues=new LinkedHashMap<>(); ActorProfile(String id){this.id=id;this.displayName=human(id);} }
    private static final class ActorCue { String pose="",sound=""; double volume=.35,pitch=1; Boolean glowing; }
    private static final class DialogueMeta { final String id; final Map<String,NodeMeta> nodes=new LinkedHashMap<>(); DialogueMeta(String id){this.id=id;} ChoiceMeta choice(String n,String c){NodeMeta x=nodes.get(n);return x==null?null:x.choices.get(c);} }
    private static final class NodeMeta { String emotion="",pose="",event="";boolean lookAtPlayer;final Map<String,Double> add=new LinkedHashMap<>(),set=new LinkedHashMap<>();final Map<String,Boolean> flags=new LinkedHashMap<>();final List<String> commands=new ArrayList<>();final Map<String,ChoiceMeta> choices=new LinkedHashMap<>(); }
    private static final class ChoiceMeta { String actor="",emotion="",pose="",event="";boolean lookAtPlayer;final Map<String,Double> add=new LinkedHashMap<>(),set=new LinkedHashMap<>();final Map<String,Boolean> flags=new LinkedHashMap<>();final List<String> commands=new ArrayList<>(); }
}
