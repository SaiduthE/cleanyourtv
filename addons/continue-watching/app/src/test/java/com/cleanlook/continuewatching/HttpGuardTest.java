package com.cleanlook.continuewatching;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HttpGuardTest {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("commons.wikimedia.org", "upload.wikimedia.org"));

    @Test public void onlyHttpsToTheTwoWikimediaHostsPasses() {
        assertTrue(Http.allowedHttps("https://commons.wikimedia.org/w/api.php?action=query", HOSTS));
        assertTrue(Http.allowedHttps("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1920px-X.jpg", HOSTS));
        assertTrue(Http.allowedHttps("https://UPLOAD.wikimedia.org:443/x.jpg", HOSTS));
    }

    @Test public void everythingElseIsRefused() {
        assertFalse(Http.allowedHttps("http://commons.wikimedia.org/w/api.php", HOSTS));
        assertFalse(Http.allowedHttps("https://thumb.wikimedia.org/x.jpg", HOSTS));
        assertFalse(Http.allowedHttps("https://wikimedia.org/x.jpg", HOSTS));
        assertFalse(Http.allowedHttps("https://commons.wikimedia.org.evil.example/x", HOSTS));
        assertFalse(Http.allowedHttps("https://evil.example/commons.wikimedia.org", HOSTS));
        assertFalse(Http.allowedHttps("https://user@commons.wikimedia.org/x", HOSTS));
        assertFalse(Http.allowedHttps("https://commons.wikimedia.org:8443/x", HOSTS));
        assertFalse(Http.allowedHttps("https://evil.example\\@commons.wikimedia.org/x", HOSTS));
        assertFalse(Http.allowedHttps("ftp://commons.wikimedia.org/x", HOSTS));
        assertFalse(Http.allowedHttps("not a url", HOSTS));
        assertFalse(Http.allowedHttps(null, HOSTS));
    }
}
