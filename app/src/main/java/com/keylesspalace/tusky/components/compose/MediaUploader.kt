package com.keylesspalace.tusky.components.compose

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.components.compose.ComposeActivity.Companion.STATUS_IMAGE_PIXEL_SIZE_LIMIT
import com.keylesspalace.tusky.components.compose.ComposeActivity.Companion.STATUS_IMAGE_SIZE_LIMIT
import com.keylesspalace.tusky.components.compose.ComposeActivity.QueuedMedia
import com.keylesspalace.tusky.entity.Attachment
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.network.ProgressRequestBody
import com.keylesspalace.tusky.util.DownsizeImageTask
import com.keylesspalace.tusky.util.getImageSquarePixels
import com.keylesspalace.tusky.util.randomAlphanumericString
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import java.io.File
import java.util.*

sealed class UploadEvent {

    data class ProgressEvent(val percentage: Int) : UploadEvent()
    data class FinishedEvent(val attachment: Attachment) : UploadEvent()
}

interface MediaUploader {
    fun uploadMedia(media: QueuedMedia): Observable<UploadEvent>
}

class MediaUploaderImpl(
        private val context: Context,
        private val mastodonApi: MastodonApi
) : MediaUploader {
    override fun uploadMedia(media: QueuedMedia): Observable<UploadEvent> {
        return Observable
                .fromCallable {
                    if (shouldResizeMedia(media)) {
                        downsize(media)
                    }
                    media
                }
                .switchMap { upload(it) }
                .subscribeOn(Schedulers.io())
    }

    private val contentResolver = context.contentResolver

    private fun upload(media: QueuedMedia): Observable<UploadEvent> {
        return Observable.create { emitter ->
            var mimeType = contentResolver.getType(media.uri)
            val map = MimeTypeMap.getSingleton()
            val fileExtension = map.getExtensionFromMimeType(mimeType)
            val filename = String.format("%s_%s_%s.%s",
                    context.getString(R.string.app_name),
                    Date().time.toString(),
                    randomAlphanumericString(10),
                    fileExtension)

            val stream = contentResolver.openInputStream(media.uri)

            if (mimeType == null) mimeType = "multipart/form-data"


            var lastProgress = -1
            val fileBody = ProgressRequestBody(stream, media.mediaSize,
                    mimeType.toMediaTypeOrNull()) { percentage ->
                if (percentage != lastProgress) {
                    emitter.onNext(UploadEvent.ProgressEvent(percentage))
                }
                lastProgress = percentage
            }

            val body = MultipartBody.Part.createFormData("file", filename, fileBody)

            mastodonApi.uploadMedia(body)
                    .subscribe({ attachment ->
                        emitter.onNext(UploadEvent.FinishedEvent(attachment))
                        emitter.onComplete()
                    }, { e ->
                        emitter.onError(e)
                    })
        }
    }

    private fun downsize(media: QueuedMedia): QueuedMedia {
        val file = createNewImageFile()
        DownsizeImageTask.resize(arrayOf(media.uri),
                STATUS_IMAGE_SIZE_LIMIT, context.contentResolver, file)
        return media.copy(uri = file.toUri(), mediaSize = file.length())
    }

    private fun shouldResizeMedia(media: QueuedMedia): Boolean {
        return media.type == QueuedMedia.Type.IMAGE
                && (media.mediaSize > STATUS_IMAGE_SIZE_LIMIT
                || getImageSquarePixels(context.contentResolver, media.uri) > STATUS_IMAGE_PIXEL_SIZE_LIMIT)
    }


    private fun createNewImageFile(): File {
        // Create an image file name
        val randomId = randomAlphanumericString(12)
        val imageFileName = "Tusky_${randomId}_"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
                imageFileName, /* prefix */
                ".jpg", /* suffix */
                storageDir      /* directory */
        )
    }
}