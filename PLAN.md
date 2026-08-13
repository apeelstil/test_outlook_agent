# PLAN.md — Mini-ассистент по образу «Коли»

Компактный план до кода, по `Тестовое-задание-ИИ-агенты.md` (источник истины). Не расширяет и
не сужает scope ТЗ.

## Цель и flow

```
Outlook (или Mock) unread mail
  → dedup check (SeenStore, ключ = EntryID/Message-ID)
  → LLM tool-loop (тело письма как запрос; tools: current_datetime/add_reminder/find_items; maxSteps)
  → reply(Msg, ответ) через MailChannel
  → durable seen-write (после успешного reply)
  → append-only audit (письмо + tool_call)
```

Ошибки LLM/COM — WARN + graceful skip, poll-loop не падает (§3.6 ТЗ).

## Архитектурные границы

Одна строка ответственности на компонент (имена рабочие, могут быть скорректированы при
объективной причине):

- **Application/PollLoop** (orchestration boundary) — читает конфиг, раз в `pollSeconds`
  гоняет dedup → Agent → reply → SeenStore.commit → audit по каждому письму.
- **MailChannel** (интерфейс `fetchUnread()`/`reply()`) — единственная граница почтового
  транспорта.
  - **OutlookMailChannel** — JACOB/COM; бизнес-логика отделена от native-вызовов маленьким
    внутренним COM-facade (тестируемая граница, см. Roadmap #14–15).
  - **MockMailChannel** — in-memory, для тестов/демо без Outlook.
- **Msg** — value-object: стабильный id (EntryID/Message-ID), отправитель, тема, тело, дата.
- **SeenStore** — файловое persistent-хранилище обработанных id, переживает рестарт процесса.
- **LlmClient** (интерфейс `chat(messages, tools)`) — HTTP-реализация (okhttp, инфраструктура)
  и **MockLlmClient** (детерминированный скрипт для тестов).
- **Agent/tool-loop** — держит `maxSteps`, диспетчеризует `tool_call` через `ToolRegistry`, не
  падает на невалидном/неизвестном `tool_call` — возвращает модели структурированную ошибку.
- **Tool** (контракт: имя, схема аргументов, `execute`) + **ToolRegistry** (имя → Tool).
- **current_datetime** (через инжектируемый `Clock`), **add_reminder(text, dueIso)**,
  **find_items(query)** — инструменты над **reminder JSON store**.
- **Audit journal** — append-only лог писем/tool_call; hash-chain желателен (не обязателен).
- **YAML configuration** — `llm.*`, `agent.maxSteps`, `store.path`, `mail.*`; секреты — только
  как имя env-переменной.

**Расширяемость:** `delete_reminder` на защите = новая реализация `Tool` + регистрация в
`ToolRegistry`, без изменений в Agent/tool-loop.

## Идемпотентность

Acceptance target (ТЗ): успешно обработанное письмо не обрабатывается повторно — ни на
следующем poll, ни после рестарта. Ключ — **EntryID/Message-ID**, не subject/body. Persistent
`SeenStore` обязателен.

Порядок: tool-loop → успешный `reply()` → немедленная durable-запись id в SeenStore. Отмечать
seen раньше reply запрещено (риск молчаливой потери письма при неудачной отправке).

Ограничение (не понижает требование до at-least-once): `SeenStore` (файл) и Outlook COM —
независимые системы без общей транзакции; единственное узкое окно — крах строго между успешным
`reply()` и следующей за ним записью на диск. Устранение этого окна требует распределённой
транзакции — вне scope ТЗ (§6). Во всех штатных сценариях, включая рестарт, — «ровно один
раз», проверяется тестами (Roadmap #9).

## TDD

RED (падающий тест) → минимальный production-код → GREEN → verification → atomic commit. Без
предварительного падающего теста — production-код запрещён. Red→green виден в git-истории.
`mvn test` проходит без Outlook; JACOB исключён из test classpath (surefire
`classpathDependencyExcludes`).

## TDD roadmap

| # | Шаг | RED → GREEN → verification/commit |
|---|-----|------------------------------------|
| 1 | Maven/bootstrap | pom.xml (Java 8, JUnit 4, fat-jar/maven-shade) + пустой smoke-тест → `mvn test` зелёный → commit |
| 2 | Config loading | тест парсинга YAML (`apiKeyEnv`, без секрета в файле) → загрузчик → `mvn test` → commit |
| 3 | Msg + MockMailChannel | тест `fetchUnread`/`reply` на in-memory → реализация → `mvn test` → commit |
| 4 | Tool abstraction/registry | тест регистрации + «tool не найден» → интерфейсы/реестр → `mvn test` → commit |
| 5 | current_datetime | тест с фиктивным `Clock` → реализация → `mvn test` → commit |
| 6 | reminder JSON store + add/find | тест записи/чтения/поиска → store + инструменты → `mvn test` → commit |
| 7 | MockLlmClient + tool-loop | тест `tool_call → tool result → final response` на скрипте → Agent с `maxSteps` → `mvn test` → commit (ключевой тест защиты, §9 ТЗ) |
| 8 | malformed/unknown tool_call + maxSteps | тест на неизвестный tool / malformed-аргументы / превышение `maxSteps` → устойчивый Agent, без падения → `mvn test` → commit |
| 9 | SeenStore + restart idempotency | тест: повтор в рамках процесса и после нового экземпляра store не переобрабатывает id → файловый SeenStore → `mvn test` → commit |
| 10 | mail orchestration + graceful fallback | тест LLM timeout/error → graceful WARN без stacktrace; тест ошибки MailChannel → graceful WARN, цикл продолжает → `mvn test` → commit |
| 11 | audit/logging/no PII | тест: запись в journal после обработки; лог без тел писем/ПДн → писатель журнала → `mvn test` → commit |
| 12 | golden mock scenarios | end-to-end тест по 4 golden-примерам §10 ТЗ на моках → сборка Application → `mvn test` → commit |
| 13 | HTTP LlmClient | тест сериализации запроса/разбора ответа через локальную заглушку транспорта (без сети в `mvn test`) → HTTP-клиент → commit |
| 14 | OutlookMailChannel через testable COM boundary | тест бизнес-логики (Msg из данных facade, reply-параметры, graceful WARN) через fake-facade → facade-интерфейс + OutlookMailChannel → `mvn test` без Outlook → commit |
| 15 | native JACOB infrastructure adapter | тонкий `Dispatch`/`Invoke`-адаптер; не покрывается `mvn test` (JACOB исключён из classpath, ТЗ §5) → статус `requires target environment verification` до живого прогона на защите (вся логика выше уже red→green — это не «исключение из TDD») |
| 16 | fat-jar | build-конфигурация maven-shade (не логика приложения, TDD неприменим по природе) → `mvn package` + ручной `java -jar` → commit |
| 17 | README/security review/final verification | самопроверка на секреты/ПДн/инъекции; README (build/run/test + «как я работал с ИИ»); прогон чек-листа §11 ТЗ → commit |

## Environment сейчас

Отсутствуют: организаторская Windows/Outlook-среда, test mailbox/credentials/письма, native
`jacob-1.20-x64.dll`, организаторский LLM endpoint/key (§1 ТЗ). Момент предоставления
неизвестен, не предполагается.

Следствие: основной JVM/test-контур (Roadmap #1–13) разрабатывается и проверяется без
Outlook, согласно ТЗ §5.

## JACOB strategy

- Maven dependency `net.sf.jacob-project:jacob:1.20` (JACOB 1.20) — часть стека, добавляется
  на шаге #1.
- Native DLL `jacob-1.20-x64.dll` — отсутствует; не скачивается самостоятельно, не коммитится,
  не подделывается заглушкой/фейковым бинарником.
- `MailChannel`/Agent не зависят от JACOB; сам COM/JACOB — на infrastructure edge за facade
  (Roadmap #14–15).
- Fake/mock-facade тестирует логику `OutlookMailChannel`, но не доказывает работу настоящего
  JACOB/JNI runtime.
- Native/live путь: статус `requires target environment verification` до живого прогона
  (future live JACOB verification, см. ниже).

## LLM strategy

`LlmClient` — граница; `MockLlmClient` — основной способ тестирования Agent/core (Roadmap #7).
HTTP-реализация (HTTP LLM client) — отдельная infrastructure-деталь (#13). Endpoint/model/
timeout — из конфига; имя env-переменной для ключа — из конфига; сам секрет — только из env
(никогда в YAML/коде/Git). Конкретный provider ядру не известен.

## Future live verification

Только на предоставленной Windows/Outlook/JACOB-среде; до выполнения — статус `requires
target environment verification`:

1. native DLL грузится, JACOB взаимодействует с COM, приложение подключается к Outlook (нужный
   profile/folder);
2. реальные unread messages получены со стабильным EntryID/Message-ID;
3. письмо доходит до agent/tool-loop, формируется ответ;
4. реальный `reply()` отправляется через Outlook;
5. письмо не переобрабатывается ни на следующем poll, ни после рестарта (restart idempotency);
6. COM-ошибка не валит poll-loop, следующее письмо всё же обрабатывается;
7. в логах нет тел писем/секретов/ПДн.

Главный сценарий: `real incoming email → Outlook/JACOB → assistant → real reply` — до
фактического прогона не документируется как проверенный.

## Verification status policy

- **Implemented** — код написан, не более.
- **Automatically tested** — автотест реально запускался, есть фактический результат.
- **Manually verified** — сценарий выполнен вручную (например `mvn package` + запуск jar).
- **Requires target environment verification** — код есть/запланирован, но проверка требует
  недоступной сейчас среды.

Fake/mock-верификация ≠ live JACOB-верификация. Слова «работает»/«проверено»/«интегрировано»
без фактически выполненной команды/сценария не используются.

## Security / audit

Секреты — только env; тела писем/ПДн не логировать (структурные event-keys, no PII в логах);
append-only audit journal, hash-chain желателен; security-review (секреты, ПДн, инъекции в
tool-аргументах) перед сдачей; валидация tool-аргументов в Agent.

## Out of scope (ТЗ §6)

Реальный Telegram, Confluence, календарь, DPAPI/cookies, RAG/эмбеддинги, БД сложнее
JSON-файла, мультипользовательность, OAuth/SSO, веб-панель, деплой сверх fat-jar. Один
инстанс, один ящик.

## Deliverables (ТЗ §8)

Git-репо с полной историей (атомарные коммиты, видимый red→green), `PLAN.md`, экспорт сессии
Claude Code, `README.md` (build/run/test + «Как я работал с ИИ»).

## Stretch (опционально, вне обязательной части, ТЗ §7)

retry/timeout/backoff для LLM; allow/deny-gate инструментов из конфига; память диалога по
отправителю/треду; OpenTelemetry-span; override конфига через env; reconnect при сбое COM;
расширенный аудит. Реализуется только после полного завершения обязательной части.
