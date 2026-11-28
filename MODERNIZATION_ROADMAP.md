# План модернизации архитектуры и сборки Easygram

Живой документ-дорожная карта для поэтапного приведения Easygram к современной модульной архитектуре на Kotlin и Kotlin-сборке при сохранении предсказуемого и безболезненного слияния с апстримом (`DrKLO/Telegram:master`).

> **Как пользоваться этим файлом:** после завершения каждого шага отмечайте соответствующие пункты `[x]`, фиксируйте коммит/дату в таблице прогресса внизу и переходите к следующей независимой задаче.

---

## 1. Текущий срез кодовой базы (база сравнения: `upstream/master` vs `easygram-dev`)

| Метрика | Значение | Комментарий |
|---|---|---|
| База апстрима (`merge-base`) | `21f7d109d` (Telegram 13.2.2) | Мерж апстрима выполняется вручную (`.github/workflows/upstream-release.yml` удалён) |
| Собственные файлы Easygram на **Kotlin** | **10 файлов** (~5 950 строк) | Крупные монолиты (`ChatExportController.kt` — 2 024 строки, `ChatExportActivity.kt` — 1 284 строки) |
| Собственные файлы Easygram на **Java** | **41 файл** (~11 640 строк) | Включая `OtherSettingsActivity.java` (1 060), `RoundVideoRecorderSettings.java` (875), `RoundVideoSettingsActivity.java` (830) |
| Модифицированные файлы апстрима | **133 файла** | В топ-16 Java-файлах апстрима сосредоточено **~5 350+ добавленных строк (~540 дифф-ханков)** |
| Сборка Gradle | Смешанная (Groovy + Kotlin DSL) | `buildSrc/` уже на **Kotlin DSL**, корневые и модульные `build.gradle` — на Groovy (`kotlin-gradle-plugin:2.1.0`, `kotlin-stdlib:2.1.20`) |

### Главные архитектурные проблемы сейчас
1. **Крупные монолиты внутри кода Easygram** — контроллеры на 1 000–2 000 строк смешивают модели данных, сеть/БД, рендеринг, работу с файлами и UI-ячейки.
2. **Высокая конфликтная поверхность с апстримом** — сотни строк логики Easygram (свайп-удаление, диктовка, архив сообщений, кастомная запись кружков, очистка хранилища, M3E-анализатор ритма, встроенный PhotoPicker) встроены прямо внутрь гигантских апстримных файлов (`ChatActivity.java`, `ChatActivityEnterView.java`, `MessagesStorage.java`, `InstantCameraView.java`, `CacheControlActivity.java`, `PhotoViewer.java`).
3. **Лишние конфликты при ручном мерже на `RoundVideoSettingsActivity.java`** (+735 / -224 строк) — апстримные изменения видеокружков в Easygram не нужны (используется собственная библиотека `video-message-recorder.aar`), поэтому этот файл защищён от конфликтов через `merge=ours`.
4. **Отсутствие физического разделения кода** — файлы Easygram перемешаны с тысячами апстримных классов внутри `TMessagesProj/src/main/java/org/telegram/...`.
5. **Глобальные настройки завязаны на UI-экран** — `OtherSettingsActivity.java` одновременно является и экраном настроек, и статическим хранилищем флагов для всего приложения.

---

## 2. Стратегия работы с Upstream (`DrKLO/Telegram:master`)

Мерж апстрима (`DrKLO/Telegram:master`) выполняется вручную. Массовое переписывание или переименование апстримных файлов всё равно создаёт десятки `modify/delete`-конфликтов и сильно замедляет обновление до новых версий Telegram, поэтому минимизация диффа остаётся главным приоритетом.

### Золотые правила сосуществования с апстримом

1. **Апстримные файлы Telegram остаются на Java и не дробятся.**
   - `ChatActivity.java`, `DialogsActivity.java`, `PhotoViewer.java`, `MessagesStorage.java`, `TLRPC.java` и остальные классы Telegram **не** переводятся на Kotlin и **не** рефакторятся структурно.
2. **Паттерн «Тонкие хуки + изолированный Kotlin» (Thin Hooks + Isolated Kotlin).**
   - Внутри апстримного Java-файла остаётся только минимальная точка входа (1–5 строк вызова делегата/контроллера) с коротким комментарием `// easygram: ...`.
   - Вся бизнес-логика, состояния, анимации, диалоги, SQL-запросы и вспомогательные View выносятся в собственные Kotlin-классы Easygram.
3. **Физическое разделение исходников:**
   - `TMessagesProj/src/main/java/org/telegram/...` — апстрим Telegram (Java).
   - `TMessagesProj/src/main/kotlin/org/telegram/easygram/...` — код Easygram (100% Kotlin).
   - Плагин `kotlin-android` автоматически подключает `src/main/kotlin` без каких-либо правок `TMessagesProj/build.gradle`.
