# Другие API для быстрого игрового ведущего

Исследовано 15.11.2026 по официальным публичным страницам. Внешних запросов с ключом не делал; доступность моделей для аккаунта пользователя не проверена. Публикуемые tokens/s — показатели провайдера, а не наши замеры игрового ответа.

## Какие альтернативы стоит включить в короткий список?

### Takeaway
Из альтернатив прежде всего стоит испытать GPT-OSS 120B на Groq: он сочетает публичную высокую скорость вывода и строгий JSON Schema. DeepSeek Flash и Qwen Flash интересны как недорогие модели с выключаемым рассуждением, но их русские реплики и точность игровых планов нужно проверять на наших сценариях.

### Cited Findings
- **Groq — платформа инференса; модель — `openai/gpt-oss-120b`.** Она находится в Production Models. Официальная таблица указывает 500 токенов/с и $0.15 за 1M входных / $0.60 за 1M выходных токенов. Меньший `openai/gpt-oss-20b`: 1000 токенов/с и $0.075/$0.30. Контекст обоих 131072 токена. Это тариф именно Groq, не OpenAI API. — [Groq Models](https://console.groq.com/docs/models)
- Для GPT-OSS на Groq поддерживаются `reasoning_effort: low`, `medium`, `high`. `none` для GPT-OSS в перечне отсутствует; скрытие reasoning через `include_reasoning:false` не равно отключению вычисления. Для `qwen/qwen3.8-27b` на Groq `none` явно выключает reasoning. — [Groq Reasoning](https://console.groq.com/docs/reasoning)
- **DeepSeek: точный актуальный API ID `deepseek-flash`, версия DeepSeek-V4.1-Flash-1017.** Обе модели поддерживают OpenAI и Anthropic формат API; контекст 1M. Для Flash uncached input/output: off-peak $0.15/$0.60 за 1M, peak $0.30/$1.20. Peak: понедельник–пятница 01:00–04:00 и 06:00–10:00 UTC, кроме китайских праздников; остальное off-peak. Старый ID `deepseek-v4-flash` маршрутизируется на новую версию. — [DeepSeek Pricing](https://api-docs.deepseek.com/quick_start/pricing/)
- В DeepSeek можно явно задать `thinking:{type:"disabled"}` или `reasoning_effort:"none"`; по умолчанию thinking включён и effort high. — [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- **Alibaba Cloud Model Studio: `qwen3.8-flash`.** Официальная модельная страница обновлена 05.11.2026, заявляет поддержку OpenAI и Anthropic протоколов, 1M контекста, function calling, structured outputs и context caching. Для Singapore International uncached input/output $0.15/$0.47 за 1M; Hong Kong Global, Germany Global, Tokyo Global — $0.113/$0.382. Регион и scope принципиальны, тариф не един для всех аккаунтов. — [Qwen3.8 Flash](https://www.alibabacloud.com/help/en/model-studio/qwen3-8-flash)
- Более дешёвый `qwen3.7-flash`: Singapore International до 32K входных токенов $0.03/$0.13; Global Frankfurt $0.028/$0.11. Модельная страница обновлена 13.11.2026. — [Qwen3.7 Flash](https://www.alibabacloud.com/help/en/model-studio/qwen3-7-flash)

### Inferences
- Сначала сравнить Groq `openai/gpt-oss-120b` с основным кандидатом Google/OpenAI из соседнего исследования. Groq 20B оставить вариантом для коротких реплик при приемлемом качестве; меньшая модель не доказана как достаточно точный планировщик этой игры.
- Если нужен дешёвый второй кандидат, брать DeepSeek Flash с явным non-thinking или Qwen3.8 Flash с отключённым thinking. Не оставлять автоматический reasoning по умолчанию для каждой короткой реплики.
- API совместимость не означает, что достаточно поменять base URL: параметры schema/effort и поддерживаемое подмножество JSON нужно согласовать с адаптером Koog и проверить на реальном endpoint.

### Gaps
- Ни для одного из этих сочетаний не измерены русские NPC-диалоги, TTFT, p50/p95 полной задержки, доля допустимых игровых планов и ошибки правил.
- Не подтверждены доступность конкретных тарифов, региона, квоты и возможность оплаты для пользователя. Не обещать, что существующий ключ AI STAR даёт доступ к этим платформам.

## Как строго они держат структуру и игровые правила?

### Takeaway
Строгая схема надёжно ограничивает форму ответа, но не доказывает смысловую корректность. Проверки места героя, владения вещами, эффектов, талантов и исходов должны остаться в серверном движке.

### Cited Findings
- Groq `strict:true` использует constrained decoding и поддерживается `openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `qwen/qwen3.8-27b` и `meta-models/muse-spark-open`. По умолчанию strict false — best effort. Structured Outputs сейчас несовместимы со streaming и tool use. Документация прямо различает schema compliance и semantic accuracy. — [Groq Structured Outputs](https://console.groq.com/docs/structured-outputs)
- DeepSeek Chat Completions описывает лишь `response_format.type` = `text` или `json_object`, а не `json_schema`. JSON mode гарантирует JSON, но требует инструкцию JSON в prompt. Возможен ответ из пробелов даже с finish_reason stop; требуется проверка непустоты и строгий собственный decode. — [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- Alibaba различает JSON Object (валидный JSON без фиксированной структуры) и `json_schema` с `strict:true`. В актуальном списке schema-моделей указаны Qwen3.7/3.8 Flash, Plus, Max, но краткая сравнительная таблица этой же страницы всё ещё пишет «only selected qwen-plus models». Налицо внутреннее расхождение документации; список явно включает современные Flash, но поддержку конкретного региона и схемы надо проверить. — [Alibaba Structured Output](https://docs.modelstudio.console.alibabacloud.com/en/model-studio/qwen-structured-output)
- Qwen3.8 Flash назван hybrid thinking с включённым thinking по умолчанию; настройки используют нестандартный параметр `enable_thinking`. — [Alibaba Deep Thinking](https://docs.modelstudio.console.alibabacloud.com/en/model-studio/deep-thinking)

### Inferences
- Groq GPT-OSS 120B выглядит наиболее определённо документированным альтернативным исполнителем строгого игрового JSON. Однако соблюдение схемы не мешает предложить существующий факт в неподходящем месте или выдать NPC чужие знания.
- DeepSeek в нашем кейсе потребует больше собственной валидации формы; это уже отчасти делает game/server, но JSON mode нельзя рекламировать как строгую схему.
- Для сценического диалога можно отделить короткую NPC-реплику от планирования. Для игрового действия следует сначала проверить весь план и только затем описывать подтверждённое событие; предварительно озвученный непроверенный результат опасен.

### Gaps
- Нет официального предметного теста «соблюдение именно наших игровых правил на русском» для этих моделей.
- Формальная совместимость нашей union-схемы SceneEffect с schema subset всех платформ не проверена.

## Как правильно понимать заявления о скорости?

### Takeaway
Выбирать по tps без собственного полного замера нельзя. Для 1–2 предложений важнее очередь, prefill, reasoning и сеть; у посредника появляется ещё один слой маршрутизации.

### Cited Findings
- Groq отдельно определяет TTFT, полную серверную latency и output tokens/s. Пользовательская задержка включает сеть; формула в руководстве: TTFT + decoding + network round trip. Логи консоли показывают серверную часть, поэтому рекомендуются параллельные клиентские замеры и сравнение с `usage.total_time`. — [Groq Latency Guide](https://console.groq.com/docs/production-readiness/optimizing-latency)
- Cerebras на странице тарифов предупреждает о зависимости возможностей и производительности от доступности, конфигурации и модели. Табличные цены не отдались в текстовой выгрузке; не переношу старые тарифы или заявленные tps из памяти. — [Cerebras Pricing](https://www.cerebras.ai/pricing)

### Inferences
- Для нашей игры тестировать одинаковые 30–50 сцен на прямых endpoint, отдельно dialogue и scene plan. Фиксировать first token, полную latency, tokens, долю валидных схем, принятых движком планов и качественную оценку русских реплик. Это предлагаемый эксперимент, не уже выполненные замеры.
- Не сравнивать прямой Groq/Google/OpenAI с Sonnet через AI STAR как чистое сравнение моделей: часть разницы может быть у посредника.
- Обычные кнопки, перемещение, броски и применение подтверждённых эффектов должны оставаться детерминированными и не ждать модель.

### Gaps
- Не нашёл первичных публичных гарантий p95 ответа на русских NPC-репликах с нашей длиной контекста. Никаких обещаний «эта модель всегда отвечает за N секунд» делать нельзя.