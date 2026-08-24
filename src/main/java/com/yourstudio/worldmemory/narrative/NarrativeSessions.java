package com.yourstudio.worldmemory.narrative;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Alpha.50 dialogue arbitration / session manager. */
public final class NarrativeSessions {
    private static volatile Object plugin;
    private static volatile boolean started;
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, PriorityQueue<Request>> QUEUES = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Snapshot>> PAUSED = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Map<String, SessionMeta> META = new ConcurrentHashMap<>();
    private static volatile boolean watchdogArmed;

    private NarrativeSessions() {}

    public static synchronized void startup(Object p){ plugin=p; started=true; reload(); armWatchdog(); }
    public static synchronized void shutdown(){ started=false; watchdogArmed=false; ACTIVE.clear(); QUEUES.clear(); PAUSED.clear(); META.clear(); plugin=null; }
    public static synchronized void reload(){ META.clear(); if(plugin==null)return; try{loadMeta();}catch(Throwable t){log("Session metadata reload failed",t);} }

    /** Replacement target for all NarrativeCore-internal startDialogue calls. */
    public static void startDialogue(Object player,String dialogue,Runnable onComplete){
        if(player==null||dialogue==null)return;
        try{
            UUID id=uuid(player);
            synchronized(lock(id)){
                reconcileMissing(id,false);
                if(!dialogueExists(dialogue)){ rawStart(player,dialogue,onComplete); return; }
                int priority=NarrativeFlow.dialoguePriority(dialogue);
                boolean interruptible=NarrativeFlow.dialogueInterruptible(dialogue);
                Active current=ACTIVE.get(id);
                if(current==null){ begin(player,new Request(dialogue,onComplete,priority,interruptible,SEQ.incrementAndGet())); return; }
                if(priority>current.priority && current.interruptible){
                    Snapshot snap=snapshotCurrent(id,current);
                    if(snap!=null) PAUSED.computeIfAbsent(id,k->new ArrayDeque<>()).addLast(snap);
                    removeCoreSession(id);
                    begin(player,new Request(dialogue,onComplete,priority,interruptible,SEQ.incrementAndGet()));
                    return;
                }
                QUEUES.computeIfAbsent(id,k->new PriorityQueue<>()).add(new Request(dialogue,onComplete,priority,interruptible,SEQ.incrementAndGet()));
            }
        }catch(Throwable t){ log("Dialogue request failed",t); try{rawStart(player,dialogue,onComplete);}catch(Throwable ignored){} }
    }

    /** Called by NarrativeBridge before actor presentation, after flow routing. */
    public static void onRender(Object player,Object session){
        try{
            UUID id=uuid(player); Active a=ACTIVE.get(id); if(a==null)return;
            a.coreSession=session;
            String node=str(field(session,"nodeId"));
            if(node.equals(a.lastMirroredNode))return;
            a.lastMirroredNode=node;
            if(a.participants.size()<=1)return;
            mirrorToAudience(player,session,a,node);
        }catch(Throwable t){log("Audience mirror failed",t);}
    }

    /** NarrativeCore invokes this before removing its session/calling completion. */
    public static void onDialogueFinish(Object player,Object session){
        try{
            UUID id=uuid(player); Active a=ACTIVE.get(id); if(a==null)return;
            if(a.coreSession!=null && a.coreSession!=session)return;
            a.completing=true;
            schedule(() -> afterFinish(id),1L);
        }catch(Throwable t){log("Dialogue finish arbitration failed",t);}
    }

    public static String sessionStatus(Object player){
        try{
            UUID id=uuid(player); Active a=ACTIVE.get(id); PriorityQueue<Request> q=QUEUES.get(id); Deque<Snapshot> p=PAUSED.get(id);
            if(a==null)return "active=none | queued="+(q==null?0:q.size())+" | paused="+(p==null?0:p.size());
            return "active="+a.dialogue+" priority="+a.priority+" interruptible="+a.interruptible+" | queued="+(q==null?0:q.size())+" | paused="+(p==null?0:p.size())+" | participants="+a.participants.size();
        }catch(Throwable t){return "unavailable: "+shortError(t);}
    }
    public static List<String> queueStatus(Object player){
        try{
            UUID id=uuid(player); List<String> out=new ArrayList<>(); PriorityQueue<Request> q=QUEUES.get(id);
            if(q!=null){List<Request>x=new ArrayList<>(q);Collections.sort(x);for(Request r:x)out.add(r.dialogue+" [p="+r.priority+"]");}
            Deque<Snapshot> ps=PAUSED.get(id); if(ps!=null)for(Snapshot s:ps)out.add("PAUSED "+s.dialogue+" [p="+s.priority+"]");
            return out;
        }catch(Throwable t){return List.of("unavailable: "+shortError(t));}
    }
    public static List<String> participantNames(Object player){
        try{Active a=ACTIVE.get(uuid(player));if(a==null)return List.of();List<String>out=new ArrayList<>();for(UUID id:a.participants){Object p=online(id);out.add(p==null?id.toString():name(p));}return out;}catch(Throwable t){return List.of();}
    }
    public static void clearPending(Object player){try{UUID id=uuid(player);QUEUES.remove(id);PAUSED.remove(id);}catch(Throwable ignored){}}

