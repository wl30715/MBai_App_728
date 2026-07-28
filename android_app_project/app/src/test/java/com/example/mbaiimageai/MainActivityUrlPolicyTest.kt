package com.example.mbaiimageai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUrlPolicyTest {
    @Test
    fun onlyProductionHttpsHostStaysInsideWebView() {
        assertTrue(MainActivity.isInternalWebUrl("https://mbai.wang/"))
        assertTrue(MainActivity.isInternalWebUrl("https://MBAI.WANG/history"))
        assertFalse(MainActivity.isInternalWebUrl("http://mbai.wang/"))
        assertFalse(MainActivity.isInternalWebUrl("https://evil.example/"))
        assertFalse(MainActivity.isInternalWebUrl("file:///sdcard/private.txt"))
    }

    @Test
    fun externalIntentSchemeAllowlistRejectsLocalAndScriptUrls() {
        assertTrue(MainActivity.isAllowedExternalUrl("https://openai.com/"))
        assertTrue(MainActivity.isAllowedExternalUrl("mailto:support@mbai.wang"))
        assertTrue(MainActivity.isAllowedExternalUrl("tel:+10086"))
        assertFalse(MainActivity.isAllowedExternalUrl("http://openai.com/"))
        assertFalse(MainActivity.isAllowedExternalUrl("file:///sdcard/private.txt"))
        assertFalse(MainActivity.isAllowedExternalUrl("javascript:alert(1)"))
    }

    @Test
    fun onlyPackagedFrontendFilesAreIntercepted() {
        assertTrue(MainActivity.isBundledAssetPath("/static/styles.css"))
        assertTrue(MainActivity.isBundledAssetPath("/static/brand/logo.png"))
        assertTrue(MainActivity.isBundledAssetPath("/static/gallery/images/category-covers/portrait.webp"))

        assertFalse(MainActivity.isBundledAssetPath("/static/gallery/images/template-001.webp"))
        assertFalse(MainActivity.isBundledAssetPath("/outputs/20260724/task-image-1.png"))
        assertFalse(MainActivity.isBundledAssetPath("/api/tasks/task-001/outputs/1/thumbnail"))
    }

    @Test
    fun detectsOnlyTheBackendSessionCookie() {
        assertTrue(MainActivity.hasSessionCookie("theme=dark; ilab_session=valid-token"))
        assertTrue(MainActivity.hasSessionCookie("ilab_session=valid-token"))

        assertFalse(MainActivity.hasSessionCookie(null))
        assertFalse(MainActivity.hasSessionCookie(""))
        assertFalse(MainActivity.hasSessionCookie("ilab_session="))
        assertFalse(MainActivity.hasSessionCookie("old_ilab_session=valid-token"))
        assertFalse(MainActivity.hasSessionCookie("codex_webui_session=legacy-token"))
    }
}