4. **Неизменность FQN для компонентов из `AndroidManifest.xml`:**
   - Классы, на которые ссылается Android OS (`MusicPlayerWidgetProvider`, `MusicPlayerWidgetLargeProvider`, `NotificationMuteScheduleReceiver`, `DefaultIcon`, `BloomMonoIcon`, `BloomDarkIcon`), **обязаны сохранить свои текущие пакет и имя класса**, иначе после обновления у пользователей пропадут ярлыки приложения, виджеты и запланированные алармы. Их можно перевести на Kotlin на месте как тонкие 15-строчные обёртки, делегирующие работу в `org.telegram.easygram.*`.
5. **Локализация (`strings.xml`):**
   - Сейчас в `values/strings.xml` (+399/-15) и `values-ru/strings.xml` (+397/-9) много правок, создающих лишние диффы с апстримом.
   - **Важный нюанс:** `buildSrc/src/main/kotlin/org/telegram/plugin/TelegramBuildAppPlugin.kt` и `TelegramBuildPlugin.kt` генерируют таблицу строк `LocaleController` только из файлов с именем `strings.xml`.
   - Чтобы вынести строки Easygram в отдельные `values/easygram_strings.xml` и `values-ru/easygram_strings.xml` без поломки `LocaleController.getString(...)`, достаточно добавить `include("easygram_strings.xml")` в оба плагина в `buildSrc/` (2 строки, апстрим почти не трогает эти файлы).
6. **Видеокружки (`video-message-recorder.aar`) — игнорируются от апстрима:**
   - В Easygram используется собственная библиотека записи видеокружков; апстримная логика видеокружков вливаться не будет (максимум — точечные косметические правки UI при желании).
   - Весь стек видеокружков свободно проектируется и переводится на Kotlin в `org.telegram.easygram.roundvideo`.
   - Чтобы при ручном мерже апстрима Git не поднимал лишние конфликты на `RoundVideoSettingsActivity.java`:
     - В `.gitattributes` прописано правило `TMessagesProj/src/main/java/org/telegram/ui/RoundVideoSettingsActivity.java merge=ours`, а локально включён драйвер `git config merge.ours.driver true`.
     - **Важно про Git:** драйвер `merge=ours` срабатывает только когда файл существует в обеих ветках (при удалении файла возникает `modify/delete`-конфликт). Поэтому `RoundVideoSettingsActivity.java` остаётся как минимальная заглушка-обёртка (~10 строк, делегирующая/наследующая от Kotlin-реализации), а вся реальная логика и UI живут в `org.telegram.easygram.roundvideo`.
     - В `InstantCameraView.java` также выносим 461 строку AAR-сессии в `RoundVideoSessionController.kt`, чтобы сам апстримный файл оставался чистым и любые косметические правки из апстрима входили без боли.

---

## 3. Сборка на Kotlin (Gradle Kotlin DSL): анализ вариантов

### Что есть сейчас
- `buildSrc/build.gradle.kts` и все плагины/таски внутри `buildSrc/` **уже написаны на Kotlin**.
- `easygram_abis.gradle` (39 строк) и `easygram_version.gradle` (45 строк) — собственные Groovy-скрипты Easygram.
- В апстримных Gradle-файлах дифф Easygram невелик:
  - `build.gradle`: +2 / -1 строка
  - `settings.gradle`: 0 изменений
  - `TMessagesProj/build.gradle`: +29 / -2 строки
  - `TMessagesProj_App/build.gradle`: +58 / -23 строки
  - `TMessagesProj_App{Huawei,HockeyApp,Standalone}/build.gradle`: по +1 / -1 строке
- **Статистика апстрима:** за последние 12 релизов апстрим изменял `TMessagesProj/build.gradle` и/или `TMessagesProj_App/build.gradle` в **6 из 12 релизов** (50%+), а `gradle.properties` — в каждом релизе.

### Выбор стратегии для Gradle

| Критерий | Вариант A (Рекомендуемый): Merge-Safe Kotlin Build | Вариант B: Полный перевод всех `build.gradle` на `.gradle.kts` |
|---|---|---|
| Язык всей логики сборки Easygram | **100% Kotlin** (`buildSrc` convention plugins + `libs.versions.toml` для Easygram) | **100% Kotlin DSL** во всех модулях |
| Простота ручного мержа апстрима | **Высокая.** Апстримные `build.gradle` остаются Groovy-оболочками с 1 строкой подключения плагина Easygram — `git merge` проходит чисто | **Низкая.** Удаление `TMessagesProj/build.gradle` и `TMessagesProj_App/build.gradle` вызывает `modify/delete`-конфликт почти в каждом релизе Telegram |
| Новые Gradle-модули Easygram (например `:easygram-core`) | Сразу создаются на `build.gradle.kts` | На `build.gradle.kts` |
| Типобезопасность и IDE-поддержка | Полная для всей кастомной логики сборки Easygram | Полная для всего проекта (кроме сабмодулей) |

