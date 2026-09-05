package com.operit.aiclaw.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigModelDefaultTest {

    @TempDir
    Path home;

    @Test
    void leavesModelUnsetWhenNeitherProfileNorConfigSuppliesOne() {
        Config config = Config.load(new ProfileStore(home));
        Profile profile = Profile.fromProperties("test", home.resolve("test.properties"), new Properties());

        assertEquals("", Config.DEFAULT_MODEL);
        assertEquals("", config.getModel());
        assertEquals("", profile.getModel());
    }
}