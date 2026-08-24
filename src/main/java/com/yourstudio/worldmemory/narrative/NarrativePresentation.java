package com.yourstudio.worldmemory.narrative;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Alpha.46 presentation adapter. Reflection-only so narrative story logic stays independent of Paper UI classes. */
public final class NarrativePresentation {
    private static Object plugin;
    private static final Map<UUID,String> LAST = new ConcurrentHashMap<>();
    private static final Map<String,Speaker> SPEAKERS = new ConcurrentHashMap<>();
    private static boolean nativeDialogs = true;
    private static boolean subtitles = true;
    private static boolean choiceNumbers = true;
    private static int dialogWidth = 360;
    private static int choiceColumns = 1;
    private static boolean started;

    private record Speaker(String id, String displayName, String subtitlePrefix, String sound, float volume, float pitch) {}

    private NarrativePresentation() {}

    public static synchronized void startup() {
        if (started) return;
        try {
            Class<?> core = Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore");
            Field pf = core.getDeclaredField("plugin"); pf.setAccessible(true); plugin = pf.get(null);
            if (plugin == null) return;
            loadPresentation();
            scheduleRepeating(NarrativePresentation::tick, 2L, 2L);
            started = true;
            logger("Narrative presentation online: nativeDialogs=" + nativeDialogs + ", speakers=" + SPEAKERS.size());
        } catch (Throwable t) {
            logger("Narrative presentation could not start: " + shortError(t));
        }
    }

    private static void tick() {
        try {
            Map<?,?> sessions = dialogueSessions();
            Set<UUID> active = new HashSet<>();
            for (Map.Entry<?,?> e : sessions.entrySet()) {
                if (!(e.getKey() instanceof UUID id)) continue;
                active.add(id);
                Object session = e.getValue();
                Object player = onlinePlayer(id);
                if (player == null || session == null) continue;
                String nodeId = String.valueOf(field(session,"nodeId"));
                Object def = field(session,"definition");
                String dialogId = String.valueOf(call0(def,"id"));
                @SuppressWarnings("unchecked") List<Object> choices = (List<Object>) field(session,"visibleChoices");
                String signature = dialogId + "|" + nodeId + "|" + choiceSignature(choices);
                if (signature.equals(LAST.put(id, signature))) continue;
                render(player, session, def, nodeId, choices == null ? List.of() : choices);
            }
            LAST.keySet().removeIf(id -> !active.contains(id));
        } catch (Throwable t) {
            logger("Narrative presentation tick failed: " + shortError(t));
        }
    }

    private static void render(Object player, Object session, Object def, String nodeId, List<Object> choices) throws Exception {
        @SuppressWarnings("unchecked") Map<String,Object> nodes = (Map<String,Object>) call0(def,"nodes");
        Object node = nodes.get(nodeId);
        if (node == null) return;
        String speakerId = str(call0(node,"speaker"));
        String textKey = str(call0(node,"textKey"));
        String rawText = str(call0(node,"text"));
        String text = resolveText(player, textKey, rawText);
        String speaker = resolveSpeaker(player, speakerId);
        Speaker profile = SPEAKERS.get(speakerId);
        if (profile != null && !blank(profile.displayName())) speaker = profile.displayName();

        if (subtitles) showSubtitle(player, speaker, profile, text);
        if (profile != null && !blank(profile.sound())) playSound(player, profile);

        if (!choices.isEmpty() && nativeDialogs && showNativeDialog(player, speaker, text, choices)) return;
        // Alpha.45 clickable chat remains the fallback renderer. We only add presentation on top.
    }

