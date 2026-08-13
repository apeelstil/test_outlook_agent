# Outlook AI Agent

Мини-агент, который через Outlook читает непрочитанные письма, прогоняет их текст через
LLM (с доступом к нескольким инструментам — reminder'ы, текущая дата/время, поиск), отвечает
на письмо и отмечает его прочитанным. Разработан по `Тестовое-задание-ИИ-агенты.md` 

## Архитектура

Слои (пакеты под `com.testtask.outlookagent`):

- `mail` — `MailChannel` (интерфейс) + `MailProcessor` (оркестрация: fetch → agent → reply →
  markSeen). `mail.outlook` — `OutlookMailChannel` и `JacobOutlookComFacade` (реальный Outlook
  через JACOB), `MockMailChannel` — Outlook-free реализация для тестов и mock-demo.
- `agent` — `Agent`: цикл LLM ↔ tool-calls (до `agent.maxSteps` шагов), безопасная обработка
  ошибок LLM и инструментов.
- `tool` — `Tool` (интерфейс), `ToolRegistry`, конкретные инструменты (`CurrentDateTimeTool`,
  `AddReminderTool`, `FindItemsTool`).
- `llm` — `LlmClient` (интерфейс) и `HttpLlmClient` — OpenAI-совместимый chat-completions клиент
  поверх `HttpURLConnection`.
- `store` — `FileSeenStore` (idempotency), `FileReminderStore` (напоминания) — JSON-файлы на
  диске.
- `audit` — `FileAuditJournal` — append-only, hash-chained (SHA-256) текстовый журнал событий
  без ПДн; `NoOpAuditJournal` — заглушка для тестов.
- `config` — `AppConfig`/`ConfigLoader` (YAML) и `EnvSecretResolver` (секрет только из env).
- `app` — сборка приложения: `ApplicationFactory`/`ProductionLauncher` (production wiring),
  `PollingLoop` (периодический опрос), `MockDemoRunner` (Outlook/network-free демо), `Main`
  (единственная исполняемая точка входа).

## Flow одного письма

1. `MailProcessor.processUnread()` вызывает `MailChannel.fetchUnread()`.
2. Для каждого письма, если `msg.getId()` уже есть в `SeenStore` — пропускаем (idempotency по
   стабильному message id, а не по содержимому).
3. `Agent.run(msg.getBody())` — цикл с LLM: LLM либо зовёт инструмент (`tool_call`), либо
   возвращает финальный ответ. Каждый `tool_call` пишется в audit (`agent_tool_call`,
   только имя зарегистрированного инструмента, без аргументов).
4. `MailChannel.reply(msg, replyBody)` — ответ отправляется **раньше**, чем письмо помечается
   прочитанным.
5. `SeenStore.markSeen(msg.getId())` — только после успешной отправки ответа; в audit пишется
   `agent_mail_seen` с SHA-256-хэшем message id (не сам id и не содержимое письма).

Порядок `reply → markSeen` осознанно фиксирован: если процесс упадёt между шагами 4 и 5, письмо
будет обработано (ответ уже отправлен), но не помечено как seen — при следующем запуске агент
попытается ответить на него повторно. Это известное **crash-window**: между «ответ отправлен» и
«факт этого durable-зафиксирован» есть небольшой интервал без атомарности. Задваивание ответа в
этом окне возможно; полная атомарность потребовала бы транзакционного Outlook API, которого нет.

## Стек и сборка

Java 8, Maven, JUnit 4, SLF4J + Logback (структурные event-key логи, без ПДн), YAML
(`org.yaml.snakeyaml` + `jackson-databind` для парсинга), `net.sf.jacob-project:jacob:1.20`
(JACOB) — единственный способ доступа к Outlook, только через COM Object Model.

```bash
mvn clean test        # JACOB исключён из test classpath (surefire classpathDependencyExcludes) —
                       # зелено на машине без Outlook/JACOB native runtime
mvn clean package      # fat-jar через maven-shade-plugin, Main-Class = com.testtask.outlookagent.app.Main
```

## Запуск mock-demo

Не требует Outlook, JACOB native runtime или сети. Использует `MockMailChannel`, скриптованный
`LlmClient` и временную директорию для стораджей/audit:

```bash
java -jar target/outlook-agent-1.0-SNAPSHOT.jar mock-demo
```

## Production-запуск

```bash
java -jar target/outlook-agent-1.0-SNAPSHOT.jar path/to/config.yaml
# без аргумента — по умолчанию ищет ./config.yaml
```

Требует:

- Windows с установленным и **уже запущенным/залогиненным** Outlook (профиль из `mail.profile`);
- переменную окружения с API-ключом LLM, имя которой задано в `llm.apiKeyEnv`;
- `jacob-1.20-x64.dll` (или `-x86.dll` под 32-битную JVM) **отдельно** — рядом с `java.exe`
  или в `-Djava.library.path`. Файл **не входит** в репозиторий и не входит в fat-jar (см. ниже).

Live-проверка на реальном Outlook в этой сессии **не выполнялась** — статус:
`requires target environment verification`.

## YAML-конфиг

`config.example.yaml` — шаблон без секретных значений, безопасен для коммита. Локальный рабочий
`config.yaml` — в `.gitignore`, в Git не попадает.

Обязательные поля:

```yaml
llm:
  endpoint: "..."      # URL chat-completions endpoint, OpenAI-совместимый
  model: "..."
  apiKeyEnv: "..."     # имя переменной окружения с API-ключом (не сам ключ)
  timeoutMs: 15000

agent:
  maxSteps: 5          # ограничение на число tool-call шагов на одно письмо

store:
  path: "./data/reminders.json"   # seen.json и audit.log создаются рядом (resolveSibling)

mail:
  pollSeconds: 30
  profile: "..."       # имя почтового хранилища (top-level store) в Outlook
  folder: "..."        # имя папки внутри этого хранилища
```

### `llm.apiKeyEnv`

Сам API-ключ **никогда** не хранится в конфиге/коде/git — только имя переменной окружения.
`EnvSecretResolver` читает её значение в момент запуска; если переменная не задана или пуста —
`MissingSecretException` (production падает на старте с понятной ошибкой, а не с NPE в рантайме).

## Outlook / JACOB setup

- Доступ к Outlook — только через `com.jacob-project:jacob:1.20` (COM Object Model,
  `Outlook.Application` → `Namespace("MAPI")` → `Folders` → `Items`), не MAPI/EWS/Graph напрямую.
- JACOB загружает нативную DLL при инициализации класса — поэтому она **исключена** из test
  classpath (`maven.compiler` не при чём, это `surefire.classpathDependencyExcludes`), и `mvn
  test` остаётся зелёным на машине без Outlook.
- `JacobOutlookComFacade` не вызывает `Application.Quit` — не завершает уже запущенный у
  пользователя Outlook. Использует `ComThread.InitSTA()`/`Release()` — по документации JACOB
  MAPI требует STA-поток.

### Происхождение vendored JACOB 1.20 JAR

`vendor/maven-repo/net/sf/jacob-project/jacob/1.20/` — минимальный project-local Maven-репозиторий
(`file://` в `pom.xml`), чтобы зависимость резолвилась без `mvn install:install-file`. Подробная
провенанс-запись (источник релиза, SHA-256, лицензия LGPL v2.1) — в
`vendor/maven-repo/net/sf/jacob-project/jacob/1.20/PROVENANCE.md`. **Нативные DLL из официального
zip сознательно не извлечены и не закоммичены** — только `jacob-1.20.jar` и `LICENSE.TXT`.

### Внешний `jacob-1.20-x64.dll`

Не входит в репозиторий и не может быть частью fat-jar (нативный код, JVM bitness-specific).
Взять из официального релиза JACOB 1.20
(https://github.com/freemansoft/jacob-project/releases/tag/Root_B-1_20, тот же архив, из которого
провенанс-запись описывает извлечение JAR) и разместить в целевой Windows-среде рядом с `java.exe`
или указать `-Djava.library.path`.

## Reminder / seen / audit storage

Все три — простые файлы на диске, путь для `seen.json` и `audit.log` вычисляется от
`store.path` через `resolveSibling` (см. `Main`):

- `FileReminderStore` (`store.path`, по умолчанию `./data/reminders.json`) — JSON-список
  напоминаний (`text`, `dueIso`), пишет/читает `AddReminderTool`/`FindItemsTool`.
- `FileSeenStore` (`seen.json`) — множество обработанных message id, restart-safe.
- `FileAuditJournal` (`audit.log`) — append-only текстовый файл, каждая строка — событие с
  SHA-256 hash-chain к предыдущей строке (`verifyChainIntegrity()` детектирует любую правку
  задним числом).

## Idempotency

По стабильному `msg.getId()` (Outlook `EntryID`), не по хэшу содержимого письма — письмо с
одинаковым текстом, но другим id, обрабатывается заново; повторная доставка/повторный опрос
того же id — пропускается через `SeenStore.isSeen()`.

## Logging / audit

- SLF4J + Logback, структурные `event=...` ключи (`event=agent_tool_call tool=...`,
  `event=agent_mail_seen ref=<sha256>`, `event=mail_fetch_failed`, `event=mail_reply_failed`,
  `event=llm_failed`) — без тела письма, sender, subject, tool-аргументов или секретов.
- `FileAuditJournal` хранит только: `eventKey` (фиксированный литерал), `hashedMessageRef`
  (SHA-256 от message id), `toolName` (только имя уже найденного в `ToolRegistry` инструмента —
  не произвольная строка от LLM) и `timestamp`. Ошибки LLM (`Agent`) и ошибки tool-вызовов не
  прокидывают внутренние provider-детали или сырые исключения пользователю/в LLM-историю дальше
  необходимого (см. `AgentToolErrorHandlingTest`).

## Известное ограничение: crash-window между reply и seen-write

См. раздел «Flow одного письма» — между успешной отправкой ответа и durable-записью в
`SeenStore` нет атомарности. При падении процесса именно в этом окне возможен повторный ответ на
то же письмо при следующем запуске. Осознанный компромисс, не дефект.

## Статус реального Outlook

`requires target environment verification` — интеграция с реальным Outlook через JACOB не
проверялась вживую в этой сессии (нет целевой Windows/Outlook-среды с установленным
JACOB native runtime). Тесты через `MockMailChannel`/фейковый `OutlookComFacade` таким
подтверждением не являются.

## Как добавить новый Tool

1. Реализовать интерфейс `com.testtask.outlookagent.tool.Tool`: `getName()`,
   `execute(Map<String, Object> args)`, опционально `getDescription()` и
   `getParametersSchema()` (JSON Schema для function-calling у LLM).
2. Валидировать аргументы в начале `execute()` и бросать `IllegalArgumentException` со
   **статическим**, безопасным текстом (без эха произвольных значений аргументов — `Agent`
   прокидывает `e.getMessage()` дальше в LLM-историю как есть, см. `Agent.run`).
3. Зарегистрировать инструмент через `toolRegistry.register(new MyTool(...))` в
   `ApplicationFactory.create(...)`.
4. Написать TDD-тест по образцу `AddReminderToolTest`/`FindItemsToolTest` до реализации.

## Как я работал с ИИ

Работа велась через Claude Code, по этапам из `PLAN.md`, каждый — в отдельной сессии
(`/clear` между блоками, чтобы не накапливать контекст прошлых этапов). Для каждого этапа:

1. **Plan-first** — перед кодом фиксировался scope этапа и то, что явно вне scope.
2. **TDD RED → GREEN** — сначала падающий тест, описывающий контракт, затем минимальный
   production-код, делающий его зелёным; переход red → green — отдельными коммитами (см.
   `git log`, пары `test: ...` / `feat: ...`).
3. Перед использованием библиотечного API (JACOB, Jackson/SnakeYAML, maven-shade-plugin) —
   сверка с официальной документацией/провенансом, а не по памяти.
4. Небольшие, изолированные логические этапы — один этап = один срез функциональности, без
   забегания вперёд на будущие этапы.
5. Финальный этап — самопроверка качества (секреты/ПДн в логах, инъекции через tool-аргументы)
   перед документацией и packaging.
