package com.bspc.blood

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vercel-এ চলা সার্ভারের সাথে কথা বলা (server/ ফোল্ডারের কোড)।
 * প্রতিটা অনুরোধের সাথে Firebase লগইন টোকেন যায়, সার্ভার সেটা যাচাই করে।
 */
object ServerApi {
    /** Vercel-এ ডিপ্লয়ের পর পাওয়া ঠিকানা */
    const val BASE_URL = "https://SERVER_URL_NOT_SET"

    /** নতুন রিকোয়েস্টে আশেপাশের ডোনারদের পুশ নোটিফিকেশন পাঠানো। কতজনকে পাঠানো হলো সেটা ফেরত দেয়। */
    suspend fun notifyRequest(requestId: String): Int =
        post("/api/notify-request", JSONObject().put("requestId", requestId)).optInt("sent", 0)

    /** "রক্ত দেব" */
    suspend fun respond(requestId: String) {
        post("/api/respond", JSONObject().put("requestId", requestId))
    }

    /** "রক্ত পেয়েছি": রিকোয়েস্ট বন্ধ, আর যারা রক্ত দিয়েছেন তাদের তারিখ আপডেট */
    suspend fun closeRequest(requestId: String, donatedUids: List<String>) {
        post(
            "/api/close-request",
            JSONObject().put("requestId", requestId).put("donatedUids", JSONArray(donatedUids)),
        )
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("আবার লগইন করুন")
        val token = user.getIdToken(false).await().token
            ?: throw IllegalStateException("আবার লগইন করুন")

        return withContext(Dispatchers.IO) {
            val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (code !in 200..299) {
                    throw IllegalStateException(json.optString("error").ifBlank { "সার্ভারে সমস্যা ($code)" })
                }
                json
            } catch (e: java.io.IOException) {
                throw IllegalStateException("সার্ভারে পৌঁছানো যায়নি। ইন্টারনেট দেখে আবার চেষ্টা করুন।")
            } finally {
                conn.disconnect()
            }
        }
    }
}
