package io.anonero.ui.onboard

/**
 * Validates the vocabulary and supported length for a pasted recovery phrase.
 * The native Monero wallet remains responsible for checksum and seed validation.
 */
internal data class RestoreSeedValidation(
    val words: List<String>,
    val invalid: Boolean
)

internal fun validateRestoreSeedWords(
    rawSeed: String,
    polyseedWords: Set<String>,
    moneroWords: Set<String>
): RestoreSeedValidation {
    if (rawSeed.isBlank()) return RestoreSeedValidation(emptyList(), false)

    val words = rawSeed.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }

    if (words.size != 16 && words.size != 25) {
        return RestoreSeedValidation(emptyList(), words.size >= 16)
    }

    val vocabulary = if (words.size == 16) polyseedWords else moneroWords
    if (vocabulary.isEmpty()) {
        // Word lists may still be loading. Leave the button disabled without
        // incorrectly reporting a valid phrase as invalid.
        return RestoreSeedValidation(emptyList(), false)
    }

    val invalid = words.any { it !in vocabulary }
    return RestoreSeedValidation(if (invalid) emptyList() else words, invalid)
}
