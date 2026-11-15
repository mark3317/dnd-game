package ru.markn.dnd.adventure

/** Complete vertical slice. Combat values remain the prototype's, not final game balance. */
object ArchiveDemo {
    const val ID = "archive_demo"
    const val BANDAGE = "HEALING_BANDAGE"
    const val READY = "DEFEND_ARCHIVE"
    const val WON = "RAIDERS_DRIVEN_OFF"
    const val DEFEAT = "ARCHIVE_CAPTURED"
    const val ESCORT_READY = "ESCORT_COURIER"
    const val ESCAPED = "COURIER_ESCAPED"
    const val WINDOW_LOST = "COURIER_WINDOW_LOST"
    const val COURIER_SAFE = "COURIER_SAFE"
    private const val BRIEFED = "VILLAGE_BRIEFING"
    private const val DRAIN = "DRAIN_REPAIRED"
    private const val CLUE = "LOCK_PATTERN"
    internal const val TRACKS = "RAIDER_TRACKS"

    private fun factAction(id: String, text: String, fact: String, ability: Ability? = null,
        domains: Set<CheckDomain> = emptySet(), location: String = FloodedArchive.HALL,
        checkDescription: String = text) =
        ScenarioAction(id, text, setOf(location), plan = SceneActionPlan(checkDescription,
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
                    "Сначала защитите двор; после победы мы сможем спокойно заняться бумагами. Повязки в сумках — если кого заденут, не мешкайте. И не тратьте их на мебель!", "ARCHIVIST", "Агата",
                    conversationOverrides = mapOf(WINDOW_LOST to "Дорогу смыло, курьер не выедет. Остаётся спасти оригиналы в сундуке подвала. Чертёж поможет с замками, слив замедлит воду.")),
                YARD to Site("Двор архива", "Повозка Егора у ворот, каменные обломки закрывают прострел. " +
                    "Налётчики не собираются уходить без документов. Осмотрите подходы, к воротам тянутся свежие следы.",
                    "Если знаете номера из описи, прикройте мой выезд — я доставлю копии. Или отбейте архив и договоримся о маршруте после. " +
                        "Промедлим с выездом — дорогу смоет, и останется спасать оригиналы.", "COURIER", "Егор",
                    conversationOverrides = mapOf(WINDOW_LOST to "Дорогу смыло. Копии я теперь не доставлю. Отбейте архив и спасайте оригиналы, пока они ещё целы.")),
            ),
            facts = base.facts + mapOf(
                BRIEFED to FactDefinition("Агата объяснила ставки: грамоты защищают землю посёлка, курьер может заказать копии.", Requirements(location = HALL)),
                CLUE to FactDefinition("Чертёж замков изучен: открыть дверь и сундук теперь проще.", Requirements(location = HALL,
                    minimumDifficulty = 10, checkDomains = setOf(CheckDomain.DEVICES, CheckDomain.DOCUMENTS, CheckDomain.CRAFT))),
                DRAIN to FactDefinition("Слив восстановлен: спасать грамоты теперь проще.", Requirements(location = HALL,
                    minimumDifficulty = 10, checkDomains = setOf(CheckDomain.REPAIR, CheckDomain.CRAFT, CheckDomain.FORCE))),
                TRACKS to FactDefinition("Подходы налётчиков разведаны: герои знают, откуда ждать нападения, и готовы встретить врага прицельным ударом.",
                    Requirements(location = YARD, minimumDifficulty = 10, checkDomains = setOf(CheckDomain.OBSERVATION, CheckDomain.MILITARY),
                        forbiddenFacts = setOf(WON, ESCAPED))),
                READY to FactDefinition("Группа собирается во дворе. Налётчики требуют отдать документы. Начинается оборона архива.",
                    Requirements(location = HALL, forbiddenFacts = setOf(ESCORT_READY))),
                ESCORT_READY to FactDefinition("Егор принимает номера грамот и берётся за вожжи. Группа прикрывает выезд повозки.",
                    Requirements(location = YARD, facts = setOf(INDEX_READ), forbiddenFacts = setOf(READY, WON, ESCAPED))),
                ESCAPED to FactDefinition("Повозка Егора вышла со двора с номерами грамот. Доставка заверенных копий обеспечена."),
                WINDOW_LOST to FactDefinition("Дождь перекрыл выезд. Егор укрылся во дворе: доставка копий сорвалась, но оригиналы ещё можно спасти после победы."),
                WON to FactDefinition("Налётчики отступили. Вся группа возвращается в зал; проход к грамотам и курьер свободны."),
                DOOR_OPEN to base.facts.getValue(DOOR_OPEN).copy(requirements = Requirements(HALL, LOCKPICKS, setOf(WON), 10,
                    CLUE, 8, setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT, CheckDomain.FORCE)),
                    alternativeRequirements = listOf(Requirements(location = HALL, facts = setOf(WON), minimumDifficulty = 14,
                        easierWhen = CLUE, easierDifficulty = 12, checkDomains = setOf(CheckDomain.FORCE), allowedAbilities = setOf(Ability.STRENGTH), minimumFailureDamage = 2))),
                CHEST_OPEN to base.facts.getValue(CHEST_OPEN).copy(requirements = Requirements(CELLAR, LOCKPICKS, setOf(WON), 10,
                    CLUE, 8, setOf(CheckDomain.LOCKS, CheckDomain.DEVICES, CheckDomain.CRAFT, CheckDomain.FORCE)),
                    alternativeRequirements = listOf(Requirements(location = CELLAR, facts = setOf(WON), minimumDifficulty = 14,
                        easierWhen = CLUE, easierDifficulty = 12, checkDomains = setOf(CheckDomain.FORCE), allowedAbilities = setOf(Ability.STRENGTH), minimumFailureDamage = 2))),
                DOCUMENTS_SAVED to base.facts.getValue(DOCUMENTS_SAVED).copy(requirements = Requirements(CELLAR, WRAP,
                    setOf(WON, CHEST_OPEN), 10, DRAIN, 8, setOf(CheckDomain.BALANCE, CheckDomain.SEAMANSHIP))),
                COPIES_PROMISED to base.facts.getValue(COPIES_PROMISED).copy(requirements = Requirements(YARD,
                    facts = setOf(WON, INDEX_READ), minimumDifficulty = 10, checkDomains = setOf(CheckDomain.PERSUASION, CheckDomain.TRADE),
                    forbiddenFacts = setOf(WINDOW_LOST))),
            ),
            endings = mapOf(
                COURIER_SAFE to Outcome("Повозка на тракте · частичный успех", "Вы прикрыли выезд Егора. Он увозит номера грамот и обеспечит доставку заверенных копий. " +
                    "Оригиналы остались в архиве; спасти их вы не успели. Посёлок сохранит доказательства права на землю, хотя ему ещё предстоит дождаться копий. " +
                    "За поворотом смолкает стук колёс — ваш общий труд не пропадёт."),
                ORIGINALS_ENDING to Outcome("Бумаги над водой · успех", "Вы отбили архив и вынесли оригиналы в непромокаемой ткани. " +
                    "Наутро Агата предъявит грамоты совету: земля останется у посёлка. Агата бережно принимает спасённые бумаги, а в трактире уже спорят, " +
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
                factAction("SCOUT", "Разведать подходы к воротам", TRACKS, Ability.WISDOM,
                    setOf(CheckDomain.OBSERVATION, CheckDomain.MILITARY), YARD,
                    checkDescription = "Разведать подходы: при успехе каждый герой получит преимущество на первую атаку в обороне двора (2d20, лучший); с помощью не складывается"),
                factAction("DEFEND", "Всем занять оборону", READY),
                ScenarioAction("ESCORT", "Прикрыть выезд Егора", setOf(YARD), contactsNpc = true,
                    plan = SceneActionPlan("Прикрыть выезд курьера с номерами грамот", null, null,
                        listOf(SceneEffect.Fact(ESCORT_READY)), emptyList()),
                    detail = "Повозку нужно продвинуть A5→F5 за 5 основных действий соседних героев до конца раунда 6. " +
                        "Свободный выезд сразу завершит историю частичным успехом: копии обеспечены, оригиналы остаются. " +
                        "Если не успеть, тот же бой продолжится как оборона, путь копий закроется; после победы можно спасать оригиналы."),
                ScenarioAction("FORCE_DOOR", "Выбить дверь подвала силой", setOf(HALL), plan = SceneActionPlan(
                    "Выбить дверь без отмычек: сила против 14 (12 с чертежом); при провале — 2 урона",
                    ProposedCheck(Ability.STRENGTH, 14, setOf(CheckDomain.FORCE)), null,
                    listOf(SceneEffect.Fact(DOOR_OPEN)), listOf(SceneEffect.Damage(2)))),
                ScenarioAction("FORCE_CHEST", "Вскрыть сундук силой", setOf(CELLAR), plan = SceneActionPlan(
                    "Вскрыть сундук без отмычек: сила против 14 (12 с чертежом); при провале — 2 урона",
                    ProposedCheck(Ability.STRENGTH, 14, setOf(CheckDomain.FORCE)), null,
                    listOf(SceneEffect.Fact(CHEST_OPEN)), listOf(SceneEffect.Damage(2)))),
                ScenarioAction("SAVE_DOCUMENTS", "Спасти грамоты", setOf(CELLAR), plan = SceneActionPlan("Обернуть и вынести грамоты", ProposedCheck(Ability.DEXTERITY, 10,
                    setOf(CheckDomain.BALANCE, CheckDomain.SEAMANSHIP)), WRAP, listOf(SceneEffect.Fact(DOCUMENTS_SAVED)), listOf(SceneEffect.Damage(1)))),
                ScenarioAction("REQUEST_COPIES", "Договориться о копиях", setOf(YARD), contactsNpc = true, plan = SceneActionPlan("Изменить маршрут курьера",
                    ProposedCheck(Ability.CHARISMA, 10, setOf(CheckDomain.PERSUASION, CheckDomain.TRADE)), null, listOf(SceneEffect.Fact(COPIES_PROMISED)), emptyList())),
            ),
            encounter = EncounterDefinition(READY, WON, DEFEAT, HALL, "Оборона архива",
                "Ворота распахиваются от удара. Налётчики врываются во двор, обходя каменные обломки. За вашей спиной — архив и его документы. " +
                    "Вышибала поднимает дубину, стрелок вскидывает оружие.", YARD,
                variants = listOf(EncounterVariantDefinition("courier_escort", ESCORT_READY, "Прикрыть выезд курьера",
                    "Егор садится на облучок. Колёса вязнут в грязи: нужно расчистить путь и помочь повозке выехать. " +
                        "Вышибала врывается во двор, стрелок занимает позицию за обломками.",
                    ru.markn.dnd.combat.EscortObjectiveDefinition((0..5).map { ru.markn.dnd.combat.Tile(it, 4) }),
                    ESCAPED, COURIER_SAFE, WINDOW_LOST))),
        )
    }
}
