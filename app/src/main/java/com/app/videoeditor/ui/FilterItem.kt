package com.app.videoeditor.ui

/**
 * @param lutAssetPath path under assets/luts/, e.g. "luts/moody.cube" - null means "Normal" (no filter).
 * @param thumbnailRes a drawable resource for the round thumbnail. Swap these for real
 * filtered preview thumbnails once you have them; a static icon is fine to start with.
 */
data class FilterItem(
    val id: String,
    val displayName: String,
    val lutAssetPath: String?,
    val thumbnailRes: Int
)
