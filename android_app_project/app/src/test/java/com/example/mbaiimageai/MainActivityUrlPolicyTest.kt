package com.example.mbaiimageai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUrlPolicyTest {
    @Test
    fun productionOriginOnlyAcceptsMatchingHttpsHostAndPort() {
        val origin = "https://mbai.wang"
        assertTrue(MainActivity.isInternalWebUrlForOrigin("https://mbai.wang/", origin))
        assertTrue(MainActivity.isInternalWebUrlForOrigin("https://MBAI.WANG/history", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("http://mbai.wang/", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("https://mbai.wang:8443/", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("https://evil.example/", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("file:///sdcard/private.txt", origin))
    }

    @Test
    fun localOriginOnlyAcceptsAdbForwardedBackend() {
        val origin = "http://127.0.0.1:8787"
        assertTrue(MainActivity.isInternalWebUrlForOrigin("http://127.0.0.1:8787/", origin))
        assertTrue(MainActivity.isInternalWebUrlForOrigin("http://127.0.0.1:8787/history", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("http://127.0.0.1/", origin))
        assertFalse(MainActivity.isInternalWebUrlForOrigin("https://127.0.0.1:8787/", origin))
    }

    @Test
    fun splashImagePathsResolveAgainstTheActiveServer() {
        assertTrue(
            MainActivity.resolveAppUrlForOrigin(
                "/static/splash-ad-images/ad.webp",
                "http://127.0.0.1:8787",
            ) == "http://127.0.0.1:8787/static/splash-ad-images/ad.webp"
        )
        assertTrue(
            MainActivity.resolveAppUrlForOrigin(
                "https://cdn.example/ad.webp",
                "https://mbai.wang",
            ) == "https://cdn.example/ad.webp"
        )
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

    @Test
    fun splashLimitsRejectUnsafeOrAccidentalValues() {
        assertEquals(1, MainActivity.clampSplashDuration(-1))
        assertEquals(3, MainActivity.clampSplashDuration(3))
        assertEquals(15, MainActivity.clampSplashDuration(999))
        assertEquals(0, MainActivity.clampSplashDailyViews(-1))
        assertEquals(3, MainActivity.clampSplashDailyViews(3))
        assertEquals(20, MainActivity.clampSplashDailyViews(999))
    }

    @Test
    fun downloadNamesPreferContentDispositionAndStaySafe() {
        assertEquals(
            "测试图片.png",
            MainActivity.suggestDownloadFileName(
                "https://mbai.wang/api/tasks/task-1/outputs/1/download",
                "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E5%9B%BE%E7%89%87.png",
                "image/png",
            ),
        )
        assertEquals(
            "result_image.webp",
            MainActivity.suggestDownloadFileName(
                "https://mbai.wang/download",
                "attachment; filename=\"result/image.webp\"",
                "image/webp",
            ),
        )
        assertEquals(
            "task-1.zip",
            MainActivity.suggestDownloadFileName(
                "https://mbai.wang/download/task-1",
                null,
                "application/zip",
            ),
        )
    }

    @Test
    fun downloadsAcceptHttpsAndInlineImagesButRejectUnsafeSchemes() {
        assertTrue(MainActivity.isSafeDownloadUrl("https://cdn.example/result.png"))
        assertTrue(MainActivity.isSafeDownloadUrl("data:image/png;base64,iVBORw0KGgo="))
        assertTrue(MainActivity.isInlineImageDataUrl("data:image/webp;base64,UklGRg=="))

        assertFalse(MainActivity.isSafeDownloadUrl("data:text/html;base64,PHNjcmlwdD4="))
        assertFalse(MainActivity.isSafeDownloadUrl("file:///sdcard/private.png"))
        assertFalse(MainActivity.isSafeDownloadUrl("javascript:alert(1)"))
        assertFalse(MainActivity.isInlineImageDataUrl("data:image/png,not-base64"))
    }
}
