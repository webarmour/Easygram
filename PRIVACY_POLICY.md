# Privacy Policy for Easygram

[English](#english) · [Русский](#русский)

---

## English

**Effective date:** November 27, 2026  
**Application:** Easygram (`io.github.webarmour.easygram.store`, `io.github.webarmour.easygram`)  
**Developer:** webarmour

Easygram is an independent, unofficial, open-source Telegram client for Android based on the official Telegram for Android codebase. Easygram is not affiliated with or endorsed by Telegram.

This Privacy Policy explains how Easygram handles data on your device and which third-party services are involved when you use the app.

### 1. Telegram Account, Messages, Media, and Contacts
Easygram connects directly to Telegram's official servers using the Telegram API. The developer of Easygram does **not** operate messaging servers and has **no access** to your phone number, Telegram account, contacts, messages, media, calls, or secret chats.

All data transmitted to and stored by Telegram is governed by Telegram's own Privacy Policy:  
https://telegram.org/privacy

### 2. Analytics (Firebase Analytics)
Release builds of Easygram distributed via Google Play (`io.github.webarmour.easygram.store`) and GitHub Releases (`io.github.webarmour.easygram`) include **Google Firebase Analytics** solely to understand basic app usage and stability (such as first opens, session counts, engagement, app updates, and top-level activity views).

- Collection of the Android Advertising ID (`AD_ID`) is **disabled**.
- Easygram does **not** send custom events, Telegram user IDs, phone numbers, usernames, chat names, or message contents to Firebase Analytics.
- Firebase Analytics is provided by Google LLC and processed under Google's Privacy Policy: https://policies.google.com/privacy

### 3. Push Notifications and Google Play Services
- **Firebase Cloud Messaging (FCM)** is used solely to deliver push notifications when new Telegram messages arrive.
- **Google Play Services** (such as Google Play Integrity, Google Play Billing, Maps, or ML Kit where invoked by standard Telegram features) are used to support core app functionality and security checks under Google's Privacy Policy: https://policies.google.com/privacy

### 4. On-Device Features (Processed Locally Only)
Easygram includes additional features that run **strictly on your device**:

- **Voice Transcription and Dictation:** Optional speech-recognition models (powered by sherpa-onnx) are downloaded on demand and execute completely offline on your phone. Audio and transcribed text are never transmitted to the Easygram developer or external transcription servers.
- **Video Messages (Camera2):** Camera and microphone permissions are used only when you explicitly record video or voice messages, take photos, or place calls. Media is processed locally before being sent through Telegram.
- **Chat Export:** Exported HTML and ZIP chat archives are generated locally on your device and saved to your device's Downloads folder.
- **App Settings and Notification Schedules:** Custom preferences, navigation layout, and quiet-hours schedules are stored locally on your device.

### 5. Data Retention and Deletion
- **Local data:** You can clear cached media, delete downloaded speech-recognition models, or remove local app data at any time via Easygram Settings, Android App Settings, or by uninstalling the app.
- **Telegram account and cloud data:** You can manage or permanently delete your Telegram account and cloud messages directly through Telegram's in-app Privacy & Security settings or at https://my.telegram.org/delete.

### 6. Children's Privacy
Easygram is not directed to children under the minimum age required by applicable law or Telegram's Terms of Service. The developer does not knowingly collect personal information from children.

### 7. Changes to This Privacy Policy
This Privacy Policy may be updated from time to time. Any changes will be published on this page with an updated effective date.

### 8. Contact
If you have any questions or privacy requests regarding Easygram, please contact the developer:

- **Email:** `webarmour.dev@gmail.com`
- **GitHub:** https://github.com/webarmour/Easygram/issues

---

## Русский

**Дата вступления в силу:** 27 ноября 2026 г.  
**Приложение:** Easygram (`io.github.webarmour.easygram.store`, `io.github.webarmour.easygram`)  
**Разработчик:** webarmour

Easygram — независимый неофициальный клиент Telegram для Android с открытым исходным кодом, созданный на базе исходного кода Telegram для Android. Проект не связан с командой Telegram.

Настоящая Политика конфиденциальности описывает, как Easygram обрабатывает данные на устройстве и какие сторонние сервисы задействованы при работе приложения.

### 1. Аккаунт Telegram, сообщения, медиа и контакты
Easygram подключается напрямую к официальным серверам Telegram по Telegram API. Разработчик Easygram **не использует промежуточные серверы** для обмена сообщениями и **не имеет доступа** к вашему номеру телефона, аккаунту Telegram, контактам, перепискам, медиафайлам, звонкам и секретным чатам.

Обработка данных, передаваемых и хранимых на серверах Telegram, регулируется Политикой конфиденциальности Telegram:  
https://telegram.org/privacy

### 2. Аналитика (Firebase Analytics)
Релизные сборки Easygram для Google Play (`io.github.webarmour.easygram.store`) и GitHub Releases (`io.github.webarmour.easygram`) включают **Google Firebase Analytics** исключительно для базовой оценки стабильности и использования приложения (первый запуск, сессии, обновления приложения и верхнеуровневые экраны).

- Сбор рекламного идентификатора Android (`AD_ID`) **отключён**.
- Easygram **не передаёт** в Firebase Analytics пользовательские события, Telegram ID, номера телефонов, имена пользователей, названия чатов или содержимое сообщений.
- Сервис предоставляется Google LLC и регулируется Политикой конфиденциальности Google: https://policies.google.com/privacy

### 3. Push-уведомления и сервисы Google Play
- **Firebase Cloud Messaging (FCM)** используется только для доставки push-уведомлений о новых сообщениях Telegram.
- **Сервисы Google Play** (Google Play Integrity, Google Play Billing, Карты, ML Kit в рамках стандартных функций клиента Telegram) используются для базовой функциональности и проверок безопасности в соответствии с Политикой конфиденциальности Google: https://policies.google.com/privacy

### 4. Локальные функции (обработка только на устройстве)
Дополнительные функции Easygram работают **исключительно локально на вашем устройстве**:

- **Расшифровка голосовых и диктовка:** модели распознавания речи (на базе sherpa-onnx) загружаются по вашему запросу и выполняются полностью офлайн. Аудиозаписи и распознанный текст не отправляются разработчику Easygram или на сторонние серверы распознавания речи.
- **Видеосообщения (Camera2):** доступ к камере и микрофону используется только тогда, когда вы сами записываете видео- или голосовое сообщение, делаете фото или совершаете звонок. Обработка выполняется локально перед отправкой через Telegram.
- **Экспорт чатов:** файлы HTML и ZIP-архивы создаются локально на устройстве и сохраняются в папку «Загрузки».
- **Настройки и расписание уведомлений:** пользовательские параметры интерфейса и расписание «тихих часов» хранятся локально на устройстве.

### 5. Хранение и удаление данных
- **Локальные данные:** вы можете в любой момент очистить кэш, удалить загруженные модели распознавания речи или сбросить локальные данные через настройки Easygram, настройки Android либо удалив приложение.
- **Аккаунт и облачные данные Telegram:** управление и полное удаление аккаунта и переписок выполняется напрямую через настройки «Конфиденциальность» в Telegram или по адресу https://my.telegram.org/delete.

### 6. Конфиденциальность детей
Easygram не предназначен для детей младше минимального возраста, установленного применимым законодательством и Условиями использования Telegram. Разработчик не собирает персональные данные детей сознательно.

### 7. Изменения политики
Политика конфиденциальности может периодически обновляться. Актуальная версия всегда публикуется на этой странице с указанием даты обновления.

### 8. Контакты
По всем вопросам, связанным с конфиденциальностью в Easygram, вы можете обратиться к разработчику:

- **Email:** `webarmour.dev@gmail.com`
- **GitHub:** https://github.com/webarmour/Easygram/issues
