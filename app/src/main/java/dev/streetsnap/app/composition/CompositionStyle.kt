package dev.streetsnap.app.composition

/**
 * Photography-level vocabulary for the rule-based advisor.
 *
 * The advisor never claims the frame contains a named object: each style is the label of
 * the composition language the image statistics support, which is what the UI copy uses.
 */
internal enum class CompositionStyle(val title: String) {
    THIRDS("三分构图"),
    CENTERED("中心构图"),
    DIAGONAL("对角线构图"),
    TRIANGLE("三角形构图"),
    LEADING_LINES("引导线构图"),
    FRAME("框式构图"),
    FOREGROUND_DEPTH("前景层次"),
    NEGATIVE_SPACE("留白构图"),
    WIDE_CONTEXT("环境构图"),
    CONSERVATIVE("稳妥取景"),
    GENERIC("画面分析"),
}
