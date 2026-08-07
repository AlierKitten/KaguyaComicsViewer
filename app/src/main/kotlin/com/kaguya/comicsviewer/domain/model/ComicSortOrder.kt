package com.kaguya.comicsviewer.domain.model

/** 阅读模式。 */
enum class ReadingMode {
    /** 左右翻页（横屏/单页式）。 */
    PAGED,

    /** 上下滚动（长条/连环画）。 */
    CONTINUOUS,

    /** Webtoon 式条带滚动（左右单页，垂直连续）。 */
    WEBTOON
}
