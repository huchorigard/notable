package com.ethran.notable.utils

class GemmaTokenizer {
    // TODO: Load vocab from assets or file, implement real encoding/decoding
    fun encode(text: String): IntArray {
        // Placeholder: split by space and map to fake IDs
        return text.split(" ").map { it.hashCode() and 0x7FFFFFFF }.toIntArray()
    }

    fun decode(tokens: IntArray): String {
        // Placeholder: join fake tokens as string
        return tokens.joinToString(" ") { it.toString() }
    }
} 