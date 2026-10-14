package com.bspc.blood

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class Profile(
    val name: String = "",
    val phone: String = "",
    val bloodGroup: String = "",
    /** null মানে কখনো রক্ত দেননি */
    val lastDonationMillis: Long? = null,
)

object AuthRepository {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUid: String? get() = auth.currentUser?.uid
    val currentDisplayName: String? get() = auth.currentUser?.displayName

    suspend fun signInWithGoogle(context: Context) {
        val option = GetSignInWithGoogleOption
            .Builder(context.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val result = CredentialManager.create(context).getCredential(context, request)
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } else {
            error("Google অ্যাকাউন্ট পাওয়া যায়নি")
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}

object ProfileRepository {
    private val users get() = FirebaseFirestore.getInstance().collection("users")

    suspend fun load(uid: String): Profile? {
        val snap = users.document(uid).get().await()
        if (!snap.exists()) return null
        return Profile(
            name = snap.getString("name") ?: "",
            phone = snap.getString("phone") ?: "",
            bloodGroup = snap.getString("bloodGroup") ?: "",
            lastDonationMillis = snap.getLong("lastDonationMillis"),
        )
    }

    suspend fun save(uid: String, profile: Profile) {
        val data = hashMapOf<String, Any?>(
            "name" to profile.name,
            "phone" to profile.phone,
            "bloodGroup" to profile.bloodGroup,
            "lastDonationMillis" to profile.lastDonationMillis,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        users.document(uid).set(data).await()
    }
}