> **Выбран Вариант A** — вся логика сборки Easygram (`EasygramVersionPlugin`, `EasygramAbisPlugin`, `EasygramLibraryPlugin`, `EasygramAppPlugin`) перенесена в типобезопасные Kotlin-плагины в `buildSrc/src/main/kotlin/org/easygram/gradle/` + добавлен `gradle/libs.versions.toml`. Апстримные `TMessagesProj/build.gradle`, `TMessagesProj_App/build.gradle` и корневой `build.gradle` содержат по 1 строке `apply plugin: 'org.easygram.*'`.
> *Если позже захочется перейти к полному Варианту B, это будет тривиально, потому что вся логика уже находится в Kotlin-плагинах.*
>
> **Не трогать:** git-сабмодули `TMessagesProj_Modules/media/` и `TMessagesProj/lib/jlatexmath/`, а также неиспользуемые апстримные обёртки (`TMessagesProj_AppHuawei`, `TMessagesProj_AppHockeyApp`, `TMessagesProj_AppStandalone`).

---

## 4. Целевая структура пакетов Easygram

Весь код Easygram постепенно собирается в `TMessagesProj/src/main/kotlin/org/telegram/easygram/`:

```text
TMessagesProj/src/main/kotlin/org/telegram/easygram/
├── core/               # EasygramBuild, EasygramLocalFeatures, общие утилиты и расширения
├── settings/           # EasygramPreferences (единое хранилище настроек), OtherSettingsActivity, ячейки
├── export/             # Экспорт чатов: модели, загрузчики сообщений/медиа, HTML/ZIP, UI и календарь
├── muteschedule/       # Расписание мьюта: модели, планировщик алармов, контроллер, экраны и ячейки
├── asr/                # Локальная расшифровка и диктовка: модели, каталог, загрузчик, движок, аудио, UI
├── roundvideo/         # Кастомный рекордер кружков: настройки, санитайзер, Camera2, адаптер, сессия, UI
├── m3e/                # Material 3 Expressive: лоадеры, волны, beat/drum-детектор, превью-ячейки, экран настроек
├── widget/             # Музыкальный виджет: оркестратор, рендерер обложек/винила, палитра цветов
├── updater/            # OTA-обновления: клиент релизов, загрузчик и верификатор APK, UI-панель и шторка
├── archive/            # Архив удалённых/изменённых сообщений: SQLite-хранилище, diff, диалог истории
├── chat/               # Делегаты для ChatActivity/EnterView/PhotoViewer: свайп-удаление, диктовка, контент-фильтр, меню фото
├── storage/            # Анализ занятого места и очистка кэша/архива (делегаты для CacheControlActivity)
└── picker/             # Встроенный Android PhotoPicker и управление камерой (делегат для ChatAttachAlertPhotoLayout)
```

---

## 5. Поэтапный план работ (чеклист)

### Фаза 0 — Инфраструктура сборки на Kotlin и базовый фундамент
Цель: перевести всю сборку Easygram на Kotlin, синхронизировать версии Kotlin, подготовить `src/main/kotlin` и изоляцию строк.

- [x] **Выровнять версии Kotlin** в сборке (`kotlin-gradle-plugin:2.1.0`, `kotlin-stdlib:2.1.0` через `gradle/libs.versions.toml` и `buildSrc/build.gradle.kts`).
- [x] **Создать `gradle/libs.versions.toml`** (Version Catalog) для версий Kotlin, AGP и собственных зависимостей Easygram.
- [x] **Перенести `easygram_version.gradle` → `buildSrc/src/main/kotlin/org/easygram/gradle/EasygramVersionPlugin.kt`** (плагин `org.easygram.version`, `easygram_version.gradle` удалён, таска `easygramVersionCode` проверена).
- [x] **Перенести `easygram_abis.gradle` → `buildSrc/src/main/kotlin/org/easygram/gradle/EasygramAbisPlugin.kt`** (плагин `org.easygram.abis`, `easygram_abis.gradle` удалён, проверено на `arm64-v8a` в APK).
- [x] **Вынести Easygram-конфигурацию приложения → `buildSrc/src/main/kotlin/org/easygram/gradle/EasygramAppPlugin.kt`** (signing из `local.properties`, flavors `afat`/`bundleAfat`, именование `Easygram Beta/v*.apk` и `EasygramBundle *.aab`, зависимости, хук `easygram_strings.xml`).
- [x] **Вынести Easygram-конфигурацию библиотеки → `buildSrc/src/main/kotlin/org/easygram/gradle/EasygramLibraryPlugin.kt`** (`kotlin-android`, `sherpa-onnx` / `video-message-recorder`, `minSdk 24`, `TELEGRAM_API_ID`/`HASH` + debug fallback, хук `easygram_strings.xml`).
- [ ] **Перенести строки Easygram в `values/easygram_strings.xml` и `values-ru/easygram_strings.xml`** *(хуки в `EasygramLibraryPlugin` и `EasygramAppPlugin` уже подключены — осталось перенести сами ~400 ключей из `strings.xml`)*.
- [x] **Настроить игнорирование апстрима для видеокружков и убрать CI-автомерж:** создан `.gitattributes` с `merge=ours` для `RoundVideoSettingsActivity.java`, включён `git config merge.ours.driver true` локально, удалён `.github/workflows/upstream-release.yml`.
- [ ] **Начать наполнять `TMessagesProj/src/main/kotlin/org/telegram/easygram/`** (с первого переносимого модуля в Фазе 1).

