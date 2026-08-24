package com.yourstudio.worldmemory.narrative;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Alpha.49 dialogue graph / conversation flow extension.
 *
 * This class intentionally depends on Bukkit only through reflection. That keeps
 * the binary surface narrow and avoids repeating the API-linkage mistakes fixed
 * in the animation subsystem.
 */
public final class NarrativeFlow {
    private static final String PREFIX = "\u00a78[\u00a7dWorldMemory\u00a78] \u00a7r";
    private static final String ADMIN = "worldmemory.narrative.admin";
    private static volatile Object plugin;
    private static volatile boolean started;
    private static volatile Object commandProxy;

    private static final Map<String, DialogueFlow> FLOWS = new ConcurrentHashMap<>();
    private static final Map<String, ActorAmbient> AMBIENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, FlowState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<String>> CALL_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> ACTIVE_SESSIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<UUID> CURRENT_PLAYER = new ThreadLocal<>();
    private static final List<String> ERRORS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> WARNINGS = Collections.synchronizedList(new ArrayList<>());

    private static final long DEFAULT_AMBIENT_INTERVAL = 60L;
    private static volatile long ambientIntervalTicks = DEFAULT_AMBIENT_INTERVAL;
    private static volatile boolean ambientSchedulerArmed;

    private NarrativeFlow() {}

    public static synchronized void startup(Object p) {
        plugin = p;
        started = true;
        reload();
        registerCommand();
        armAmbientScheduler();
    }

