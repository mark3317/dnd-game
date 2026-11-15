package ru.markn.dnd.adventure

/** Complete vertical slice. Combat values remain the prototype's, not final game balance. */
object ArchiveDemo {
    const val ID = "archive_demo"
    const val BANDAGE = "HEALING_BANDAGE"
    const val READY = "DEFEND_ARCHIVE"
    const val WON = "RAIDERS_DRIVEN_OFF"
    const val DEFEAT = "ARCHIVE_CAPTURED"
    private const val BRIEFED = "VILLAGE_BRIEFING"
    private const val DRAIN = "DRAIN_REPAIRED"
    private const val CLUE = "LOCK_PATTERN"
    private const val TRACKS = "RAIDER_TRACKS"

    private fun factAction(id: String, text: String, fact: String, ability: Ability? = null,
        domains: Set<CheckDomain> = emptySet(), location: String = FloodedArchive.HALL) =
        ScenarioAction(id, text, setOf(location), plan = SceneActionPlan(text,
            ability?.let { ProposedCheck(it, 10, domains) }, null, listOf(SceneEffect.Fact(fact)), emptyList()))

    val definition: ScenarioDefinition = with(FloodedArchive) {
        val base = definition
        base.copy(
            id = ArchiveDemo.ID, title = "Затопленный архив · полное демо",
            goal = "Защитить архив от налётчиков и спасти земельные грамоты или заказать заверенные копии.",
            introduction = "Над посёлком гремит гроза; в лужах дрожат последние огни, а за тяжёлыми окнами архива ещё теплится свет. " +
                "Ливень хлещет по крыше, вода просачивается под дверь подвала — туда, где в сундуке хранятся земельные грамоты посёлка. " +
                "Со двора доносятся окрик курьера и тяжёлые шаги: к воротам приближаются налётчики сборщика долгов. " +
                "В зале пахнет воском и мокрой бумагой; архивариус Агата стоит у раскрытой описи, прислушиваясь к каждому удару в ворота.",
            checkBudget = 8,
            startingItems = base.startingItems + BANDAGE,
            items = base.items + (BANDAGE to "Лечебная повязка (+4 здоровья)"),
            sites = base.sites + mapOf(
                HALL to Site("Зал архива", "Сухая опись на столе. На стене — чертёж замков, под окном — заклинивший слив. " +
                    "За дверью подвал, снаружи слышны шаги налётчиков. Ворота пока держатся, но тяжёлые шаги уже совсем близко.",
                    "Номера в описи — наш запасной путь. Оригиналы в сундуке подвала. Слив замедлит воду, чертёж поможет с замками. " +
                    "Сначала защитите двор; после победы мы сможем спокойно заняться бумагами. Повязки в сумках — если кого заденут, не мешкайте. И не тратьте их на мебель!", "ARCHIVIST", "Агата"),
                YARD to Site("Двор архива", "Повозка Егора у ворот, каменные обломки закрывают прострел. " +
                    "Налётчики не собираются уходить без документов. Осмотрите подходы, к воротам тянутся свежие следы.",
                    "Защитите двор, сообщите номера из описи и убедите меня изменить маршрут. Копии заверены уездной печатью, но приедут позже оригиналов.", "COURIER", "Егор"),
            ),
            facts = base.facts + mapOf(
                BRIEFED to FactDefinition("Агата объяснила ставки: грамоты защищают землю посёлка, курьер может заказать копии.", Requirements(location = HALL)),
                CLUE to FactDefinition("Чертёж замков изучен: открыть дверь и сундук теперь проще.", Requirements(location = HALL,
                    minimumDifficulty = 10, checkDomains = setOf(CheckDomain.DEVICES, CheckDomain.DOCUMENTS, CheckDomain.CRAFT))),
                DRAIN to FactDefinition("Слив восстановлен: спасать грамоты теперь проще.", Requirements(location = HALL,
                    minimumDifficulty = 10, checkDomains = setOf(CheckDomain.REPAIR, CheckDomain.CRAFT, CheckDomain.FORCE))),
                TRACKS to FactDefinition("По свежим следам и царапинам на воротах становится ясно, откуда придут налётчики.",
                    Requirements(location = YARD, minimumDifficulty = 10, checkDomains = setOf(CheckDomain.OBSERVATION, CheckDomain.MILITARY))),
                READY to FactDefinition("Группа собирается во дворе. Налётчики требуют отдать документы. Начинается оборона архива.", Requirements(location = HALL)),
                WON to FactDefinition("Налётчики отступили. Вся группа возвращается в зал; проход к грамотам и курьер свободны."),
                DOOR_OPEN to base.facts.getValue(DOOR_OPEN).copy(requirements = Requirements(HALL, LOCKPICKS, setOf(WON), 10,
                    CLUE, 8, setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT, CheckDomain.FORCE))),
                CHEST_OPEN to base.facts.getValue(CHEST_OPEN).copy(requirements = Requirements(CELLAR, LOCKPICKS, setOf(WON), 10,
                    CLUE, 8, setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT, CheckDomain.FORCE))),
                DOCUMENTS_SAVED to base.facts.getValue(DOCUMENTS_SAVED).copy(requirements = Requirements(CELLAR, WRAP,
                    setOf(WON, CHEST_OPEN), 10, DRAIN, 8, setOf(CheckDomain.BALANCE, CheckDomain.SEAMANSHIP))),
                COPIES_PROMISED to base.facts.getValue(COPIES_PROMISED).copy(requirements = Requirements(YARD,
                    facts = setOf(WON, INDEX_READ), minimumDifficulty = 10, checkDomains = setOf(CheckDomain.PERSUASION, CheckDomain.TRADE))),
            ),
            endings = mapOf(
                ORIGINALS_ENDING to Outcome("Бумаги над водой · успех", "Вы отбили архив и вынесли оригиналы в непромокаемой ткани. " +
                    "Наутро Агата предъявит грамоты совету: земля останется у посёлка. Егор везёт добрые вести, а в трактире уже спорят, " +
                    "кто храбрее — ваш отряд или сундук, выдержавший потоп. В трактире ещё долго будут вспоминать эту ночь."),
                COPIES_ENDING to Outcome("Печать на замену · частичный успех", "Архив защищён, Егор согласился изменить маршрут и доставить заверенные копии. " +
                    "Оригиналы остаются в затопленном подвале. Посёлок сохранит право на землю, но до доставки придётся ждать и восстанавливать архив. " +
                    "Агата благодарит вас: вы спасли главное, хотя не всё."),
                FLOODED_ENDING to Outcome("Размытые строки · поражение", "Вода скрыла сундук. Спасти оригиналы не удалось, а доставка копий не обеспечена. " +
                    "Посёлку предстоит тяжёлый спор за землю. Агата закрывает мокрую опись: эта попытка закончилась поражением. " +
                    "За окнами продолжает шуметь дождь.", false),
                DEFEAT to Outcome("Захваченный архив · поражение", "Отряд выведен из боя. Налётчики забирают опись и сундук, бросив раненых у ворот. " +
                    "После их ухода Агата перевязывает вас, но документы уже увезены: посёлок потерял доказательства права на землю. " +
                    "Герои живы, но сегодня посёлок остался без защиты.", false),
            ),
            actions = base.actions.filter { it.id !in setOf("SAVE_DOCUMENTS", "REQUEST_COPIES") } + listOf(
                factAction("BRIEFING", "Узнать, что поставлено на кон", BRIEFED),
                factAction("STUDY_LOCKS", "Изучить чертёж замков", CLUE, Ability.INTELLIGENCE, setOf(CheckDomain.DEVICES, CheckDomain.DOCUMENTS)),
                factAction("FIX_DRAIN", "Починить слив", DRAIN, Ability.INTELLIGENCE, setOf(CheckDomain.REPAIR, CheckDomain.CRAFT)),
                factAction("CLEAR_DRAIN", "Прочистить слив силой", DRAIN, Ability.STRENGTH, setOf(CheckDomain.FORCE)),
                factAction("SCOUT", "Осмотреть подходы к воротам", TRACKS, Ability.WISDOM, setOf(CheckDomain.OBSERVATION, CheckDomain.MILITARY), YARD),
                factAction("DEFEND", "Всем занять оборону", READY),
                ScenarioAction("SAVE_DOCUMENTS", "Спасти грамоты", setOf(CELLAR), plan = SceneActionPlan("Обернуть и вынести грамоты", ProposedCheck(Ability.DEXTERITY, 10,
                    setOf(CheckDomain.BALANCE, CheckDomain.SEAMANSHIP)), WRAP, listOf(SceneEffect.Fact(DOCUMENTS_SAVED)), listOf(SceneEffect.Damage(1)))),
                ScenarioAction("REQUEST_COPIES", "Договориться о копиях", setOf(YARD), contactsNpc = true, plan = SceneActionPlan("Изменить маршрут курьера",
                    ProposedCheck(Ability.CHARISMA, 10, setOf(CheckDomain.PERSUASION, CheckDomain.TRADE)), null, listOf(SceneEffect.Fact(COPIES_PROMISED)), emptyList())),
            ),
            encounter = EncounterDefinition(READY, WON, DEFEAT, HALL, "Оборона архива",
                "Ворота распахиваются от удара. Налётчики врываются во двор, обходя каменные обломки. За вашей спиной — архив и его документы. " +
                    "Вышибала поднимает дубину, стрелок вскидывает оружие.", YARD),
        )
    }
}
