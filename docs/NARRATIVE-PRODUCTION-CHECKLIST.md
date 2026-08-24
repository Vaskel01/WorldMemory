# Narrative production checklist

Before shipping a content update:

1. `/studio backup`
2. `/studio index`
3. `/studio validate`
4. `/nguard doctor`
5. Ensure no active narrative sessions with `/nguard status`
6. `/studio reload` (routes through the safe reload in alpha.53)
7. Play-test the changed story/dialogue/scene/cutscene.
8. Run `/nguard doctor` again.

If a player is stuck after an unusual disconnect or interrupted cinematic, use `/nguard repair` and inspect the server log for the watchdog finding.
