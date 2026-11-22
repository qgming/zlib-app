package com.qgming.zlib.core.model

enum class BookGridColumns(private val fixedCount: Int?) {
    AUTO(null),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    ;

    /** Uses the grid's current window width, including split screen and rotation. */
    fun countForWidth(widthDp: Int): Int = fixedCount ?: when {
        widthDp >= 840 -> 5
        widthDp >= 600 -> 4
        widthDp >= 360 -> 3
        else -> 2
    }

    companion object {
        fun fromPreference(value: String?): BookGridColumns = entries.firstOrNull { it.name == value } ?: AUTO
    }
}