---

### Фаза 1 — Декомпозиция крупных монолитов Easygram (наибольший эффект, нулевой риск для апстрима)

#### 1.1 Экспорт чатов (`ChatExportController.kt` — 2 024 строки, `ChatExportActivity.kt` — 1 284 строки)
Сейчас два файла содержат 3 308 строк и объединяют всё подряд: модели, TL-запросы, SQLite секретных чатов, скачивание медиа, HTML/CSS-генерацию, ZIP, MediaStore и кастомный календарь.

- [ ] Разбить `ChatExportController.kt` (2 024 → 6 файлов в `org.telegram.easygram.export`):
  - `ChatExportModels.kt` — `ExportConfig`, `ExportResult`, `MediaKind`, `MediaDescriptor`, `SenderInfo`, `Callback` (~130 строк)
  - `ChatExportMessageFetcher.kt` — `fetchAllMessages`, `fetchCloudMessagesForDialog`, `fetchEncryptedLocalMessages`, пагинация и `mergeDialogId` (~320 строк)
  - `ChatExportMediaLoader.kt` — `inspectMedia`, `isDownloadablePhotoSize`, `resolveAndDownloadMedia`, `findExistingMediaFile`, `downloadMediaFile` (~290 строк)
  - `ChatExportHtmlRenderer.kt` — `writeHtmlContent`, `writeMediaHtml`, `writeSpecialMediaHtml`, `writeReactionsHtml`, `formatEntitiesToHtml`, экранирование и резолв отправителей (~550 строк)
  - `ChatExportTemplates.kt` — `CSS_STYLES`, `THEME_TOGGLE_SCRIPT`, константы палитры (~150 строк)
  - `ChatExportStorage.kt` — `writeZipExport`, `writeHtmlFile`, `saveCopyToDownloads`, `cleanupOldExports`, генерация имён файлов (~260 строк)
  - `ChatExportController.kt` — тонкий оркестратор пайплайна и отмены (~250 строк)
- [ ] Разбить `ChatExportActivity.kt` (1 284 → 5 файлов в `org.telegram.easygram.export.ui`):
  - `ChatExportDateUtils.kt` — `toDayKey`, `dayKeyToCalendar`, форматирование периода, пресеты (~120 строк)
  - `ExportPresetChipsCell.kt` — `PresetChipsCell` + `Factory` (~140 строк)
  - `ExportCalendarMonthGridView.kt` — `CalendarMonthGridView` (отрисовка сетки и hit-testing) (~230 строк)
  - `ExportCalendarRangeCell.kt` — `CalendarRangeCell`, `CalendarState`, `CalendarListener` + `Factory` (~190 строк)
  - `ExportMonthYearPickerDialog.kt` — `showMonthYearPicker` (~100 строк)
  - `ChatExportActivity.kt` — только координация экрана, прогресса и шаринга (~450 строк)

#### 1.2 Глобальные настройки и экран «Прочие настройки» (`OtherSettingsActivity.java` — 1 060 строк)
Сейчас UI-фрагмент одновременно служит глобальным хранилищем SharedPreferences с десятками статических методов, вызываемых из десятков классов.

