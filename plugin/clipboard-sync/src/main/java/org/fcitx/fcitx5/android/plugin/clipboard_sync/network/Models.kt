package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClipboardData(
    @SerialName("type")
    @JsonNames("Type")
    val type: String = "Text",

    @SerialName("text")
    @JsonNames("Clipboard")
    val text: String = "",

    @SerialName("hash")
    val hash: String = "",

    @SerialName("hasData")
    val hasData: Boolean = false,

    @SerialName("dataName")
    @JsonNames("File")
    val dataName: String = "",

    @SerialName("size")
    val size: Long = 0
)