    public static synchronized void shutdown() {
        started = false;
        ambientSchedulerArmed = false;
        for (Map.Entry<UUID, FlowState> e : STATES.entrySet()) {
            try { saveState(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
        }
        CALL_STACKS.clear();
        ACTIVE_SESSIONS.clear();
        CURRENT_PLAYER.remove();
        plugin = null;
    }

    public static synchronized void reload() {
        FLOWS.clear();
        AMBIENTS.clear();
        ERRORS.clear();
        WARNINGS.clear();
        ambientIntervalTicks = DEFAULT_AMBIENT_INTERVAL;
        if (plugin == null) return;
        try {
            loadGlobalSettings();
            loadDialogueFlow();
            composeImportedFlow();
            loadAmbientActors();
            validateInternal();
        } catch (Throwable t) {
            ERRORS.add("Conversation flow reload failed: " + shortError(t));
            log("Conversation flow reload failed", t);
        }
    }

    /** Called before NarrativeCore resolves the current DialogueNode. */
    public static void enterRender(Object player, Object session) {
        try {
            UUID id = uuid(player);
            CURRENT_PLAYER.set(id);
            ACTIVE_SESSIONS.put(id, session);

            String dialogue = dialogueId(session);
            if (dialogue.isBlank()) return;
            DialogueFlow df = FLOWS.get(dialogue);
            if (df == null) {
                markVisit(id, dialogue, nodeId(session));
                return;
            }

            Deque<String> stack = CALL_STACKS.computeIfAbsent(id, k -> new ArrayDeque<>());
            Set<String> guard = new HashSet<>();
            for (int hop = 0; hop < 24; hop++) {
                String node = nodeId(session);
                if (node.isBlank() || !guard.add(node + "@" + stack.size())) break;
                FlowNode meta = df.nodes.get(node);
                if (meta == null) {
                    markVisit(id, dialogue, node);
                    break;
                }

                if (meta.returnNode) {
                    String ret = stack.pollLast();
                    if (ret != null && !ret.isBlank()) {
                        setNodeId(session, ret);
                        continue;
                    }
                    if (!meta.fallback.isBlank()) {
                        setNodeId(session, meta.fallback);
                        continue;
                    }
                }

                if (!meta.call.isBlank()) {
                    String ret = !meta.returnTo.isBlank() ? meta.returnTo : coreNext(dialogue, node);
                    if (ret != null && !ret.isBlank()) stack.addLast(ret);
                    setNodeId(session, meta.call);
                    continue;
                }

                if (!available(id, dialogue, node, meta.once, meta.cooldownSeconds)) {
                    String fallback = !meta.fallback.isBlank() ? meta.fallback : coreNext(dialogue, node);
                    if (fallback != null && !fallback.isBlank() && !fallback.equals(node)) {
                        setNodeId(session, fallback);
                        continue;
                    }
                }

                if (!meta.variants.isEmpty()) {
                    Variant v = chooseVariant(player, dialogue, node, meta.variants);
                    if (v != null && !v.node.isBlank() && !v.node.equals(node)) {
                        markVisit(id, dialogue, "variant:" + node + ":" + v.idKey());
                        setNodeId(session, v.node);
                        continue;
                    }
                    if (!meta.fallback.isBlank()) {
                        setNodeId(session, meta.fallback);
                        continue;
                    }
                }

                markVisit(id, dialogue, node);
                break;
            }
        } catch (Throwable t) {
            log("Conversation enter-render hook failed", t);
        }
    }

    public static void exitRender() {
        CURRENT_PLAYER.remove();
    }

    public static void onDialogueFinish(Object player, Object session) {
        try {
            UUID id = uuid(player);
            ACTIVE_SESSIONS.remove(id, session);
            CALL_STACKS.remove(id);
        } catch (Throwable ignored) {}
    }

    public static void onChoice(Object player, Object choice) {
        try {
            UUID id = uuid(player);
            Object session = currentDialogueSession(id);
            String dialogue = session == null ? "" : dialogueId(session);
            String node = session == null ? "" : nodeId(session);
            String choiceId = str(call0(choice, "id"));
            if (!dialogue.isBlank() && !choiceId.isBlank()) {
                markVisit(id, dialogue, "choice:" + node + ":" + choiceId);
            }
        } catch (Throwable t) {
            log("Conversation choice hook failed", t);
        }
    }

    /** Adds flow-aware predicates to the existing `when:` map. */
    public static boolean conditionsMatch(Object context, Map<String, Object> when) {
        if (when == null || when.isEmpty()) return true;
        Object raw = first(when, "conversation-condition", "flow-condition");
        if (!(raw instanceof Map<?, ?> map)) return true;
        try {
            UUID id = currentOrContextPlayer(context);
            if (id == null) return true;
            Map<String, Object> c = stringMap(map);
            String dialogue = str(first(c, "dialogue", "dialogue-id"));
            String node = str(first(c, "node", "node-id"));
            String choice = str(c.get("choice"));
            if (dialogue.isBlank()) {
                Object session = currentDialogueSession(id);
                if (session != null) dialogue = dialogueId(session);
            }
            if (node.isBlank() && !choice.isBlank()) {
                Object session = currentDialogueSession(id);
                String current = session == null ? "" : nodeId(session);
                node = "choice:" + current + ":" + choice;
            }
            if (dialogue.isBlank() || node.isBlank()) return true;

            Visit v = visit(id, dialogue, node);
            if (c.containsKey("visited") && bool(c.get("visited")) != (v.visits > 0)) return false;
            if (c.containsKey("visits-min") && v.visits < integer(c.get("visits-min"), 0)) return false;
            if (c.containsKey("visits-max") && v.visits > integer(c.get("visits-max"), Integer.MAX_VALUE)) return false;
            if (c.containsKey("cooldown-ready")) {
                long sec = longNum(first(c, "cooldown-seconds", "cooldown"), 0L);
                boolean ready = sec <= 0 || System.currentTimeMillis() - v.lastMillis >= sec * 1000L;
                if (bool(c.get("cooldown-ready")) != ready) return false;
            }
            return true;
        } catch (Throwable t) {
            log("Conversation condition failed", t);
            return true;
        }
    }

    public static Map<String, Object> stripConditions(Map<String, Object> when) {
        if (when == null || when.isEmpty()) return when;
        Map<String, Object> out = new LinkedHashMap<>(when);
        out.remove("conversation-condition");
        out.remove("flow-condition");
        return out;
    }

    public static String interpolate(Object player, String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            UUID id = uuid(player);
            String result = text;
            // Simple, explicit tokens: %conversation:visits:<dialogue>:<node>% and %conversation:seen:...%
            int guard = 0;
            while (guard++ < 32) {
                int start = result.indexOf("%conversation:");
                if (start < 0) break;
                int end = result.indexOf('%', start + 1);
                if (end < 0) break;
                String token = result.substring(start + 1, end);
                String[] p = token.split(":", 4);
                if (p.length < 4) break;
                String mode = p[1];
                String dialogue = p[2];
                String node = p[3];
                Visit v = visit(id, dialogue, node);
                String value = mode.equalsIgnoreCase("seen") ? String.valueOf(v.visits > 0) : String.valueOf(v.visits);
                result = result.substring(0, start) + value + result.substring(end + 1);
            }
            return result;
        } catch (Throwable ignored) {
            return text;
        }
    }


    public static int dialoguePriority(String dialogue) {
        DialogueFlow f = FLOWS.get(dialogue);
        return f == null ? 0 : f.priority;
    }

    public static boolean dialogueInterruptible(String dialogue) {
        DialogueFlow f = FLOWS.get(dialogue);
        return f == null || f.interruptible;
    }

    public static List<String> snapshotCallStack(UUID playerId) {
        Deque<String> s = CALL_STACKS.get(playerId);
        return s == null ? List.of() : List.copyOf(s);
    }

    public static void restoreCallStack(UUID playerId, List<String> values) {
        if (playerId == null) return;
        if (values == null || values.isEmpty()) { CALL_STACKS.remove(playerId); return; }
        CALL_STACKS.put(playerId, new ArrayDeque<>(values));
    }

    /** Merge alpha.50 imported flow metadata into the consumer dialogue graph. */
    private static void composeImportedFlow() {
        for (Map.Entry<String, List<NarrativeComposition.ImportSpec>> e : NarrativeComposition.describeImportSpecs().entrySet()) {
            String consumerId = e.getKey();
            DialogueFlow consumer = FLOWS.computeIfAbsent(consumerId, DialogueFlow::new);
            for (NarrativeComposition.ImportSpec spec : e.getValue()) {
                DialogueFlow source = FLOWS.get(spec.dialogue);
                if (source == null) continue;
                for (Map.Entry<String, FlowNode> n : source.nodes.entrySet()) {
                    String newId = spec.alias + "." + n.getKey();
                    if (consumer.nodes.containsKey(newId)) continue;
                    consumer.nodes.put(newId, copyFlowNode(n.getValue(), spec.alias, source.nodes.keySet()));
                }
            }
        }
    }

    private static FlowNode copyFlowNode(FlowNode in, String alias, Set<String> sourceIds) {
        FlowNode out = new FlowNode();
        out.once = in.once;
        out.cooldownSeconds = in.cooldownSeconds;
        out.fallback = rewriteImportedTarget(in.fallback, alias, sourceIds);
        out.call = rewriteImportedTarget(in.call, alias, sourceIds);
        out.returnTo = rewriteImportedTarget(in.returnTo, alias, sourceIds);
        out.returnNode = in.returnNode;
        for (Variant v : in.variants) {
            Variant x = new Variant();
            x.id = v.id; x.node = rewriteImportedTarget(v.node, alias, sourceIds);
            x.weight = v.weight; x.once = v.once; x.cooldownSeconds = v.cooldownSeconds; x.when = v.when;
            out.variants.add(x);
        }
        return out;
    }

    private static String rewriteImportedTarget(String target, String alias, Set<String> sourceIds) {
        return sourceIds.contains(target) ? alias + "." + target : target;
    }

    private static Variant chooseVariant(Object player, String dialogue, String dispatcher, List<Variant> variants) {
        UUID id;
        try { id = uuid(player); } catch (Throwable t) { return null; }
        List<Variant> eligible = new ArrayList<>();
        double total = 0.0;
        for (Variant v : variants) {
            String key = "variant:" + dispatcher + ":" + v.idKey();
            if (!available(id, dialogue, key, v.once, v.cooldownSeconds)) continue;
            if (!v.when.isEmpty() && !coreConditionMatches(player, v.when)) continue;
            double w = Math.max(0.0, v.weight);
            if (w <= 0) continue;
            eligible.add(v);
            total += w;
        }
        if (eligible.isEmpty()) return null;
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (Variant v : eligible) {
            r -= Math.max(0.0, v.weight);
            if (r <= 0) return v;
        }
        return eligible.get(eligible.size() - 1);
    }

    private static boolean coreConditionMatches(Object player, Map<String, Object> when) {
        try {
            UUID id = uuid(player);
            Object story = staticMap("STORY_SESSIONS").get(id);
            if (story == null) {
                // Standalone dialogues do not have a StorySession. Actor/flow
                // conditions can still be evaluated against the player.
                PlayerContext ctx = new PlayerContext(id);
                if (!NarrativeActors.actorConditionsMatch(ctx, when)) return false;
                return conditionsMatch(ctx, when);
            }
            for (Method m : coreClass().getDeclaredMethods()) {
                if (m.getName().equals("conditionMatches") && m.getParameterCount() == 2) {
                    m.setAccessible(true);
                    return bool(m.invoke(null, story, when));
                }
            }
        } catch (Throwable t) {
            log("Variant condition check failed", t);
        }
        return true;
    }

    private static boolean available(UUID id, String dialogue, String node, boolean once, long cooldownSeconds) {
        Visit v = visit(id, dialogue, node);
        if (once && v.visits > 0) return false;
        return cooldownSeconds <= 0 || System.currentTimeMillis() - v.lastMillis >= cooldownSeconds * 1000L;
    }

    private static void markVisit(UUID id, String dialogue, String node) {
        if (id == null || dialogue == null || dialogue.isBlank() || node == null || node.isBlank()) return;
        FlowState state = state(id);
        String key = dialogue + "#" + node;
        Visit v = state.visits.computeIfAbsent(key, k -> new Visit());
        v.visits++;
        v.lastMillis = System.currentTimeMillis();
        try { saveState(id, state); } catch (Throwable t) { log("Could not persist conversation state", t); }
    }

    private static Visit visit(UUID id, String dialogue, String node) {
        FlowState s = state(id);
        Visit v = s.visits.get(dialogue + "#" + node);
        return v == null ? Visit.EMPTY : v;
    }

    private static FlowState state(UUID id) {
        return STATES.computeIfAbsent(id, NarrativeFlow::loadState);
    }

    private static FlowState loadState(UUID id) {
        FlowState state = new FlowState();
        File file = stateFile(id);
        if (!file.isFile()) return state;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            p.load(r);
            for (String name : p.stringPropertyNames()) {
                if (!name.endsWith(".visits")) continue;
                String enc = name.substring(0, name.length() - 7);
                String key = decode(enc);
                Visit v = new Visit();
                v.visits = integer(p.getProperty(name), 0);
                v.lastMillis = longNum(p.getProperty(enc + ".last"), 0L);
                state.visits.put(key, v);
            }
        } catch (Throwable t) {
            WARNINGS.add("Could not read conversation state for " + id + ": " + shortError(t));
        }
        return state;
    }