- [ ] **Выделить `EasygramPreferences.kt`** (`org.telegram.easygram.settings`) — перенести все ключи, кэши, `initDefaultsIfNeeded`, геттеры/сеттеры (M3E, галерея, скрытие подписей навигации, скрытие клавиатуры при скролле, вибрация загрузки, волны мини-плеера, порядок вкладок и меню фото) с `@JvmStatic` для Java-вызовов.
- [ ] Оставить в `OtherSettingsActivity` либо перенаправить все вызовы напрямую на `EasygramPreferences` (постепенно убрать зависимость всего приложения от UI-класса `OtherSettingsActivity`).
- [ ] Выделить `ReorderCheckCell.kt` и `ReorderTouchHelperCallback.kt` (~200 строк).
- [ ] Переписать `OtherSettingsActivity.java` → `OtherSettingsActivity.kt` (опционально перевести с ручного `ListAdapter` на современный `UniversalRecyclerView` + `UItem`, сократив объём кода примерно вдвое).

#### 1.3 Расписание отключения уведомлений (`NotificationMuteScheduleController.kt` — 998 строк + 2 экрана на 1 213 строк)
Уже на Kotlin, но контроллер и экраны перегружены вложенными классами.

- [ ] Разбить `NotificationMuteScheduleController.kt` (998 → 3 файла в `org.telegram.easygram.muteschedule`):
  - `MuteScheduleModels.kt` — `MuteInterval`, `DialogSchedule`, расчёт `getNextTransitionTimeMillis`, форматирование статусов, JSON-сериализация (~350 строк)
  - `MuteScheduleAlarmScheduler.kt` — `AlarmManager`, `setReceiverEnabled`, `checkAllAccounts`, multi-account coordination (~250 строк)
  - `NotificationMuteScheduleController.kt` — хранение в prefs, загрузка пиров, применение mute/unmute (~380 строк)
- [ ] Разбить UI (`org.telegram.easygram.muteschedule.ui`):
  - Вынести `WeekDaysSelectorCell.kt` (~140 строк) и `MuteScheduleTimePickerDialog.kt` (~150 строк) из `NotificationMuteScheduleActivity.kt` (675 → ~380 строк).
  - Вынести `DialogScheduleCell.kt` (~280 строк) из `NotificationMuteSchedulesActivity.kt` (538 → ~260 строк).
- [ ] Оставить `NotificationMuteScheduleReceiver.kt` на текущем FQN `org.telegram.messenger.NotificationMuteScheduleReceiver` (т. к. зарегистрирован в `AndroidManifest.xml`), делегируя внутрь `MuteScheduleAlarmScheduler`.

#### 1.4 Настройки кастомного рекордера кружков (`RoundVideoSettingsActivity.java` — 830 строк, `RoundVideoRecorderSettings.java` — 875 строк)
**Политика апстрима:** все апстримные изменения видеокружков игнорируются — у Easygram своя библиотека `video-message-recorder.aar`.

- [ ] **Перенести экран настроек в `org.telegram.easygram.roundvideo.ui.EasygramRoundVideoSettingsActivity.kt`**, а в `RoundVideoSettingsActivity.java` оставить минимальную заглушку (под защитой `merge=ours` в `.gitattributes`), чтобы не ловить `modify/delete`-конфликт при мерже апстрима.
- [ ] Разбить `RoundVideoRecorderSettings.java` (875 → 3 файла в `org.telegram.easygram.roundvideo`):
  - `RoundVideoPreferences.kt` — ключи, дефолты, чтение/запись и сборка `RecordingMode` (~320 строк)
  - `RoundVideoPreferenceSanitizer.kt` — `sanitizePreferences`, `nearestSupportedInt`, `sanitizeMode` (~240 строк)
  - Проверить и удалить мёртвый код или вынести в `RoundVideoQuickDialogs.kt` старые `AlertDialog`-меню (`showDialog`, `showVideo`, `showFrontCamera`, `showProcessing`, ~270 строк), если они ещё используются.
- [ ] Разбить `EasygramRoundVideoSettingsActivity.kt`:
  - `RoundVideoSettingsFormatter.kt` — форматирование значений (FPS, битрейт, диоптрии, зум, enum-лейблы, описания строк) (~260 строк)
  - `EasygramRoundVideoSettingsActivity.kt` — экран настроек (на `UniversalRecyclerView` + `UItem`, ~350 строк)

#### 1.5 Музыкальный виджет (`MusicWidgetUpdater.java` — 627 строк)
- [ ] Разбить и перевести на Kotlin в `org.telegram.easygram.widget`:
  - `MusicWidgetColors.kt` — динамическая палитра, Material You / night / HSL-расчёт (~100 строк)
  - `MusicWidgetRenderer.kt` — отрисовка обложки, виниловой пластинки, волны и иконки play/pause в `Bitmap` (~220 строк)
  - `MusicWidgetUpdater.kt` — цикл обновления, загрузка обложек, `RemoteViews` (~280 строк)
- [ ] Перевести `MusicPlayerWidgetProvider.java` и `MusicPlayerWidgetLargeProvider.java` на Kotlin на текущем FQN (`org.telegram.messenger.*`, т. к. прописаны в `AndroidManifest.xml`).

