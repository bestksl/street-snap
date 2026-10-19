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
    SYMMETRY("对称构图"),
    DIAGONAL("对角线构图"),
    TRIANGLE("三角形构图"),
    LEADING_LINES("引导线构图"),
    CONVERGENCE("汇聚线构图"),
    VERTICAL("竖线构图"),
    HORIZON("水平分割"),
    /** A horizon stranded in the middle band: the classic bisected-frame mistake. */
    HORIZON_SPLIT("水平腰斩"),
    FRAME("框式构图"),
    FOREGROUND_DEPTH("前景层次"),
    REPETITION("节奏构图"),
    CONTRAST("明暗对比"),
    NEGATIVE_SPACE("留白构图"),
    WIDE_CONTEXT("环境构图"),
    /** Content presses against three or more frame edges while the middle is not empty. */
    TOO_TIGHT("取景太满"),
    /** One edge alone is crossed by content (a cropped subject), with an action direction. */
    EDGE_CUT("主体贴边"),
    /** Most of the frame is quiet and a single small mass carries the content. */
    MINIMAL("极简留白"),
    CONSERVATIVE("稳妥取景"),
    GENERIC("画面分析"),
}
