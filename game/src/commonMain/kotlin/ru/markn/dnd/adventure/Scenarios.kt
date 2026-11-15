package ru.markn.dnd.adventure

private fun attempt(description: String, ability: Ability, difficulty: Int, item: String?, effect: SceneEffect,
                    damage: Int = 0, domains: Set<CheckDomain> = emptySet()) = SceneActionPlan(description, ProposedCheck(ability, difficulty, domains), item,
    listOf(effect), if (damage == 0) emptyList() else listOf(SceneEffect.Damage(damage)))

object Lighthouse {
    const val ID = "lighthouse"
    const val SHORE = "SHORE"
    const val LANTERN_ROOM = "LANTERN_ROOM"
    const val LANTERN = "LANTERN"
    const val ROPE = "ROPE"
    const val TOOLS = "TOOLS"
    const val ROPE_SECURED = "ROPE_SECURED"
    const val KEEPER_HELPING = "KEEPER_HELPING"
    const val DRIVE_FREED = "DRIVE_FREED"
    const val FERRY_WARNED = "FERRY_WARNED"
    const val LIGHTHOUSE_LIT = "LIGHTHOUSE_LIT"
    const val SIGNAL_GIVEN = "SIGNAL_GIVEN"
    const val FERRY_LOST = "FERRY_LOST"
    val LOOK = ScenarioAction("LOOK", "Осмотреться", narrative = NarrativeKind.INSPECTION)
    val TALK = ScenarioAction("TALK", "Поговорить с хранителем", narrative = NarrativeKind.CONVERSATION)
    val CLIMB = ScenarioAction("CLIMB", "Подняться к фонарю", setOf(SHORE), plan = attempt(
        "Удержаться при подъёме к фонарю", Ability.DEXTERITY, 10, null, SceneEffect.Move(LANTERN_ROOM), 2, setOf(CheckDomain.BALANCE)))
    val REPAIR = ScenarioAction("REPAIR", "Починить механизм", setOf(LANTERN_ROOM), plan = attempt(
        "Освободить шестерню с помощью инструментов", Ability.INTELLIGENCE, 12, TOOLS, SceneEffect.Fact(DRIVE_FREED), domains = setOf(CheckDomain.DEVICES, CheckDomain.REPAIR, CheckDomain.CRAFT)))
    val SIGNAL = ScenarioAction("SIGNAL", "Подать сигнал", plan = attempt(
        "Выбрать момент между полосами тумана и подать парому сигнал", Ability.WISDOM, 12, LANTERN, SceneEffect.Fact(FERRY_WARNED)))

    val definition = ScenarioDefinition(
        ID, "Погасший маяк", "Вернуть свет или предупредить паром другим способом.",
        "Туман стелется по тёмной реке, скрывая берег и острые камни отмели. Сегодня привычный луч маяка не разрезает мглу. " +
            "Из белой пелены доносится протяжный гудок парома — с каждым разом всё ближе к камням. " +
            "У двери башни ждёт встревоженный хранитель; за его спиной уходит вверх мокрая лестница, пахнет речной водой и остывшим маслом.",
        "Туманный берег", "Проверок до парома", 3, SHORE, listOf(LANTERN, ROPE, TOOLS),
        sites = mapOf(
            SHORE to Site("У подножия маяка", "С берега видна отмель. Наверх ведёт мокрая лестница. Фонарём можно предупредить паром.",
                "Шестерня заела, но лампа исправна. Почините привод или подайте три вспышки фонарём. Я подскажу, если понадобится.", npcId = "KEEPER", npcName = "Хранитель"),
            LANTERN_ROOM to Site("Фонарная комната", "Линза и лампа целы, привод заклинило. Инструменты помогут освободить шестерню. Через окно можно подать сигнал.",
                "Освободите шестерню! Если не выйдет, подайте три вспышки фонарём.", npcId = "KEEPER", npcName = "Хранитель"),
        ),
        items = mapOf(LANTERN to "Походный фонарь", ROPE to "Верёвка", TOOLS to "Набор инструментов"),
        facts = mapOf(
            ROPE_SECURED to FactDefinition("Верёвка крепко держится на маяке. Теперь на неё может опереться каждый из товарищей.",
                Requirements(usingItem = ROPE), consumeItem = true),
            KEEPER_HELPING to FactDefinition("Хранитель показывает, где заело привод, и указывает в сторону парома.", Requirements(location = SHORE)),
            DRIVE_FREED to FactDefinition("Привод освобождён, маяк снова светит.",
                Requirements(LANTERN_ROOM, TOOLS, minimumDifficulty = 12, easierWhen = KEEPER_HELPING, easierDifficulty = 10,
                    checkDomains = setOf(CheckDomain.DEVICES, CheckDomain.REPAIR, CheckDomain.CRAFT, CheckDomain.FORCE)), ending = LIGHTHOUSE_LIT),
            FERRY_WARNED to FactDefinition("Сигнал замечен капитаном парома.",
                Requirements(usingItem = LANTERN, minimumDifficulty = 12, easierWhen = KEEPER_HELPING, easierDifficulty = 10), ending = SIGNAL_GIVEN),
        ),
        routes = listOf(Route(SHORE, LANTERN_ROOM, Requirements(minimumDifficulty = 10, easierWhen = ROPE_SECURED, easierDifficulty = 8,
            checkDomains = setOf(CheckDomain.BALANCE, CheckDomain.FORCE, CheckDomain.ENDURANCE))), Route(LANTERN_ROOM, SHORE)),
        endings = mapOf(
            LIGHTHOUSE_LIT to Outcome("Свет над рекой", "Маяк снова горит. Паром обходит отмель и добирается до пристани."),
            SIGNAL_GIVEN to Outcome("Огонь в тумане", "Сигнал замечен: паром останавливается перед отмелью. Люди спасены, хотя маяк ещё предстоит починить."),
            FERRY_LOST to Outcome("Слишком поздно", "Из тумана доносится удар о мель. Предупредить паром вовремя не удалось.", false),
        ), timeoutEnding = FERRY_LOST,
        actions = listOf(LOOK, TALK, CLIMB, REPAIR, SIGNAL,
            ScenarioAction("DESCEND", "Спуститься", setOf(LANTERN_ROOM), plan = SceneActionPlan("Спуститься по лестнице", null, null, listOf(SceneEffect.Move(SHORE)), emptyList())),
            ScenarioAction("SECURE_ROPE", "Закрепить верёвку", plan = SceneActionPlan("Закрепить верёвку для группы", null, ROPE, listOf(SceneEffect.Fact(ROPE_SECURED)), emptyList())),
            ScenarioAction("ASK_HELP", "Попросить помощи", setOf(SHORE), plan = SceneActionPlan("Попросить совета хранителя", null, null, listOf(SceneEffect.Fact(KEEPER_HELPING)), emptyList()), contactsNpc = true),
        ),
    )
}