    private static synchronized void saveState(UUID id, FlowState state) throws IOException {
        File file = stateFile(id);
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        Properties p = new Properties();
        for (Map.Entry<String, Visit> e : state.visits.entrySet()) {
            String enc = encode(e.getKey());
            p.setProperty(enc + ".visits", String.valueOf(e.getValue().visits));
            p.setProperty(enc + ".last", String.valueOf(e.getValue().lastMillis));
        }
        Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "WorldMemory alpha.49 conversation flow state");
        }
        try {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------------
    // Dialogue metadata / graph loading
    // ---------------------------------------------------------------------

    private static void loadDialogueFlow() throws Exception {
        File dir = new File(dataFolder(), "content/dialogue");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            Object yaml = loadYaml(file);
            String id = yamlString(yaml, "id", stripExt(file.getName()));
            Object nodes = section(yaml, "nodes");
            if (nodes == null) continue;
            DialogueFlow df = new DialogueFlow(id);
            df.priority = integer(yamlGet(yaml, "flow.priority"), 0);
            df.interruptible = yamlBool(yaml, "flow.interruptible", true);
            for (String nodeId : keys(nodes)) {
                Object node = section(nodes, nodeId);
                if (node == null) continue;
                FlowNode fn = new FlowNode();
                fn.once = bool(firstPath(node, "flow.once", "once"));
                fn.cooldownSeconds = longNum(firstPath(node, "flow.cooldown-seconds", "cooldown-seconds"), 0L);
                fn.fallback = str(firstPath(node, "flow.fallback", "fallback"));
                fn.call = str(firstPath(node, "flow.call", "call"));
                if (fn.call.startsWith("{")) fn.call = "";
                fn.returnTo = str(firstPath(node, "flow.return-to", "return-to"));
                fn.returnNode = bool(firstPath(node, "flow.return", "return"));
                Object variantRaw = firstPath(node, "flow.variants", "variants");
                if (variantRaw instanceof List<?> list) {
                    int index = 0;
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> m)) continue;
                        Map<String, Object> vm = stringMap(m);
                        Variant v = new Variant();
                        v.id = nonBlank(str(vm.get("id")), "v" + (++index));
                        v.node = str(first(vm, "node", "target"));
                        v.weight = number(vm.get("weight"), 1.0);
                        v.once = bool(vm.get("once"));
                        v.cooldownSeconds = longNum(first(vm, "cooldown-seconds", "cooldown"), 0L);
                        Object when = vm.get("when");
                        if (when instanceof Map<?, ?> wm) v.when = stringMap(wm);
                        if (!v.node.isBlank()) fn.variants.add(v);
                    }
                }
                if (fn.hasFlow()) df.nodes.put(nodeId, fn);
            }
            if (!df.nodes.isEmpty() || df.priority != 0 || !df.interruptible) FLOWS.put(id, df);
        }
    }

    private static void loadGlobalSettings() {
        try {
            File file = new File(dataFolder(), "content/narrative/conversation.yml");
            if (!file.isFile()) return;
            Object yaml = loadYaml(file);
            ambientIntervalTicks = Math.max(20L, longNum(yamlGet(yaml, "ambient.interval-ticks"), DEFAULT_AMBIENT_INTERVAL));
        } catch (Throwable t) {
            WARNINGS.add("Could not read conversation.yml: " + shortError(t));
        }
    }

    private static void loadAmbientActors() throws Exception {
        File dir = new File(dataFolder(), "content/narrative/actors");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        if (files == null) return;
        for (File file : files) {
            Object yaml = loadYaml(file);
            String id = yamlString(yaml, "id", stripExt(file.getName()));
            Object ambient = section(yaml, "ambient");
            if (ambient == null) continue;
            ActorAmbient a = new ActorAmbient();
            a.id = id;
            a.name = yamlString(yaml, "display-name", human(lastPart(id)));
            a.enabled = yamlBool(ambient, "enabled", false);
            a.radius = Math.max(1.0, number(yamlGet(ambient, "radius"), 8.0));
            a.cooldownSeconds = Math.max(0L, longNum(yamlGet(ambient, "cooldown-seconds"), 45L));
            a.chance = clamp(number(yamlGet(ambient, "chance"), 0.2), 0.0, 1.0);
            a.priority = integer(yamlGet(ambient, "priority"), 0);
            Object lines = yamlGet(ambient, "lines");
            if (lines instanceof List<?> list) {
                int index = 0;
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    Map<String, Object> lm = stringMap(m);
                    BarkLine line = new BarkLine();
                    line.id = nonBlank(str(lm.get("id")), "line" + (++index));
                    line.text = str(lm.get("text"));
                    line.textKey = str(first(lm, "text-key", "key"));
                    line.weight = Math.max(0.0, number(lm.get("weight"), 1.0));
                    line.once = bool(lm.get("once"));
                    line.cooldownSeconds = Math.max(0L, longNum(first(lm, "cooldown-seconds", "cooldown"), 0L));
                    if (!line.text.isBlank() || !line.textKey.isBlank()) a.lines.add(line);
                }
            }
            if (!a.lines.isEmpty()) AMBIENTS.put(id, a);
        }
    }

    private static void validateInternal() {
        Map<String, Object> dialogues = staticMap("DIALOGUES");
        for (DialogueFlow df : FLOWS.values()) {
            Object def = dialogues.get(df.id);
            if (def == null) {
                WARNINGS.add("Flow metadata references dialogue not loaded by NarrativeCore: " + df.id);
                continue;
            }
            Set<String> coreNodes = coreNodeIds(def);
            for (Map.Entry<String, FlowNode> e : df.nodes.entrySet()) {
                String node = e.getKey();
                FlowNode f = e.getValue();
                if (!coreNodes.contains(node)) ERRORS.add(df.id + ": flow node does not exist: " + node);
                if (!f.call.isBlank() && !coreNodes.contains(f.call)) ERRORS.add(df.id + "." + node + ": call target missing: " + f.call);
                if (!f.returnTo.isBlank() && !coreNodes.contains(f.returnTo)) ERRORS.add(df.id + "." + node + ": return-to missing: " + f.returnTo);
                if (!f.fallback.isBlank() && !coreNodes.contains(f.fallback)) ERRORS.add(df.id + "." + node + ": fallback missing: " + f.fallback);
                for (Variant v : f.variants) if (!coreNodes.contains(v.node)) ERRORS.add(df.id + "." + node + ": variant target missing: " + v.node);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Ambient barks
    // ---------------------------------------------------------------------

    private static synchronized void armAmbientScheduler() {
        if (!started || ambientSchedulerArmed) return;
        ambientSchedulerArmed = true;
        schedule(() -> {
            ambientSchedulerArmed = false;
            if (!started) return;
            try { ambientTick(); } catch (Throwable t) { log("Ambient bark tick failed", t); }
            armAmbientScheduler();
        }, ambientIntervalTicks);
    }

    private static void ambientTick() throws Exception {
        if (AMBIENTS.values().stream().noneMatch(a -> a.enabled)) return;
        Object server = call0(plugin, "getServer");
        Object online = call0(server, "getOnlinePlayers");
        if (!(online instanceof Iterable<?> players)) return;
        double maxRadius = AMBIENTS.values().stream().filter(a -> a.enabled).mapToDouble(a -> a.radius).max().orElse(8.0);
        for (Object player : players) {
            UUID pid = uuid(player);
            if (currentDialogueSession(pid) != null) continue; // barks never interrupt conversations
            Object nearbyObj = call(player, "getNearbyEntities", maxRadius, maxRadius, maxRadius);
            if (!(nearbyObj instanceof Iterable<?> nearby)) continue;
            List<ActorAmbient> candidates = new ArrayList<>();
            for (Object entity : nearby) {
                for (ActorAmbient a : AMBIENTS.values()) {
                    if (!a.enabled || !matchesActor(entity, a)) continue;
                    if (!ambientReady(pid, a)) continue;
                    candidates.add(a);
                }
            }
            if (candidates.isEmpty()) continue;
            int best = candidates.stream().mapToInt(a -> a.priority).max().orElse(0);
            candidates.removeIf(a -> a.priority != best);
            ActorAmbient pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            if (ThreadLocalRandom.current().nextDouble() <= pick.chance) emitBark(player, pick, false);
        }
    }

    private static boolean ambientReady(UUID player, ActorAmbient actor) {
        return available(player, "@ambient", actor.id, false, actor.cooldownSeconds);
    }

    private static boolean emitBark(Object player, ActorAmbient actor, boolean force) {
        try {
            UUID pid = uuid(player);
            if (!force && !ambientReady(pid, actor)) return false;
            List<BarkLine> eligible = new ArrayList<>();
            double total = 0;
            for (BarkLine line : actor.lines) {
                if (!available(pid, "@ambient:" + actor.id, line.id, line.once, line.cooldownSeconds)) continue;
                if (line.weight <= 0) continue;
                eligible.add(line); total += line.weight;
            }
            if (eligible.isEmpty()) return false;
            double r = ThreadLocalRandom.current().nextDouble(total);
            BarkLine chosen = eligible.get(eligible.size() - 1);
            for (BarkLine line : eligible) { r -= line.weight; if (r <= 0) { chosen = line; break; } }
            String text = resolveText(player, chosen.textKey, chosen.text);
            text = NarrativeActors.interpolate(player, text);
            text = interpolate(player, text);
            sendRaw(player, "\u00a78<\u00a7d" + actor.name + "\u00a78> \u00a77" + text);
            markVisit(pid, "@ambient", actor.id);
            markVisit(pid, "@ambient:" + actor.id, chosen.id);
            return true;
        } catch (Throwable t) {
            log("Could not emit ambient bark", t);
            return false;
        }
    }

    private static boolean matchesActor(Object entity, ActorAmbient a) {
        Set<String> candidates = new HashSet<>();
        try { candidates.add(norm(str(call0(entity, "getName")))); } catch (Throwable ignored) {}
        try { candidates.add(norm(str(call0(entity, "getCustomName")))); } catch (Throwable ignored) {}
        try {
            Object tags = call0(entity, "getScoreboardTags");
            if (tags instanceof Iterable<?> it) for (Object tag : it) candidates.add(norm(str(tag)));
        } catch (Throwable ignored) {}
        String full = norm(a.id), shortId = norm(lastPart(a.id)), display = norm(a.name);
        return candidates.contains(full) || candidates.contains(shortId) || candidates.contains(display)
                || candidates.contains(norm("wm_actor_" + a.id));
    }

    // ---------------------------------------------------------------------
    // /conversation command
    // ---------------------------------------------------------------------

    private static void registerCommand() {
        try {
            Object command = call(plugin, "getCommand", "conversation");
            if (command == null) return;
            ClassLoader cl = plugin.getClass().getClassLoader();
            Class<?> exec = Class.forName("org.bukkit.command.CommandExecutor", true, cl);
            Class<?> tab = Class.forName("org.bukkit.command.TabCompleter", true, cl);
            commandProxy = Proxy.newProxyInstance(cl, new Class<?>[]{exec, tab}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("onCommand")) {
                    Object sender = args[0];
                    String[] argv = (String[]) args[3];
                    try { return handleCommand(sender, argv); }
                    catch (SecurityException se) { send(sender, "\u00a7c" + se.getMessage()); return true; }
                    catch (Throwable t) { send(sender, "\u00a7cConversation command failed: " + shortError(t)); log("Conversation command failed", t); return true; }
                }
                if (name.equals("onTabComplete")) return tabComplete((String[]) args[3]);
                return null;
            });
            call(command, "setExecutor", commandProxy);
            call(command, "setTabCompleter", commandProxy);
        } catch (Throwable t) {
            log("Could not register /conversation", t);
        }
    }

    private static boolean handleCommand(Object sender, String[] args) throws Exception {
        if (args.length == 0 || eq(args[0], "help")) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listDialogues(sender);
            case "graph" -> { requireAdmin(sender); if (args.length < 2) send(sender, "\u00a7eUsage: /conversation graph <dialogue-id>"); else graph(sender, args[1]); }
            case "trace", "state" -> { Object target = args.length >= 2 ? requireOnlineAdmin(sender, args[1]) : requirePlayer(sender); trace(sender, target); }
            case "reset" -> { requireAdmin(sender); Object target = args.length >= 2 ? requireOnline(sender, args[1]) : requirePlayer(sender); reset(target); send(sender, "\u00a7aConversation flow state reset for " + playerName(target) + "."); }
            case "validate" -> { requireAdmin(sender); validate(sender); }
            case "reload" -> { requireAdmin(sender); NarrativeBridge.reload(); send(sender, ERRORS.isEmpty() ? "\u00a7aConversation flow reloaded." : "\u00a7cReloaded with " + ERRORS.size() + " error(s). Run /conversation validate."); }
            case "session" -> { Object target = args.length >= 2 ? requireOnlineAdmin(sender, args[1]) : requirePlayer(sender); sessionInfo(sender, target); }
            case "queue" -> { Object target = args.length >= 2 ? requireOnlineAdmin(sender, args[1]) : requirePlayer(sender); queueInfo(sender, target); }
            case "participants" -> { Object target = args.length >= 2 ? requireOnlineAdmin(sender, args[1]) : requirePlayer(sender); participantInfo(sender, target); }
            case "clear" -> { requireAdmin(sender); Object target = args.length >= 2 ? requireOnline(sender, args[1]) : requirePlayer(sender); NarrativeSessions.clearPending(target); send(sender, "\u00a7aPending/paused conversation sessions cleared for " + playerName(target) + "."); }
            case "bark" -> {
                requireAdmin(sender);
                if (args.length < 2) { send(sender, "\u00a7eUsage: /conversation bark <actor-id> [player]"); break; }
                ActorAmbient a = AMBIENTS.get(args[1]);
                if (a == null) { send(sender, "\u00a7cNo ambient bark profile for actor " + args[1] + "."); break; }
                Object target = args.length >= 3 ? requireOnline(sender, args[2]) : requirePlayer(sender);
                if (!emitBark(target, a, true)) send(sender, "\u00a7eNo eligible bark line was available.");
            }
            default -> help(sender);
        }
        return true;
    }


    private static void sessionInfo(Object sender, Object player) {
        send(sender, "\u00a7d--- Narrative Session: " + playerName(player) + " ---");
        sendRaw(sender, "\u00a77" + NarrativeSessions.sessionStatus(player));
        List<String> imports = NarrativeComposition.describeImports().getOrDefault(activeDialogueId(player), List.of());
        if (!imports.isEmpty()) sendRaw(sender, "\u00a77Imports: \u00a7f" + String.join(", ", imports));
    }

    private static void queueInfo(Object sender, Object player) {
        send(sender, "\u00a7d--- Conversation Queue: " + playerName(player) + " ---");
        List<String> q = NarrativeSessions.queueStatus(player);
        if (q.isEmpty()) sendRaw(sender, "\u00a78No queued or paused dialogues.");
        else for (String x : q) sendRaw(sender, "\u00a77- \u00a7f" + x);
    }

    private static void participantInfo(Object sender, Object player) {
        send(sender, "\u00a7d--- Conversation Participants: " + playerName(player) + " ---");
        List<String> p = NarrativeSessions.participantNames(player);
        sendRaw(sender, p.isEmpty() ? "\u00a78No managed active session." : "\u00a77" + String.join(", ", p));
    }

    private static String activeDialogueId(Object player) {
        try {
            Object s = currentDialogueSession(uuid(player));
            return s == null ? "" : dialogueId(s);
        } catch (Throwable t) { return ""; }
    }

    private static void help(Object sender) {
        send(sender, "\u00a7d--- WorldMemory Conversation Flow ---");
        sendRaw(sender, "\u00a77/conversation list");
        sendRaw(sender, "\u00a77/conversation graph <dialogue-id>");
        sendRaw(sender, "\u00a77/conversation trace [player]");
        sendRaw(sender, "\u00a77/conversation validate");
        sendRaw(sender, "\u00a77/conversation reload");
        sendRaw(sender, "\u00a77/conversation session [player]");
        sendRaw(sender, "\u00a77/conversation queue [player]");
        sendRaw(sender, "\u00a77/conversation participants [player]");
        sendRaw(sender, "\u00a77/conversation clear [player]");
        sendRaw(sender, "\u00a77/conversation bark <actor-id> [player]");
        sendRaw(sender, "\u00a77/conversation reset [player]");
    }

    private static void listDialogues(Object sender) {
        Map<String, Object> core = staticMap("DIALOGUES");
        send(sender, "\u00a7dConversation graphs: \u00a7f" + core.size());
        List<String> ids = new ArrayList<>(core.keySet()); Collections.sort(ids);
        for (String id : ids) {
            DialogueFlow f = FLOWS.get(id);
            sendRaw(sender, "\u00a77- \u00a7f" + id + (f == null ? "" : " \u00a78(flow=" + f.nodes.size() + ")"));
        }
    }

    private static void graph(Object sender, String id) throws Exception {
        Object def = staticMap("DIALOGUES").get(id);
        if (def == null) { send(sender, "\u00a7cUnknown dialogue: " + id); return; }
        Map<String, Object> nodes = coreNodes(def);
        String entry = str(call0(def, "entry"));
        DialogueFlow flow = FLOWS.get(id);
        send(sender, "\u00a7d--- Dialogue Graph: " + id + " ---");
        sendRaw(sender, "\u00a77Entry: \u00a7f" + entry + " \u00a78| nodes=" + nodes.size());
        Set<String> reachable = reachable(id, def, entry);
        for (Map.Entry<String, Object> e : nodes.entrySet()) {
            String node = e.getKey();
            List<String> edges = coreEdges(e.getValue());
            FlowNode fm = flow == null ? null : flow.nodes.get(node);
            if (fm != null) {
                if (!fm.call.isBlank()) edges.add("call:" + fm.call);
                if (fm.returnNode) edges.add("return");
                if (!fm.fallback.isBlank()) edges.add("fallback:" + fm.fallback);
                for (Variant v : fm.variants) edges.add("~" + trim(v.weight) + ":" + v.node);
            }
            String flags = fm == null ? "" : flowFlags(fm);
            sendRaw(sender, (reachable.contains(node) ? "\u00a77" : "\u00a7c") + node + " \u00a78-> \u00a7f" + (edges.isEmpty() ? "[end]" : String.join(", ", edges)) + flags);
        }
        long unreachable = nodes.keySet().stream().filter(n -> !reachable.contains(n)).count();
        sendRaw(sender, unreachable == 0 ? "\u00a7aAll nodes reachable from entry." : "\u00a7eUnreachable nodes: " + unreachable);
    }

    private static String flowFlags(FlowNode f) {
        List<String> x = new ArrayList<>();
        if (f.once) x.add("once");
        if (f.cooldownSeconds > 0) x.add("cd=" + f.cooldownSeconds + "s");
        if (!f.variants.isEmpty()) x.add("variants=" + f.variants.size());
        return x.isEmpty() ? "" : " \u00a78[" + String.join(",", x) + "]";
    }

    private static void trace(Object sender, Object player) throws Exception {
        UUID id = uuid(player);
        Object session = currentDialogueSession(id);
        send(sender, "\u00a7d--- Conversation Trace: " + playerName(player) + " ---");
        if (session == null) sendRaw(sender, "\u00a77Active dialogue: \u00a78none");
        else {
            String d = dialogueId(session), n = nodeId(session);
            sendRaw(sender, "\u00a77Dialogue: \u00a7f" + d);
            sendRaw(sender, "\u00a77Node: \u00a7f" + n);
            Visit v = visit(id, d, n);
            sendRaw(sender, "\u00a77Visits: \u00a7f" + v.visits + " \u00a78| last=" + age(v.lastMillis));
        }
        Deque<String> stack = CALL_STACKS.get(id);
        sendRaw(sender, "\u00a77Call stack: \u00a7f" + (stack == null || stack.isEmpty() ? "[]" : stack.toString()));
        FlowState s = state(id);
        sendRaw(sender, "\u00a77Persisted flow keys: \u00a7f" + s.visits.size());
    }

    private static void reset(Object player) throws Exception {
        UUID id = uuid(player);
        STATES.remove(id); CALL_STACKS.remove(id);
        Files.deleteIfExists(stateFile(id).toPath());
    }

    private static void validate(Object sender) {
        NarrativeBridge.reload();
        send(sender, "\u00a7d--- Conversation Validation ---");
        sendRaw(sender, "\u00a77Flow dialogues: \u00a7f" + FLOWS.size());
        sendRaw(sender, "\u00a77Ambient actors: \u00a7f" + AMBIENTS.size());
        sendRaw(sender, "\u00a77Errors: " + (ERRORS.isEmpty() ? "\u00a7a0" : "\u00a7c" + ERRORS.size()));
        List<String> compositionErrors = NarrativeComposition.errors();
        List<String> compositionWarnings = NarrativeComposition.warnings();
        sendRaw(sender, "\u00a77Warnings: " + (WARNINGS.isEmpty() ? "\u00a7a0" : "\u00a7e" + WARNINGS.size()));
        sendRaw(sender, "\u00a77Composition: " + (compositionErrors.isEmpty() ? "\u00a7aREADY" : "\u00a7c" + compositionErrors.size() + " error(s)"));
        int max = 12;
        synchronized (ERRORS) { for (int i = 0; i < Math.min(max, ERRORS.size()); i++) sendRaw(sender, "\u00a7c- " + ERRORS.get(i)); }
        synchronized (WARNINGS) { for (int i = 0; i < Math.min(max, WARNINGS.size()); i++) sendRaw(sender, "\u00a7e- " + WARNINGS.get(i)); }
        for (int i = 0; i < Math.min(max, compositionErrors.size()); i++) sendRaw(sender, "\u00a7c- [composition] " + compositionErrors.get(i));
        for (int i = 0; i < Math.min(max, compositionWarnings.size()); i++) sendRaw(sender, "\u00a7e- [composition] " + compositionWarnings.get(i));
        if (ERRORS.isEmpty() && compositionErrors.isEmpty()) sendRaw(sender, "\u00a7aConversation graph validation passed.");
    }

    private static List<String> tabComplete(String[] args) {
        if (args.length == 1) return match(args[0], List.of("help","list","graph","trace","state","session","queue","participants","clear","reset","validate","reload","bark"));
        if (args.length == 2 && eq(args[0], "graph")) return match(args[1], new ArrayList<>(staticMap("DIALOGUES").keySet()));
        if (args.length == 2 && eq(args[0], "bark")) return match(args[1], new ArrayList<>(AMBIENTS.keySet()));
        if (args.length == 2 && (eq(args[0], "trace") || eq(args[0], "state") || eq(args[0], "session") || eq(args[0], "queue") || eq(args[0], "participants") || eq(args[0], "clear") || eq(args[0], "reset"))) return match(args[1], onlinePlayerNames());
        if (args.length == 3 && eq(args[0], "bark")) return match(args[2], onlinePlayerNames());
        return List.of();
    }

    // ---------------------------------------------------------------------
    // Graph helpers
    // ---------------------------------------------------------------------

    private static Set<String> reachable(String id, Object def, String entry) {
        Map<String, Object> nodes = coreNodes(def);
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        if (entry != null && !entry.isBlank()) q.add(entry);
        DialogueFlow f = FLOWS.get(id);
        while (!q.isEmpty()) {
            String n = q.removeFirst();
            if (!seen.add(n)) continue;
            Object node = nodes.get(n);
            if (node != null) for (String edge : coreEdges(node)) if (nodes.containsKey(edge)) q.add(edge);
            FlowNode fm = f == null ? null : f.nodes.get(n);
            if (fm != null) {
                if (nodes.containsKey(fm.call)) q.add(fm.call);
                if (nodes.containsKey(fm.returnTo)) q.add(fm.returnTo);
                if (nodes.containsKey(fm.fallback)) q.add(fm.fallback);
                for (Variant v : fm.variants) if (nodes.containsKey(v.node)) q.add(v.node);
            }
        }
        return seen;
    }

    private static List<String> coreEdges(Object node) {
        List<String> out = new ArrayList<>();
        try {
            String next = str(call0(node, "next")); if (!next.isBlank()) out.add(next);
            Object choices = call0(node, "choices");
            if (choices instanceof Iterable<?> it) for (Object c : it) {
                if (bool(call0(c, "end"))) continue;
                String n = str(call0(c, "next")); if (!n.isBlank()) out.add(n);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String coreNext(String dialogue, String nodeId) {
        try {
            Object def = staticMap("DIALOGUES").get(dialogue);
            if (def == null) return "";
            Object node = coreNodes(def).get(nodeId);
            return node == null ? "" : str(call0(node, "next"));
        } catch (Throwable t) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coreNodes(Object def) {
        try {
            Object o = call0(def, "nodes");
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Throwable ignored) {}
        return Map.of();
    }
    private static Set<String> coreNodeIds(Object def) { return coreNodes(def).keySet(); }

    // ---------------------------------------------------------------------
    // Reflection / Bukkit helpers
    // ---------------------------------------------------------------------

    private static Object currentDialogueSession(UUID id) {
        try { return staticMap("DIALOGUE_SESSIONS").get(id); } catch (Throwable t) { return ACTIVE_SESSIONS.get(id); }
    }

    private static UUID currentOrContextPlayer(Object context) {
        UUID id = CURRENT_PLAYER.get(); if (id != null) return id;
        if (context != null) {
            try {
                Field f = fieldOf(context.getClass(), "playerId");
                Object v = f.get(context);
                if (v instanceof UUID u) return u;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String dialogueId(Object session) throws Exception {
        Object def = fieldOf(session.getClass(), "definition").get(session);
        return str(call0(def, "id"));
    }
    private static String nodeId(Object session) throws Exception { return str(fieldOf(session.getClass(), "nodeId").get(session)); }
    private static void setNodeId(Object session, String node) throws Exception { fieldOf(session.getClass(), "nodeId").set(session, node); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> staticMap(String field) {
        try {
            Field f = coreClass().getDeclaredField(field); f.setAccessible(true);
            Object o = f.get(null);
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Throwable ignored) {}
        return Collections.emptyMap();
    }

    private static Class<?> coreClass() throws ClassNotFoundException {
        ClassLoader cl = plugin != null ? plugin.getClass().getClassLoader() : NarrativeFlow.class.getClassLoader();
        return Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore", true, cl);
    }

    private static String resolveText(Object player, String key, String fallback) {
        try {
            for (Method m : coreClass().getDeclaredMethods()) if (m.getName().equals("resolveText") && m.getParameterCount() == 3) {
                m.setAccessible(true); return str(m.invoke(null, player, key, fallback));
            }
        } catch (Throwable ignored) {}
        return !fallback.isBlank() ? fallback : key;
    }

    private static Object loadYaml(File file) throws Exception {
        ClassLoader cl = plugin.getClass().getClassLoader();
        Class<?> yc = Class.forName("org.bukkit.configuration.file.YamlConfiguration", true, cl);
        return yc.getMethod("loadConfiguration", File.class).invoke(null, file);
    }
    private static Object section(Object root, String path) {
        try { return call(root, "getConfigurationSection", path); } catch (Throwable t) { return null; }
    }
    private static Set<String> keys(Object sec) {
        try {
            Object o = call(sec, "getKeys", false);
            if (o instanceof Set<?> s) { LinkedHashSet<String> out = new LinkedHashSet<>(); for (Object x : s) out.add(str(x)); return out; }
        } catch (Throwable ignored) {}
        return Set.of();
    }
    private static Object yamlGet(Object root, String path) {
        try { return call(root, "get", path); } catch (Throwable t) { return null; }
    }
    private static Object firstPath(Object root, String... paths) {
        for (String p : paths) { Object v = yamlGet(root, p); if (v != null) return v; } return null;
    }
    private static String yamlString(Object root, String path, String fallback) {
        Object v = yamlGet(root, path); String s = str(v); return s.isBlank() ? fallback : s;
    }
    private static boolean yamlBool(Object root, String path, boolean fallback) {
        Object v = yamlGet(root, path); return v == null ? fallback : bool(v);
    }

    private static File dataFolder() {
        try { return (File) call0(plugin, "getDataFolder"); } catch (Throwable t) { return new File("plugins/WorldMemory"); }
    }
    private static File stateFile(UUID id) { return new File(new File(dataFolder(), "narrative/flow/state"), id + ".properties"); }

    private static void schedule(Runnable task, long ticks) {
        try {
            Object server = call0(plugin, "getServer");
            Object scheduler = call0(server, "getScheduler");
            call(scheduler, "runTaskLater", plugin, task, Math.max(1L, ticks));
        } catch (Throwable t) { log("Could not schedule conversation task", t); }
    }

    private static Object requirePlayer(Object sender) {
        if (!isPlayer(sender)) throw new IllegalArgumentException("This command requires a player.");
        return sender;
    }
    private static Object requireOnlineAdmin(Object sender, String name) throws Exception { requireAdmin(sender); return requireOnline(sender, name); }
    private static Object requireOnline(Object sender, String name) throws Exception {
        Object server = call0(plugin, "getServer");
        Object p = call(server, "getPlayer", name);
        if (p == null) throw new IllegalArgumentException("Player is not online: " + name);
        return p;
    }
    private static void requireAdmin(Object sender) {
        if (!hasPermission(sender, ADMIN) && !hasPermission(sender, "worldmemory.admin")) throw new SecurityException("You do not have permission.");
    }
    private static boolean hasPermission(Object sender, String permission) {
        try { return bool(call(sender, "hasPermission", permission)); } catch (Throwable t) { return false; }
    }
    private static boolean isPlayer(Object o) {
        try { return Class.forName("org.bukkit.entity.Player", true, plugin.getClass().getClassLoader()).isInstance(o); } catch (Throwable t) { return false; }
    }
    private static UUID uuid(Object player) throws Exception { return (UUID) call0(player, "getUniqueId"); }
    private static String playerName(Object player) { try { return str(call0(player, "getName")); } catch (Throwable t) { return "player"; } }
    private static List<String> onlinePlayerNames() {
        List<String> out = new ArrayList<>();
        try {
            Object server = call0(plugin, "getServer"), online = call0(server, "getOnlinePlayers");
            if (online instanceof Iterable<?> it) for (Object p : it) out.add(playerName(p));
        } catch (Throwable ignored) {}
        return out;
    }
    private static void send(Object sender, String s) { sendRaw(sender, PREFIX + s); }
    private static void sendRaw(Object sender, String s) { try { call(sender, "sendMessage", s); } catch (Throwable ignored) {} }

    private static Object call0(Object target, String name) throws Exception { return call(target, name, new Object[0]); }
    private static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException(name + " target");
        Method m = findCompatible(target.getClass(), name, args);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        m.setAccessible(true); return m.invoke(target, args);
    }
    private static Method findCompatible(Class<?> type, String name, Object[] args) {
        for (Method m : type.getMethods()) if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) return m;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) return m;
        return null;
    }
    private static boolean compatible(Class<?>[] p, Object[] args) {
        if (p.length != args.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (args[i] == null) { if (p[i].isPrimitive()) return false; continue; }
            if (!wrap(p[i]).isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }
    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class; if (c == long.class) return Long.class; if (c == double.class) return Double.class;
        if (c == float.class) return Float.class; if (c == boolean.class) return Boolean.class; if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class; if (c == char.class) return Character.class; return c;
    }
    private static Field fieldOf(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }

    private static Object first(Map<String, Object> map, String... keys) { for (String k : keys) if (map.containsKey(k)) return map.get(k); return null; }
    private static Map<String, Object> stringMap(Map<?, ?> in) { Map<String, Object> out = new LinkedHashMap<>(); in.forEach((k,v) -> out.put(str(k), v)); return out; }
    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String nonBlank(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private static boolean bool(Object o) { if (o instanceof Boolean b) return b; String s = str(o); return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes"); }
    private static int integer(Object o, int d) { try { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); } catch (Throwable t) { return d; } }
    private static long longNum(Object o, long d) { try { return o instanceof Number n ? n.longValue() : Long.parseLong(str(o)); } catch (Throwable t) { return d; } }
    private static double number(Object o, double d) { try { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(str(o)); } catch (Throwable t) { return d; } }
    private static double clamp(double x, double a, double b) { return Math.max(a, Math.min(b, x)); }
    private static String trim(double d) { return Math.rint(d) == d ? Long.toString((long)d) : String.format(Locale.ROOT, "%.2f", d); }
    private static String stripExt(String n) { int i = n.lastIndexOf('.'); return i > 0 ? n.substring(0,i) : n; }
    private static String lastPart(String id) { int i = Math.max(id.lastIndexOf('.'), id.lastIndexOf(':')); return i >= 0 ? id.substring(i+1) : id; }
    private static String human(String s) { String[] p = s.replace('-', '_').split("_"); StringBuilder b = new StringBuilder(); for (String x : p) if (!x.isBlank()) b.append(b.length()==0?"":" ").append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)); return b.toString(); }
    private static String norm(String s) { return str(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._:-]", ""); }
    private static String encode(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) { try { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); } catch (Throwable t) { return s; } }
    private static String age(long millis) { if (millis <= 0) return "never"; long sec = Math.max(0,(System.currentTimeMillis()-millis)/1000); if (sec < 60) return sec + "s ago"; if (sec < 3600) return (sec/60) + "m ago"; return (sec/3600) + "h ago"; }
    private static String shortError(Throwable t) { Throwable x = t instanceof InvocationTargetException it && it.getCause()!=null ? it.getCause() : t; return x.getClass().getSimpleName() + (x.getMessage()==null?"":": "+x.getMessage()); }
    private static void log(String msg, Throwable t) { try { Logger l=(Logger)call0(plugin,"getLogger"); l.warning("[NarrativeFlow] "+msg+": "+shortError(t)); } catch(Throwable ignored){} }
    private static List<String> match(String prefix, List<String> values) { String p = str(prefix).toLowerCase(Locale.ROOT); List<String> out = new ArrayList<>(); for (String v : values) if (v.toLowerCase(Locale.ROOT).startsWith(p)) out.add(v); Collections.sort(out); return out; }

    // ---------------------------------------------------------------------
    // Data holders
    // ---------------------------------------------------------------------
    private static final class PlayerContext {
        final UUID playerId;
        PlayerContext(UUID playerId) { this.playerId = playerId; }
    }
    private static final class DialogueFlow {
        final String id; final Map<String, FlowNode> nodes = new LinkedHashMap<>(); int priority; boolean interruptible = true;
        DialogueFlow(String id) { this.id = id; }
    }
    private static final class FlowNode {
        boolean once; long cooldownSeconds; String fallback="", call="", returnTo=""; boolean returnNode; final List<Variant> variants = new ArrayList<>();
        boolean hasFlow() { return once || cooldownSeconds>0 || !fallback.isBlank() || !call.isBlank() || !returnTo.isBlank() || returnNode || !variants.isEmpty(); }
    }
    private static final class Variant {
        String id="", node=""; double weight=1.0; boolean once; long cooldownSeconds; Map<String,Object> when = Map.of();
        String idKey() { return !id.isBlank() ? id : node; }
    }
    private static final class FlowState { final Map<String, Visit> visits = new ConcurrentHashMap<>(); }
    private static final class Visit { static final Visit EMPTY = new Visit(); int visits; long lastMillis; }
    private static final class ActorAmbient {
        String id="", name=""; boolean enabled; double radius=8, chance=.2; long cooldownSeconds=45; int priority; final List<BarkLine> lines=new ArrayList<>();
    }
    private static final class BarkLine { String id="", text="", textKey=""; double weight=1; boolean once; long cooldownSeconds; }
}
