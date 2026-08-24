package com.yourstudio.worldmemory.narrative;
import java.util.Map;
public final class NarrativeBridge {
    private NarrativeBridge() {}
    public static synchronized void startup(Object plugin) {
        NarrativeComposition.startup(plugin);
        NarrativeActors.startup(plugin);
        NarrativeFlow.startup(plugin);
        NarrativeSessions.startup(plugin);
        NarrativeScenes.startup(plugin);
        NarrativeStudio.startup(plugin);
        NarrativeHardening.startup(plugin);
    }
    public static synchronized void shutdown() {
        NarrativeHardening.shutdown();
        NarrativeStudio.shutdown();
        NarrativeScenes.shutdown();
        NarrativeSessions.shutdown();
        NarrativeFlow.shutdown();
        NarrativeActors.shutdown();
        NarrativeComposition.shutdown();
    }
    public static synchronized void reload() {
        NarrativeComposition.reload();
        NarrativeActors.reload();
        NarrativeFlow.reload();
        NarrativeSessions.reload();
        NarrativeScenes.reload();
        NarrativeStudio.reload();
        NarrativeHardening.reload();
    }
    public static void enterRender(Object player,Object session){NarrativeFlow.enterRender(player,session);NarrativeSessions.onRender(player,session);NarrativeActors.enterRender(player,session);NarrativeHardening.onRender(player,session);}
    public static void exitRender(){NarrativeFlow.exitRender();NarrativeActors.exitRender();}
    public static void onDialogueFinish(Object player,Object session){NarrativeHardening.onDialogueFinish(player,session);NarrativeFlow.onDialogueFinish(player,session);NarrativeActors.onDialogueFinish(player,session);NarrativeSessions.onDialogueFinish(player,session);}
    public static void onChoice(Object player,Object choice){NarrativeActors.onChoice(player,choice);NarrativeFlow.onChoice(player,choice);}
    public static boolean actorConditionsMatch(Object player,Map<String,Object> when){return NarrativeActors.actorConditionsMatch(player,when)&&NarrativeFlow.conditionsMatch(player,when);}
    public static Map<String,Object> stripActorConditions(Map<String,Object> when){return NarrativeFlow.stripConditions(NarrativeActors.stripActorConditions(when));}
    public static String interpolate(Object player,String text){return NarrativeFlow.interpolate(player,NarrativeActors.interpolate(player,text));}
}
