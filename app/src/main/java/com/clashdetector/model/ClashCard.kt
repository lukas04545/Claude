package com.clashdetector.model

enum class CardRarity { COMMON, RARE, EPIC, LEGENDARY }

enum class CardType { TROOP, SPELL, BUILDING }

data class ClashCard(
    val id: String,
    val name: String,
    val elixirCost: Int,
    val rarity: CardRarity,
    val type: CardType,
    val imageAsset: String   // filename in assets/card_images/
)

/**
 * Registry of all Clash Royale cards with their elixir costs.
 * Used by the detector to look up card metadata after recognition.
 */
object CardRegistry {

    val ALL_CARDS: List<ClashCard> = listOf(
        // 1-elixir
        ClashCard("ice_spirit",     "Ice Spirit",       1, CardRarity.COMMON,    CardType.TROOP,    "ice_spirit.png"),
        ClashCard("skeletons",      "Skeletons",        1, CardRarity.COMMON,    CardType.TROOP,    "skeletons.png"),
        ClashCard("fire_spirit",    "Fire Spirit",      1, CardRarity.COMMON,    CardType.TROOP,    "fire_spirit.png"),
        // 2-elixir
        ClashCard("ice_goblin",     "Ice Goblin",       2, CardRarity.COMMON,    CardType.TROOP,    "ice_goblin.png"),
        ClashCard("bats",           "Bats",             2, CardRarity.COMMON,    CardType.TROOP,    "bats.png"),
        ClashCard("goblin",         "Goblin",           2, CardRarity.COMMON,    CardType.TROOP,    "goblin.png"),
        ClashCard("spear_goblins",  "Spear Goblins",    2, CardRarity.COMMON,    CardType.TROOP,    "spear_goblins.png"),
        ClashCard("log",            "The Log",          2, CardRarity.LEGENDARY, CardType.SPELL,    "log.png"),
        // 3-elixir
        ClashCard("knight",         "Knight",           3, CardRarity.COMMON,    CardType.TROOP,    "knight.png"),
        ClashCard("archers",        "Archers",          3, CardRarity.COMMON,    CardType.TROOP,    "archers.png"),
        ClashCard("minions",        "Minions",          3, CardRarity.COMMON,    CardType.TROOP,    "minions.png"),
        ClashCard("barbarians",     "Barbarians",       5, CardRarity.COMMON,    CardType.TROOP,    "barbarians.png"),
        ClashCard("zap",            "Zap",              2, CardRarity.COMMON,    CardType.SPELL,    "zap.png"),
        ClashCard("arrows",         "Arrows",           3, CardRarity.COMMON,    CardType.SPELL,    "arrows.png"),
        ClashCard("fireball",       "Fireball",         4, CardRarity.RARE,      CardType.SPELL,    "fireball.png"),
        ClashCard("rocket",         "Rocket",           6, CardRarity.RARE,      CardType.SPELL,    "rocket.png"),
        ClashCard("lightning",      "Lightning",        6, CardRarity.EPIC,      CardType.SPELL,    "lightning.png"),
        ClashCard("freeze",         "Freeze",           4, CardRarity.EPIC,      CardType.SPELL,    "freeze.png"),
        ClashCard("poison",         "Poison",           4, CardRarity.EPIC,      CardType.SPELL,    "poison.png"),
        ClashCard("earthquake",     "Earthquake",       3, CardRarity.RARE,      CardType.SPELL,    "earthquake.png"),
        // 4-elixir troops
        ClashCard("musketeer",      "Musketeer",        4, CardRarity.RARE,      CardType.TROOP,    "musketeer.png"),
        ClashCard("mini_pekka",     "Mini P.E.K.K.A",   4, CardRarity.RARE,      CardType.TROOP,    "mini_pekka.png"),
        ClashCard("valkyrie",       "Valkyrie",         4, CardRarity.RARE,      CardType.TROOP,    "valkyrie.png"),
        ClashCard("witch",          "Witch",            5, CardRarity.EPIC,      CardType.TROOP,    "witch.png"),
        ClashCard("wizard",         "Wizard",           5, CardRarity.RARE,      CardType.TROOP,    "wizard.png"),
        ClashCard("prince",         "Prince",           5, CardRarity.EPIC,      CardType.TROOP,    "prince.png"),
        ClashCard("dark_prince",    "Dark Prince",      4, CardRarity.EPIC,      CardType.TROOP,    "dark_prince.png"),
        ClashCard("mega_knight",    "Mega Knight",      7, CardRarity.LEGENDARY, CardType.TROOP,    "mega_knight.png"),
        ClashCard("pekka",          "P.E.K.K.A",        7, CardRarity.EPIC,      CardType.TROOP,    "pekka.png"),
        ClashCard("giant",          "Giant",            5, CardRarity.RARE,      CardType.TROOP,    "giant.png"),
        ClashCard("golem",          "Golem",            8, CardRarity.EPIC,      CardType.TROOP,    "golem.png"),
        ClashCard("lava_hound",     "Lava Hound",       7, CardRarity.LEGENDARY, CardType.TROOP,    "lava_hound.png"),
        ClashCard("balloon",        "Balloon",          5, CardRarity.EPIC,      CardType.TROOP,    "balloon.png"),
        ClashCard("hog_rider",      "Hog Rider",        4, CardRarity.RARE,      CardType.TROOP,    "hog_rider.png"),
        ClashCard("royal_giant",    "Royal Giant",      6, CardRarity.COMMON,    CardType.TROOP,    "royal_giant.png"),
        ClashCard("three_musketeers","3 Musketeers",    9, CardRarity.RARE,      CardType.TROOP,    "three_musketeers.png"),
        // Buildings
        ClashCard("cannon",         "Cannon",           3, CardRarity.COMMON,    CardType.BUILDING, "cannon.png"),
        ClashCard("tesla",          "Tesla",            4, CardRarity.COMMON,    CardType.BUILDING, "tesla.png"),
        ClashCard("inferno_tower",  "Inferno Tower",    5, CardRarity.RARE,      CardType.BUILDING, "inferno_tower.png"),
        ClashCard("bomb_tower",     "Bomb Tower",       4, CardRarity.RARE,      CardType.BUILDING, "bomb_tower.png"),
        ClashCard("goblin_hut",     "Goblin Hut",       5, CardRarity.RARE,      CardType.BUILDING, "goblin_hut.png"),
        ClashCard("elixir_collector","Elixir Collector",6, CardRarity.RARE,      CardType.BUILDING, "elixir_collector.png"),
        // Legendaries
        ClashCard("sparky",         "Sparky",           6, CardRarity.LEGENDARY, CardType.TROOP,    "sparky.png"),
        ClashCard("miner",          "Miner",            3, CardRarity.LEGENDARY, CardType.TROOP,    "miner.png"),
        ClashCard("inferno_dragon", "Inferno Dragon",   4, CardRarity.LEGENDARY, CardType.TROOP,    "inferno_dragon.png"),
        ClashCard("graveyard",      "Graveyard",        5, CardRarity.LEGENDARY, CardType.SPELL,    "graveyard.png"),
        ClashCard("electro_wizard", "Electro Wizard",   4, CardRarity.LEGENDARY, CardType.TROOP,    "electro_wizard.png"),
        ClashCard("bandit",         "Bandit",           3, CardRarity.LEGENDARY, CardType.TROOP,    "bandit.png"),
        ClashCard("ghost",          "Ghost",            3, CardRarity.LEGENDARY, CardType.TROOP,    "ghost.png"),
        ClashCard("ram_rider",      "Ram Rider",        5, CardRarity.LEGENDARY, CardType.TROOP,    "ram_rider.png"),
        ClashCard("royal_ghost",    "Royal Ghost",      3, CardRarity.LEGENDARY, CardType.TROOP,    "royal_ghost.png")
    )

    private val byId: Map<String, ClashCard> = ALL_CARDS.associateBy { it.id }

    fun findById(id: String): ClashCard? = byId[id]

    fun findByName(name: String): ClashCard? =
        ALL_CARDS.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
