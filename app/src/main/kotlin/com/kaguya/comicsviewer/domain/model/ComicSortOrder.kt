package com.kaguya.comicsviewer.domain.model

/** 阅读模式。 */
enum class ReadingMode {
    /** 左右翻页（横屏/单页式）。 */
    PAGED,

    /** 上下翻页（垂直方向一页一页翻）。 */
    CONTINUOUS,

    /** Webtoon 式条带滚动（垂直方向连续滚动）。 */
    WEBTOON
}
