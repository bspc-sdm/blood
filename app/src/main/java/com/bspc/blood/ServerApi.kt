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
    const val BASE_URL = "https://blood-server-ashy.vercel.app"

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

    /** নিজের খোলা রিকোয়েস্ট এডিট। রাজি থাকা কতজন ডোনারকে পরিবর্তন জানানো হলো সেটা ফেরত দেয়। */
    suspend fun editRequest(
        requestId: String,
        bags: Int,
        hospital: String,
        neededByMillis: Long,
        urgent: Boolean,
        note: String,
    ): Int =
        post(
            "/api/edit-request",
            JSONObject()
                .put("requestId", requestId)
                .put("bags", bags)
                .put("hospital", hospital)
                .put("neededByMillis", neededByMillis)
                .put("urgent", urgent)
                .put("note", note),
        ).optInt("notified", 0)

    /** অ্যাডমিন: অপেক্ষায় থাকা তারিখ সংশোধনের অনুরোধ */
    suspend fun adminCorrections(): List<CorrectionRequest> {
        val arr = post("/api/admin/corrections", JSONObject()).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CorrectionRequest(
                id = o.optString("id"),
                uid = o.optString("uid"),
                name = o.optString("name"),
                currentMillis = if (o.isNull("currentMillis")) null else o.optLong("currentMillis"),
                requestedMillis = if (o.isNull("requestedMillis")) null else o.optLong("requestedMillis"),
                reason = o.optString("reason"),
                createdAtMillis = o.optLong("createdAtMillis"),
            )
        }
    }

    /** অ্যাডমিন: অনুমোদন (true) বা বাতিল (false) */
    suspend fun resolveCorrection(id: String, approve: Boolean) {
        post("/api/admin/resolve", JSONObject().put("id", id).put("approve", approve))
    }

    /** লগইন: ইমেইলে কোড পাঠানো (লগইন ছাড়াই) */
    suspend fun sendLoginCode(email: String) {
        request("/api/auth/send-code", JSONObject().put("email", email), null)
    }

    /** লগইন: কোড যাচাই, Firebase লগইন টোকেন ফেরত দেয় */
    suspend fun verifyLoginCode(email: String, code: String): String =
        request("/api/auth/verify-code", JSONObject().put("email", email).put("code", code), null)
            .optString("token").ifBlank { throw IllegalStateException("লগইন হয়নি, আবার চেষ্টা করুন") }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("আবার লগইন করুন")
        val token = user.getIdToken(false).await().token
            ?: throw IllegalStateException("আবার লগইন করুন")
        return request(path, body, token)
    }

    private suspend fun request(path: String, body: JSONObject, token: String?): JSONObject {
        return withContext(Dispatchers.IO) {
            val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
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