---

### Фаза 2 — Вынос логики Easygram из апстримных монолитов (снижение конфликтов при мерже)

Цель: сократить число дифф-ханков и добавленных строк в топ-файлах Telegram на **60–80%**, превратив их в тонкие хуки.

| Приоритет | Апстримный файл | Сейчас (строк / ханков) | Что вынести в Kotlin (`org.telegram.easygram.*`) |
|---|---|---|---|
| **P1** | `RoundVideoSettingsActivity.java` | `+735 / -224` (игнор апстрима) | Перенести реализацию в `roundvideo/ui/EasygramRoundVideoSettingsActivity.kt` (см. Фазу 1.4), файл оставить заглушкой с `merge=ours` в `.gitattributes` |
| **P1** | `ChatActivity.java` (49k строк) | `+668 / -38` (52 ханка) | `chat/SwipeDeleteController.kt` (жест, пружинные анимации кольца/иконки, удаление для всех), `chat/ExternalMediaSender.kt` (`didSelectEmbeddedMedia`, `didSelectExternalMedia`, `sendExternalMediaNow`), `archive/MessageHistoryDialog.kt` (`showMessageHistoryDialog`), `chat/ChatHeaderSwapHelper.kt` (`updateCallSearchSwap`) |
| **P1** | `ChatActivityEnterView.java` (16.4k) | `+513 / -78` (62 ханка) | `asr/DictationInputController.kt` (вся логика удержания/нажатия, превью диктовки, вставка текста в `messageEditText`, анимация кольца записи) |
| **P1** | `CacheControlActivity.java` (3.7k) | `+501 / -107` (57 ханков) | `storage/PackageStorageUsage.kt` (рекурсивный подсчёт по категориям), `storage/EasygramStorageCleaner.kt` (`cleanupFolders`, `clearMessageArchive`, форматирование предупреждения об архиве) |
| **P1** | `InstantCameraView.java` (4.4k) | `+461 / -3` (19 ханков) | `roundvideo/RoundVideoSessionController.kt` (`showAarCamera`, `sendAarCamera`, `finishAarRecording`, `createAarVideoInfo`, `cancelAarCamera`, таймер лимита) |
| **P1** | `MessagesStorage.java` (19.6k) | `+359 / -2` (11 ханков) | `archive/MessageArchiveStorage.kt` (таблица архива, `getMessageVersions`, `markLocallyDeletedMessages`, `archiveDownloadedMedia`, `clearMessageArchive`, `appendArchivedDeletedMessages`, `restoreArchivedMedia`) |
| **P2** | `ChatAttachAlertPhotoLayout.java` (5.3k) | `+371 / -30` (51 ханк) | `picker/EmbeddedPhotoPickerController.kt` (сессия Android EmbeddedPhotoPicker, lifecycle оффскрин-камеры) |
| **P2** | `VideoPlayer.java` (2.4k) | `+345 / -66` (21 ханк) | `m3e/M3eBeatAudioAnalyzer.kt` (FFT-анализ PCM, `DrumOnsetDetector`, расчёт полос частот). Фолбэк/ретраи ошибок ExoPlayer оставить хирургически или вынести в `core/VideoPlayerErrorPolicy.kt` |
| **P2** | `PhotoViewer.java` (24.4k) | `+437 / -74` (90 ханков) | `chat/PhotoMenuCustomizer.kt` (`updatePhotoMenuOrderAndVisibility`). Правки анимаций/кропа оставить минимальными |
| **P2** | `MainTabsActivity.java` (1.4k) | `+100 / -69` (34 ханка) | `settings/MainTabsConfigHelper.kt` (расчёт видимости, порядка и дефолтной вкладки) |
| **P3** | `SeekBarWaveform.java`, `RadialProgress2.java`, `FragmentContextView.java`, `TranscribeButton.java`, `ChatMessageCell.java` | `+150..290` на файл | По возможности вынести чистую геометрию/рендеринг M3E в `m3e/*`, оставив короткие ветки `if (M3ExpressiveLoader.enabled())` |
| **Не дробить** | `CropView.java` (1.6k) | `+411 / -177` (79 ханков) | Правки тесно вплетены в математику матрицы/жестов кропа апстрима — вынос создаст лишнюю связанность и только усложнит мерж. Оставить как есть |

