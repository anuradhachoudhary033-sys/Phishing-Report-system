package com.soar.client.data.util

object HtmlTextCleaner {
    /**
     * Aggressively cleans raw email snippets and plain-text fallbacks by removing:
     * - DOCTYPE, HTML headers, XML namespaces, schemas
     * - Closed and unclosed <head>, <style>, <script> blocks (even when truncated midway)
     * - All closed and unclosed HTML tags
     * - Stray HTML attribute fragments (e.g. xmlns=..., class=..., lang=...)
     * - Residual CSS selectors / rules / braces
     * - HTML entities (&nbsp;, &amp;, etc.)
     * - "(failed to parse)" or parse error markers
     * - Normalizes spaces and provides a clean preview fallback
     */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw

        // 1. Remove DOCTYPE, unclosed doctype
        text = text.replace(Regex("(?is)<!DOCTYPE[^>]*>"), " ")
        text = text.replace(Regex("(?is)<!DOCTYPE[\\s\\S]*?$"), " ")

        // 2. Remove <head>...</head> and unclosed <head... to end
        text = text.replace(Regex("(?is)<head[\\s\\S]*?</head>"), " ")
        text = text.replace(Regex("(?is)<head[\\s\\S]*?$"), " ")

        // 3. Remove <style>...</style> and unclosed <style... to end
        text = text.replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        text = text.replace(Regex("(?is)<style[\\s\\S]*?$"), " ")

        // 4. Remove <script>...</script> and unclosed <script... to end
        text = text.replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        text = text.replace(Regex("(?is)<script[\\s\\S]*?$"), " ")

        // 5. Remove comments <!-- ... --> and unclosed comments
        text = text.replace(Regex("(?is)<!--[\\s\\S]*?-->"), " ")
        text = text.replace(Regex("(?is)<!--[\\s\\S]*?$"), " ")

        // 6. Remove <xml>...</xml>
        text = text.replace(Regex("(?is)<xml[\\s\\S]*?</xml>"), " ")
        text = text.replace(Regex("(?is)<xml[\\s\\S]*?$"), " ")

        // 7. Strip all complete HTML tags <...>
        text = text.replace(Regex("<[^>]+>"), " ")

        // 8. Strip trailing unclosed tag at end of truncated string e.g. <body class="...
        text = text.replace(Regex("<[^>]*$"), " ")

        // 9. Strip leading orphan tag fragment e.g. ...ang="en" ...>
        text = text.replace(Regex("^[^<]*>"), " ")

        // 10. Strip residual XML / W3C namespace URLs
        text = text.replace(Regex("https?://www.w3.org/[^\\s\"'>]+"), " ")

        // 11. Strip lingering HTML attributes (e.g. xmlns=..., class="...", lang="en")
        text = text.replace(
            Regex("(?i)\\b(xmlns|lang|class|style|href|src|target|align|valign|bgcolor|width|height)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"),
            " "
        )

        // 12. Strip CSS selectors / rule blocks e.g. { ... } or unclosed { ...
        text = text.replace(Regex("\\{[^}]*\\}"), " ")
        text = text.replace(Regex("\\{[^}]*$"), " ")

        // 13. Decode common HTML entities
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&#8217;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace("&hellip;", "...")

        // 14. Strip "(failed to parse)" or technical boilerplate
        text = text.replace("(failed to parse)", "")

        // 15. Normalize spaces and linebreaks
        text = text.replace(Regex("\\s+"), " ").trim()

        // If after stripping it's empty or still contains mostly tag attribute garbage
        if (text.isEmpty() || text.matches(Regex("^[\\s_\\-=+*/\\\\:;,.|~`!@#\$%^&*()<>\"'\\[\\]{}]+$"))) {
            return ""
        }

        return text
    }
}
