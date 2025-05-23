package com.ethran.notable.utils

object StringUtils {
    fun fuzzyMatch(input: String, target: String): Boolean {
        val inputNormalized = input.trim().lowercase()
        val targetNormalized = target.trim().lowercase()
        return inputNormalized == targetNormalized
    }

    fun findBestMatch(input: String, candidates: List<String>): String? {
        return candidates.find { fuzzyMatch(input, it) }
    }
} 