    private static boolean showNativeDialog(Object player, String speaker, String text, List<Object> choices) {
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Method textMethod = component.getMethod("text", String.class);
            Object titleComponent = textMethod.invoke(null, speaker == null ? "Dialogue" : speaker);
            Object bodyComponent = textMethod.invoke(null, text == null ? "" : text);

            Class<?> dialogBody = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody");
            Object body;
            try { body = dialogBody.getMethod("plainMessage", component, int.class).invoke(null, bodyComponent, dialogWidth); }
            catch (NoSuchMethodException ex) { body = dialogBody.getMethod("plainMessage", component).invoke(null, bodyComponent); }

            Class<?> dialogBase = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
            Object baseBuilder = dialogBase.getMethod("builder", component).invoke(null, titleComponent);
            invokeCompatible(baseBuilder, "body", List.of(body));
            try { invokeCompatible(baseBuilder, "canCloseWithEscape", true); } catch (Throwable ignored) {}
            Object base = invokeCompatible(baseBuilder, "build");

            Class<?> dialogAction = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
            Method commandTemplate = dialogAction.getMethod("commandTemplate", String.class);
            Class<?> actionButton = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
            List<Object> buttons = new ArrayList<>();
            for (int i=0;i<choices.size();i++) {
                Object choice = choices.get(i);
                String ck = str(call0(choice,"textKey"));
                String cr = str(call0(choice,"text"));
                String label = resolveText(player, ck, cr);
                if (choiceNumbers) label = (i+1) + ". " + label;
                Object action = commandTemplate.invoke(null, "narrative choose " + (i+1));
                Object button = actionButton.getMethod("create", component, component, int.class, dialogAction)
                        .invoke(null, textMethod.invoke(null,label), null, Math.min(300, Math.max(100, dialogWidth / Math.max(1,choiceColumns))), action);
                buttons.add(button);
            }
            Object stopAction = commandTemplate.invoke(null, "narrative stop");
            Object exit = actionButton.getMethod("create", component, component, int.class, dialogAction)
                    .invoke(null, textMethod.invoke(null,"Skip / Close"), null, 120, stopAction);

            Class<?> dialogType = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
            Object type;
            try { type = dialogType.getMethod("multiAction", List.class, actionButton, int.class).invoke(null, buttons, exit, choiceColumns); }
            catch (NoSuchMethodException ex) {
                Object tb = dialogType.getMethod("multiAction", List.class).invoke(null, buttons);
                try { invokeCompatible(tb,"columns",choiceColumns); } catch (Throwable ignored) {}
                try { invokeCompatible(tb,"exitAction",exit); } catch (Throwable ignored) {}
                type = invokeCompatible(tb,"build");
            }

            final Object finalType = type;
            Class<?> dialog = Class.forName("io.papermc.paper.dialog.Dialog");
            Object created = dialog.getMethod("create", Consumer.class).invoke(null, (Consumer<Object>) factory -> {
                try {
                    Object builder = invokeCompatible(factory,"empty");
                    invokeCompatible(builder,"base",base);
                    invokeCompatible(builder,"type",finalType);
                } catch (Throwable t) { throw new RuntimeException(t); }
            });
            invokeCompatible(player,"showDialog",created);
            return true;
        } catch (Throwable t) {
            nativeDialogs = false; // fail soft once; alpha.45 chat renderer remains available
            logger("Paper dialog renderer unavailable; falling back to chat: " + shortError(t));
            return false;
        }
    }

    private static void showSubtitle(Object player, String speaker, Speaker profile, String text) {
        try {
            String prefix = profile == null ? "" : profile.subtitlePrefix();
            String sub = (blank(prefix) ? "" : prefix + " ") + text;
            Method m = find(player.getClass(), "sendTitle", String.class,String.class,int.class,int.class,int.class);
            if (m != null) m.invoke(player, speaker == null ? "" : speaker, sub, 3, 45, 8);
        } catch (Throwable ignored) {}
    }

    private static void playSound(Object player, Speaker p) {
        try {
            Object loc = invokeCompatible(player,"getLocation");
            Method m = findByNameCount(player.getClass(),"playSound",4);
            if (m != null) m.invoke(player, loc, p.sound(), p.volume(), p.pitch());
        } catch (Throwable ignored) {}
    }

    private static void loadPresentation() {
        SPEAKERS.clear();
        File data = dataFolder();
        File cfg = new File(data,"content/narrative/presentation.yml");
        if (cfg.isFile()) {
            Object y = loadYaml(cfg);
            nativeDialogs = yamlBool(y,"renderer.native-dialogs",true);
            subtitles = yamlBool(y,"renderer.subtitles",true);
            choiceNumbers = yamlBool(y,"renderer.choice-numbers",true);
            dialogWidth = yamlInt(y,"renderer.dialog-width",360);
            choiceColumns = Math.max(1,yamlInt(y,"renderer.choice-columns",1));
        }
        File dir = new File(data,"content/narrative/speakers");
        File[] files = dir.listFiles((d,n)->n.endsWith(".yml")||n.endsWith(".yaml"));
        if (files != null) for (File f: files) {
            try {
                Object y=loadYaml(f); String id=yamlString(y,"id",""); if(blank(id)) continue;
                SPEAKERS.put(id,new Speaker(id,yamlString(y,"display-name",id),yamlString(y,"subtitle-prefix",""),yamlString(y,"voice.sound",""),(float)yamlDouble(y,"voice.volume",1.0),(float)yamlDouble(y,"voice.pitch",1.0)));
            } catch(Throwable t){ logger("Could not load speaker profile " + f.getName() + ": " + shortError(t)); }
        }
    }

    @SuppressWarnings("unchecked") private static Map<?,?> dialogueSessions() throws Exception {
        Field f=Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore").getDeclaredField("DIALOGUE_SESSIONS"); f.setAccessible(true); return (Map<?,?>)f.get(null);
    }
    private static String resolveText(Object player,String key,String raw) throws Exception { return String.valueOf(invokeCore("resolveText",new Class<?>[]{Object.class,String.class,String.class},player,key,raw)); }
    private static String resolveSpeaker(Object player,String id) throws Exception { return String.valueOf(invokeCore("resolveSpeaker",new Class<?>[]{Object.class,String.class},player,id)); }
    private static Object invokeCore(String name,Class<?>[] sig,Object...args) throws Exception { Method m=Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore").getDeclaredMethod(name,sig);m.setAccessible(true);return m.invoke(null,args); }

    private static Object field(Object o,String n) throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o); }
    private static Object call0(Object o,String n) throws Exception { Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o); }
    private static String choiceSignature(List<Object> xs){ if(xs==null)return"";StringBuilder s=new StringBuilder();for(Object x:xs)try{s.append(call0(x,"id")).append(';');}catch(Throwable t){s.append('?');}return s.toString(); }

    private static void scheduleRepeating(Runnable r,long delay,long period) throws Exception {
        Class<?> bukkit=Class.forName("org.bukkit.Bukkit"); Object scheduler=bukkit.getMethod("getScheduler").invoke(null);
        for(Method m:scheduler.getClass().getMethods()) if(m.getName().equals("runTaskTimer")&&m.getParameterCount()==4){ try { m.invoke(scheduler,plugin,r,delay,period); return; } catch(IllegalArgumentException ignored){} }
        throw new NoSuchMethodException("runTaskTimer");
    }
    private static Object onlinePlayer(UUID id) throws Exception { return Class.forName("org.bukkit.Bukkit").getMethod("getPlayer",UUID.class).invoke(null,id); }
    private static File dataFolder(){ try{return (File)invokeCompatible(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");} }
    private static Object loadYaml(File f){ try{Class<?> c=Class.forName("org.bukkit.configuration.file.YamlConfiguration");return c.getMethod("loadConfiguration",File.class).invoke(null,f);}catch(Throwable t){throw new RuntimeException(t);} }
    private static String yamlString(Object y,String p,String d){try{Object v=invokeCompatible(y,"getString",p,d);return v==null?d:String.valueOf(v);}catch(Throwable t){return d;}}
    private static boolean yamlBool(Object y,String p,boolean d){try{return (Boolean)invokeCompatible(y,"getBoolean",p,d);}catch(Throwable t){return d;}}
    private static int yamlInt(Object y,String p,int d){try{return ((Number)invokeCompatible(y,"getInt",p,d)).intValue();}catch(Throwable t){return d;}}
    private static double yamlDouble(Object y,String p,double d){try{return ((Number)invokeCompatible(y,"getDouble",p,d)).doubleValue();}catch(Throwable t){return d;}}

    private static Object invokeCompatible(Object target,String name,Object...args) throws Exception { Method m=findCompatible(target.getClass(),name,args); if(m==null)throw new NoSuchMethodException(name);m.setAccessible(true);return m.invoke(target,args); }
    private static Method findCompatible(Class<?> c,String name,Object[] args){ for(Method m:c.getMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args))return m; for(Method m:c.getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length&&compatible(m.getParameterTypes(),args))return m; return null; }
    private static boolean compatible(Class<?>[] p,Object[] a){for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;continue;}Class<?> pc=wrap(p[i]);if(!pc.isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?> wrap(Class<?> c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==short.class)return Short.class;if(c==byte.class)return Byte.class;if(c==char.class)return Character.class;return c;}
    private static Method find(Class<?> c,String n,Class<?>...p){try{return c.getMethod(n,p);}catch(Throwable t){return null;}}
    private static Method findByNameCount(Class<?> c,String n,int count){for(Method m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==count)return m;return null;}
    private static String str(Object o){return o==null?"":String.valueOf(o);} private static boolean blank(String s){return s==null||s.isBlank();}
    private static String shortError(Throwable t){while(t instanceof InvocationTargetException && ((InvocationTargetException)t).getTargetException()!=null)t=((InvocationTargetException)t).getTargetException();return t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage());}
    private static void logger(String msg){try{Object l=invokeCompatible(plugin,"getLogger");invokeCompatible(l,"info","[NarrativePresentation] "+msg);}catch(Throwable ignored){System.out.println("[WorldMemory] "+msg);}}
}
