package com.ypwang.plugin.model

import com.google.gson.annotations.SerializedName

class GithubAsset(
    val url: String,
    val id: Int,
    val name: String,
    val size: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String
)

class GithubRelease(
    val url: String,
    val name: String,
    @SerializedName("prerelease") val preRelease: Boolean,
    val assets: List<GithubAsset>,
    val body: String
)