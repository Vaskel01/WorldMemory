package com.yourstudio.worldmemory.narrative;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Alpha.50 cross-file dialogue composition. Dialogue files can import another
 * loaded dialogue and expose its nodes under an alias. Composition mutates only
 * NarrativeCore's in-memory dialogue definitions; authored source files remain
 * untouched.
 */
public final class NarrativeComposition {
    private static volatile Object plugin;
    private static final Map<String, List<ImportSpec>> IMPORTS = new ConcurrentHashMap<>();
    private static final List<String> ERRORS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> WARNINGS = Collections.synchronizedList(new ArrayList<>());

    private NarrativeComposition() {}

    public static synchronized void startup(Object p) {
        plugin = p;
        reload();
    }

    public static synchronized void shutdown() {
        IMPORTS.clear(); ERRORS.clear(); WARNINGS.clear(); plugin = null;
    }

    public static synchronized void reload() {
        IMPORTS.clear(); ERRORS.clear(); WARNINGS.clear();
        if (plugin == null) return;
        try {
            loadImports();
            applyComposition();
        } catch (Throwable t) {
            ERRORS.add("Composition reload failed: " + shortError(t));
            log("Composition reload failed", t);
        }
    }

    public static List<String> errors() { synchronized (ERRORS) { return List.copyOf(ERRORS); } }
    public static List<String> warnings() { synchronized (WARNINGS) { return List.copyOf(WARNINGS); } }
    public static Map<String, List<String>> describeImports() {
        Map<String,List<String>> out = new TreeMap<>();
        for (Map.Entry<String,List<ImportSpec>> e : IMPORTS.entrySet()) {
            List<String> x = new ArrayList<>();
            for (ImportSpec s : e.getValue()) x.add(s.dialogue + " as " + s.alias);
            out.put(e.getKey(), x);
        }
        return out;
    }

