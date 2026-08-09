package com.kaguya.comicsviewer.domain.model

/** 漫画库排序字段。 */
enum class ComicSortField {
    /** 按名称。 */
    NAME,

    /** 按大小。 */
    SIZE,

    /** 按日期（加入漫画库的时间）。 */
    DATE
}

/** 漫画库排序方向。 */
enum class SortDirection {
    ASCENDING,
    DESCENDING
}

/** 漫画库排序方式，由字段 + 方向组成。 */
data class ComicSortOrder(
    val field: ComicSortField = ComicSortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING
)