Чеклист Фазы 2:
- [ ] Вынести `MessageArchiveStorage.kt` из `MessagesStorage.java` *(самый чистый и изолированный кандидат — всего 11 ханков!)*
- [ ] Вынести `M3eBeatAudioAnalyzer.kt` из `VideoPlayer.java`
- [ ] Вынести `RoundVideoSessionController.kt` из `InstantCameraView.java`
- [ ] Вынести `PackageStorageUsage.kt` и `EasygramStorageCleaner.kt` из `CacheControlActivity.java`
- [ ] Вынести `DictationInputController.kt` из `ChatActivityEnterView.java`
- [ ] Вынести делегаты (`SwipeDeleteController`, `ExternalMediaSender`, `MessageHistoryDialog`, `ChatHeaderSwapHelper`) из `ChatActivity.java`
- [ ] Вынести `EmbeddedPhotoPickerController.kt` из `ChatAttachAlertPhotoLayout.java`
- [ ] Вынести `PhotoMenuCustomizer.kt` из `PhotoViewer.java` и `MainTabsConfigHelper.kt` из `MainTabsActivity.java`

---

### Фаза 3 — Миграция оставшихся подсистем Easygram с Java на Kotlin

Все эти файлы принадлежат исключительно Easygram, уже неплохо разбиты по смыслу и могут переводиться по одному домену за раз с одновременным переносом в `TMessagesProj/src/main/kotlin/org/telegram/easygram/`.

#### 3.1 ASR / Локальная расшифровка и ИИ (12 Java-файлов, 3 404 строки → `org.telegram.easygram.asr`)
- [ ] Модели и конфиг: `EasygramAsrModels.java` (167), `EasygramAsrCatalog.java` (77), `EasygramAsrSettings.java` (133) → Kotlin `data class` / `object`
- [ ] Аудио и движок: `EasygramAsrAudio.java` (301), `EasygramAsrEngine.java` (420), `EasygramDictation.java` (206) → Kotlin
- [ ] Менеджер моделей (`EasygramAsrModelManager.java`, 546 строк) → разбить на `AsrModelDownloader.kt` (HTTP, докачка, SHA-256) + `EasygramAsrModelManager.kt` (состояния, очередь, слушатели)
- [ ] Транскрибер (`EasygramAsrTranscriber.java`, 373 строки) → Kotlin + опционально вынести `AsrLanguageDetector.kt` (эвристика скриптов и кэш языков диалогов)
- [ ] UI ASR:
  - `EasygramAsrModelCell.java` (494 строки) → разбить на `AsrDownloadButton.kt` (~235 строк) + `EasygramAsrModelCell.kt` (~240 строк)
  - `EasygramAsrSettingsActivity.java` (453 строки) → Kotlin (опционально перевести на `UniversalRecyclerView`)
  - `EasygramLocalAiActivity.java` (266 строк) → Kotlin
  - `EasygramTranscriptionReveal.java` (360 строк) → Kotlin

#### 3.2 Material 3 Expressive и аудиоволны (12 Java-файлов, 2 372 строки → `org.telegram.easygram.m3e`)
- [ ] Ядро рендеринга: `M3ExpressiveLoader.java` (302), `M3ExpressiveTrack.java` (335), `M3PlayPauseIcon.java` (195), `M3PlayPauseDrawable.java` (105) → Kotlin
- [ ] Анализ аудио: `AudioWaveformGenerator.java` (291), `MusicBeatTracker.java` (187), `DrumOnsetDetector.java` (158) → Kotlin
- [ ] Настройки и превью-ячейки: `M3ExpressiveSettingsActivity.java` (285), `EasygramM3eOverviewCell.java` (128), `EasygramLoaderPreviewCell.java` (115), `EasygramWavePreviewCell.java` (118), `EasygramMiniWavePreviewCell.java` (153) → Kotlin

#### 3.3 Поддержка кастомного рекордера кружков (3 Java-файла, 1 127 строк → `org.telegram.easygram.roundvideo`)
- [ ] `VideoMessageSegmentMerger.java` (144) → Kotlin
- [ ] `RoundVideoCameraSupport.java` (460) → Kotlin
- [ ] `VideoMessageRecorderAdapter.java` (523) → Kotlin (вынести запись телеметрии CSV в `RoundVideoTelemetryWriter.kt`)

#### 3.4 OTA-обновления (3 Java-файла, 862 строки → `org.telegram.easygram.updater`)
- [ ] `EasygramUpdater.java` (511) → разбить на `UpdateFeedClient.kt` (GitHub/JSON фид, парсинг релизов), `UpdateApkInstaller.kt` (загрузка, SHA-256, проверка подписи APK, установка) и `EasygramUpdater.kt` (координация и UI-состояние)
- [ ] `UpdateLayout.java` (240) → переименовать в `EasygramUpdateLayout.kt` (защита от возможного совпадения имени с апстримом)
- [ ] `EasygramUpdateSheet.java` (111) → Kotlin

