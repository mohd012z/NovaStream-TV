package com.novastream.tv

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class DownloadItem(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val progress: Int = 0,
    val state: DownloadState = DownloadState.QUEUED
)

/**
 * UI-facing download queue model. Actual download implementation should only be connected
 * to media the user/provider has authorized for offline storage.
 */
class DownloadQueue {
    private val items = mutableListOf<DownloadItem>()
    fun all(): List<DownloadItem> = items.toList()
    fun add(item: DownloadItem) { if (items.none { it.id == item.id }) items += item }
    fun remove(id: String) { items.removeAll { it.id == id } }
}
