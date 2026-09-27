package com.github.tartaricacid.tlm_sincerely.priority.decision;

import com.github.tartaricacid.tlm_sincerely.priority.detection.Availability;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaidSwitchStateTest {
    @Test
    void trackCurrentAvailability_available_resetsTimer() {
        MaidSwitchState state = new MaidSwitchState();
        ResourceLocation task = ResourceLocation.fromNamespaceAndPath("test", "task");
        state.trackCurrentAvailability(task, Availability.UNAVAILABLE, 100);
        state.trackCurrentAvailability(task, Availability.UNAVAILABLE, 101);
        assertTrue(state.isUnavailableHoldElapsed(200, 0));
        state.trackCurrentAvailability(task, Availability.AVAILABLE, 201);
        assertFalse(state.isUnavailableHoldElapsed(202, 0));
    }

    @Test
    void trackCurrentAvailability_unknown_keepsTimer() {
        MaidSwitchState state = new MaidSwitchState();
        ResourceLocation task = ResourceLocation.fromNamespaceAndPath("test", "task");
        state.trackCurrentAvailability(task, Availability.UNAVAILABLE, 100);
        state.trackCurrentAvailability(task, Availability.UNKNOWN, 101);
        assertTrue(state.isUnavailableHoldElapsed(200, 0));
    }

    @Test
    void trackCurrentAvailability_unknown_doesNotStartTimer() {
        MaidSwitchState state = new MaidSwitchState();
        ResourceLocation task = ResourceLocation.fromNamespaceAndPath("test", "task");
        state.trackCurrentAvailability(task, Availability.UNKNOWN, 100);
        assertFalse(state.isUnavailableHoldElapsed(200, 0));
    }

    @Test
    void trackCurrentAvailability_taskChange_resetsTimerOnAvailable() {
        MaidSwitchState state = new MaidSwitchState();
        ResourceLocation task1 = ResourceLocation.fromNamespaceAndPath("test", "task1");
        ResourceLocation task2 = ResourceLocation.fromNamespaceAndPath("test", "task2");
        state.trackCurrentAvailability(task1, Availability.UNAVAILABLE, 100);
        state.trackCurrentAvailability(task2, Availability.AVAILABLE, 101);
        assertFalse(state.isUnavailableHoldElapsed(102, 0));
    }
}