    private static void begin(Object player,Request r)throws Exception{
        UUID id=uuid(player); Active a=new Active(r.dialogue,r.priority,r.interruptible,r.onComplete);
        populateParticipants(player,a,r.dialogue); ACTIVE.put(id,a); rawStart(player,r.dialogue,r.onComplete);
        Object core=currentCoreSession(id); a.coreSession=core;
        if(core==null){ ACTIVE.remove(id,a); schedule(() -> afterFinish(id),1L); }
    }

    private static void afterFinish(UUID id){
        if(!started)return;
        try{
            Object player=online(id); if(player==null){ACTIVE.remove(id);return;}
            synchronized(lock(id)){
                Active old=ACTIVE.get(id);
                if(old!=null && !old.completing && currentCoreSession(id)!=null)return;
                ACTIVE.remove(id);
                if(currentCoreSession(id)!=null)return; // another unmanaged dialogue took over
                Deque<Snapshot> paused=PAUSED.get(id);
                if(paused!=null && !paused.isEmpty()){
                    Snapshot s=paused.pollLast(); if(paused.isEmpty())PAUSED.remove(id);
                    resume(player,s); return;
                }
                PriorityQueue<Request> q=QUEUES.get(id);
                if(q!=null&&!q.isEmpty()){
                    Request r=q.poll();if(q.isEmpty())QUEUES.remove(id);begin(player,r);
                }
            }
        }catch(Throwable t){log("Could not advance dialogue queue",t);}
    }

    private static Snapshot snapshotCurrent(UUID id,Active a){
        try{
            Object s=currentCoreSession(id); if(s==null)return null;
            Object def=field(s,"definition"); String node=str(field(s,"nodeId")); Runnable cb=(Runnable)field(s,"onComplete");
            List<String> flowStack=NarrativeFlow.snapshotCallStack(id);
            return new Snapshot(a.dialogue,a.priority,a.interruptible,def,node,cb,flowStack,new LinkedHashSet<>(a.participants));
        }catch(Throwable t){log("Could not snapshot interrupted dialogue",t);return null;}
    }

    @SuppressWarnings("unchecked")
    private static void resume(Object player,Snapshot s)throws Exception{
        UUID id=uuid(player); Class<?> sessionClass=Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore$DialogueSession",true,cl());
        Constructor<?> ctor=sessionClass.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object session=ctor.newInstance(s.definition,s.nodeId,s.onComplete);
        Map<UUID,Object> map=(Map<UUID,Object>)coreField("DIALOGUE_SESSIONS"); map.put(id,session);
        NarrativeFlow.restoreCallStack(id,s.flowStack);
        Active a=new Active(s.dialogue,s.priority,s.interruptible,s.onComplete);a.coreSession=session;a.participants.addAll(s.participants);ACTIVE.put(id,a);
        Method render=coreMethod("renderDialogue",2);render.invoke(null,player,session);
    }

    private static void reconcileMissing(UUID id,boolean watchdog){
        Active a=ACTIVE.get(id);if(a==null)return;
        try{
            Object core=currentCoreSession(id);
            if(core!=null){a.coreSession=core;return;}
            if(a.completing)return;
            // Missing without a finish hook means /narrative stop, disconnect cleanup,
            // or another hard cancellation. Do not resume queued story callbacks.
            ACTIVE.remove(id);QUEUES.remove(id);PAUSED.remove(id);NarrativeFlow.restoreCallStack(id,List.of());
        }catch(Throwable t){if(watchdog)log("Session watchdog reconciliation failed",t);}
    }

    private static synchronized void armWatchdog(){
        if(!started||watchdogArmed)return;watchdogArmed=true;
        schedule(() -> {watchdogArmed=false;if(!started)return;for(UUID id:new ArrayList<>(ACTIVE.keySet()))reconcileMissing(id,true);armWatchdog();},20L);
    }

