package com.operit.aiclaw.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingControlConfigTest {

    @Test
    void parsesAndRoundTripsUserDefinedModeControlsThroughProfile() {
        Properties source = new Properties();
        source.setProperty("thinking.modes", "1,2,5");
        source.setProperty("thinking.1.value", "high");
        source.setProperty("thinking.1.options", "none,low,medium,high,high");
        source.setProperty("thinking.1.off", "none");
        source.setProperty("thinking.2.value", "enabled");
        source.setProperty("thinking.2.options", "enabled,adaptive,disabled");
        source.setProperty("thinking.2.off", "disabled");
        source.setProperty("thinking.5.off", "remove");

        Profile profile = Profile.fromProperties("test", Path.of("test.properties"), source);
        ThinkingControlConfig controls = profile.getThinkingControls();

        assertEquals(List.of(1, 2, 5), List.copyOf(controls.modes().keySet()));
        assertEquals("high", controls.mode(1).value());
        assertEquals(List.of("none", "low", "medium", "high"), controls.mode(1).options());
        assertEquals("disabled", controls.mode(2).off());
        assertEquals("remove", controls.mode(5).off());

        Properties saved = profile.toProperties();
        assertEquals("none,low,medium,high", saved.getProperty("thinking.1.options"));
        assertEquals("enabled,adaptive,disabled", saved.getProperty("thinking.2.options"));
        assertEquals("remove", saved.getProperty("thinking.5.off"));

        Profile updated = profile.withOverrides(null, null, "new-model", null, null, null, null, null);
        assertEquals("new-model", updated.getModel());
        assertEquals("none,low,medium,high", updated.toProperties().getProperty("thinking.1.options"));
    }

    @Test
    void enforcesConfirmedModeCompatibilityRules() {
        assertTrue(ThinkingControlConfig.validateModes("1,2,5").isEmpty());
        assertTrue(ThinkingControlConfig.validateModes("3,4,5").isEmpty());

        assertFalse(ThinkingControlConfig.validateModes("1,3").isEmpty());
        assertFalse(ThinkingControlConfig.validateModes("2,4").isEmpty());
        assertFalse(ThinkingControlConfig.validateModes("5").isEmpty());
        assertFalse(ThinkingControlConfig.validateModes("1,1").isEmpty());
        assertFalse(ThinkingControlConfig.validateModes("6").isEmpty());
    }

    @Test
    void validatesNumericShortcutModesAfterTheyAreMergedWithProfileModes() {
        assertFalse(ThinkingControlConfig.validateModeNumbers(
                ThinkingControlConfig.mergeModes("1", List.of("3=4096"))).isEmpty());
        assertFalse(ThinkingControlConfig.validateModeNumbers(
                ThinkingControlConfig.mergeModes("2", List.of("4=enabled"))).isEmpty());
        assertFalse(ThinkingControlConfig.validateModeNumbers(
                ThinkingControlConfig.numericModesFromArguments(List.of("5=anything"))).isEmpty());
        assertTrue(ThinkingControlConfig.validateModeNumbers(
                ThinkingControlConfig.mergeModes("1", List.of("5=anything"))).isEmpty());
    }

    @Test
    void rejectsProfileValuesThatContradictFixedModes() {
        Properties source = new Properties();
        source.setProperty("thinking.4.value", "adaptive");
        source.setProperty("thinking.4.off", "disabled");
        source.setProperty("thinking.5.value", "lite");
        source.setProperty("thinking.5.options", "pro,lite");

        ThinkingControlConfig controls = ThinkingControlConfig.fromProperties(source);

        assertFalse(ThinkingControlConfig.validateControls("1,4", controls).isEmpty());
        assertFalse(ThinkingControlConfig.validateControls("1,5", controls).isEmpty());

        Properties valid = new Properties();
        valid.setProperty("thinking.4.value", "enabled");
        valid.setProperty("thinking.4.off", "remove");
        valid.setProperty("thinking.5.value", "pro");
        valid.setProperty("thinking.5.off", "remove");
        assertTrue(ThinkingControlConfig.validateControls("1,4,5",
                ThinkingControlConfig.fromProperties(valid)).isEmpty());
    }
}