#### 3.5 Мелкие утилиты и оставшиеся классы (5 Java-файлов, 224 строки)
- [ ] `MessageHistoryDiff.java` (152) → `org.telegram.easygram.archive.MessageHistoryDiff.kt`
- [ ] `EasygramBuild.java` (28) → `org.telegram.easygram.core.EasygramBuild.kt`
- [ ] Перенести существующие Kotlin-файлы `EasygramContentFilter.kt` (255) и `EasygramLocalFeatures.kt` (32) в `TMessagesProj/src/main/kotlin/org/telegram/easygram/`
- [ ] `DefaultIcon.java`, `BloomMonoIcon.java`, `BloomDarkIcon.java` (по 15 строк) — оставить на текущем FQN `org.telegram.messenger.*` (manifest `activity-alias`), перевод в Kotlin опционален и не обязателен

---

### Фаза 4 — Юнит-тесты и контроль качества

После выноса чистой логики из монолитов появляется возможность покрывать её быстрыми JVM-тестами (без сборки C++ и без эмулятора):

- [ ] Настроить и проверить запуск JVM unit-тестов (`./gradlew :TMessagesProj:testDebugUnitTest`).
- [ ] Покрыть тестами чистую логику Easygram:
  - `MuteScheduleModels` (переходы через полночь, DST, выходные/будни, сериализация JSON)
  - `ChatExportHtmlRenderer` / `ChatExportDateUtils` (экранирование HTML, небезопасные схемы URL, вложенные entities, високосные годы и 29–31 числа)
  - `MessageHistoryDiff` (сравнение версий текста)
  - `EasygramContentFilter` (фильтры рекламы/контента)
  - `UpdateFeedClient` (парсинг тегов и сравнение `versionCode`)

---

## 6. Правила выполнения каждого шага (Definition of Done)

Перед тем как поставить `[x]` на любой пункт плана:
1. **Удалить старый `.java` файл**, если класс был перенесён/переписан на Kotlin (проверить `git status`, что нет дублей).
2. **Проверить Java-совместимость:** на методах/константах, вызываемых из апстримных `.java`-файлов, стоят `@JvmStatic`, `@JvmField` или `@JvmOverloads`.
3. **Проверить дифф апстримных файлов:** убедиться, что количество изменённых строк в апстримных `.java` уменьшилось, а не выросло.
4. **Собрать проект реальным Gradle:**
   ```bash
   ./gradlew :TMessagesProj_App:assembleAfatDebug
   ```
5. **Обновить таблицу прогресса ниже.**

---

## 7. Сводный трекер прогресса

| Фаза | Область | Статус | Прогресс | Коммит / Заметки |
|---|---|---|---|---|
| **Фаза 0** | Сборка на Kotlin (`buildSrc`, Version Catalog, `merge=ours`, хуки строк) | В работе | `7 / 9` | Вариант A внедрён и проверен; осталось перенести ключи в `easygram_strings.xml` |
| **Фаза 1.1** | Декомпозиция `ChatExport` (3 308 строк → 11 файлов) | Не начато | `0 / 2` | Самый крупный монолит Easygram |
| **Фаза 1.2** | `OtherSettingsActivity` → `EasygramPreferences` + Kotlin UI | Не начато | `0 / 4` | Развяжет глобальные настройки и UI |
| **Фаза 1.3** | Декомпозиция `NotificationMuteSchedule` | Не начато | `0 / 3` | Уже на Kotlin, нужно разбить |
| **Фаза 1.4** | `RoundVideoSettings` (свой стек, игнор апстрима + `merge=ours`) | Не начато | `0 / 3` | Заглушка с `merge=ours` + Kotlin-реализация |
| **Фаза 1.5** | Декомпозиция `MusicWidgetUpdater` | Не начато | `0 / 2` | Сохранить FQN провайдеров виджетов |
| **Фаза 2** | Вынос логики Easygram из апстримных монолитов | Не начато | `0 / 8` | Начать с `MessagesStorage` и `VideoPlayer` |
| **Фаза 3.1** | Миграция ASR / Local AI на Kotlin (12 файлов) | Не начато | `0 / 5` | — |
| **Фаза 3.2** | Миграция M3E и аудиоволн на Kotlin (12 файлов) | Не начато | `0 / 3` | — |
| **Фаза 3.3** | Миграция Round Video поддержки на Kotlin (3 файла) | Не начато | `0 / 3` | — |
| **Фаза 3.4** | Миграция OTA Updater на Kotlin (3 файла) | Не начато | `0 / 3` | — |
| **Фаза 3.5** | Мелкие утилиты и перенос существующих `.kt` | Не начато | `0 / 4` | — |
| **Фаза 4** | JVM unit-тесты для вынесенной логики | Не начато | `0 / 2` | — |
