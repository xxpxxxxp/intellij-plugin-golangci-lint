package com.ypwang.plugin.util

import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod

class GoLinterDownloader {
    class GithubAsset(
            
    )

    class GithubRelease(
            val url: String,
            val preRelease: Boolean,
            val assets: List<GithubAsset>
    )

    companion object {
        fun httpGetSimple(url: String): String? {
            val httpClient = HttpClient()
            httpClient.httpConnectionManager.params.connectionTimeout = 2000
            val getMethod = GetMethod(url)
            httpClient.executeMethod(getMethod)
            return getMethod.responseBodyAsString       // be careful of null here
        }
    }
}

fun main() {
    println(GoLinterDownloader.httpGetSimple("https://api.github.com/repos/golangci/golangci-lint/releases/latest"))
}