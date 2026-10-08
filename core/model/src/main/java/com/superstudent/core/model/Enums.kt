package com.superstudent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Business task state per design §4 state machine + §1.5 error mapping. */
@Serializable
enum class TaskState {
    @SerialName("QUEUED") QUEUED,
    @SerialName("RUNNING") RUNNING,
    @SerialName("SUCCEEDED") SUCCEEDED,
    @SerialName("FAILED_RETRYABLE") FAILED_RETRYABLE,
    @SerialName("FAILED_PERMANENT") FAILED_PERMANENT,
    @SerialName("CANCEL_REQUESTED") CANCEL_REQUESTED,
    @SerialName("CANCELED") CANCELED,
    @SerialName("UNKNOWN") UNKNOWN,
    @SerialName("RETRY_WAIT") RETRY_WAIT,
    @SerialName("AUTH_EXPIRED") AUTH_EXPIRED,
    @SerialName("ACCESS_DENIED") ACCESS_DENIED,
    @SerialName("IDENTITY_INVALID") IDENTITY_INVALID,
}

/** Pipeline stages per design §4. */
@Serializable
enum class TaskStage {
    @SerialName("UPLOAD") UPLOAD,
    @SerialName("PARSE") PARSE,
    @SerialName("QMIND_INDEX") QMIND_INDEX,
    @SerialName("GENERATE_PLAN") GENERATE_PLAN,
    @SerialName("GENERATE_CARDS") GENERATE_CARDS,
    @SerialName("GENERATE_MINDMAP") GENERATE_MINDMAP,
    @SerialName("GENERATE_DECK") GENERATE_DECK,
    @SerialName("GENERATE_EXERCISES") GENERATE_EXERCISES,
    @SerialName("VALIDATE") VALIDATE,
    @SerialName("PUBLISH") PUBLISH,
}

@Serializable
enum class LearningGoal {
    @SerialName("FINAL_REVIEW") FINAL_REVIEW,
    @SerialName("PREVIEW") PREVIEW,
    @SerialName("DAILY") DAILY,
    @SerialName("PRESENTATION") PRESENTATION,
}

@Serializable
enum class PackageStatus {
    @SerialName("DRAFT") DRAFT,
    @SerialName("READY") READY,
    @SerialName("GENERATING") GENERATING,
    @SerialName("DONE") DONE,
    @SerialName("LOCAL_ONLY") LOCAL_ONLY,
    @SerialName("RECOVERY_REQUIRED") RECOVERY_REQUIRED,
}

@Serializable
enum class ResultKind {
    @SerialName("PLAN") PLAN,
    @SerialName("CARDS") CARDS,
    @SerialName("MINDMAP") MINDMAP,
    @SerialName("DECK") DECK,
    @SerialName("EXERCISES") EXERCISES,
}

@Serializable
enum class UploadState {
    @SerialName("PENDING") PENDING,
    @SerialName("UPLOADING") UPLOADING,
    @SerialName("UPLOADED") UPLOADED,
    @SerialName("FAILED") FAILED,
    @SerialName("LOCAL_ONLY") LOCAL_ONLY,
}

@Serializable
enum class CardKind {
    @SerialName("DEFINITION") DEFINITION,
    @SerialName("FORMULA") FORMULA,
    @SerialName("CONFUSION") CONFUSION,
    @SerialName("EXAMPLE") EXAMPLE,
}

@Serializable
enum class CardDifficulty {
    @SerialName("EASY") EASY,
    @SerialName("MEDIUM") MEDIUM,
    @SerialName("HARD") HARD,
}

@Serializable
enum class TopicDifficulty {
    @SerialName("EASY") EASY,
    @SerialName("MEDIUM") MEDIUM,
    @SerialName("HARD") HARD,
}

@Serializable
enum class TopicPriority {
    @SerialName("MUST") MUST,
    @SerialName("SHOULD") SHOULD,
    @SerialName("OPTIONAL") OPTIONAL,
}

@Serializable
enum class PlanTaskType {
    @SerialName("READ") READ,
    @SerialName("RECALL") RECALL,
    @SerialName("PRACTICE") PRACTICE,
}

@Serializable
enum class CardMastery {
    @SerialName("NEW") NEW,
    @SerialName("LEARNING") LEARNING,
    @SerialName("MASTERED") MASTERED,
}

@Serializable
enum class CardImageStatus {
    @SerialName("READY") READY,
    @SerialName("FAILED") FAILED,
    @SerialName("SKIPPED") SKIPPED,
}

@Serializable
enum class MindmapLayout {
    @SerialName("CENTER") CENTER,
    @SerialName("HORIZONTAL") HORIZONTAL,
}

@Serializable
enum class SlideKind {
    @SerialName("TITLE") TITLE,
    @SerialName("CONCEPT") CONCEPT,
    @SerialName("EXAMPLE") EXAMPLE,
    @SerialName("SUMMARY") SUMMARY,
}

@Serializable
enum class ExerciseType {
    @SerialName("SINGLE_CHOICE") SINGLE_CHOICE,
    @SerialName("CALCULATION") CALCULATION,
}

@Serializable
enum class ExerciseDifficulty {
    @SerialName("EASY") EASY,
    @SerialName("MEDIUM") MEDIUM,
    @SerialName("HARD") HARD,
}

/** qmind source deletion state (design §7.2). A pending delete hides content until it clears. */
@Serializable
enum class QmindDeletionState {
    @SerialName("NONE") NONE,
    @SerialName("DELETE_PENDING") DELETE_PENDING,
}

/**
 * Canonical source format (design increment §4). The Drive object's extension is always one of
 * these; the raw user suffix never enters a path. `generatable=false` means the learning package
 * cannot be generated from this source and the UI must say so instead of failing silently.
 */
@Serializable
enum class CanonicalType(val ext: String, val mimeType: String, val generatable: Boolean) {
    @SerialName("PDF") PDF("pdf", "application/pdf", true),
    @SerialName("DOCX") DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true),
    @SerialName("PPTX") PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", true),
    @SerialName("TXT") TXT("txt", "text/plain", true),
    @SerialName("MD") MD("md", "text/markdown", true),
    @SerialName("JPG") JPG("jpg", "image/jpeg", true),
    @SerialName("PNG") PNG("png", "image/png", true),
    @SerialName("WEBP") WEBP("webp", "image/webp", true),
    @SerialName("HEIC") HEIC("heic", "image/heic", true),
    @SerialName("HEIF") HEIF("heif", "image/heif", true),
    @SerialName("UNKNOWN") UNKNOWN("bin", "application/octet-stream", false);

    companion object {
        fun of(name: String?): CanonicalType =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** How the app can still reach the picked file after the picker returned (design increment §1). */
@Serializable
enum class LocalAccessMode {
    @SerialName("NONE") NONE,
    @SerialName("PERSISTED_URI") PERSISTED_URI,
    @SerialName("APP_COPY") APP_COPY;

    companion object {
        fun of(name: String?): LocalAccessMode =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}
