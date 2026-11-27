# Account and Data Deletion — Easygram

[English](#english) · [Русский](#русский)

---

## English

**Application:** Easygram (`io.github.webarmour.easygram.store`, `io.github.webarmour.easygram`)  
**Developer:** webarmour  
**Contact:** `webarmour.dev@gmail.com`

Easygram is an independent, unofficial Telegram client for Android. Easygram does **not** operate its own user account or messaging servers — your account, chats, contacts, and cloud media are hosted directly by Telegram.

### 1. How to Delete Your Account Permanently

#### Option A — Immediate Deletion via Web (Recommended)
1. Open Telegram's official account deactivation page: https://my.telegram.org/delete
2. Enter the phone number associated with your Telegram account (in international format) and tap **Next**.
3. Enter the confirmation code sent to your Telegram app (including inside Easygram).
4. Tap **Delete My Account** and confirm permanent deletion.

#### Option B — Automatic Deletion After Inactivity (In-App)
1. Open **Easygram** and go to **Settings → Privacy and Security**.
2. Under **Delete my account**, tap **If away for** and select the inactivity period (e.g., **1 month**).
3. If you do not come online during the selected period, Telegram automatically deletes your account and cloud data.

#### Clear Remaining Local Data on Your Device
After deleting your account, uninstall **Easygram** from your Android device (or go to Android **Settings → Apps → Easygram → Storage → Clear data**) to immediately remove all locally cached files, settings, and downloaded speech-recognition models.

### 2. How to Delete Data Without Deleting Your Account
You can delete specific data at any time while keeping your account:
- **Messages, chats, and cloud media:** Delete individual messages, clear chat history, or delete entire chats inside Easygram.
- **Synced contacts:** Go to **Settings → Privacy and Security → Data Settings → Delete Synced Contacts**.
- **Local media cache:** Go to **Settings → Data and Storage → Storage Usage → Clear Cache**.
- **Downloaded speech-recognition models:** Go to **Settings → Advanced → On-device processing → Delete all models**.
- **Notification schedules and local preferences:** Clear app data in Android Settings or uninstall Easygram.

### 3. Data Deleted vs. Retained & Retention Periods

| Data Type | What Happens Upon Deletion | Retention Period |
|---|---|---|
| Telegram profile, phone number, cloud messages, chats, contacts, and cloud media | Permanently deleted from Telegram's servers upon account deactivation (copies of messages sent to other users may remain in those recipients' own chat histories per Telegram's architecture). | Deleted immediately upon confirming deactivation at https://my.telegram.org/delete (see https://telegram.org/privacy). |
| Local cache, app settings, notification schedules, exported files in app storage, downloaded on-device speech models | Stored strictly on your device and permanently removed when you clear app data or uninstall Easygram. | Deleted immediately (0 days). |
| Aggregated app usage analytics (Google Firebase Analytics, Play Store builds only) | Easygram does **not** link Firebase Analytics to your Telegram account, phone number, user ID, or Advertising ID (`AD_ID` is disabled). Anonymous event-level analytics data cannot be matched to a specific user. | Automatically expired by Google Firebase Analytics according to Google's default retention schedule (up to 14 months for event-level data; aggregated counts contain no personal identifiers). |

### 4. Need Help?
If you have questions about deleting your data in **Easygram**, contact the developer **webarmour** at:
- **Email:** `webarmour.dev@gmail.com`
- **Privacy Policy:** [PRIVACY_POLICY.md](PRIVACY_POLICY.md)

---

## Русский

**Приложение:** Easygram (`io.github.webarmour.easygram.store`, `io.github.webarmour.easygram`)  
**Разработчик:** webarmour  
**Контакты:** `webarmour.dev@gmail.com`

Easygram — независимый неофициальный клиент Telegram для Android. Easygram **не использует собственные серверы** для аккаунтов и сообщений — ваш аккаунт, чаты, контакты и облачные медиафайлы хранятся напрямую на серверах Telegram.

### 1. Как полностью удалить аккаунт

#### Способ А — Немедленное удаление через веб-страницу (рекомендуется)
1. Откройте официальную страницу удаления аккаунта Telegram: https://my.telegram.org/delete
2. Введите номер телефона, привязанный к вашему аккаунту (в международном формате), и нажмите **Next**.
3. Введите код подтверждения, который придёт в приложение Telegram (в том числе в Easygram).
4. Нажмите **Delete My Account** и подтвердите безвозвратное удаление.

#### Способ Б — Автоматическое удаление при неактивности (внутри приложения)
1. Откройте **Easygram** и перейдите в **Настройки → Конфиденциальность**.
2. В разделе **Удалить мой аккаунт** выберите пункт **Если я не захожу** и задайте срок (например, **1 месяц**).
3. Если вы не зайдёте в аккаунт в течение выбранного срока, Telegram автоматически удалит аккаунт и облачные данные.

#### Удаление локальных данных на устройстве
После удаления аккаунта удалите приложение **Easygram** с устройства (либо откройте **Настройки Android → Приложения → Easygram → Память → Очистить данные**), чтобы немедленно удалить локальный кэш, настройки и загруженные модели распознавания речи.

### 2. Как удалить часть данных без удаления аккаунта
Вы можете удалить отдельные данные в любой момент, сохранив аккаунт:
- **Сообщения, чаты и медиа:** удаляйте отдельные сообщения, очищайте историю или удаляйте чаты прямо в Easygram.
- **Синхронизированные контакты:** **Настройки → Конфиденциальность → Контакты → Удалить импортированные контакты**.
- **Локальный кэш медиа:** **Настройки → Данные и память → Использование памяти → Очистить кэш**.
- **Модели распознавания речи:** **Настройки → Расширенные → Обработка на устройстве → Удалить все модели**.
- **Расписание уведомлений и локальные настройки:** очистите данные приложения в настройках Android или удалите Easygram.

### 3. Какие данные удаляются, какие сохраняются и сроки хранения

| Тип данных | Что происходит при удалении | Срок хранения |
|---|---|---|
| Профиль Telegram, номер телефона, облачные чаты, контакты и медиафайлы | Безвозвратно удаляются с серверов Telegram после подтверждения удаления аккаунта (копии отправленных сообщений могут оставаться в переписках у собеседников в соответствии с архитектурой Telegram). | Удаляются немедленно после подтверждения на https://my.telegram.org/delete (подробнее: https://telegram.org/privacy). |
| Локальный кэш, настройки приложения, расписание уведомлений, загруженные модели распознавания речи | Хранятся только на вашем устройстве и полностью удаляются при очистке данных или удалении Easygram. | Удаляются немедленно (0 дней). |
| Агрегированная статистика использования (Google Firebase Analytics, только в сборке Google Play) | Easygram **не связывает** Firebase Analytics с аккаунтом Telegram, номером телефона, Telegram ID или рекламным идентификатором (`AD_ID` отключён). Анонимные события не позволяют идентифицировать пользователя. | Автоматически удаляются Google Firebase Analytics в соответствии со стандартным сроком хранения Google (до 14 месяцев для событий; агрегированные отчёты не содержат персональных данных). |

### 4. Поддержка
По вопросам удаления данных в **Easygram** обращайтесь к разработчику **webarmour**:
- **Email:** `webarmour.dev@gmail.com`
- **Политика конфиденциальности:** [PRIVACY_POLICY.md](PRIVACY_POLICY.md)
