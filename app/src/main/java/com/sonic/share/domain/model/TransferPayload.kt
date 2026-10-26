package com.sonic.share.domain.model

data class TransferPayload(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val sha256: String,
    val uriString: String? = null
)
