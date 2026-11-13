package ru.markn.dnd.adventure

private fun attempt(description: String, ability: Ability, difficulty: Int, item: String?, effect: SceneEffect,
                    damage: Int = 0) = SceneActionPlan(description, ProposedCheck(ability, difficulty), item,
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
        "Удержаться при подъёме к фонарю", Ability.DEXTERITY, 10, null, SceneEffect.Move(LANTERN_ROOM), 2))
    val REPAIR = ScenarioAction("REPAIR", "Починить механизм", setOf(LANTERN_ROOM), plan = attempt(
        "Освободить шестерню с помощью инструментов", Ability.INTELLIGENCE, 12, TOOLS, SceneEffect.Fact(DRIVE_FREED)))
    val SIGNAL = ScenarioAction("SIGNAL", "Подать сигнал", plan = attempt(
        "Выбрать момент между полосами тумана и подать парому сигнал", Ability.WISDOM, 12, LANTERN, SceneEffect.Fact(FERRY_WARNED)))

    val definition = ScenarioDefinition(
        ID, "Погасший маяк", "Вернуть свет или предупредить паром другим способом.",
        "Речной маяк погас. В тумане слышен гудок парома — он идёт прямо к отмели. " +
            "У двери ждёт встревоженный хранитель. Наверх ведёт мокрая лестница. " +
            "Можно попытаться вернуть свет или подать сигнал с берега. Действуйте по очереди.",
        "Туманный берег", "Проверок до парома", 3, SHORE, listOf(LANTERN, ROPE, TOOLS),
        sites = mapOf(
            SHORE to Site("У подножия маяка", "С берега видна отмель. Наверх ведёт мокрая лестница. Фонарём можно предупредить паром.",
                "Хранитель говорит: «Шестерня заела, но лампа исправна. Почините привод или подайте три вспышки фонарём». Он готов помочь советом."),
            LANTERN_ROOM to Site("Фонарная комната", "Линза и лампа целы, привод заклинило. Инструменты помогут освободить шестерню. Через окно можно подать сигнал.",
                "Снизу доносится голос хранителя: «Освободите шестерню! Если не выйдет, подайте три вспышки фонарём»."),
        ),
        items = mapOf(LANTERN to "Походный фонарь", ROPE to "Верёвка", TOOLS to "Набор инструментов"),
        facts = mapOf(
            ROPE_SECURED to FactDefinition("Верёвка закреплена на маяке для всей группы. Минимальная сложность подъёма теперь 8.",
                Requirements(usingItem = ROPE), consumeItem = true),
            KEEPER_HELPING to FactDefinition("Хранитель помогает группе. Минимальная сложность починки и сигнала теперь 10.", Requirements(location = SHORE)),
            DRIVE_FREED to FactDefinition("Привод освобождён, маяк снова светит.",
                Requirements(LANTERN_ROOM, TOOLS, minimumDifficulty = 12, easierWhen = KEEPER_HELPING, easierDifficulty = 10), ending = LIGHTHOUSE_LIT),
            FERRY_WARNED to FactDefinition("Сигнал замечен капитаном парома.",
                Requirements(usingItem = LANTERN, minimumDifficulty = 12, easierWhen = KEEPER_HELPING, easierDifficulty = 10), ending = SIGNAL_GIVEN),
        ),
        routes = listOf(Route(SHORE, LANTERN_ROOM, Requirements(minimumDifficulty = 10, easierWhen = ROPE_SECURED, easierDifficulty = 8)), Route(LANTERN_ROOM, SHORE)),
        endings = mapOf(
            LIGHTHOUSE_LIT to Outcome("Свет над рекой", "Маяк снова горит. Паром обходит отмель и добирается до пристани."),
            SIGNAL_GIVEN to Outcome("Огонь в тумане", "Сигнал замечен: паром останавливается перед отмелью. Люди спасены, хотя маяк ещё предстоит починить."),
            FERRY_LOST to Outcome("Слишком поздно", "Из тумана доносится удар о мель. Предупредить паром вовремя не удалось.", false),
        ), timeoutEnding = FERRY_LOST,
        actions = listOf(LOOK, TALK, CLIMB, REPAIR, SIGNAL,
            ScenarioAction("DESCEND", "Спуститься", setOf(LANTERN_ROOM), plan = SceneActionPlan("Спуститься по лестнице", null, null, listOf(SceneEffect.Move(SHORE)), emptyList())),
            ScenarioAction("SECURE_ROPE", "Закрепить верёвку", plan = SceneActionPlan("Закрепить верёвку для группы", null, ROPE, listOf(SceneEffect.Fact(ROPE_SECURED)), emptyList())),
            ScenarioAction("ASK_HELP", "Попросить помощи", setOf(SHORE), plan = SceneActionPlan("Попросить совета хранителя", null, null, listOf(SceneEffect.Fact(KEEPER_HELPING)), emptyList())),
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
        "После ливня вода поднимается в подвале архива. В запертом сундуке лежат земельные грамоты посёлка. " +
            "Архивариус просит спасти бумаги. В зале есть опись документов, во дворе ждёт курьер из уездного хранилища. " +
            "Можно спуститься за оригиналами или найти способ получить заверенные копии. До затопления осталось четыре рискованные попытки.",
        "Вода прибывает", "Проверок до затопления", 4, HALL, listOf(LOCKPICKS, WRAP),
        sites = mapOf(
            HALL to Site("Зал архива", "Опись на столе содержит номера земельных грамот. Запертая дверь ведёт в подвал; открытый проход — во двор.",
                "Архивариус: «Оригиналы в сундуке подвала. По номерам из описи курьер сможет заказать копии. Отмычками можно открыть дверь и сундук»."),
            YARD to Site("Двор с курьером", "Курьер укрывается от дождя у повозки. Проход ведёт обратно в зал.",
                "Курьер: «Нужны номера грамот из описи. Если убедите меня изменить маршрут, заверенные копии привезут из уездного хранилища»."),
            CELLAR to Site("Затопленный подвал", "Вода по колено. На каменном постаменте запертый сундук; открытые бумаги нужно завернуть в непромокаемую ткань. Лестница ведёт в зал.",
                "Архивариус сверху напоминает: «Сначала откройте сундук, затем защитите бумаги от воды!»"),
        ),
        items = mapOf(LOCKPICKS to "Отмычки", WRAP to "Непромокаемая ткань"),
        facts = mapOf(
            INDEX_READ to FactDefinition("Номера грамот прочитаны в описи и известны группе.", Requirements(location = HALL)),
            DOOR_OPEN to FactDefinition("Замок двери открыт. Группа может спуститься в подвал.", Requirements(HALL, LOCKPICKS, minimumDifficulty = 10)),
            CHEST_OPEN to FactDefinition("Сундук открыт. Грамоты доступны, но пока не защищены от воды.", Requirements(CELLAR, LOCKPICKS, minimumDifficulty = 10)),
            DOCUMENTS_SAVED to FactDefinition("Грамоты обёрнуты непромокаемой тканью и вынесены из воды.",
                Requirements(CELLAR, WRAP, setOf(CHEST_OPEN), 10), consumeItem = true, ending = ORIGINALS_ENDING),
            COPIES_PROMISED to FactDefinition("Курьер принял номера грамот и обязался доставить заверенные копии.",
                Requirements(YARD, facts = setOf(INDEX_READ), minimumDifficulty = 12), ending = COPIES_ENDING),
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
            ScenarioAction("OPEN_DOOR", "Открыть дверь подвала", setOf(HALL), plan = attempt("Открыть замок двери отмычками", Ability.DEXTERITY, 10, LOCKPICKS, SceneEffect.Fact(DOOR_OPEN))),
            ScenarioAction("GO_CELLAR", "Спуститься в подвал", setOf(HALL), plan = SceneActionPlan("Спуститься через открытую дверь", null, null, listOf(SceneEffect.Move(CELLAR)), emptyList())),
            ScenarioAction("OPEN_CHEST", "Открыть сундук", setOf(CELLAR), plan = attempt("Открыть замок сундука отмычками", Ability.DEXTERITY, 10, LOCKPICKS, SceneEffect.Fact(CHEST_OPEN))),
            ScenarioAction("SAVE_DOCUMENTS", "Спасти грамоты", setOf(CELLAR), plan = attempt("Завернуть грамоты и вынести из воды", Ability.DEXTERITY, 10, WRAP, SceneEffect.Fact(DOCUMENTS_SAVED), 1)),
            ScenarioAction("REQUEST_COPIES", "Договориться о копиях", setOf(YARD), plan = attempt("Убедить курьера изменить маршрут доставки", Ability.CHARISMA, 12, null, SceneEffect.Fact(COPIES_PROMISED))),
        ),
    )
}

object Scenarios {
    val all: List<ScenarioDefinition> = listOf(Lighthouse.definition, FloodedArchive.definition)
    fun find(id: String): ScenarioDefinition? = all.firstOrNull { it.id == id }
}