object FloodedArchive {
    const val ID = "flooded_archive"
    const val HALL = "ARCHIVE_HALL"
    const val YARD = "COURIER_YARD"
    const val CELLAR = "FLOODED_CELLAR"
    const val LOCKPICKS = "LOCKPICKS"
    const val WRAP = "WATERPROOF_WRAP"
    const val INDEX_READ = "INDEX_READ"
    const val DOOR_OPEN = "CELLAR_DOOR_OPEN"
    const val CHEST_OPEN = "CHEST_OPEN"
    const val DOCUMENTS_SAVED = "DOCUMENTS_SAVED"
    const val COPIES_PROMISED = "COPIES_PROMISED"
    const val ORIGINALS_ENDING = "ORIGINALS_SAVED"
    const val COPIES_ENDING = "COPIES_ORDERED"
    const val FLOODED_ENDING = "ARCHIVE_FLOODED"
    val definition = ScenarioDefinition(
        ID, "Затопленный архив", "Спасти земельные грамоты посёлка или обеспечить доставку заверенных копий.",
        "За окнами архива шумит дождь; по тёмной мостовой бегут ручьи, отражая свет одинокого фонаря. " +
            "Внизу, за запертой дверью, вода поднимается к сундуку с земельными грамотами посёлка. " +
            "Во дворе ждёт курьер из уездного хранилища; рядом с его повозкой дождь выбивает дробь по пустым бочкам. " +
            "В зале архивариус бережно разворачивает сухую опись и смотрит на мокрую полосу под дверью подвала.",
        "Вода прибывает", "Проверок до затопления", 4, HALL, listOf(LOCKPICKS, WRAP),
        sites = mapOf(
            HALL to Site("Зал архива", "Опись на столе содержит номера земельных грамот. Запертая дверь ведёт в подвал; открытый проход — во двор.",
                "Оригиналы в сундуке подвала. По номерам из описи курьер сможет заказать копии. Отмычками можно открыть дверь и сундук.", npcId = "ARCHIVIST", npcName = "Агата"),
            YARD to Site("Двор с курьером", "Курьер укрывается от дождя у повозки. Проход ведёт обратно в зал.",
                "Нужны номера грамот из описи. Если я изменю маршрут, заверенные копии привезут из уездного хранилища.", npcId = "COURIER", npcName = "Егор"),
            CELLAR to Site("Затопленный подвал", "Вода по колено. На каменном постаменте запертый сундук; открытые бумаги нужно завернуть в непромокаемую ткань. Лестница ведёт в зал.",
                "Сначала откройте сундук, затем защитите бумаги от воды!", npcId = "ARCHIVIST", npcName = "Агата"),
        ),
        items = mapOf(LOCKPICKS to "Отмычки", WRAP to "Непромокаемая ткань"),
        facts = mapOf(
            INDEX_READ to FactDefinition("Номера грамот прочитаны в описи и известны группе.", Requirements(location = HALL)),
            DOOR_OPEN to FactDefinition("Замок двери открыт. Группа может спуститься в подвал.", Requirements(HALL, LOCKPICKS, minimumDifficulty = 10,
                checkDomains = setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT))),
            CHEST_OPEN to FactDefinition("Сундук открыт. Грамоты доступны, но пока не защищены от воды.", Requirements(CELLAR, LOCKPICKS, minimumDifficulty = 10,
                checkDomains = setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT))),
            DOCUMENTS_SAVED to FactDefinition("Грамоты обёрнуты непромокаемой тканью и вынесены из воды.",
                Requirements(CELLAR, WRAP, setOf(CHEST_OPEN), 10), consumeItem = true, ending = ORIGINALS_ENDING),
            COPIES_PROMISED to FactDefinition("Курьер принял номера грамот и обязался доставить заверенные копии.",
                Requirements(YARD, facts = setOf(INDEX_READ), minimumDifficulty = 12,
                    checkDomains = setOf(CheckDomain.PERSUASION)), ending = COPIES_ENDING),
        ),
        routes = listOf(Route(HALL, YARD), Route(YARD, HALL), Route(HALL, CELLAR, Requirements(facts = setOf(DOOR_OPEN))), Route(CELLAR, HALL)),
        endings = mapOf(
            ORIGINALS_ENDING to Outcome("Бумаги над водой", "Оригиналы земельных грамот спасены. Посёлок сохранит документы на свою землю."),
            COPIES_ENDING to Outcome("Новый маршрут", "Курьер меняет маршрут: заверенные копии будут доставлены посёлку из уездного хранилища."),
            FLOODED_ENDING to Outcome("Размытые строки", "Подвал затоплен, оригиналы испорчены. Группа не успела обеспечить и доставку копий.", false),
        ), timeoutEnding = FLOODED_ENDING,
        actions = listOf(
            ScenarioAction("LOOK", "Осмотреться", narrative = NarrativeKind.INSPECTION),
            ScenarioAction("TALK", "Поговорить", narrative = NarrativeKind.CONVERSATION),
            ScenarioAction("READ_INDEX", "Прочитать опись", setOf(HALL), plan = SceneActionPlan("Прочитать номера грамот", null, null, listOf(SceneEffect.Fact(INDEX_READ)), emptyList())),
            ScenarioAction("GO_YARD", "Выйти во двор", setOf(HALL), plan = SceneActionPlan("Выйти к курьеру", null, null, listOf(SceneEffect.Move(YARD)), emptyList())),
            ScenarioAction("GO_HALL", "Вернуться в зал", setOf(YARD, CELLAR), plan = SceneActionPlan("Вернуться в зал архива", null, null, listOf(SceneEffect.Move(HALL)), emptyList())),
            ScenarioAction("OPEN_DOOR", "Открыть дверь подвала", setOf(HALL), plan = attempt("Открыть замок двери отмычками", Ability.DEXTERITY, 10, LOCKPICKS, SceneEffect.Fact(DOOR_OPEN), domains = setOf(CheckDomain.LOCKS))),
            ScenarioAction("GO_CELLAR", "Спуститься в подвал", setOf(HALL), plan = SceneActionPlan("Спуститься через открытую дверь", null, null, listOf(SceneEffect.Move(CELLAR)), emptyList())),
            ScenarioAction("OPEN_CHEST", "Открыть сундук", setOf(CELLAR), plan = attempt("Открыть замок сундука отмычками", Ability.DEXTERITY, 10, LOCKPICKS, SceneEffect.Fact(CHEST_OPEN), domains = setOf(CheckDomain.LOCKS))),
            ScenarioAction("SAVE_DOCUMENTS", "Спасти грамоты", setOf(CELLAR), plan = attempt("Завернуть грамоты и вынести из воды", Ability.DEXTERITY, 10, WRAP, SceneEffect.Fact(DOCUMENTS_SAVED), 1)),
            ScenarioAction("REQUEST_COPIES", "Договориться о копиях", setOf(YARD), plan = attempt("Убедить курьера изменить маршрут доставки", Ability.CHARISMA, 12, null, SceneEffect.Fact(COPIES_PROMISED), domains = setOf(CheckDomain.PERSUASION))),
        ),
    )
}

object Scenarios {
    /** Stories offered in game menus; older prototypes remain in the full catalog for regressions. */
    val available: List<ScenarioDefinition> = listOf(ArchiveDemo.definition)
    val all: List<ScenarioDefinition> = listOf(ArchiveDemo.definition, Lighthouse.definition, FloodedArchive.definition)
    fun find(id: String): ScenarioDefinition? = all.firstOrNull { it.id == id }
}
