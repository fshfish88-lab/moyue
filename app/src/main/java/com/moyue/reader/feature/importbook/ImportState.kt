package com.moyue.reader.feature.importbook

enum class ImportStage {
    WAITING,
    COPYING,
    DETECTING,
    PARSING,
    SAVING,
    COMPLETED,
}

sealed interface ImportState {
    data class Running(
        val taskId: String,
        val stage: ImportStage,
        val progress: Float,
    ) : ImportState {
        init {
            require(progress in 0f..1f) { "progress must be between 0 and 1" }
        }
    }

    data class Completed(val bookId: Long) : ImportState
    data class RecoverableError(val taskId: String, val message: String) : ImportState
    data class FatalError(val taskId: String, val message: String) : ImportState
    data class Cancelled(val taskId: String) : ImportState
}
