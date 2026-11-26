/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.collection.LongSparseArray
import org.telegram.SQLite.SQLiteCursor
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class ChatExportController(
    private val config: ExportConfig,
    private val callback: Callback
) {

    data class ExportConfig(
        val currentAccount: Int,
        val dialogId: Long,
        val mergeDialogId: Long,
        val topicId: Long,
        val isTopic: Boolean,
        val isSavedMode: Boolean,
        val chatTitle: String,
        val chatSubtitle: String,
        val startDateSec: Int,
        val endDateSec: Int,
        val periodDescription: String,
        val includeMedia: Boolean
    )

    data class ExportResult(
        val shareFile: File,
        val downloadsUri: Uri?,
        val fileName: String,
        val mimeType: String,
        val messageCount: Int,
        val mediaCount: Int,
        val fileSizeBytes: Long
    )

    interface Callback {
        fun onProgress(stage: Int, current: Int, total: Int)
        fun onComplete(result: ExportResult)
        fun onEmpty()
        fun onError(message: String)
        fun onCancelled()
    }

    private enum class MediaKind {
        PHOTO,
        VIDEO,
        ROUND_VIDEO,
        VOICE,
        AUDIO,
        GIF,
        STICKER,
        DOCUMENT
    }

    private class MediaDescriptor(
        val message: TLRPC.Message,
        val messageKey: String,
        val kind: MediaKind,
        val photo: TLRPC.Photo?,
        val photoSize: TLRPC.PhotoSize?,
        val document: TLRPC.Document?,
        val displayName: String,
        val extension: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationSec: Int,
        val stickerEmoji: String,
        val isTtlMedia: Boolean
    ) {
        var localFile: File? = null
        var archiveRelPath: String? = null
        var unavailable: Boolean = false
    }

    private data class SenderInfo(
        val name: String,
        val initials: String,
        val color: String
    )

    private val cancelled = AtomicBoolean(false)
    private val activeRequestId = AtomicInteger(0)

    @Volatile
    private var activeWaitLatch: CountDownLatch? = null

    @Volatile
    private var activeDownloadDescriptor: MediaDescriptor? = null

    fun start() {
        Thread({ runExport() }, "ChatExportWorker").start()
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            val reqId = activeRequestId.getAndSet(0)
            if (reqId != 0) {
                ConnectionsManager.getInstance(config.currentAccount).cancelRequest(reqId, true)
            }
            val desc = activeDownloadDescriptor
            if (desc != null) {
                val fileLoader = FileLoader.getInstance(config.currentAccount)
                when {
                    desc.document != null -> fileLoader.cancelLoadFile(desc.document, false)
                    desc.photoSize != null -> fileLoader.cancelLoadFile(desc.photoSize, false)
                }
            }
            activeWaitLatch?.countDown()
        }
    }

    private fun postProgress(stage: Int, current: Int, total: Int) {
        if (cancelled.get()) return
        AndroidUtilities.runOnUIThread {
            if (!cancelled.get()) {
                callback.onProgress(stage, current, total)
            }
        }
    }

    private fun runExport() {
        var outputFile: File? = null
        try {
            val usersMap = LongSparseArray<TLRPC.User>()
            val chatsMap = LongSparseArray<TLRPC.Chat>()

            // Seed current user and current peer
            val userConfig = UserConfig.getInstance(config.currentAccount)
            userConfig.currentUser?.let { usersMap.put(it.id, it) }

            val messagesController = MessagesController.getInstance(config.currentAccount)
            if (config.dialogId > 0) {
                messagesController.getUser(config.dialogId)?.let { usersMap.put(it.id, it) }
            } else if (config.dialogId < 0) {
                messagesController.getChat(-config.dialogId)?.let { chatsMap.put(it.id, it) }
            }

            postProgress(STAGE_MESSAGES, 0, 0)
            val messages = fetchAllMessages(usersMap, chatsMap)
            if (cancelled.get()) {
                AndroidUtilities.runOnUIThread { callback.onCancelled() }
                return
            }
            if (messages.isEmpty()) {
                AndroidUtilities.runOnUIThread { callback.onEmpty() }
                return
            }

            // Sort oldest -> newest
            messages.sortWith(compareBy<TLRPC.Message>({ it.date }, { it.id }))

            // Build collision-safe lookup for reply references (important for migrated supergroups)
            val messagesByKey = HashMap<String, TLRPC.Message>(messages.size)
            for (m in messages) {
                messagesByKey[messageKey(m)] = m
            }

            // Collect media descriptors
            val mediaByMessageKey = HashMap<String, MediaDescriptor>()
            val mediaList = ArrayList<MediaDescriptor>()
            for (m in messages) {
                val desc = inspectMedia(m, messageKey(m))
                if (desc != null) {
                    mediaByMessageKey[desc.messageKey] = desc
                    mediaList.add(desc)
                }
            }

            var exportedMediaCount = 0
            if (config.includeMedia && mediaList.isNotEmpty()) {
                exportedMediaCount = resolveAndDownloadMedia(mediaList, usersMap, chatsMap)
                if (cancelled.get()) {
                    AndroidUtilities.runOnUIThread { callback.onCancelled() }
                    return
                }
            }

            postProgress(STAGE_BUILDING, 0, if (config.includeMedia) exportedMediaCount else 0)

            val exportDir = getExportCacheDir()
            cleanupOldExports(exportDir)

            val writeZip = config.includeMedia && exportedMediaCount > 0
            val baseFileName = buildOutputFileName(if (writeZip) "zip" else "html")
            val targetFile = File(exportDir, baseFileName)
            outputFile = targetFile
            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (writeZip) {
                writeZipExport(
                    targetFile,
                    messages,
                    messagesByKey,
                    mediaByMessageKey,
                    mediaList,
                    usersMap,
                    chatsMap,
                    exportedMediaCount
                )
            } else {
                writeHtmlFile(
                    targetFile,
                    messages,
                    messagesByKey,
                    mediaByMessageKey,
                    usersMap,
                    chatsMap,
                    0
                )
            }

            if (cancelled.get()) {
                targetFile.delete()
                AndroidUtilities.runOnUIThread { callback.onCancelled() }
                return
            }

            postProgress(STAGE_SAVING, 0, 0)
            val mimeType = if (writeZip) "application/zip" else "text/html"
            val savedUri = saveCopyToDownloads(targetFile, baseFileName, mimeType)

            val result = ExportResult(
                shareFile = targetFile,
                downloadsUri = savedUri,
                fileName = baseFileName,
                mimeType = mimeType,
                messageCount = messages.size,
                mediaCount = exportedMediaCount,
                fileSizeBytes = targetFile.length()
            )
            AndroidUtilities.runOnUIThread { callback.onComplete(result) }
        } catch (t: Throwable) {
            FileLog.e(t)
            outputFile?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (_: Throwable) {
                }
            }
            if (cancelled.get()) {
                AndroidUtilities.runOnUIThread { callback.onCancelled() }
            } else {
                val err = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                AndroidUtilities.runOnUIThread { callback.onError(err) }
            }
        }
    }

    @Throws(Exception::class)
    private fun fetchAllMessages(
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): ArrayList<TLRPC.Message> {
        if (DialogObject.isEncryptedDialog(config.dialogId)) {
            return fetchEncryptedLocalMessages(usersMap)
        }

        val collected = ArrayList<TLRPC.Message>()
        val seenKeys = HashSet<String>()

        val reachedStart = fetchCloudMessagesForDialog(
            targetDialogId = config.dialogId,
            isTopic = config.isTopic,
            isSavedMode = config.isSavedMode,
            topicId = config.topicId,
            collected = collected,
            seenKeys = seenKeys,
            usersMap = usersMap,
            chatsMap = chatsMap
        )

        // If migrated supergroup has an older basic group history, continue fetching from mergeDialogId
        if (!cancelled.get() && !reachedStart && config.mergeDialogId != 0L && !config.isTopic && !config.isSavedMode) {
            fetchCloudMessagesForDialog(
                targetDialogId = config.mergeDialogId,
                isTopic = false,
                isSavedMode = false,
                topicId = 0L,
                collected = collected,
                seenKeys = seenKeys,
                usersMap = usersMap,
                chatsMap = chatsMap
            )
        }

        return collected
    }

    private fun fetchEncryptedLocalMessages(usersMap: LongSparseArray<TLRPC.User>): ArrayList<TLRPC.Message> {
        val result = ArrayList<TLRPC.Message>()
        val latch = CountDownLatch(1)
        activeWaitLatch = latch

        val storage = MessagesStorage.getInstance(config.currentAccount)
        val selfId = UserConfig.getInstance(config.currentAccount).clientUserId
        val encChat = MessagesController.getInstance(config.currentAccount)
            .getEncryptedChat(DialogObject.getEncryptedChatId(config.dialogId))
        if (encChat != null) {
            MessagesController.getInstance(config.currentAccount).getUser(encChat.user_id)
                ?.let { usersMap.put(it.id, it) }
        }

        storage.storageQueue.postRunnable {
            var cursor: SQLiteCursor? = null
            try {
                val db = storage.database ?: return@postRunnable
                cursor = if (config.startDateSec > 0 && config.endDateSec > 0) {
                    db.queryFinalized(
                        "SELECT data, mid, date, out, replydata FROM messages_v2 WHERE uid = ? AND date >= ? AND date <= ? ORDER BY date ASC, mid ASC",
                        config.dialogId, config.startDateSec, config.endDateSec
                    )
                } else {
                    db.queryFinalized(
                        "SELECT data, mid, date, out, replydata FROM messages_v2 WHERE uid = ? ORDER BY date ASC, mid ASC",
                        config.dialogId
                    )
                }
                while (cursor.next()) {
                    if (cancelled.get()) break
                    val data = cursor.byteBufferValue(0) ?: continue
                    try {
                        val message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
                        if (message != null) {
                            message.readAttachPath(data, selfId)
                            message.id = cursor.intValue(1)
                            message.date = cursor.intValue(2)
                            message.out = cursor.intValue(3) != 0
                            message.dialog_id = config.dialogId
                            if (message.from_id == null) {
                                message.from_id = TLRPC.TL_peerUser().apply {
                                    user_id = if (message.out) selfId else (encChat?.user_id ?: 0L)
                                }
                            }
                            if (!cursor.isNull(4)) {
                                val replyData = cursor.byteBufferValue(4)
                                if (replyData != null) {
                                    try {
                                        message.replyMessage = TLRPC.Message.TLdeserialize(
                                            replyData,
                                            replyData.readInt32(false),
                                            false
                                        )
                                        message.replyMessage?.readAttachPath(replyData, selfId)
                                    } finally {
                                        replyData.reuse()
                                    }
                                }
                            }
                            if (message !is TLRPC.TL_messageEmpty) {
                                result.add(message)
                                if (result.size % 50 == 0) {
                                    postProgress(STAGE_MESSAGES, result.size, 0)
                                }
                            }
                        }
                    } finally {
                        data.reuse()
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            } finally {
                cursor?.dispose()
                latch.countDown()
            }
        }

        try {
            latch.await()
        } catch (_: InterruptedException) {
        } finally {
            activeWaitLatch = null
        }
        postProgress(STAGE_MESSAGES, result.size, 0)
        return result
    }

    /**
     * Fetches cloud messages backwards in pages of 100.
     * @return true if pagination stopped because we passed config.startDateSec
     */
    @Throws(Exception::class)
    private fun fetchCloudMessagesForDialog(
        targetDialogId: Long,
        isTopic: Boolean,
        isSavedMode: Boolean,
        topicId: Long,
        collected: ArrayList<TLRPC.Message>,
        seenKeys: MutableSet<String>,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): Boolean {
        val messagesController = MessagesController.getInstance(config.currentAccount)
        val connectionsManager = ConnectionsManager.getInstance(config.currentAccount)

        val peerTargetId = if (isSavedMode && topicId != 0L) topicId else targetDialogId
        val inputPeer = messagesController.getInputPeer(peerTargetId)
        if (inputPeer == null || inputPeer is TLRPC.TL_inputPeerEmpty) {
            return false
        }

        var offsetId = 0
        // +60s safely covers MTProto strict `< offset_date` semantics and minor clock skew without scanning an extra day
        var offsetDate = if (config.endDateSec > 0) config.endDateSec + 60 else 0
        val pageSize = 100
        var emptyPages = 0

        while (!cancelled.get()) {
            val request: TLObject = when {
                isTopic && topicId != 0L -> TLRPC.TL_messages_getReplies().apply {
                    peer = inputPeer
                    msg_id = topicId.toInt()
                    offset_id = offsetId
                    offset_date = if (offsetId == 0) offsetDate else 0
                    limit = pageSize
                }
                isSavedMode && topicId != 0L -> TLRPC.TL_messages_getSavedHistory().apply {
                    peer = inputPeer
                    offset_id = offsetId
                    offset_date = if (offsetId == 0) offsetDate else 0
                    limit = pageSize
                }
                else -> TLRPC.TL_messages_getHistory().apply {
                    peer = inputPeer
                    offset_id = offsetId
                    offset_date = if (offsetId == 0) offsetDate else 0
                    limit = pageSize
                }
            }

            val responseRef = AtomicReference<TLObject?>()
            val errorRef = AtomicReference<TLRPC.TL_error?>()
            val latch = CountDownLatch(1)
            activeWaitLatch = latch

            val reqId = connectionsManager.sendRequest(request, RequestDelegate { response, error ->
                responseRef.set(response)
                errorRef.set(error)
                latch.countDown()
            })
            activeRequestId.set(reqId)

            try {
                latch.await()
            } catch (_: InterruptedException) {
            } finally {
                activeRequestId.set(0)
                activeWaitLatch = null
            }

            if (cancelled.get()) {
                return false
            }

            val error = errorRef.get()
            if (error != null) {
                val errText = error.text
                if (errText != null && errText.startsWith("FLOOD_WAIT_")) {
                    val waitSec = errText.removePrefix("FLOOD_WAIT_").toIntOrNull() ?: 3
                    if (waitSec > 30) {
                        throw IOException(errText)
                    }
                    sleepCancellable(waitSec * 1000L + 200L)
                    continue
                }
                throw IOException(errText ?: "RPC error ${error.code}")
            }

            val res = responseRef.get() as? TLRPC.messages_Messages ?: break

            res.users?.let { users ->
                for (u in users) {
                    if (u != null) usersMap.put(u.id, u)
                }
                messagesController.putUsers(users, true)
            }
            res.chats?.let { chats ->
                for (c in chats) {
                    if (c != null) chatsMap.put(c.id, c)
                }
                messagesController.putChats(chats, true)
            }

            val batch = res.messages
            if (batch == null || batch.isEmpty()) {
                break
            }

            var minIdInBatch = Int.MAX_VALUE
            var addedInBatch = 0
            var passedStartDate = false

            for (msg in batch) {
                if (msg == null) continue
                if (msg.id < minIdInBatch) {
                    minIdInBatch = msg.id
                }
                if (msg is TLRPC.TL_messageEmpty) {
                    continue
                }
                if (config.endDateSec > 0 && msg.date > config.endDateSec) {
                    continue
                }
                if (config.startDateSec > 0 && msg.date < config.startDateSec) {
                    passedStartDate = true
                    continue
                }
                msg.dialog_id = targetDialogId
                val key = messageKey(msg)
                if (seenKeys.add(key)) {
                    collected.add(msg)
                    addedInBatch++
                }
            }

            postProgress(STAGE_MESSAGES, collected.size, 0)

            if (passedStartDate) {
                return true
            }
            if (minIdInBatch == Int.MAX_VALUE || minIdInBatch <= 1 || (offsetId != 0 && minIdInBatch >= offsetId)) {
                break
            }
            if (addedInBatch == 0 && config.endDateSec == 0) {
                emptyPages++
                if (emptyPages >= 3) break
            } else {
                emptyPages = 0
            }

            offsetId = minIdInBatch
            offsetDate = 0

            if (batch.size < pageSize && res is TLRPC.TL_messages_messages) {
                break
            }

            sleepCancellable(60)
        }
        return false
    }

    private fun sleepCancellable(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (!cancelled.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try {
                Thread.sleep(min(remaining, 150L))
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun inspectMedia(msg: TLRPC.Message, msgKey: String): MediaDescriptor? {
        val media = msg.media ?: return null
        if (media is TLRPC.TL_messageMediaEmpty) return null

        val isTtl = media.ttl_seconds > 0 || msg.ttl > 0

        if (media is TLRPC.TL_messageMediaPhoto) {
            val photo = media.photo
            if (photo !is TLRPC.TL_photo || photo.id == 0L) {
                return null
            }
            val rawBest = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize())
                ?: FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280)
            val downloadableSize = rawBest?.takeIf { isDownloadablePhotoSize(it) }
            val size = downloadableSize?.size?.toLong() ?: 0L
            return MediaDescriptor(
                message = msg,
                messageKey = msgKey,
                kind = MediaKind.PHOTO,
                photo = photo,
                photoSize = downloadableSize,
                document = null,
                displayName = "photo_${msg.id}.jpg",
                extension = "jpg",
                mimeType = "image/jpeg",
                sizeBytes = size,
                durationSec = 0,
                stickerEmoji = "",
                isTtlMedia = isTtl
            )
        }

        if (media is TLRPC.TL_messageMediaDocument) {
            val doc = media.document ?: return null
            if (doc is TLRPC.TL_documentEmpty || doc.id == 0L) {
                return null
            }
            var kind = MediaKind.DOCUMENT
            var duration = 0
            val fileName = FileLoader.getDocumentFileName(doc) ?: ""
            var stickerEmoji = ""
            var audioTitle: String? = null
            var audioPerformer: String? = null

            doc.attributes?.forEach { attr ->
                when (attr) {
                    is TLRPC.TL_documentAttributeAnimated -> kind = MediaKind.GIF
                    is TLRPC.TL_documentAttributeSticker -> {
                        kind = MediaKind.STICKER
                        if (!attr.alt.isNullOrEmpty()) {
                            stickerEmoji = attr.alt
                        }
                    }
                    is TLRPC.TL_documentAttributeVideo -> {
                        duration = attr.duration.roundToInt()
                        if (attr.round_message) {
                            kind = MediaKind.ROUND_VIDEO
                        } else if (kind != MediaKind.GIF) {
                            kind = MediaKind.VIDEO
                        }
                    }
                    is TLRPC.TL_documentAttributeAudio -> {
                        duration = attr.duration.roundToInt()
                        if (attr.voice) {
                            kind = MediaKind.VOICE
                        } else {
                            kind = MediaKind.AUDIO
                            audioTitle = attr.title
                            audioPerformer = attr.performer
                        }
                    }
                }
            }

            val ext = guessExtension(doc, fileName, kind)
            val displayName = when {
                fileName.isNotEmpty() -> fileName
                kind == MediaKind.AUDIO && (!audioPerformer.isNullOrEmpty() || !audioTitle.isNullOrEmpty()) -> {
                    listOfNotNull(
                        audioPerformer?.takeIf { it.isNotBlank() },
                        audioTitle?.takeIf { it.isNotBlank() }
                    ).joinToString(" - ")
                }
                else -> "${kind.name.lowercase(Locale.US)}_${msg.id}.$ext"
            }
            val mime = doc.mime_type?.takeIf { it.isNotEmpty() } ?: guessMimeType(ext, kind)
            return MediaDescriptor(
                message = msg,
                messageKey = msgKey,
                kind = kind,
                photo = null,
                photoSize = null,
                document = doc,
                displayName = displayName,
                extension = ext,
                mimeType = mime,
                sizeBytes = doc.size,
                durationSec = duration,
                stickerEmoji = stickerEmoji,
                isTtlMedia = isTtl
            )
        }

        return null
    }

    private fun isDownloadablePhotoSize(size: TLRPC.PhotoSize): Boolean {
        if (size is TLRPC.TL_photoSizeEmpty ||
            size is TLRPC.TL_photoStrippedSize ||
            size is TLRPC.TL_photoCachedSize ||
            size is TLRPC.TL_photoPathSize
        ) {
            return false
        }
        return !FileLoader.getAttachFileName(size).isNullOrEmpty()
    }

    private fun resolveAndDownloadMedia(
        mediaList: ArrayList<MediaDescriptor>,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): Int {
        val fileLoader = FileLoader.getInstance(config.currentAccount)
        val total = mediaList.size
        var exported = 0
        val usedArchiveNames = HashSet<String>()

        postProgress(STAGE_MEDIA, 0, total)

        for (i in 0 until total) {
            if (cancelled.get()) {
                return exported
            }
            val desc = mediaList[i]
            if (desc.isTtlMedia) {
                desc.unavailable = true
                postProgress(STAGE_MEDIA, i + 1, total)
                continue
            }

            var file = findExistingMediaFile(fileLoader, desc)
            if (!isValidFile(file) && !DialogObject.isEncryptedDialog(desc.message.dialog_id)) {
                file = downloadMediaFile(fileLoader, desc, usersMap, chatsMap)
            }

            if (isValidFile(file)) {
                desc.localFile = file
                desc.archiveRelPath = "media/${makeUniqueArchiveFileName(desc, usedArchiveNames)}"
                exported++
            } else {
                desc.unavailable = true
            }

            postProgress(STAGE_MEDIA, i + 1, total)
        }

        return exported
    }

    private fun findExistingMediaFile(fileLoader: FileLoader, desc: MediaDescriptor): File? {
        val attachPath = desc.message.attachPath
        if (!attachPath.isNullOrEmpty()) {
            val f = File(attachPath)
            if (isValidFile(f)) return f
        }
        val pathToMsg = fileLoader.getPathToMessage(desc.message, false)
        if (isValidFile(pathToMsg)) return pathToMsg

        desc.photoSize?.let { ps ->
            val photoPath = fileLoader.getPathToAttach(ps, true)
            if (isValidFile(photoPath)) return photoPath
        }
        desc.document?.let { doc ->
            val docPath = fileLoader.getPathToAttach(doc, true)
            if (isValidFile(docPath)) return docPath
        }
        return null
    }

    private fun downloadMediaFile(
        fileLoader: FileLoader,
        desc: MediaDescriptor,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): File? {
        val expectedFileName = when {
            desc.photo != null && desc.photoSize != null -> FileLoader.getAttachFileName(desc.photoSize)
            desc.document != null -> FileLoader.getAttachFileName(desc.document)
            else -> null
        }
        if (expectedFileName.isNullOrEmpty()) {
            return null
        }

        val latch = CountDownLatch(1)
        val loadFailed = AtomicBoolean(false)
        val loadedFileRef = AtomicReference<File?>()
        activeWaitLatch = latch
        activeDownloadDescriptor = desc

        val notificationCenter = NotificationCenter.getInstance(config.currentAccount)
        val delegate = NotificationCenter.NotificationCenterDelegate { id, _, args ->
            if (args == null || args.isEmpty()) return@NotificationCenterDelegate
            val fn = args[0] as? String ?: return@NotificationCenterDelegate
            if (expectedFileName != fn) return@NotificationCenterDelegate
            if (id == NotificationCenter.fileLoadFailed) {
                loadFailed.set(true)
            } else if (id == NotificationCenter.fileLoaded && args.size > 1 && args[1] is File) {
                loadedFileRef.set(args[1] as File)
            }
            latch.countDown()
        }

        try {
            AndroidUtilities.runOnUIThread {
                if (cancelled.get()) {
                    latch.countDown()
                    return@runOnUIThread
                }
                notificationCenter.addObserver(delegate, NotificationCenter.fileLoaded)
                notificationCenter.addObserver(delegate, NotificationCenter.fileLoadFailed)

                // Re-check right after registering observer in case download completed just before
                val already = findExistingMediaFile(fileLoader, desc)
                if (isValidFile(already)) {
                    loadedFileRef.set(already)
                    latch.countDown()
                    return@runOnUIThread
                }

                val parentObject = try {
                    MessageObject(config.currentAccount, desc.message, usersMap, chatsMap, false, false)
                } catch (_: Throwable) {
                    null
                }

                when {
                    desc.photo != null && desc.photoSize != null -> {
                        val loc = ImageLocation.getForPhoto(desc.photoSize, desc.photo)
                        if (loc != null) {
                            fileLoader.loadFile(loc, parentObject, null, FileLoader.PRIORITY_HIGH, 0)
                        } else {
                            latch.countDown()
                        }
                    }
                    desc.document != null -> {
                        fileLoader.loadFile(desc.document, parentObject, FileLoader.PRIORITY_HIGH, 0)
                    }
                    else -> latch.countDown()
                }
            }

            try {
                latch.await(MEDIA_DOWNLOAD_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        } finally {
            activeDownloadDescriptor = null
            activeWaitLatch = null
            AndroidUtilities.runOnUIThread {
                notificationCenter.removeObserver(delegate, NotificationCenter.fileLoaded)
                notificationCenter.removeObserver(delegate, NotificationCenter.fileLoadFailed)
            }
        }

        if (cancelled.get() || loadFailed.get()) {
            return null
        }
        val direct = loadedFileRef.get()
        if (isValidFile(direct)) {
            return direct
        }
        return findExistingMediaFile(fileLoader, desc)
    }

    @Throws(IOException::class)
    private fun writeZipExport(
        zipFile: File,
        messages: ArrayList<TLRPC.Message>,
        messagesByKey: Map<String, TLRPC.Message>,
        mediaByMessageKey: Map<String, MediaDescriptor>,
        mediaList: ArrayList<MediaDescriptor>,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>,
        exportedMediaCount: Int
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), 65536)).use { zos ->
            zos.setLevel(1)

            zos.putNextEntry(ZipEntry("messages.html"))
            val writer = BufferedWriter(OutputStreamWriter(zos, StandardCharsets.UTF_8), 65536)
            writeHtmlContent(writer, messages, messagesByKey, mediaByMessageKey, usersMap, chatsMap, exportedMediaCount)
            writer.flush()
            zos.closeEntry()

            val buffer = ByteArray(65536)
            var packed = 0
            for (desc in mediaList) {
                if (cancelled.get()) return
                val localFile = desc.localFile ?: continue
                val relPath = desc.archiveRelPath ?: continue
                if (!localFile.exists() || !localFile.isFile) continue

                zos.putNextEntry(ZipEntry(relPath))
                FileInputStream(localFile).use { fis ->
                    while (true) {
                        if (cancelled.get()) return
                        val read = fis.read(buffer)
                        if (read == -1) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
                packed++
                if (packed % 10 == 0 || packed == exportedMediaCount) {
                    postProgress(STAGE_BUILDING, packed, exportedMediaCount)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writeHtmlFile(
        htmlFile: File,
        messages: ArrayList<TLRPC.Message>,
        messagesByKey: Map<String, TLRPC.Message>,
        mediaByMessageKey: Map<String, MediaDescriptor>,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>,
        exportedMediaCount: Int
    ) {
        BufferedWriter(OutputStreamWriter(FileOutputStream(htmlFile), StandardCharsets.UTF_8), 65536).use { writer ->
            writeHtmlContent(writer, messages, messagesByKey, mediaByMessageKey, usersMap, chatsMap, exportedMediaCount)
            writer.flush()
        }
    }

    @Throws(IOException::class)
    private fun writeHtmlContent(
        out: BufferedWriter,
        messages: ArrayList<TLRPC.Message>,
        messagesByKey: Map<String, TLRPC.Message>,
        mediaByMessageKey: Map<String, MediaDescriptor>,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>,
        exportedMediaCount: Int
    ) {
        val locale = LocaleController.getInstance().currentLocale ?: Locale.getDefault()

        val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayHeaderFmt = SimpleDateFormat("d MMMM yyyy", locale)
        val timeFmt = SimpleDateFormat("HH:mm", locale)
        val fullDateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", locale)

        val titleEsc = escapeHtml(config.chatTitle.ifEmpty { "Chat" })
        val exportedAtText = LocaleController.formatString(R.string.ExportHtmlExportedAt, fullDateTimeFmt.format(Date()))
        val periodText = LocaleController.formatString(
            R.string.ExportHtmlPeriodLabel,
            config.periodDescription.ifEmpty { LocaleController.getString(R.string.ExportChatSelectedAllTime) }
        )
        val msgCountText = LocaleController.formatString(R.string.ExportHtmlMessagesCount, messages.size)
        val mediaCountText = if (exportedMediaCount > 0) {
            LocaleController.formatString(R.string.ExportHtmlMediaCount, exportedMediaCount)
        } else {
            null
        }

        out.write("<!DOCTYPE html>\n<html lang=\"")
        out.write(escapeHtml(locale.language))
        out.write("\">\n<head>\n<meta charset=\"UTF-8\">\n")
        out.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        out.write("<title>")
        out.write(titleEsc)
        out.write("</title>\n<style>\n")
        out.write(CSS_STYLES)
        out.write("</style>\n</head>\n<body>\n")

        // Header
        out.write("<header class=\"chat-header\">\n<div class=\"chat-header-inner\">\n<div class=\"chat-header-info\">\n")
        out.write("<h1 class=\"chat-title\">")
        out.write(titleEsc)
        out.write("</h1>\n")
        if (config.chatSubtitle.isNotEmpty()) {
            out.write("<div class=\"chat-subtitle\">")
            out.write(escapeHtml(config.chatSubtitle))
            out.write("</div>\n")
        }
        out.write("<div class=\"chat-meta\">")
        out.write("<span>${escapeHtml(periodText)}</span>")
        out.write("<span>•</span>")
        out.write("<span>${escapeHtml(msgCountText)}</span>")
        if (mediaCountText != null) {
            out.write("<span>•</span><span>${escapeHtml(mediaCountText)}</span>")
        }
        out.write("<span>•</span><span>${escapeHtml(exportedAtText)}</span>")
        out.write("</div>\n</div>\n")
        out.write("<button class=\"theme-toggle\" type=\"button\" onclick=\"toggleTheme()\" title=\"Theme\">☀ / ☾</button>\n")
        out.write("</div>\n</header>\n")

        out.write("<main class=\"chat-container\">\n")

        var lastDayKey: String? = null
        val selfUserId = UserConfig.getInstance(config.currentAccount).clientUserId

        for (msg in messages) {
            if (cancelled.get()) return
            val msgDate = Date(msg.date * 1000L)
            val dayKey = dayKeyFmt.format(msgDate)

            if (dayKey != lastDayKey) {
                lastDayKey = dayKey
                out.write("<div class=\"date-divider\"><span>")
                out.write(escapeHtml(dayHeaderFmt.format(msgDate)))
                out.write("</span></div>\n")
            }

            val shortTime = timeFmt.format(msgDate)
            val fullTime = fullDateTimeFmt.format(msgDate)
            val anchorId = messageAnchorId(msg)

            if (msg is TLRPC.TL_messageService) {
                val serviceText = formatServiceMessage(msg, usersMap, chatsMap)
                out.write("<div class=\"service-msg\" id=\"$anchorId\" title=\"${escapeHtml(fullTime)}\">")
                out.write(escapeHtml(serviceText))
                out.write(" <span class=\"service-time\">${escapeHtml(shortTime)}</span></div>\n")
                continue
            }

            val sender = resolveSender(msg, usersMap, chatsMap, selfUserId)
            val isOutgoing = msg.out && !msg.post

            out.write("<div class=\"msg-row ${if (isOutgoing) "out" else "in"}\" id=\"$anchorId\">\n")

            if (!isOutgoing) {
                out.write("<div class=\"avatar\" style=\"background:${sender.color}\">${escapeHtml(sender.initials)}</div>\n")
            }

            out.write("<div class=\"bubble\">\n")

            // Sender header
            out.write("<div class=\"msg-header\">\n")
            out.write("<span class=\"sender-name\" style=\"color:${sender.color}\">${escapeHtml(sender.name)}</span>\n")
            if (!msg.post_author.isNullOrEmpty() && msg.post_author != sender.name) {
                out.write("<span class=\"post-author\">${escapeHtml(msg.post_author)}</span>\n")
            }
            out.write("</div>\n")

            // Forwarded header
            msg.fwd_from?.let { fwd ->
                val fwdName = resolveForwardSender(fwd, usersMap, chatsMap)
                if (fwdName.isNotEmpty()) {
                    out.write("<div class=\"fwd-header\">")
                    out.write(escapeHtml(LocaleController.formatString(R.string.ExportHtmlForwardedFrom, fwdName)))
                    if (fwd.date > 0) {
                        out.write(" · ")
                        out.write(escapeHtml(fullDateTimeFmt.format(Date(fwd.date * 1000L))))
                    }
                    out.write("</div>\n")
                }
            }

            // Reply preview (lookup in same dialog_id first to avoid collisions on migrated groups)
            val replyHeader = msg.reply_to
            if (replyHeader != null && replyHeader.reply_to_msg_id != 0) {
                val replyKey = "${msg.dialog_id}_${replyHeader.reply_to_msg_id}"
                val targetInExport = messagesByKey[replyKey]
                val repliedMsg = targetInExport ?: msg.replyMessage

                var replyAuthor = repliedMsg?.let { resolveSender(it, usersMap, chatsMap, selfUserId).name }
                var replySnippet = repliedMsg?.let { buildPlainPreview(it) }
                if (!replyHeader.quote_text.isNullOrEmpty()) {
                    replySnippet = replyHeader.quote_text
                }
                if (replyAuthor.isNullOrEmpty()) {
                    replyAuthor = "#${replyHeader.reply_to_msg_id}"
                }

                if (targetInExport != null) {
                    out.write("<a class=\"reply-box\" href=\"#${messageAnchorId(targetInExport)}\">")
                } else {
                    out.write("<div class=\"reply-box\">")
                }
                out.write("<div class=\"reply-author\">")
                out.write(escapeHtml(LocaleController.formatString(R.string.ExportHtmlReplyTo, replyAuthor)))
                out.write("</div>")
                if (!replySnippet.isNullOrEmpty()) {
                    out.write("<div class=\"reply-text\">${escapeHtml(truncate(replySnippet, 140))}</div>")
                }
                out.write(if (targetInExport != null) "</a>\n" else "</div>\n")
            }

            // Media / special attachments
            val mediaDesc = mediaByMessageKey[messageKey(msg)]
            if (mediaDesc != null) {
                writeMediaHtml(out, mediaDesc)
            } else if (msg.media != null) {
                writeSpecialMediaHtml(out, msg.media)
            }

            // Message text / caption
            if (!msg.message.isNullOrEmpty()) {
                out.write("<div class=\"msg-text\">")
                out.write(formatEntitiesToHtml(msg.message, msg.entities))
                out.write("</div>\n")
            }

            // Reactions
            val reactions = msg.reactions?.results
            if (!reactions.isNullOrEmpty()) {
                writeReactionsHtml(out, reactions)
            }

            // Timestamp & edited badge
            out.write("<div class=\"msg-footer\" title=\"${escapeHtml(fullTime)}\">")
            if (msg.edit_date > 0) {
                out.write("<span class=\"edited\">${escapeHtml(LocaleController.getString(R.string.ExportHtmlEdited))}</span> ")
            }
            out.write("<time datetime=\"${escapeHtml(fullTime)}\">${escapeHtml(shortTime)}</time>")
            out.write("</div>\n")

            out.write("</div>\n") // .bubble
            out.write("</div>\n") // .msg-row
        }

        out.write("</main>\n")
        out.write(THEME_TOGGLE_SCRIPT)
        out.write("</body>\n</html>\n")
    }

    @Throws(IOException::class)
    private fun writeMediaHtml(out: BufferedWriter, media: MediaDescriptor) {
        val relPath = media.archiveRelPath
        val hasExportedFile = config.includeMedia && !relPath.isNullOrEmpty()

        if (hasExportedFile && relPath != null) {
            val relUrl = escapeHtml(encodeRelativePathForHref(relPath))
            when (media.kind) {
                MediaKind.PHOTO -> {
                    out.write("<div class=\"media-wrap\"><a href=\"$relUrl\" target=\"_blank\"><img class=\"media-img\" src=\"$relUrl\" loading=\"lazy\" alt=\"Photo\"></a></div>\n")
                    return
                }
                MediaKind.STICKER -> {
                    val extLower = media.extension.lowercase(Locale.US)
                    if (extLower == "webp" || extLower == "png" || extLower == "jpg" || extLower == "jpeg") {
                        out.write("<div class=\"media-wrap\"><img class=\"media-sticker\" src=\"$relUrl\" loading=\"lazy\" alt=\"${escapeHtml(media.stickerEmoji)}\"></div>\n")
                        return
                    } else if (extLower == "webm" || extLower == "mp4") {
                        out.write("<div class=\"media-wrap\"><video class=\"media-sticker\" src=\"$relUrl\" autoplay loop muted playsinline></video></div>\n")
                        return
                    }
                }
                MediaKind.VIDEO, MediaKind.ROUND_VIDEO, MediaKind.GIF -> {
                    val cssClass = if (media.kind == MediaKind.ROUND_VIDEO) "media-round" else "media-video"
                    val gifAttrs = if (media.kind == MediaKind.GIF) " loop muted" else ""
                    out.write("<div class=\"media-wrap\"><video class=\"$cssClass\" src=\"$relUrl\" controls playsinline preload=\"metadata\"$gifAttrs></video></div>\n")
                    return
                }
                MediaKind.VOICE, MediaKind.AUDIO -> {
                    out.write("<div class=\"media-audio-card\">")
                    out.write("<div class=\"media-audio-label\">")
                    out.write(if (media.kind == MediaKind.VOICE) "🎤 " else "🎵 ")
                    val audioLabel = if (media.kind == MediaKind.VOICE) {
                        LocaleController.getString(R.string.ExportHtmlMediaVoice)
                    } else {
                        media.displayName
                    }
                    out.write(escapeHtml(audioLabel))
                    if (media.durationSec > 0) {
                        out.write(" · ${formatDuration(media.durationSec)}")
                    }
                    out.write("</div>")
                    out.write("<audio class=\"media-audio\" src=\"$relUrl\" controls preload=\"none\"></audio>")
                    out.write("</div>\n")
                    return
                }
                MediaKind.DOCUMENT -> Unit
            }

            out.write("<a class=\"media-doc\" href=\"$relUrl\" download>")
            out.write("<span class=\"media-icon\">📎</span>")
            out.write("<span class=\"media-doc-info\"><span class=\"media-doc-name\">${escapeHtml(media.displayName)}</span>")
            if (media.sizeBytes > 0) {
                out.write("<span class=\"media-doc-meta\">${escapeHtml(AndroidUtilities.formatFileSize(media.sizeBytes))}</span>")
            }
            out.write("</span></a>\n")
            return
        }

        // Placeholder badge (without media or unavailable)
        val (icon, label) = when (media.kind) {
            MediaKind.PHOTO -> "📷" to LocaleController.getString(R.string.ExportHtmlMediaPhoto)
            MediaKind.VIDEO -> "🎥" to LocaleController.getString(R.string.ExportHtmlMediaVideo)
            MediaKind.ROUND_VIDEO -> "⭕" to LocaleController.getString(R.string.ExportHtmlMediaRoundVideo)
            MediaKind.VOICE -> "🎤" to LocaleController.getString(R.string.ExportHtmlMediaVoice)
            MediaKind.AUDIO -> "🎵" to "${LocaleController.getString(R.string.ExportHtmlMediaAudio)}: ${media.displayName}"
            MediaKind.GIF -> "🎞️" to LocaleController.getString(R.string.ExportHtmlMediaGif)
            MediaKind.STICKER -> (media.stickerEmoji.ifEmpty { "🙂" }) to LocaleController.getString(R.string.ExportHtmlMediaSticker)
            MediaKind.DOCUMENT -> "📎" to "${LocaleController.getString(R.string.ExportHtmlMediaDocument)}: ${media.displayName}"
        }

        val metaParts = ArrayList<String>(3)
        if (media.durationSec > 0) {
            metaParts.add(formatDuration(media.durationSec))
        }
        if (media.sizeBytes > 0) {
            metaParts.add(AndroidUtilities.formatFileSize(media.sizeBytes))
        }
        metaParts.add(
            if (media.unavailable) {
                LocaleController.getString(R.string.ExportHtmlMediaUnavailable)
            } else {
                LocaleController.getString(R.string.ExportHtmlMediaNotIncluded)
            }
        )

        out.write("<div class=\"media-placeholder\">")
        out.write("<span class=\"media-icon\">${escapeHtml(icon)}</span>")
        out.write("<span class=\"media-doc-info\">")
        out.write("<span class=\"media-doc-name\">${escapeHtml(label)}</span>")
        out.write("<span class=\"media-doc-meta\">${escapeHtml(metaParts.joinToString(" · "))}</span>")
        out.write("</span></div>\n")
    }

    @Throws(IOException::class)
    private fun writeSpecialMediaHtml(out: BufferedWriter, media: TLRPC.MessageMedia) {
        when {
            media is TLRPC.TL_messageMediaWebPage && media.webpage is TLRPC.TL_webPage -> {
                val wp = media.webpage as TLRPC.TL_webPage
                out.write("<div class=\"webpage-card\">")
                if (!wp.site_name.isNullOrEmpty()) {
                    out.write("<div class=\"webpage-site\">${escapeHtml(wp.site_name)}</div>")
                }
                if (!wp.title.isNullOrEmpty()) {
                    val url = wp.url
                    if (!url.isNullOrEmpty() && isSafeHref(url)) {
                        out.write("<a class=\"webpage-title\" href=\"${escapeHtml(url)}\" target=\"_blank\" rel=\"noopener\">${escapeHtml(wp.title)}</a>")
                    } else {
                        out.write("<div class=\"webpage-title\">${escapeHtml(wp.title)}</div>")
                    }
                }
                if (!wp.description.isNullOrEmpty()) {
                    out.write("<div class=\"webpage-desc\">${escapeHtml(truncate(wp.description, 260))}</div>")
                }
                out.write("</div>\n")
            }
            media is TLRPC.TL_messageMediaPoll -> {
                val poll = media.poll ?: return
                out.write("<div class=\"special-card\">")
                out.write("<div class=\"special-title\">📊 ${escapeHtml(LocaleController.getString(R.string.ExportHtmlMediaPoll))}: ")
                out.write(escapeHtml(poll.question?.text ?: ""))
                out.write("</div><ul class=\"poll-options\">")
                val voters = media.results?.results
                poll.answers?.forEach { ans ->
                    val ansText = ans.text?.text ?: ""
                    val votes = voters?.firstOrNull {
                        it.option != null && ans.option != null && it.option.contentEquals(ans.option)
                    }?.voters ?: -1
                    out.write("<li>${escapeHtml(ansText)}")
                    if (votes >= 0) {
                        out.write(" <span class=\"poll-votes\">($votes)</span>")
                    }
                    out.write("</li>")
                }
                out.write("</ul></div>\n")
            }
            media is TLRPC.TL_messageMediaContact -> {
                val name = ContactsController.formatName(media.first_name, media.last_name)
                out.write("<div class=\"special-card\">")
                out.write("<div class=\"special-title\">👤 ${escapeHtml(LocaleController.getString(R.string.ExportHtmlMediaContact))}</div>")
                if (!name.isNullOrEmpty()) {
                    out.write("<div>${escapeHtml(name)}</div>")
                }
                if (!media.phone_number.isNullOrEmpty()) {
                    out.write("<div class=\"media-doc-meta\">${escapeHtml(media.phone_number)}</div>")
                }
                out.write("</div>\n")
            }
            media is TLRPC.TL_messageMediaVenue || media is TLRPC.TL_messageMediaGeo || media is TLRPC.TL_messageMediaGeoLive -> {
                out.write("<div class=\"special-card\">")
                out.write("<div class=\"special-title\">📍 ${escapeHtml(LocaleController.getString(R.string.ExportHtmlMediaLocation))}</div>")
                if (!media.title.isNullOrEmpty()) {
                    out.write("<div>${escapeHtml(media.title)}</div>")
                }
                if (!media.address.isNullOrEmpty()) {
                    out.write("<div class=\"media-doc-meta\">${escapeHtml(media.address)}</div>")
                }
                val geo = media.geo
                if (geo is TLRPC.TL_geoPoint) {
                    val coords = String.format(Locale.US, "%.6f, %.6f", geo.lat, geo._long)
                    val mapUrl = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", geo.lat, geo._long)
                    out.write("<a class=\"webpage-title\" href=\"$mapUrl\" target=\"_blank\" rel=\"noopener\">$coords</a>")
                }
                out.write("</div>\n")
            }
            media is TLRPC.TL_messageMediaDice -> {
                out.write("<div class=\"dice-box\">${escapeHtml(media.emoticon ?: "🎲")} (${media.value})</div>\n")
            }
        }
    }

    @Throws(IOException::class)
    private fun writeReactionsHtml(out: BufferedWriter, results: ArrayList<TLRPC.ReactionCount>) {
        val sb = StringBuilder()
        var any = false
        for (rc in results) {
            val reaction = rc?.reaction ?: continue
            val label = when (reaction) {
                is TLRPC.TL_reactionEmoji -> reaction.emoticon
                is TLRPC.TL_reactionPaid -> "⭐"
                else -> null
            }
            if (label.isNullOrEmpty()) continue
            any = true
            sb.append("<span class=\"reaction\">").append(escapeHtml(label))
            if (rc.count > 1) {
                sb.append(' ').append(rc.count)
            }
            sb.append("</span>")
        }
        if (any) {
            out.write("<div class=\"reactions\">$sb</div>\n")
        }
    }

    private fun resolveSender(
        msg: TLRPC.Message,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>,
        selfUserId: Long
    ): SenderInfo {
        val mc = MessagesController.getInstance(config.currentAccount)
        var senderId = 0L
        var name: String? = null

        val from = msg.from_id
        if (from != null) {
            when {
                from.user_id != 0L -> {
                    senderId = from.user_id
                    val u = usersMap.get(senderId) ?: mc.getUser(senderId)
                    if (u != null) {
                        name = ContactsController.formatName(u.first_name, u.last_name)
                        if (name.isNullOrEmpty() && !u.username.isNullOrEmpty()) {
                            name = "@${u.username}"
                        }
                    }
                }
                from.channel_id != 0L -> {
                    senderId = -from.channel_id
                    val c = chatsMap.get(from.channel_id) ?: mc.getChat(from.channel_id)
                    if (c != null) name = c.title
                }
                from.chat_id != 0L -> {
                    senderId = -from.chat_id
                    val c = chatsMap.get(from.chat_id) ?: mc.getChat(from.chat_id)
                    if (c != null) name = c.title
                }
            }
        }

        if (name.isNullOrEmpty()) {
            when {
                msg.post -> {
                    name = config.chatTitle
                    senderId = config.dialogId
                }
                msg.out -> {
                    senderId = selfUserId
                    val self = usersMap.get(selfUserId) ?: UserConfig.getInstance(config.currentAccount).currentUser
                    if (self != null) {
                        name = ContactsController.formatName(self.first_name, self.last_name)
                    }
                    if (name.isNullOrEmpty()) {
                        name = LocaleController.getString(R.string.ExportHtmlYou)
                    }
                }
                config.dialogId > 0 -> {
                    senderId = config.dialogId
                    val u = usersMap.get(config.dialogId) ?: mc.getUser(config.dialogId)
                    if (u != null) {
                        name = ContactsController.formatName(u.first_name, u.last_name)
                    }
                    if (name.isNullOrEmpty()) name = config.chatTitle
                }
                else -> {
                    name = msg.post_author?.takeIf { it.isNotEmpty() }
                        ?: LocaleController.getString(R.string.ExportHtmlUnknownSender)
                }
            }
        }

        val resolvedName = name?.takeIf { it.isNotEmpty() }
            ?: LocaleController.getString(R.string.ExportHtmlUnknownSender)

        val hashSeed = if (senderId != 0L) senderId else resolvedName.hashCode().toLong()
        val colorIdx = (abs(hashSeed % SENDER_COLORS.size)).toInt()
        return SenderInfo(resolvedName, extractInitials(resolvedName), SENDER_COLORS[colorIdx])
    }

    private fun resolveForwardSender(
        fwd: TLRPC.MessageFwdHeader,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): String {
        if (!fwd.from_name.isNullOrEmpty()) {
            return fwd.from_name
        }
        val mc = MessagesController.getInstance(config.currentAccount)
        val peer = fwd.from_id ?: fwd.saved_from_peer ?: return ""
        return when {
            peer.user_id != 0L -> {
                val u = usersMap.get(peer.user_id) ?: mc.getUser(peer.user_id)
                if (u != null) ContactsController.formatName(u.first_name, u.last_name) ?: "" else ""
            }
            peer.channel_id != 0L -> {
                val c = chatsMap.get(peer.channel_id) ?: mc.getChat(peer.channel_id)
                c?.title ?: ""
            }
            peer.chat_id != 0L -> {
                val c = chatsMap.get(peer.chat_id) ?: mc.getChat(peer.chat_id)
                c?.title ?: ""
            }
            else -> ""
        }
    }

    private fun formatServiceMessage(
        msg: TLRPC.TL_messageService,
        usersMap: LongSparseArray<TLRPC.User>,
        chatsMap: LongSparseArray<TLRPC.Chat>
    ): String {
        val selfUserId = UserConfig.getInstance(config.currentAccount).clientUserId
        val actor = resolveSender(msg, usersMap, chatsMap, selfUserId).name
        val action = msg.action ?: return LocaleController.getString(R.string.ExportHtmlServiceMessage)
        return when {
            action is TLRPC.TL_messageActionPinMessage ->
                "$actor: ${LocaleController.getString(R.string.ExportHtmlPinnedMessage)}"
            action is TLRPC.TL_messageActionPhoneCall -> {
                val label = LocaleController.getString(R.string.ExportHtmlCall)
                if (action.duration > 0) "$actor · $label (${formatDuration(action.duration)})" else "$actor · $label"
            }
            action is TLRPC.TL_messageActionChatEditTitle && !action.title.isNullOrEmpty() ->
                "$actor → «${action.title}»"
            action is TLRPC.TL_messageActionChatCreate && !action.title.isNullOrEmpty() ->
                "$actor · «${action.title}»"
            action is TLRPC.TL_messageActionChannelCreate && !action.title.isNullOrEmpty() ->
                action.title
            action is TLRPC.TL_messageActionChatAddUser ->
                "$actor +${action.users?.size ?: 1}"
            action is TLRPC.TL_messageActionChatDeleteUser ->
                "$actor —"
            else ->
                "$actor · ${LocaleController.getString(R.string.ExportHtmlServiceMessage)}"
        }
    }

    private fun buildPlainPreview(msg: TLRPC.Message): String {
        if (!msg.message.isNullOrEmpty()) {
            return msg.message.replace('\n', ' ')
        }
        val desc = inspectMedia(msg, messageKey(msg)) ?: return ""
        return when (desc.kind) {
            MediaKind.PHOTO -> "[${LocaleController.getString(R.string.ExportHtmlMediaPhoto)}]"
            MediaKind.VIDEO -> "[${LocaleController.getString(R.string.ExportHtmlMediaVideo)}]"
            MediaKind.ROUND_VIDEO -> "[${LocaleController.getString(R.string.ExportHtmlMediaRoundVideo)}]"
            MediaKind.VOICE -> "[${LocaleController.getString(R.string.ExportHtmlMediaVoice)}]"
            MediaKind.AUDIO -> "[${LocaleController.getString(R.string.ExportHtmlMediaAudio)}]"
            MediaKind.GIF -> "[GIF]"
            MediaKind.STICKER -> "[${LocaleController.getString(R.string.ExportHtmlMediaSticker)}]"
            MediaKind.DOCUMENT -> "[${desc.displayName}]"
        }
    }

    private fun buildOutputFileName(ext: String): String {
        var safeTitle = sanitizeFilename(config.chatTitle)
        if (safeTitle.isEmpty()) {
            safeTitle = "chat_${abs(config.dialogId)}"
        }
        if (safeTitle.length > 36) {
            safeTitle = safeTitle.substring(0, 36)
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rangePart = if (config.startDateSec > 0 && config.endDateSec > 0) {
            val s = fmt.format(Date(config.startDateSec * 1000L))
            val e = fmt.format(Date(config.endDateSec * 1000L))
            if (s == e) s else "${s}_$e"
        } else {
            fmt.format(Date())
        }
        return "export_${safeTitle}_$rangePart.$ext"
    }

    private fun messageAnchorId(msg: TLRPC.Message): String {
        return if (config.mergeDialogId != 0L) {
            "msg-${abs(msg.dialog_id)}-${msg.id}"
        } else {
            "msg-${msg.id}"
        }
    }

    companion object {
        const val STAGE_MESSAGES = 0
        const val STAGE_MEDIA = 1
        const val STAGE_BUILDING = 2
        const val STAGE_SAVING = 3

        private const val MEDIA_DOWNLOAD_TIMEOUT_SECONDS = 120

        private val SENDER_COLORS = arrayOf(
            "#e17076", "#eda86c", "#a695e7", "#7bc862",
            "#6ec9cb", "#65aadd", "#ee7aae", "#f08a5d"
        )

        private fun isValidFile(file: File?): Boolean {
            return file != null && file.exists() && file.isFile && file.length() > 0L
        }

        private fun messageKey(m: TLRPC.Message): String = "${m.dialog_id}_${m.id}"

        /**
         * Converts Telegram message text and MessageEntities into clean, XSS-safe HTML.
         * Keeps continuous block-level runs (<blockquote>, <pre>) unbroken even when inline
         * entities (bold, links, etc.) occur inside them.
         */
        internal fun formatEntitiesToHtml(text: String?, entities: ArrayList<TLRPC.MessageEntity>?): String {
            if (text.isNullOrEmpty()) return ""
            val len = text.length
            if (entities.isNullOrEmpty()) return escapeHtmlWithBreaks(text)

            val boundarySet = HashSet<Int>()
            boundarySet.add(0)
            boundarySet.add(len)
            val validEntities = ArrayList<TLRPC.MessageEntity>(entities.size)
            for (e in entities) {
                if (e == null || e.length <= 0) continue
                val start = e.offset.coerceIn(0, len)
                val end = (e.offset + e.length).coerceIn(start, len)
                if (start < end) {
                    validEntities.add(e)
                    boundarySet.add(start)
                    boundarySet.add(end)
                }
            }
            if (validEntities.isEmpty()) return escapeHtmlWithBreaks(text)

            val boundaries = boundarySet.sorted()
            val sb = StringBuilder(len + 64)
            var inBlockquote = false
            var inPre = false

            for (i in 0 until boundaries.size - 1) {
                val segStart = boundaries[i]
                val segEnd = boundaries[i + 1]
                if (segStart >= segEnd) continue

                val segment = text.substring(segStart, segEnd)
                var bold = false
                var italic = false
                var underline = false
                var strike = false
                var code = false
                var pre = false
                var spoiler = false
                var blockquote = false
                var mentionOrTag = false
                var linkHref: String? = null

                for (e in validEntities) {
                    val es = e.offset.coerceIn(0, len)
                    val ee = (e.offset + e.length).coerceIn(es, len)
                    if (es <= segStart && ee >= segEnd) {
                        when (e) {
                            is TLRPC.TL_messageEntityBold -> bold = true
                            is TLRPC.TL_messageEntityItalic -> italic = true
                            is TLRPC.TL_messageEntityUnderline -> underline = true
                            is TLRPC.TL_messageEntityStrike -> strike = true
                            is TLRPC.TL_messageEntityCode -> code = true
                            is TLRPC.TL_messageEntityPre -> pre = true
                            is TLRPC.TL_messageEntitySpoiler -> spoiler = true
                            is TLRPC.TL_messageEntityBlockquote -> blockquote = true
                            is TLRPC.TL_messageEntityTextUrl -> {
                                if (!e.url.isNullOrEmpty()) linkHref = e.url
                            }
                            is TLRPC.TL_messageEntityUrl -> {
                                var fullUrl = text.substring(es, ee)
                                if (!fullUrl.startsWith("http://", ignoreCase = true) &&
                                    !fullUrl.startsWith("https://", ignoreCase = true)
                                ) {
                                    fullUrl = "https://$fullUrl"
                                }
                                linkHref = fullUrl
                            }
                            is TLRPC.TL_messageEntityEmail -> {
                                linkHref = "mailto:${text.substring(es, ee)}"
                            }
                            is TLRPC.TL_messageEntityPhone -> {
                                linkHref = "tel:${text.substring(es, ee).replace("\\s+".toRegex(), "")}"
                            }
                            is TLRPC.TL_messageEntityMention,
                            is TLRPC.TL_messageEntityMentionName,
                            is TLRPC.TL_messageEntityHashtag,
                            is TLRPC.TL_messageEntityCashtag,
                            is TLRPC.TL_messageEntityBotCommand -> {
                                mentionOrTag = true
                            }
                        }
                    }
                }

                // Transition block wrappers cleanly across segment boundaries without misnesting
                if (inPre && (!pre || (inBlockquote && !blockquote))) {
                    sb.append("</code></pre>")
                    inPre = false
                }
                if (inBlockquote && !blockquote) {
                    sb.append("</blockquote>")
                    inBlockquote = false
                }
                if (blockquote && !inBlockquote) {
                    if (inPre) {
                        sb.append("</code></pre>")
                        inPre = false
                    }
                    sb.append("<blockquote>")
                    inBlockquote = true
                }
                if (pre && !inPre) {
                    sb.append("<pre><code>")
                    inPre = true
                }

                var content = if (inPre) escapeHtml(segment) else escapeHtmlWithBreaks(segment)
                if (!inPre) {
                    if (code) content = "<code>$content</code>"
                    if (bold) content = "<strong>$content</strong>"
                    if (italic) content = "<em>$content</em>"
                    if (underline) content = "<u>$content</u>"
                    if (strike) content = "<s>$content</s>"
                    if (spoiler) content = "<span class=\"spoiler\" onclick=\"this.classList.toggle('revealed')\">$content</span>"
                    if (linkHref != null && isSafeHref(linkHref)) {
                        content = "<a href=\"${escapeHtml(linkHref)}\" target=\"_blank\" rel=\"noopener\">$content</a>"
                    } else if (mentionOrTag) {
                        content = "<span class=\"entity-tag\">$content</span>"
                    }
                }
                sb.append(content)
            }

            if (inPre) sb.append("</code></pre>")
            if (inBlockquote) sb.append("</blockquote>")
            return sb.toString()
        }

        private fun isSafeHref(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.US)
            return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("tel:") ||
                lower.startsWith("tg://")
        }

        private fun encodeRelativePathForHref(relPath: String): String {
            return relPath.split('/').joinToString("/") { segment ->
                try {
                    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
                } catch (_: Throwable) {
                    segment
                }
            }
        }

        private fun escapeHtml(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun escapeHtmlWithBreaks(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    '\n' -> sb.append("<br>")
                    '\r' -> Unit
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun extractInitials(name: String?): String {
            if (name.isNullOrBlank()) return "?"
            val parts = name.trim().split("\\s+".toRegex())
            val sb = StringBuilder()
            for (part in parts) {
                if (part.isNotEmpty()) {
                    val cp = part.codePointAt(0)
                    sb.appendCodePoint(Character.toUpperCase(cp))
                    if (sb.codePointCount(0, sb.length) >= 2) break
                }
            }
            return if (sb.isNotEmpty()) sb.toString() else "?"
        }

        private fun truncate(text: String?, maxLen: Int): String {
            if (text == null) return ""
            return if (text.length <= maxLen) text else text.substring(0, maxLen - 1) + "…"
        }

        private fun formatDuration(seconds: Int): String {
            val safe = seconds.coerceAtLeast(0)
            val h = safe / 3600
            val m = (safe % 3600) / 60
            val s = safe % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        private fun guessExtension(doc: TLRPC.Document?, fileName: String, kind: MediaKind): String {
            if (fileName.isNotEmpty()) {
                val dot = fileName.lastIndexOf('.')
                if (dot > 0 && dot < fileName.length - 1) {
                    val ext = fileName.substring(dot + 1)
                        .lowercase(Locale.US)
                        .replace("[^a-z0-9]".toRegex(), "")
                    if (ext.isNotEmpty() && ext.length <= 8) {
                        return ext
                    }
                }
            }
            val mime = doc?.mime_type?.lowercase(Locale.US) ?: ""
            return when {
                "mp4" in mime -> "mp4"
                "webm" in mime -> "webm"
                "ogg" in mime || "opus" in mime -> "ogg"
                "mpeg" in mime || "mp3" in mime -> "mp3"
                "m4a" in mime || "aac" in mime -> "m4a"
                "webp" in mime -> "webp"
                "png" in mime -> "png"
                "jpeg" in mime || "jpg" in mime -> "jpg"
                "gif" in mime -> "gif"
                "pdf" in mime -> "pdf"
                "tgs" in mime || "tgsticker" in mime -> "tgs"
                kind == MediaKind.VIDEO || kind == MediaKind.ROUND_VIDEO || kind == MediaKind.GIF -> "mp4"
                kind == MediaKind.VOICE -> "ogg"
                kind == MediaKind.AUDIO -> "mp3"
                kind == MediaKind.STICKER -> "webp"
                else -> "bin"
            }
        }

        private fun guessMimeType(ext: String, kind: MediaKind): String = when (kind) {
            MediaKind.PHOTO -> "image/jpeg"
            MediaKind.VIDEO, MediaKind.ROUND_VIDEO, MediaKind.GIF -> "video/mp4"
            MediaKind.VOICE -> "audio/ogg"
            MediaKind.AUDIO -> "audio/mpeg"
            MediaKind.STICKER -> if (ext == "webp") "image/webp" else "application/octet-stream"
            MediaKind.DOCUMENT -> "application/octet-stream"
        }

        private fun makeUniqueArchiveFileName(desc: MediaDescriptor, usedNames: MutableSet<String>): String {
            var safeBase = sanitizeFilename(desc.displayName)
            if (safeBase.isEmpty()) {
                safeBase = "${desc.kind.name.lowercase(Locale.US)}_${desc.message.id}.${desc.extension}"
            }
            if (!safeBase.contains('.') && desc.extension.isNotEmpty()) {
                safeBase = "$safeBase.${desc.extension}"
            }
            var candidate = "${desc.message.id}_$safeBase"
            if (candidate.length > 80) {
                val extPart = ".${desc.extension}"
                candidate = candidate.substring(0, (80 - extPart.length).coerceAtLeast(10)) + extPart
            }
            var suffix = 1
            var unique = candidate
            while (!usedNames.add(unique.lowercase(Locale.US))) {
                val dot = candidate.lastIndexOf('.')
                unique = if (dot > 0) {
                    "${candidate.substring(0, dot)}_$suffix${candidate.substring(dot)}"
                } else {
                    "${candidate}_$suffix"
                }
                suffix++
            }
            return unique
        }

        private fun sanitizeFilename(input: String?): String {
            if (input.isNullOrBlank()) return ""
            return input.trim()
                .replace("[\\\\/:*?\"<>|#%\\s]+".toRegex(), "_")
                .replace("_+".toRegex(), "_")
                .trim('_')
        }

        private fun getExportCacheDir(): File {
            val dir = File(ApplicationLoader.applicationContext.filesDir, "cache/chat_exports")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        private fun cleanupOldExports(dir: File) {
            try {
                val files = dir.listFiles() ?: return
                if (files.size <= 5) return
                files.sortByDescending { it.lastModified() }
                for (i in 5 until files.size) {
                    files[i].delete()
                }
            } catch (_: Throwable) {
            }
        }

        private fun saveCopyToDownloads(sourceFile: File, fileName: String, mimeType: String): Uri? {
            val context = ApplicationLoader.applicationContext ?: return null
            if (!sourceFile.exists()) return null
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}${File.separator}easygram${File.separator}"
                        )
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values
                    ) ?: return null
                    try {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Unable to open Downloads output stream")
                        out.use { outputStream ->
                            FileInputStream(sourceFile).use { inputStream ->
                                inputStream.copyTo(outputStream, bufferSize = 65536)
                            }
                            outputStream.flush()
                        }
                        uri
                    } catch (e: Throwable) {
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (_: Throwable) {
                        }
                        throw e
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "easygram")
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    var dest = File(dir, fileName)
                    var idx = 1
                    while (dest.exists() && idx < 50) {
                        val dot = fileName.lastIndexOf('.')
                        val alt = if (dot > 0) {
                            "${fileName.substring(0, dot)}($idx)${fileName.substring(dot)}"
                        } else {
                            "$fileName($idx)"
                        }
                        dest = File(dir, alt)
                        idx++
                    }
                    FileInputStream(sourceFile).use { inputStream ->
                        FileOutputStream(dest).use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 65536)
                            outputStream.flush()
                        }
                    }
                    Uri.fromFile(dest)
                }
            } catch (t: Throwable) {
                FileLog.e(t)
                null
            }
        }

        private const val THEME_TOGGLE_SCRIPT = """<script>
function toggleTheme() {
  var root = document.documentElement;
  var current = root.getAttribute('data-theme');
  if (!current) {
    current = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  root.setAttribute('data-theme', current === 'dark' ? 'light' : 'dark');
}
</script>
"""

        private const val CSS_STYLES = """:root {
  --bg: #eef2f5;
  --header-bg: rgba(255,255,255,0.92);
  --header-border: rgba(0,0,0,0.08);
  --text: #1c2228;
  --muted: #6c7883;
  --bubble-in: #ffffff;
  --bubble-out: #e3f3d8;
  --bubble-shadow: 0 1px 2px rgba(0,0,0,0.07);
  --divider-bg: rgba(0,0,0,0.14);
  --divider-text: #ffffff;
  --reply-bg: rgba(51,144,236,0.09);
  --accent: #3390ec;
  --code-bg: rgba(0,0,0,0.06);
  --card-bg: rgba(0,0,0,0.04);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) {
    --bg: #0e1621;
    --header-bg: rgba(23,33,43,0.94);
    --header-border: rgba(255,255,255,0.08);
    --text: #f5f5f5;
    --muted: #8b9bab;
    --bubble-in: #182533;
    --bubble-out: #2b5278;
    --bubble-shadow: 0 1px 2px rgba(0,0,0,0.28);
    --divider-bg: rgba(255,255,255,0.14);
    --divider-text: #eef2f5;
    --reply-bg: rgba(113,186,255,0.14);
    --accent: #71baff;
    --code-bg: rgba(255,255,255,0.08);
    --card-bg: rgba(255,255,255,0.06);
  }
}
:root[data-theme='dark'] {
  --bg: #0e1621;
  --header-bg: rgba(23,33,43,0.94);
  --header-border: rgba(255,255,255,0.08);
  --text: #f5f5f5;
  --muted: #8b9bab;
  --bubble-in: #182533;
  --bubble-out: #2b5278;
  --bubble-shadow: 0 1px 2px rgba(0,0,0,0.28);
  --divider-bg: rgba(255,255,255,0.14);
  --divider-text: #eef2f5;
  --reply-bg: rgba(113,186,255,0.14);
  --accent: #71baff;
  --code-bg: rgba(255,255,255,0.08);
  --card-bg: rgba(255,255,255,0.06);
}
* { box-sizing: border-box; }
body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: var(--bg); color: var(--text); line-height: 1.45; }
.chat-header { position: sticky; top: 0; z-index: 20; background: var(--header-bg); backdrop-filter: blur(12px); border-bottom: 1px solid var(--header-border); padding: 12px 16px; }
.chat-header-inner { max-width: 820px; margin: 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.chat-title { margin: 0; font-size: 18px; font-weight: 700; }
.chat-subtitle { font-size: 13px; color: var(--muted); margin-top: 2px; }
.chat-meta { display: flex; flex-wrap: wrap; gap: 6px; font-size: 12px; color: var(--muted); margin-top: 4px; }
.theme-toggle { border: 1px solid var(--header-border); background: var(--card-bg); color: var(--text); border-radius: 18px; padding: 6px 12px; font-size: 13px; cursor: pointer; flex-shrink: 0; }
.chat-container { max-width: 820px; margin: 0 auto; padding: 14px 12px 40px; display: flex; flex-direction: column; gap: 6px; }
.date-divider { display: flex; justify-content: center; margin: 14px 0 8px; }
.date-divider span { background: var(--divider-bg); color: var(--divider-text); font-size: 12px; font-weight: 600; padding: 4px 12px; border-radius: 14px; }
.service-msg { align-self: center; background: var(--divider-bg); color: var(--divider-text); font-size: 12px; padding: 4px 12px; border-radius: 12px; margin: 4px 0; text-align: center; max-width: 90%; }
.service-time { opacity: 0.8; margin-left: 4px; }
.msg-row { display: flex; align-items: flex-end; gap: 8px; max-width: 100%; }
.msg-row.out { justify-content: flex-end; }
.msg-row.in { justify-content: flex-start; }
.avatar { width: 34px; height: 34px; border-radius: 50%; color: #fff; font-weight: 700; font-size: 13px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; user-select: none; }
.bubble { max-width: min(580px, 86%); background: var(--bubble-in); border-radius: 16px; padding: 8px 12px 6px; box-shadow: var(--bubble-shadow); overflow-wrap: anywhere; }
.msg-row.out .bubble { background: var(--bubble-out); border-bottom-right-radius: 5px; }
.msg-row.in .bubble { border-bottom-left-radius: 5px; }
.msg-header { display: flex; align-items: baseline; gap: 6px; margin-bottom: 3px; }
.sender-name { font-size: 13px; font-weight: 700; }
.post-author { font-size: 11px; color: var(--muted); }
.fwd-header { font-size: 12px; color: var(--accent); margin-bottom: 4px; font-weight: 500; }
.reply-box { display: block; text-decoration: none; color: inherit; background: var(--reply-bg); border-left: 3px solid var(--accent); border-radius: 6px; padding: 4px 8px; margin-bottom: 6px; }
.reply-author { font-size: 12px; font-weight: 600; color: var(--accent); }
.reply-text { font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.msg-text { font-size: 15px; white-space: pre-wrap; }
.msg-text a { color: var(--accent); text-decoration: none; }
.msg-text a:hover { text-decoration: underline; }
.entity-tag { color: var(--accent); }
.msg-text code { background: var(--code-bg); padding: 1px 4px; border-radius: 4px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.9em; }
.msg-text pre { background: var(--code-bg); padding: 8px 10px; border-radius: 8px; overflow-x: auto; margin: 6px 0; }
.msg-text pre code { background: none; padding: 0; }
.msg-text blockquote { margin: 4px 0; padding: 4px 10px; border-left: 3px solid var(--accent); background: var(--card-bg); border-radius: 4px; }
.spoiler { background: var(--muted); color: transparent; border-radius: 4px; padding: 0 3px; cursor: pointer; transition: all 0.2s; }
.spoiler.revealed { background: var(--code-bg); color: inherit; }
.media-wrap { margin: 4px 0 6px; }
.media-img { max-width: 100%; max-height: 420px; border-radius: 10px; display: block; object-fit: contain; }
.media-video { max-width: 100%; max-height: 420px; border-radius: 10px; display: block; background: #000; }
.media-round { width: 220px; height: 220px; border-radius: 50%; object-fit: cover; display: block; background: #000; }
.media-sticker { max-width: 180px; max-height: 180px; display: block; }
.media-audio-card { background: var(--card-bg); border-radius: 10px; padding: 8px 10px; margin: 4px 0 6px; }
.media-audio-label { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.media-audio { width: 100%; max-width: 320px; height: 36px; }
.media-doc, .media-placeholder { display: flex; align-items: center; gap: 10px; background: var(--card-bg); border-radius: 10px; padding: 8px 10px; margin: 4px 0 6px; text-decoration: none; color: inherit; }
.media-icon { font-size: 20px; flex-shrink: 0; }
.media-doc-info { display: flex; flex-direction: column; min-width: 0; }
.media-doc-name { font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; }
.media-doc-meta { font-size: 12px; color: var(--muted); }
.webpage-card, .special-card { background: var(--card-bg); border-left: 3px solid var(--accent); border-radius: 8px; padding: 7px 10px; margin: 4px 0 6px; }
.webpage-site { font-size: 12px; font-weight: 600; color: var(--accent); }
.webpage-title { display: block; font-size: 13px; font-weight: 600; color: var(--accent); text-decoration: none; margin-top: 2px; }
.webpage-desc { font-size: 12px; color: var(--muted); margin-top: 3px; }
.special-title { font-size: 13px; font-weight: 700; margin-bottom: 4px; }
.poll-options { margin: 4px 0 0 18px; padding: 0; font-size: 13px; }
.poll-votes { color: var(--muted); font-size: 12px; }
.dice-box { font-size: 26px; margin: 2px 0 4px; }
.reactions { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 6px; }
.reaction { font-size: 12px; background: var(--card-bg); padding: 2px 8px; border-radius: 12px; }
.msg-footer { display: flex; justify-content: flex-end; align-items: center; gap: 4px; margin-top: 4px; font-size: 11px; color: var(--muted); }
.edited { font-style: italic; }
"""
    }
}
