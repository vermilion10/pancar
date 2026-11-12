package com.github.vermilion10.pancar.youtube

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AuthOutcome {
    data class Token(val accessToken: String) : AuthOutcome
    data class NeedsConsent(val intent: PendingIntent) : AuthOutcome
}

// Google Identity Services authorization; needs an Android OAuth client for this package + signing SHA-1.
object YouTubeAuth {
    private const val SCOPE_YOUTUBE = "https://www.googleapis.com/auth/youtube"

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_YOUTUBE)))
        .build()

    suspend fun authorize(context: Context): AuthOutcome {
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request).await()
        } catch (e: ApiException) {
            throw YouTubeException(describe(e))
        }
        return result.toOutcome()
    }

    fun tokenFromConsent(context: Context, data: Intent?): String {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw YouTubeException(describe(e))
        }
        return result.accessToken ?: throw YouTubeException("Google didn't return an access token.")
    }

    private fun AuthorizationResult.toOutcome(): AuthOutcome {
        val pending = pendingIntent
        if (hasResolution() && pending != null) return AuthOutcome.NeedsConsent(pending)
        return AuthOutcome.Token(accessToken ?: throw YouTubeException("Google didn't return an access token."))
    }

    private fun describe(e: ApiException) = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google sign-in isn't set up for this build. Register an Android OAuth client for this app's package and SHA-1."
        CommonStatusCodes.NETWORK_ERROR -> "No connection to Google. Check your internet."
        CommonStatusCodes.CANCELED -> "Sign-in was cancelled."
        else -> "Google sign-in failed (code ${e.statusCode})."
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