    private static void loadMeta()throws Exception{
        File dir=new File(dataFolder(),"content/dialogue");File[]files=dir.listFiles((d,n)->n.endsWith(".yml")||n.endsWith(".yaml"));if(files==null)return;
        for(File f:files){Object y=loadYaml(f);String id=yamlString(y,"id",stripExt(f.getName()));SessionMeta m=new SessionMeta();m.audienceRadius=Math.max(0.0,num(yamlGet(y,"session.audience-radius"),0.0));m.mirrorAudience=yamlBool(y,"session.mirror-audience",m.audienceRadius>0);META.put(id,m);}
    }

    private static void populateParticipants(Object owner,Active a,String dialogue){
        try{
            UUID ownerId=uuid(owner);a.participants.add(ownerId);SessionMeta m=META.get(dialogue);if(m==null||m.audienceRadius<=0)return;
            Object loc=call0(owner,"getLocation");Object world=call0(owner,"getWorld");Object server=call0(plugin,"getServer");Object online=call0(server,"getOnlinePlayers");
            if(online instanceof Iterable<?> it)for(Object p:it){if(p==owner)continue;if(call0(p,"getWorld")!=world)continue;Object pl=call0(p,"getLocation");double d2=num(call(loc,"distanceSquared",pl),Double.MAX_VALUE);if(d2<=m.audienceRadius*m.audienceRadius)a.participants.add(uuid(p));}
        }catch(Throwable ignored){}
    }

    private static void mirrorToAudience(Object owner,Object session,Active a,String node)throws Exception{
        SessionMeta meta=META.get(a.dialogue);if(meta==null||!meta.mirrorAudience)return;
        Object def=field(session,"definition");Object nodes=call0(def,"nodes");if(!(nodes instanceof Map<?,?> map))return;Object nd=map.get(node);if(nd==null)return;
        String speaker=str(call0(nd,"speaker"));String key=str(call0(nd,"textKey"));String fallback=str(call0(nd,"text"));
        for(UUID pid:a.participants){if(pid.equals(uuid(owner)))continue;Object p=online(pid);if(p==null)continue;String text=resolveText(p,key,fallback);text=NarrativeBridge.interpolate(p,text);String who=human(lastPart(speaker));call(p,"sendMessage","§8[Scene] §d"+who+"§7: §f"+text);}
    }

