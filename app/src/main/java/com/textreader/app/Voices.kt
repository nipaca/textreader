package com.textreader.app

/** One English voice from the Kokoro v1.0 model. [id] is the model's speaker number. */
data class Voice(val id: Int, val name: String, val accent: String, val gender: String) {
    val label: String get() = "$name ($accent $gender)"
}

object Voices {
    const val DEFAULT_ID = 3 // Heart

    val all: List<Voice> = listOf(
        Voice(0, "Alloy", "American", "female"),
        Voice(1, "Aoede", "American", "female"),
        Voice(2, "Bella", "American", "female"),
        Voice(3, "Heart", "American", "female"),
        Voice(4, "Jessica", "American", "female"),
        Voice(5, "Kore", "American", "female"),
        Voice(6, "Nicole", "American", "female"),
        Voice(7, "Nova", "American", "female"),
        Voice(8, "River", "American", "female"),
        Voice(9, "Sarah", "American", "female"),
        Voice(10, "Sky", "American", "female"),
        Voice(11, "Adam", "American", "male"),
        Voice(12, "Echo", "American", "male"),
        Voice(13, "Eric", "American", "male"),
        Voice(14, "Fenrir", "American", "male"),
        Voice(15, "Liam", "American", "male"),
        Voice(16, "Michael", "American", "male"),
        Voice(17, "Onyx", "American", "male"),
        Voice(18, "Puck", "American", "male"),
        Voice(19, "Santa", "American", "male"),
        Voice(20, "Alice", "British", "female"),
        Voice(21, "Emma", "British", "female"),
        Voice(22, "Isabella", "British", "female"),
        Voice(23, "Lily", "British", "female"),
        Voice(24, "Daniel", "British", "male"),
        Voice(25, "Fable", "British", "male"),
        Voice(26, "George", "British", "male"),
        Voice(27, "Lewis", "British", "male"),
    )

    fun byId(id: Int): Voice = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}
