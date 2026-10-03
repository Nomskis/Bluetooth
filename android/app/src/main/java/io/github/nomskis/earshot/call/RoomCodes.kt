package io.github.nomskis.earshot.call

import java.security.SecureRandom

/** Friendly, hard-to-guess room codes like "calm-otter-4821". Same word lists as web/js/rooms.js. */
object RoomCodes {
    private val adjectives = listOf(
        "amber", "bold", "brave", "bright", "calm", "clever", "cosmic", "crisp", "eager", "fancy",
        "fuzzy", "gentle", "golden", "happy", "jolly", "kind", "lively", "lucky", "mellow", "merry",
        "mighty", "misty", "noble", "plucky", "proud", "quick", "quiet", "rapid", "rosy", "shiny",
        "silver", "sleek", "snowy", "sunny", "swift", "tidy", "vivid", "warm", "wild", "witty",
    )
    private val nouns = listOf(
        "badger", "beacon", "bison", "canyon", "cedar", "comet", "coral", "crane", "delta", "ember",
        "falcon", "fjord", "forest", "glacier", "harbor", "heron", "island", "lagoon", "lynx", "maple",
        "meadow", "meteor", "moose", "nebula", "orbit", "otter", "panda", "pebble", "puffin", "quartz",
        "raven", "reef", "river", "sparrow", "summit", "tiger", "tundra", "valley", "walrus", "willow",
    )

    private val pattern = Regex("^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])$")
    private val random = SecureRandom()

    fun generate(): String {
        val number = 1000 + random.nextInt(9000)
        return "${adjectives[random.nextInt(adjectives.size)]}-${nouns[random.nextInt(nouns.size)]}-$number"
    }

    /** Mirrors the server's validation. Returns null when the code is not usable. */
    fun normalize(input: String): String? {
        val room = input.trim().lowercase().replace(Regex("\\s+"), "-")
        return room.takeIf { pattern.matches(it) }
    }
}
