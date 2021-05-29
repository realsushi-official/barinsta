package awais.instagrabber.managers

import android.content.ContentResolver
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import awais.instagrabber.managers.ThreadManager.Companion.getInstance
import awais.instagrabber.models.Resource
import awais.instagrabber.models.Resource.Companion.error
import awais.instagrabber.models.Resource.Companion.loading
import awais.instagrabber.models.Resource.Companion.success
import awais.instagrabber.repositories.requests.directmessages.ThreadIdOrUserIds.Companion.of
import awais.instagrabber.repositories.responses.User
import awais.instagrabber.repositories.responses.directmessages.DirectThread
import awais.instagrabber.repositories.responses.directmessages.DirectThreadBroadcastResponse
import awais.instagrabber.repositories.responses.directmessages.RankedRecipient
import awais.instagrabber.utils.Constants
import awais.instagrabber.utils.Utils
import awais.instagrabber.utils.getCsrfTokenFromCookie
import awais.instagrabber.utils.getUserIdFromCookie
import awais.instagrabber.webservices.DirectMessagesService
import awais.instagrabber.webservices.DirectMessagesService.Companion.getInstance
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.*

object DirectMessagesManager {
    val inboxManager: InboxManager by lazy { InboxManager.getInstance(false) }
    val pendingInboxManager: InboxManager by lazy { InboxManager.getInstance(true) }

    private val TAG = DirectMessagesManager::class.java.simpleName
    private val viewerId: Long
    private val deviceUuid: String
    private val csrfToken: String
    private val service: DirectMessagesService

    fun moveThreadFromPending(threadId: String) {
        val pendingThreads = pendingInboxManager.threads.value ?: return
        val index = pendingThreads.indexOfFirst { it.threadId == threadId }
        if (index < 0) return
        val thread = pendingThreads[index]
        val threadFirstDirectItem = thread.firstDirectItem ?: return
        val threads = inboxManager.threads.value
        var insertIndex = 0
        if (threads != null) {
            for (tempThread in threads) {
                val firstDirectItem = tempThread.firstDirectItem ?: continue
                val timestamp = firstDirectItem.getTimestamp()
                if (timestamp < threadFirstDirectItem.getTimestamp()) {
                    break
                }
                insertIndex++
            }
        }
        thread.pending = false
        inboxManager.addThread(thread, insertIndex)
        pendingInboxManager.removeThread(threadId)
        val currentTotal = inboxManager.getPendingRequestsTotal().value ?: return
        inboxManager.setPendingRequestsTotal(currentTotal - 1)
    }

    fun getThreadManager(
        threadId: String,
        pending: Boolean,
        currentUser: User,
        contentResolver: ContentResolver,
    ): ThreadManager {
        return getInstance(threadId, pending, currentUser, contentResolver, viewerId, csrfToken, deviceUuid)
    }

    fun createThread(
        userPk: Long,
        callback: ((DirectThread) -> Unit)?,
    ) {
        val createThreadRequest = service.createThread(listOf(userPk), null)
        createThreadRequest.enqueue(object : Callback<DirectThread?> {
            override fun onResponse(call: Call<DirectThread?>, response: Response<DirectThread?>) {
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()
                    if (errorBody != null) {
                        try {
                            val string = errorBody.string()
                            val msg = String.format(Locale.US,
                                "onResponse: url: %s, responseCode: %d, errorBody: %s",
                                call.request().url().toString(),
                                response.code(),
                                string)
                            Log.e(TAG, msg)
                        } catch (e: IOException) {
                            Log.e(TAG, "onResponse: ", e)
                        }
                        return
                    }
                    Log.e(TAG, "onResponse: request was not successful and response error body was null")
                    return
                }
                val thread = response.body()
                if (thread == null) {
                    Log.e(TAG, "onResponse: thread is null")
                    return
                }
                callback?.invoke(thread)
            }

            override fun onFailure(call: Call<DirectThread?>, t: Throwable) {}
        })
    }

    fun sendMedia(recipients: Set<RankedRecipient>, mediaId: String) {
        val resultsCount = intArrayOf(0)
        val callback: () -> Unit = {
            resultsCount[0]++
            if (resultsCount[0] == recipients.size) {
                inboxManager.refresh()
            }
        }
        for (recipient in recipients) {
            sendMedia(recipient, mediaId, false, callback)
        }
    }

    fun sendMedia(recipient: RankedRecipient, mediaId: String) {
        sendMedia(recipient, mediaId, true, null)
    }

    private fun sendMedia(
        recipient: RankedRecipient,
        mediaId: String,
        refreshInbox: Boolean,
        callback: (() -> Unit)?,
    ) {
        if (recipient.thread == null && recipient.user != null) {
            // create thread and forward
            createThread(recipient.user.pk) { (threadId) ->
                val threadIdTemp = threadId ?: return@createThread
                sendMedia(threadIdTemp, mediaId) {
                    if (refreshInbox) {
                        inboxManager.refresh()
                    }
                    callback?.invoke()
                }
            }
        }
        if (recipient.thread == null) return
        // just forward
        val thread = recipient.thread
        val threadId = thread.threadId ?: return
        sendMedia(threadId, mediaId) {
            if (refreshInbox) {
                inboxManager.refresh()
            }
            callback?.invoke()
        }
    }

    private fun sendMedia(
        threadId: String,
        mediaId: String,
        callback: (() -> Unit)?,
    ): LiveData<Resource<Any?>> {
        val data = MutableLiveData<Resource<Any?>>()
        data.postValue(loading(null))
        val request = service.broadcastMediaShare(
            UUID.randomUUID().toString(),
            of(threadId),
            mediaId
        )
        request.enqueue(object : Callback<DirectThreadBroadcastResponse?> {
            override fun onResponse(
                call: Call<DirectThreadBroadcastResponse?>,
                response: Response<DirectThreadBroadcastResponse?>,
            ) {
                if (response.isSuccessful) {
                    data.postValue(success(Any()))
                    callback?.invoke()
                    return
                }
                val errorBody = response.errorBody()
                if (errorBody != null) {
                    try {
                        val string = errorBody.string()
                        val msg = String.format(Locale.US,
                            "onResponse: url: %s, responseCode: %d, errorBody: %s",
                            call.request().url().toString(),
                            response.code(),
                            string)
                        Log.e(TAG, msg)
                        data.postValue(error(msg, null))
                    } catch (e: IOException) {
                        Log.e(TAG, "onResponse: ", e)
                        data.postValue(error(e.message, null))
                    }
                    callback?.invoke()
                    return
                }
                val msg = "onResponse: request was not successful and response error body was null"
                Log.e(TAG, msg)
                data.postValue(error(msg, null))
                callback?.invoke()
            }

            override fun onFailure(call: Call<DirectThreadBroadcastResponse?>, t: Throwable) {
                Log.e(TAG, "onFailure: ", t)
                data.postValue(error(t.message, null))
                callback?.invoke()
            }
        })
        return data
    }

    init {
        val cookie = Utils.settingsHelper.getString(Constants.COOKIE)
        viewerId = getUserIdFromCookie(cookie)
        deviceUuid = Utils.settingsHelper.getString(Constants.DEVICE_UUID)
        val csrfToken = getCsrfTokenFromCookie(cookie)
        require(!csrfToken.isNullOrBlank() && viewerId != 0L && deviceUuid.isNotBlank()) { "User is not logged in!" }
        this.csrfToken = csrfToken
        service = getInstance(csrfToken, viewerId, deviceUuid)
    }
}