    private static boolean dialogueExists(String id){try{Object m=coreField("DIALOGUES");return m instanceof Map<?,?>x&&x.containsKey(id);}catch(Throwable t){return false;}}
    private static Object currentCoreSession(UUID id)throws Exception{Object m=coreField("DIALOGUE_SESSIONS");return m instanceof Map<?,?>x?x.get(id):null;}
    private static void removeCoreSession(UUID id)throws Exception{Object m=coreField("DIALOGUE_SESSIONS");if(m instanceof Map<?,?>x)((Map<?,?>)x).remove(id);}
    private static void rawStart(Object player,String dialogue,Runnable cb)throws Exception{Method m=coreMethod("startDialogue",3);m.invoke(null,player,dialogue,cb);}
    private static Object coreField(String name)throws Exception{Class<?>c=core();Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Method coreMethod(String name,int params)throws Exception{for(Method m:core().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==params){m.setAccessible(true);return m;}throw new NoSuchMethodException(name);}
    private static Class<?> core()throws Exception{return Class.forName("com.yourstudio.worldmemory.narrative.NarrativeCore",true,cl());}

    private static Object lock(UUID id){return id.toString().intern();}
    private static Object online(UUID id){try{return call(call0(plugin,"getServer"),"getPlayer",id);}catch(Throwable t){return null;}}
    private static UUID uuid(Object p)throws Exception{return (UUID)call0(p,"getUniqueId");}
    private static String name(Object p){try{return str(call0(p,"getName"));}catch(Throwable t){return "player";}}
    private static String resolveText(Object player,String key,String fallback){try{Method m=coreMethod("resolveText",3);return str(m.invoke(null,player,key,fallback));}catch(Throwable t){return !fallback.isBlank()?fallback:key;}}
    private static File dataFolder(){try{return (File)call0(plugin,"getDataFolder");}catch(Throwable t){return new File("plugins/WorldMemory");}}
    private static ClassLoader cl(){return plugin!=null?plugin.getClass().getClassLoader():NarrativeSessions.class.getClassLoader();}
    private static Object loadYaml(File f)throws Exception{Class<?>y=Class.forName("org.bukkit.configuration.file.YamlConfiguration",true,cl());return y.getMethod("loadConfiguration",File.class).invoke(null,f);}
    private static Object yamlGet(Object r,String p){try{return call(r,"get",p);}catch(Throwable t){return null;}}
    private static String yamlString(Object r,String p,String d){String s=str(yamlGet(r,p));return s.isBlank()?d:s;}
    private static boolean yamlBool(Object r,String p,boolean d){Object o=yamlGet(r,p);return o==null?d:bool(o);}
    private static void schedule(Runnable r,long ticks){try{Object s=call0(call0(plugin,"getServer"),"getScheduler");call(s,"runTaskLater",plugin,r,Math.max(1L,ticks));}catch(Throwable t){log("Could not schedule session task",t);}}
    private static Object field(Object o,String n)throws Exception{for(Class<?>c=o.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    private static Object call0(Object t,String n)throws Exception{return call(t,n,new Object[0]);}
    private static Object call(Object t,String n,Object...a)throws Exception{Method m=find(t.getClass(),n,a);if(m==null)throw new NoSuchMethodException(n);m.setAccessible(true);return m.invoke(t,a);}
    private static Method find(Class<?>c,String n,Object[]a){for(Method m:c.getMethods())if(m.getName().equals(n)&&compat(m.getParameterTypes(),a))return m;for(Class<?>x=c;x!=null;x=x.getSuperclass())for(Method m:x.getDeclaredMethods())if(m.getName().equals(n)&&compat(m.getParameterTypes(),a))return m;return null;}
    private static boolean compat(Class<?>[]p,Object[]a){if(p.length!=a.length)return false;for(int i=0;i<p.length;i++){if(a[i]==null){if(p[i].isPrimitive())return false;}else if(!wrap(p[i]).isAssignableFrom(a[i].getClass()))return false;}return true;}
    private static Class<?>wrap(Class<?>c){if(!c.isPrimitive())return c;if(c==boolean.class)return Boolean.class;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==double.class)return Double.class;if(c==float.class)return Float.class;if(c==byte.class)return Byte.class;if(c==short.class)return Short.class;if(c==char.class)return Character.class;return c;}
    private static String str(Object o){return o==null?"":String.valueOf(o).trim();}private static boolean bool(Object o){return o instanceof Boolean b?b:Boolean.parseBoolean(str(o));}private static double num(Object o,double d){try{return o instanceof Number n?n.doubleValue():Double.parseDouble(str(o));}catch(Throwable t){return d;}}
    private static String stripExt(String n){int i=n.lastIndexOf('.');return i>0?n.substring(0,i):n;}private static String lastPart(String s){int i=Math.max(s.lastIndexOf('.'),s.lastIndexOf(':'));return i>=0?s.substring(i+1):s;}private static String human(String s){String[]p=s.replace('-','_').split("_");StringBuilder b=new StringBuilder();for(String x:p)if(!x.isBlank())b.append(b.length()==0?"":" ").append(Character.toUpperCase(x.charAt(0))).append(x.substring(1));return b.toString();}
    private static String shortError(Throwable t){Throwable x=t instanceof InvocationTargetException i&&i.getCause()!=null?i.getCause():t;return x.getClass().getSimpleName()+(x.getMessage()==null?"":": "+x.getMessage());}private static void log(String m,Throwable t){try{Logger l=(Logger)call0(plugin,"getLogger");l.warning("[NarrativeSessions] "+m+": "+shortError(t));}catch(Throwable ignored){}}

    private record Request(String dialogue,Runnable onComplete,int priority,boolean interruptible,long seq) implements Comparable<Request>{public int compareTo(Request o){int p=Integer.compare(o.priority,priority);return p!=0?p:Long.compare(seq,o.seq);}}
    private record Snapshot(String dialogue,int priority,boolean interruptible,Object definition,String nodeId,Runnable onComplete,List<String> flowStack,Set<UUID> participants){}
    private static final class Active{final String dialogue;final int priority;final boolean interruptible;final Runnable onComplete;volatile Object coreSession;volatile boolean completing;volatile String lastMirroredNode="";final Set<UUID>participants=ConcurrentHashMap.newKeySet();Active(String d,int p,boolean i,Runnable c){dialogue=d;priority=p;interruptible=i;onComplete=c;}}
    private static final class SessionMeta{double audienceRadius;boolean mirrorAudience;}
}