    public static Map<String, List<ImportSpec>> describeImportSpecs() {
        Map<String,List<ImportSpec>> out = new LinkedHashMap<>();
        for (Map.Entry<String,List<ImportSpec>> e : IMPORTS.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return out;
    }

    public static List<ImportSpec> importsFor(String dialogue) {
        List<ImportSpec> v = IMPORTS.get(dialogue);
        return v == null ? List.of() : List.copyOf(v);
    }

    private static void loadImports() throws Exception {
        File dir = new File(dataFolder(), "content/dialogue");
        File[] files = dir.listFiles((d,n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            Object yaml = loadYaml(file);
            String id = yamlString(yaml, "id", stripExt(file.getName()));
            Object raw = yamlGet(yaml, "imports");
            if (!(raw instanceof List<?> list)) continue;
            List<ImportSpec> specs = new ArrayList<>();
            for (Object item : list) {
                String dialogue = "", alias = "";
                if (item instanceof String s) {
                    dialogue = s.trim(); alias = defaultAlias(dialogue);
                } else if (item instanceof Map<?,?> m) {
                    Map<String,Object> mm = stringMap(m);
                    dialogue = str(first(mm, "dialogue", "id", "source"));
                    alias = str(first(mm, "as", "alias", "prefix"));
                    if (alias.isBlank()) alias = defaultAlias(dialogue);
                }
                if (dialogue.isBlank()) continue;
                if (alias.isBlank()) alias = defaultAlias(dialogue);
                if (!alias.matches("[A-Za-z0-9_.-]+")) {
                    ERRORS.add(id + ": invalid import alias " + alias);
                    continue;
                }
                specs.add(new ImportSpec(dialogue, alias));
            }
            if (!specs.isEmpty()) IMPORTS.put(id, specs);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyComposition() throws Exception {
        Map<String,Object> dialogues = coreMap("DIALOGUES");
        if (dialogues.isEmpty() || IMPORTS.isEmpty()) return;

        // Snapshot originals first so import order never changes the source graph.
        Map<String,Object> originals = new LinkedHashMap<>(dialogues);
        for (Map.Entry<String,List<ImportSpec>> entry : IMPORTS.entrySet()) {
            String consumerId = entry.getKey();
            Object consumer = originals.get(consumerId);
            if (consumer == null) {
                ERRORS.add(consumerId + ": dialogue is not loaded by NarrativeCore");
                continue;
            }
            Map<String,Object> merged = new LinkedHashMap<>(dialogueNodes(consumer));
            for (ImportSpec spec : entry.getValue()) {
                Object source = originals.get(spec.dialogue);
                if (source == null) {
                    ERRORS.add(consumerId + ": imported dialogue missing: " + spec.dialogue);
                    continue;
                }
                for (Map.Entry<String,Object> nodeEntry : dialogueNodes(source).entrySet()) {
                    String newId = spec.alias + "." + nodeEntry.getKey();
                    if (merged.containsKey(newId)) {
                        ERRORS.add(consumerId + ": imported node collision: " + newId);
                        continue;
                    }
                    merged.put(newId, cloneNode(nodeEntry.getValue(), spec.alias, dialogueNodes(source).keySet()));
                }
            }
            Object composite = newDialogueDef(dialogueId(consumer), dialogueEntry(consumer), merged);
            dialogues.put(consumerId, composite);
        }
    }

    private static Object cloneNode(Object node, String alias, Set<String> sourceIds) throws Exception {
        String id = alias + "." + str(call0(node, "id"));
        String speaker = str(call0(node, "speaker"));
        String textKey = str(call0(node, "textKey"));
        String text = str(call0(node, "text"));
        String next = rewrite(str(call0(node, "next")), alias, sourceIds);
        List<Object> choices = new ArrayList<>();
        Object raw = call0(node, "choices");
        if (raw instanceof Iterable<?> it) for (Object c : it) choices.add(cloneChoice(c, alias, sourceIds));
        Class<?> type = node.getClass();
        Constructor<?> ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return ctor.newInstance(id, speaker, textKey, text, next, choices);
    }

    @SuppressWarnings("unchecked")
    private static Object cloneChoice(Object c, String alias, Set<String> sourceIds) throws Exception {
        String id = str(call0(c, "id"));
        String textKey = str(call0(c, "textKey"));
        String text = str(call0(c, "text"));
        String next = rewrite(str(call0(c, "next")), alias, sourceIds);
        boolean end = bool(call0(c, "end"));
        Map<String,Object> when = (Map<String,Object>) call0(c, "when");
        Map<String,Object> setVars = (Map<String,Object>) call0(c, "setVars");
        String qst = str(call0(c, "questSignalType"));
        String qsg = str(call0(c, "questSignalTarget"));
        Constructor<?> ctor = c.getClass().getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return ctor.newInstance(id, textKey, text, next, end, when, setVars, qst, qsg);
    }

    private static String rewrite(String target, String alias, Set<String> sourceIds) {
        return sourceIds.contains(target) ? alias + "." + target : target;
    }

    private static Object newDialogueDef(String id, String entry, Map<String,Object> nodes) throws Exception {
        ClassLoader cl = classLoader();
        Class<?> def = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore$DialogueDef", true, cl);
        Constructor<?> ctor = def.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return ctor.newInstance(id, entry, nodes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> dialogueNodes(Object def) throws Exception {
        Object o = call0(def, "nodes");
        return o instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
    }
    private static String dialogueId(Object def) throws Exception { return str(call0(def, "id")); }
    private static String dialogueEntry(Object def) throws Exception { return str(call0(def, "entry")); }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> coreMap(String field) throws Exception {
        Class<?> c = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore", true, classLoader());
        Field f = c.getDeclaredField(field); f.setAccessible(true);
        Object o = f.get(null);
        return o instanceof Map<?,?> m ? (Map<String,Object>)m : Collections.emptyMap();
    }

    private static Object loadYaml(File file) throws Exception {
        Class<?> yc = Class.forName("org.bukkit.configuration.file.YamlConfiguration", true, classLoader());
        return yc.getMethod("loadConfiguration", File.class).invoke(null, file);
    }
    private static Object yamlGet(Object root, String path) { try { return call(root, "get", path); } catch(Throwable t){ return null; } }
    private static String yamlString(Object root,String path,String fallback){ String s=str(yamlGet(root,path)); return s.isBlank()?fallback:s; }
    private static File dataFolder(){ try { return (File)call0(plugin,"getDataFolder"); } catch(Throwable t){ return new File("plugins/WorldMemory"); } }
    private static ClassLoader classLoader(){ return plugin != null ? plugin.getClass().getClassLoader() : NarrativeComposition.class.getClassLoader(); }
    private static Object call0(Object target,String name) throws Exception { return call(target,name,new Object[0]); }
    private static Object call(Object target,String name,Object... args) throws Exception {
        Method m = find(target.getClass(),name,args); if(m==null) throw new NoSuchMethodException(name); m.setAccessible(true); return m.invoke(target,args);
    }
    private static Method find(Class<?> c,String n,Object[] a){ for(Method m:c.getMethods()) if(m.getName().equals(n)&&compatible(m.getParameterTypes(),a)) return m; for(Class<?> x=c;x!=null;x=x.getSuperclass()) for(Method m:x.getDeclaredMethods()) if(m.getName().equals(n)&&compatible(m.getParameterTypes(),a)) return m; return null; }
    private static boolean compatible(Class<?>[] p,Object[] a){ if(p.length!=a.length)return false; for(int i=0;i<p.length;i++){ if(a[i]==null){if(p[i].isPrimitive())return false;} else if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;} return true; }
    private static Class<?> wrap(Class<?> c){ if(!c.isPrimitive())return c; if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==char.class)return Character.class;return c; }
    private static Object first(Map<String,Object> m,String...k){for(String x:k)if(m.containsKey(x))return m.get(x);return null;}
    private static Map<String,Object> stringMap(Map<?,?>m){Map<String,Object>o=new LinkedHashMap<>();m.forEach((k,v)->o.put(str(k),v));return o;}
    private static String str(Object o){return o==null?"":String.valueOf(o).trim();}
    private static boolean bool(Object o){return o instanceof Boolean b?b:Boolean.parseBoolean(str(o));}
    private static String defaultAlias(String id){ int i=Math.max(id.lastIndexOf('.'),id.lastIndexOf(':')); return i>=0?id.substring(i+1):id; }
    private static String stripExt(String n){int i=n.lastIndexOf('.');return i>0?n.substring(0,i):n;}
    private static String shortError(Throwable t){Throwable x=t instanceof InvocationTargetException i&&i.getCause()!=null?i.getCause():t;return x.getClass().getSimpleName()+(x.getMessage()==null?"":": "+x.getMessage());}
    private static void log(String m,Throwable t){try{Logger l=(Logger)call0(plugin,"getLogger");l.warning("[NarrativeComposition] "+m+": "+shortError(t));}catch(Throwable ignored){}}

    public static final class ImportSpec {
        public final String dialogue, alias;
        ImportSpec(String dialogue,String alias){this.dialogue=dialogue;this.alias=alias;}
    }
}
