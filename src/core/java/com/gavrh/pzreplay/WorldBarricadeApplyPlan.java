package com.gavrh.pzreplay;

public record WorldBarricadeApplyPlan(
    boolean removeFirst,
    int addPlanks,
    boolean addMetal,
    boolean addMetalBar,
    boolean exactPlankHealth
) {
    public static WorldBarricadeApplyPlan of(WorldBarricadeState state, int currentMaterial,
        int currentPlanks) {
        boolean removeFirst = currentMaterial != 0
            && (currentMaterial != state.material()
                || (state.material() == 1 && currentPlanks != state.plankCount()));
        int addPlanks = state.material() == 1 ? state.plankCount() : 0;
        boolean addMetal = state.material() == 2;
        boolean addMetalBar = state.material() == 3;
        boolean exactPlankHealth = !state.aggregateOnly() && state.plankHealth().length > 0;
        return new WorldBarricadeApplyPlan(removeFirst, addPlanks, addMetal, addMetalBar,
            exactPlankHealth);
    }
}
