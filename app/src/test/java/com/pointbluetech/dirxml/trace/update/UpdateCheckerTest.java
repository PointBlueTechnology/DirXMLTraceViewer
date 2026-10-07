package com.pointbluetech.dirxml.trace.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void comparesVersions() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.0"));
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.3"));
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.2.0-SNAPSHOT"), "a release is newer than its snapshot");
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"));
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"));
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.2.0-SNAPSHOT"));
        assertFalse(UpdateChecker.isNewer("1.2.0-SNAPSHOT", "1.2.0"));
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"));
    }

    @Test
    void parsesGitHubLatestReleaseResponse() throws Exception {
        String json = """
                {"url":"https://api.github.com/repos/PointBlueTechnology/DirXMLTraceViewer/releases/1",
                 "html_url":"https://github.com/PointBlueTechnology/DirXMLTraceViewer/releases/tag/v1.3.0",
                 "tag_name":"v1.3.0","name":"DirXML Trace Viewer 1.3.0",
                 "assets":[{"browser_download_url":"https://github.com/x/y/releases/download/v1.3.0/a.dmg",
                            "html_url":"ignored"}]}
                """;
        UpdateChecker.Release r = UpdateChecker.parse(json);
        assertEquals("1.3.0", r.version());
        assertEquals("v1.3.0", r.tag());
        assertEquals("https://github.com/PointBlueTechnology/DirXMLTraceViewer/releases/tag/v1.3.0", r.pageUrl());
    }

    @Test
    void missingTagIsAnError() {
        assertThrows(java.io.IOException.class, () -> UpdateChecker.parse("{\"message\":\"Not Found\"}"));
    }
}
