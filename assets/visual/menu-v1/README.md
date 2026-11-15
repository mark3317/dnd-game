# Главное меню и библиотека аватаров

Обновление 15.11.2026: этот атлас и вырезанные из него портреты сохранены как
исходные референсы внешности. В игре их заменяет [standing-v2](../standing-v2/README.md):
рисованные прозрачные фигуры для сцен и обрезки тех же фигур для карточек.
Непрозрачный фон старого атласа не используется в разговорных сценах.

Подготовлено 15.11.2026 встроенным инструментом image_gen. Во время игры генерация не вызывается.

- `menu_campfire.png` — фон меню: костёр над речной долиной, тёплый янтарный свет, прохладные тени, свободная левая часть под меню.
- `avatar_atlas.png` — исходный атлас 4 × 4. Строки: человек, эльф, дварф, полурослик. Столбцы: воин, плут, маг-исследователь, бард. Все персонажи взрослые; мужские и женские образы чередуются.
- `avatars/<race>/<class>.png` — четыре пака по расам, по четыре классовых портрета в каждом; получены извлечением ячеек атласа. Для сборки копии названы `avatar_<race>_<class>.png` в `shared/src/commonMain/composeResources/drawable`. Фон скопирован туда как `menu_campfire.png`.
- `manifest.json` — стабильные идентификаторы портретов, пути к исходным/встроенным файлам и SHA-256.

Это первый набор внешностей для всех 16 сочетаний расы/класса. Предыстория и значения шести характеристик не ограничивают внешность. В редакторе автоматически предлагается сочетание расы/класса; можно выбрать любой другой портрет. Идентификатор сохраняется в профиле и передаётся в комнату. Старые профили без идентификатора получают подходящий встроенный портрет. В боевых вставках пока используются прежние классовые атласы поз; новые портреты не являются наборами боевых анимаций или эмоций.

## Промпты генерации

### Фон

Use case: stylized-concept. Asset type: fantasy cooperative RPG main menu background, wide landscape 1536x1024. Painterly premium game key art: an inviting campfire on a hillside overlooking a mysterious medieval river town and distant castle, dusk, rich cool teal shadows, warm amber firelight, atmospheric mist, hand-painted detailed environment. Place campfire and scenic focal points in the right half; left half dark quiet trees and mist with ample room for menu text overlay. No people close to camera, no text, no UI, no logos. Save the generated image for use in the local dnd-game project.

### Атлас

Use case: stylized-concept. Asset type: fantasy RPG character portrait atlas. Create one square 2048x2048 image containing EXACTLY 16 distinct bust portraits in a strict 4 columns x 4 rows equal square grid, no gutters, no frames, no text. Each cell self-contained centered head and shoulders, entire head inside its own cell. Painterly premium fantasy game style, warm amber highlights and cool teal shadows, dark teal plain backdrop in every cell. Rows indicate races: row1 human, row2 elf with pointed ears, row3 stocky dwarf, row4 adult halfling rounded face pointed ears. Columns indicate class: col1 warrior wearing plate/leather armor, col2 rogue wearing hood and leather, col3 scholar mage wearing robes and magical brooch, col4 bard wearing colorful elegant clothes and musical instrument strap. Alternate men and women, varied faces and hair, all adults, expressive memorable personalities. Equal-size portraits, suitable to crop into 16 independent square assets. No weapon covers face. No labels, no numbers, no watermarks.

## Шрифты

Интерфейс использует встроенные Cormorant Garamond и Nunito Sans с кириллицей. Исходные TTF без изменений взяты из официального репозитория Google Fonts:

- https://github.com/google/fonts/tree/main/ofl/cormorantgaramond
- https://github.com/google/fonts/tree/main/ofl/nunitosans

Шрифты находятся в `shared/src/commonMain/composeResources/font`, лицензии OFL входят в сборку в `files/licenses/cormorant_ofl.txt` и `files/licenses/nunito_ofl.txt`. Во время запуска скачивание шрифтов не требуется.
