package com.caesiumstudio.pinstream.data

import java.io.Serializable

data class SiteEntry(
    val id: Long,
    val url: String,
    val displayName: String,
    val isFavorite: Boolean = false
) : Serializable
