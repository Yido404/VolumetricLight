package com.example.volumetriclight;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Transient edge/cooldown state; no disk writes on each tick. */
public final class TriggerState {
    private Set<UUID> previous = Set.of();
    private long nextFire, pulseUntil;
    private boolean latched;
    private boolean initialized;
    private boolean commandedOn;
    public UUID locked;
    public boolean active;
    public void commandOn() { commandedOn = true; }

    public boolean update(Light light, LinkedHashSet<UUID> inside, long tick) {
        boolean old = active;
        if (!initialized) { latched = light.trigger == Light.Trigger.ENTER && light.action == Light.Action.OFF; initialized = true; }
        if (locked != null && !inside.contains(locked)) locked = null;
        Set<UUID> entering = new HashSet<>(inside); entering.removeAll(previous);
        if (light.trigger == Light.Trigger.PRESENCE && !inside.equals(previous)) commandedOn = false;
        if (light.trigger == Light.Trigger.ENTER && !entering.isEmpty() && tick >= nextFire) {
            commandedOn = false;
            nextFire = tick + light.cooldown;
            if (locked == null) locked = inside.stream().filter(entering::contains).findFirst().orElse(null);
            switch (light.action) {
                case ON -> latched = true;
                case OFF -> latched = false;
                case PULSE -> pulseUntil = tick + light.duration;
            }
        }
        if (light.trigger == Light.Trigger.PRESENCE && locked == null && !inside.isEmpty()) locked = inside.iterator().next();
        active = light.enabled && (commandedOn || switch (light.trigger) {
            case OFF -> true;
            case PRESENCE -> !inside.isEmpty();
            case ENTER -> latched || tick < pulseUntil;
        });
        previous = Set.copyOf(inside);
        return old != active;
    }
}
