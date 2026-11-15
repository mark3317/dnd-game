# Клиентская пауза общей партии · protocol16

Предложение реализации; runtime этого среза пока не менялся. Источник истины —
`RoomView.lifecycle`, `waitingPlayerIds`, `pausedAtMillis`, `closedByPlayerId`.
Дополнительно собственная потеря транспорта немедленно удерживает презентацию,
пока не получен новый authoritative snapshot.

## Два времени

1. Remembered по sessionId `PresentationPauseClock`: monotonic elapsed без пауз,
   pause-aware delay и `MonotonicFrameClock` для явных Animatable. Задержка хранит
   остаток, а не начинает интервал заново после resume. Event/queue/FX ключи не
   меняются. Resume выдаёт кадр с вычтенной накопленной паузой без скачка прогресса.
2. Серверное epoch-время для cue: во время PAUSED фиксируется `pausedAtMillis`;
   ACTIVE приходит со сдвинутыми timestamps. Нельзя дополнительно вычесть всю паузу
   из server now, иначе сдвиг применяется дважды. `RoomSpeechPlayback` один раз
   сдвигает свои локальные activeStartedAt/activeReadUntil на подтверждённый delta;
   не считает продолжение текущего player новым recovery/join.

При local disconnect authoritative duration пока неизвестна. Визуальное время
удерживается сразу. Новый generation имеет прежние transport guards, а речь
восстанавливается штатным recovery (TEXT при неподдержанном seek). Продолжение
чужого отключения на том же socket сохраняет текущий player/position.

## Реальные точки изменения

- `AdventureScreen`: параметр paused/pausedAt, эффективный now; не вызывать
  onFinished/combatEntrance advancement в pause; оставить очередь в композиции.
- `DiceRollOverlay`, `CombatEventPresentation`, `EscortEventPresentation`:
  pause-aware windup/read/FX delay; явные Animatable под pause frame clock.
- `RollPresentationHost`: enter/exit сделать явно управляемым alpha/state вместо
  неуправляемого AnimatedVisibility; advance только после завершённого exit.
- Adventure scene/cinema transitions: явно управляемый progress, иначе встроенный
  transition завершится за overlay. Camera/avatar breathing/map pulse — общий
  pausable animation helper. Отдельно короткий таймер имени StandingSceneAvatar.
- `RoomStorySpeech`: pause текущего player без stop/setQueue/seek, при pause не
  выполнять reducer.update/completed/failed/deadline, не отправлять новые ACK;
  фоновые источники можно подготовить с удержанием ACK. Resume через play() того же
  currentItem, hasPlayed/hasProgress/position сохраняются. Ошибка resume — TEXT.
- `RoomSpeechPlayback`: shift-local-time и testable pause guard; generation/key
  продолжает отсеивать старые callbacks. Допуск ENDING по drain сохраняется.
- `GameEffects`: transportPause сохраняет slot/claim, pause/play, сдвиг watchdog
  startedAt. Не использовать suspendOutput: он stop+suppresses history.
- `StoryMusic`: room-paused отдельно от lifecycle active; resume не reassign queue.

CompositionLocal **не** подменяет coroutine frame clock встроенных
AnimatedVisibility/AnimatedContent/rememberInfiniteTransition. Одной обёртки над
экраном недостаточно; указанные переходы требуют явного progress/helper.

## UI/соединение

Home resume entry использует только persisted connection identity, не снимок игры.
Settings adapter хранит private ticket без логов/URL-параметров. Точные поля и
requestLeave/detach API предоставляет владелец game/network. Resume не создаёт
новую комнату. Недоступный сервер не очищает ticket; CLOSED/COMPLETED/явный отказ
должны обрабатываться согласно контракту клиента.

PAUSED overlay виден всем участникам, имена берутся по waitingPlayerIds из roster.
Все игровые команды, restart/cancel/Skip заблокированы; своя reconnect кнопка видна
при обрыве. Confirmed leave объясняет завершение для всех и ждёт серверного ACK.
CLOSED показывает имя вышедшего и возврат в меню. Автоматический COMPLETED detach
не вызывает abort. Desktop X остаётся accidental disconnect.

## Проверки

- Pause посреди windup/read/exit: progress и queue неизменны, нет done/новых FX.
- Resume использует оставшийся интервал, advance/FX ровно один раз.
- TEXT deadline shift один раз; ENDING/overdue gate остаются корректны.
- AUDIO pause/play без повторного setQueue и без FINISHED в pause.
- Disconnect/generation отсекают старые Ended и ACK; unknown recovery не выдумывает seek.
- Settings backward default, private ticket roundtrip/clear, root resume entry.
- Все connected peers видят paused roster; невозможно Skip/command через overlay.

## Принятая граница восстановления

Оставшиеся подключёнными клиенты продолжают тот же player/currentItem/position
через pause/play. Сам вернувшийся участник, включая новый процесс, получает
канонический snapshot и восстанавливает текущий cue проверенным seek либо TEXT
на платформах без seek. Для него не обещается сохранение decoder после закрытия
процесса; защищённая generation семантика остаётся. История и механические события
не переигрываются. Это согласовано интегратором до реализации.

Первый compile-only game/shared JVM + desktop classes прошёл (7 секунд).
Текущий новый pause/test срез ещё ожидает общего прогона.
