package com.example.markaz

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val API_BASE = "https://markaz-ajhizati-api.onrender.com"
private val network = Executors.newSingleThreadExecutor()

private data class DeviceItem(
    val id: Long,
    val name: String,
    val type: String,
    val paired: Boolean
)

private data class PairItem(
    val id: Long,
    val username: String,
    val status: String
)

private data class ChatMessageItem(
    val id: Long,
    val sender: String,
    val body: String,
    val createdAt: String
)

private data class ScreenSessionItem(
    val id: Long,
    val controllerId: Long,
    val targetId: Long,
    val controllerUsername: String,
    val targetUsername: String,
    val status: String
)

private data class ApiResult(
    val status: Int,
    val body: String
)

private fun requestApi(
    method: String,
    path: String,
    token: String? = null,
    body: JSONObject? = null
): ApiResult {
    val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection)
    try {
        connection.requestMethod = method
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        return ApiResult(status, response)
    } finally {
        connection.disconnect()
    }
}

private fun errorMessage(result: ApiResult): String {
    return try {
        val json = JSONObject(result.body)
        when (val detail = json.opt("detail")) {
            is String -> detail
            else -> "تعذر إكمال الطلب (HTTP ${result.status})."
        }
    } catch (_: Exception) {
        "تعذر الاتصال بالخادم. تحقق من الإنترنت ثم حاول مجددًا."
    }
}

/**
 * Polls commands for the signed-in target account and returns each result to the API.
 * This function must be called from Dispatchers.IO, never from the Compose UI thread.
 */
private fun pollAndExecuteCommands(authToken: String, deviceName: String) {
    // A dedicated heartbeat distinguishes the Android client from an open web session.
    try {
        requestApi(
            "POST",
            "/api/android/presence",
            authToken,
            JSONObject().put("device_name", deviceName)
        )
    } catch (_: Exception) {
        // Presence is best-effort; command polling continues independently.
    }
    val poll = requestApi("GET", "/api/commands/pending", authToken)
    if (poll.status !in 200..299) return

    val commands = JSONArray(poll.body)
    for (i in 0 until commands.length()) {
        val item = commands.optJSONObject(i) ?: continue
        val commandId = item.optLong("id", -1L)
        if (commandId <= 0L) continue

        val command = item.optString("command")
        val resultPayload = JSONObject()
        val resultStatus = try {
            when (command) {
                "/status" -> {
                    resultPayload
                        .put("online", true)
                        .put("app", "markaz-ajhizati-android")
                        .put("device_name", deviceName)
                        .put("android_version", Build.VERSION.RELEASE.orEmpty())
                        .put("sdk_int", Build.VERSION.SDK_INT)
                        .put("checked_at", System.currentTimeMillis())
                    "completed"
                }
                "/device" -> {
                    resultPayload
                        .put("device_name", deviceName)
                        .put("manufacturer", Build.MANUFACTURER.orEmpty())
                        .put("model", Build.MODEL.orEmpty())
                        .put("android_version", Build.VERSION.RELEASE.orEmpty())
                        .put("sdk_int", Build.VERSION.SDK_INT)
                    "completed"
                }
                "/help" -> {
                    resultPayload
                        .put("commands", JSONArray(listOf("/status", "/device", "/help")))
                        .put("message", "هذه أوامر معلوماتية فقط؛ لا تنفذ أوامر نظام أو تتحكم بالجهاز.")
                    "completed"
                }
                else -> {
                    resultPayload.put("error", "الأمر غير مدعوم في تطبيق أندرويد.")
                    "failed"
                }
            }
        } catch (e: Exception) {
            resultPayload.put("error", e.message ?: "تعذر تنفيذ الأمر.")
            "failed"
        }

        // A result is submitted separately for every claimed command.
        try {
            requestApi(
                "POST",
                "/api/commands/$commandId/result",
                authToken,
                JSONObject().put("status", resultStatus).put("result", resultPayload)
            )
        } catch (_: Exception) {
            // A network interruption leaves the command claimed on the server;
            // the next polling cycle can continue with other pending commands.
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarkazApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkazApp(activity: MainActivity) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    var signedInUsername by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var incoming by remember { mutableStateOf<List<PairItem>>(emptyList()) }
    var outgoing by remember { mutableStateOf<List<PairItem>>(emptyList()) }
    var screenSessions by remember { mutableStateOf<List<ScreenSessionItem>>(emptyList()) }
    var pairingCode by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("سجّل الدخول لربط تطبيق أندرويد بحسابك.") }
    var activeTab by remember { mutableIntStateOf(0) }
    var chatContacts by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedChatUsername by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf<List<ChatMessageItem>>(emptyList()) }
    var chatDraft by remember { mutableStateOf("") }
    var chatNotice by remember { mutableStateOf("اختر حسابًا مرتبطًا لعرض المحادثة.") }
    var chatBusy by remember { mutableStateOf(false) }

    val deviceName = remember {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ").take(80)
            .ifBlank { "جهاز أندرويد" }
    }

    // Keep network polling off the main/UI thread. Cancellation follows login/logout.
    LaunchedEffect(token, deviceName) {
        val authToken = token ?: return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                pollAndExecuteCommands(authToken, deviceName)
            }
            delay(5000)
        }
    }

    fun loadDevices(authToken: String, createIfMissing: Boolean) {
        busy = true
        network.execute {
            try {
                var result = requestApi("GET", "/api/devices", authToken)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                var array = JSONArray(result.body)
                var found = false
                for (i in 0 until array.length()) {
                    if (array.getJSONObject(i).optString("device_name") == deviceName) found = true
                }
                if (createIfMissing && !found) {
                    val body = JSONObject()
                        .put("device_name", deviceName)
                        .put("device_type", "android")
                    result = requestApi("POST", "/api/devices", authToken, body)
                    if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                    result = requestApi("GET", "/api/devices", authToken)
                    if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                    array = JSONArray(result.body)
                }
                val parsed = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(DeviceItem(
                            id = item.optLong("id"),
                            name = item.optString("device_name", "جهاز"),
                            type = item.optString("device_type", "android"),
                            paired = item.optInt("paired", 0) == 1
                        ))
                    }
                }
                activity.runOnUiThread {
                    devices = parsed
                    notice = if (createIfMissing) {
                        "تم الاتصال بالخادم وتسجيل هذا الهاتف ضمن حسابك."
                    } else {
                        "تم تحديث قائمة الأجهزة."
                    }
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر تحميل الأجهزة."
                    busy = false
                }
            }
        }
    }

    fun refreshPairings(authToken: String) {
        network.execute {
            try {
                val inResult = requestApi("GET", "/api/pairings/incoming", authToken)
                val outResult = requestApi("GET", "/api/pairings", authToken)
                if (inResult.status !in 200..299) throw IllegalStateException(errorMessage(inResult))
                if (outResult.status !in 200..299) throw IllegalStateException(errorMessage(outResult))
                val newIncoming = buildList {
                    val array = JSONArray(inResult.body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(PairItem(item.optLong("id"), item.optString("controller_username", "حساب"), item.optString("status", "pending")))
                    }
                }
                val newOutgoing = buildList {
                    val array = JSONArray(outResult.body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(PairItem(item.optLong("id"), item.optString("target_username", "حساب"), item.optString("status", "pending")))
                    }
                }
                activity.runOnUiThread {
                    incoming = newIncoming
                    outgoing = newOutgoing
                }
            } catch (e: Exception) {
                activity.runOnUiThread { notice = e.message ?: "تعذر تحديث طلبات الربط." }
            }
        }
    }

    fun refreshChatData(authToken: String) {
        network.execute {
            try {
                val result = requestApi("GET", "/api/pairings", authToken)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                val parsedContacts = buildList {
                    val array = JSONArray(result.body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        if (item.optString("status") == "approved") {
                            val contact = item.optString("target_username").trim()
                            if (contact.isNotBlank() && !contains(contact)) add(contact)
                        }
                    }
                }
                val current = selectedChatUsername
                val chosen = if (parsedContacts.any { it.equals(current, ignoreCase = true) }) current
                    else parsedContacts.firstOrNull().orEmpty()
                val parsedMessages = if (chosen.isNotBlank()) {
                    val messagesResult = requestApi(
                        "GET", "/api/messages?with_user=" + java.net.URLEncoder.encode(chosen, "UTF-8") + "&limit=100",
                        authToken
                    )
                    if (messagesResult.status !in 200..299) throw IllegalStateException(errorMessage(messagesResult))
                    val array = JSONArray(messagesResult.body)
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            add(ChatMessageItem(item.optLong("id"), item.optString("sender"),
                                item.optString("body"), item.optString("created_at")))
                        }
                    }
                } else emptyList()
                activity.runOnUiThread {
                    chatContacts = parsedContacts
                    selectedChatUsername = chosen
                    chatMessages = parsedMessages
                    chatNotice = if (chosen.isBlank()) "لا توجد حسابات مرتبطة وموافق عليها. اربط حسابًا أولًا."
                        else "المحادثة مع $chosen — الرسائل محفوظة على الخادم."
                }
            } catch (e: Exception) {
                activity.runOnUiThread { chatNotice = e.message ?: "تعذر تحميل المحادثات." }
            }
        }
    }

    fun sendChatMessage(authToken: String) {
        val recipient = selectedChatUsername
        val body = chatDraft.trim()
        if (recipient.isBlank()) {
            chatNotice = "اختر حسابًا مرتبطًا وموافقًا عليه أولًا."
            return
        }
        if (body.isBlank()) return
        chatBusy = true
        network.execute {
            try {
                val result = requestApi("POST", "/api/messages", authToken,
                    JSONObject().put("recipient_username", recipient).put("body", body))
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                val messagesResult = requestApi(
                    "GET", "/api/messages?with_user=" + java.net.URLEncoder.encode(recipient, "UTF-8") + "&limit=100",
                    authToken
                )
                if (messagesResult.status !in 200..299) throw IllegalStateException(errorMessage(messagesResult))
                val array = JSONArray(messagesResult.body)
                val parsed = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(ChatMessageItem(item.optLong("id"), item.optString("sender"),
                            item.optString("body"), item.optString("created_at")))
                    }
                }
                activity.runOnUiThread {
                    chatDraft = ""
                    chatMessages = parsed
                    chatNotice = "تم إرسال الرسالة إلى $recipient."
                    chatBusy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    chatNotice = e.message ?: "تعذر إرسال الرسالة."
                    chatBusy = false
                }
            }
        }
    }

    fun refreshScreenSessions(authToken: String) {
        network.execute {
            try {
                val result = requestApi("GET", "/api/screen-sessions", authToken)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                val parsed = buildList {
                    val array = JSONArray(result.body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(ScreenSessionItem(
                            id = item.optLong("id"),
                            controllerId = item.optLong("controller_id"),
                            targetId = item.optLong("target_id"),
                            controllerUsername = item.optString("controller_username", "حساب"),
                            targetUsername = item.optString("target_username", "حساب"),
                            status = item.optString("status", "requested")
                        ))
                    }
                }
                activity.runOnUiThread { screenSessions = parsed }
            } catch (e: Exception) {
                activity.runOnUiThread { notice = e.message ?: "تعذر تحديث طلبات مشاركة الشاشة." }
            }
        }
    }

    fun decideScreenSession(item: ScreenSessionItem, accepted: Boolean) {
        val authToken = token ?: return
        busy = true
        network.execute {
            try {
                val result = requestApi(
                    "POST", "/api/screen-sessions/${item.id}/decision", authToken,
                    JSONObject().put("accepted", accepted)
                )
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                activity.runOnUiThread {
                    notice = if (accepted)
                        "وافقت على الطلب. لم يبدأ بث الشاشة؛ يلزم تفعيل التقاط الشاشة بموافقتك من أندرويد."
                    else "تم رفض طلب مشاركة الشاشة."
                    busy = false
                    refreshScreenSessions(authToken)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر تسجيل قرار مشاركة الشاشة."
                    busy = false
                }
            }
        }
    }

    fun generatePairingCode() {
        val authToken = token ?: return
        busy = true
        network.execute {
            try {
                val result = requestApi("POST", "/api/pairing-codes", authToken)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                val code = JSONObject(result.body).optString("pairing_code")
                activity.runOnUiThread {
                    pairingCode = code
                    notice = "شارك الرمز مع الحساب الذي تريد السماح له بطلب الربط. صلاحية الرمز 10 دقائق."
                    busy = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر إنشاء رمز الربط."
                    busy = false
                }
            }
        }
    }

    fun submitPairingCode() {
        val authToken = token ?: return
        val cleanCode = codeInput.filter { it.isDigit() }
        if (cleanCode.length != 6) {
            notice = "أدخل رمز الربط المكوّن من 6 أرقام."
            return
        }
        busy = true
        network.execute {
            try {
                val result = requestApi("POST", "/api/pairings", authToken, JSONObject().put("code", cleanCode))
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                activity.runOnUiThread {
                    codeInput = ""
                    notice = "أُرسل طلب الربط. لن يتم الربط إلا بعد موافقة صاحب الحساب الآخر."
                    busy = false
                    refreshPairings(authToken)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر إرسال طلب الربط."
                    busy = false
                }
            }
        }
    }

    fun decidePairing(item: PairItem, approved: Boolean) {
        val authToken = token ?: return
        busy = true
        network.execute {
            try {
                val result = requestApi("POST", "/api/pairings/${item.id}/decision", authToken, JSONObject().put("approved", approved))
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                activity.runOnUiThread {
                    notice = if (approved) "وافقت على طلب الربط. هذا لا يتيح الشاشة أو الملفات تلقائيًا." else "تم رفض طلب الربط."
                    busy = false
                    refreshPairings(authToken)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر تحديث قرار الربط."
                    busy = false
                }
            }
        }
    }

    fun revokePairing(item: PairItem) {
        val authToken = token ?: return
        busy = true
        network.execute {
            try {
                val result = requestApi("POST", "/api/pairings/${item.id}/revoke", authToken)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                activity.runOnUiThread {
                    notice = "تم إلغاء الربط."
                    busy = false
                    refreshPairings(authToken)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر إلغاء الربط."
                    busy = false
                }
            }
        }
    }

    fun authenticate() {
        val cleanUsername = username.trim()
        if (cleanUsername.length < 3) {
            notice = "اسم المستخدم يجب أن يكون 3 أحرف على الأقل."
            return
        }
        if (password.length < if (registerMode) 10 else 1) {
            notice = if (registerMode) "كلمة المرور عند إنشاء الحساب يجب أن تكون 10 أحرف على الأقل."
            else "أدخل كلمة المرور."
            return
        }
        busy = true
        notice = "جارٍ الاتصال بالخادم..."
        network.execute {
            try {
                val payload = JSONObject().put("username", cleanUsername).put("password", password)
                val route = if (registerMode) "/api/auth/register" else "/api/auth/login"
                val result = requestApi("POST", route, body = payload)
                if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                val response = JSONObject(result.body)
                val authToken = response.getString("access_token")
                val accountName = response.getJSONObject("user").optString("username", cleanUsername)
                activity.runOnUiThread {
                    token = authToken
                    signedInUsername = accountName
                    password = ""
                    notice = "تم تسجيل الدخول. جارٍ تسجيل الهاتف..."
                    loadDevices(authToken, true)
                    refreshPairings(authToken)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    notice = e.message ?: "تعذر تسجيل الدخول."
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(token, activeTab, selectedChatUsername) {
        val authToken = token ?: return@LaunchedEffect
        if (activeTab != 1 || selectedChatUsername.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val result = requestApi(
                        "GET", "/api/messages?with_user=" +
                            java.net.URLEncoder.encode(selectedChatUsername, "UTF-8") + "&limit=100",
                        authToken
                    )
                    if (result.status !in 200..299) throw IllegalStateException(errorMessage(result))
                    val array = JSONArray(result.body)
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            add(ChatMessageItem(item.optLong("id"), item.optString("sender"),
                                item.optString("body"), item.optString("created_at")))
                        }
                    }
                }
                chatMessages = parsed
            } catch (e: Exception) {
                val reason = e.message ?: "تعذر تحديث الرسائل."
                if (reason.contains("approved device link", ignoreCase = true)) {
                    chatMessages = emptyList()
                    chatContacts = emptyList()
                    selectedChatUsername = ""
                    chatNotice = "تم إلغاء الربط أو لم يعد الحساب مخولًا للمحادثة."
                } else {
                    chatNotice = reason
                }
            }
            delay(5000)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF315DA8),
            secondary = Color(0xFF5379B8),
            background = Color(0xFFF5F7FB),
            surface = Color.White
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("مركز أجهزتي", fontWeight = FontWeight.Bold)
                        Text("تطبيق أندرويد المتصل", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            }
        ) { padding ->
            if (token == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))
                    Icon(Icons.Default.Lock, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(if (registerMode) "إنشاء حساب" else "تسجيل الدخول",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("استخدم نفس حساب مركز أجهزتي الموجود على الموقع.", color = Color.DarkGray)
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("اسم المستخدم") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("كلمة المرور") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { authenticate() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) "يرجى الانتظار..." else if (registerMode) "إنشاء الحساب" else "دخول")
                    }
                    TextButton(onClick = {
                        registerMode = !registerMode
                        notice = if (registerMode) "أنشئ حسابًا جديدًا أو ارجع لتسجيل الدخول." else "أدخل بيانات حسابك الحالي."
                    }) {
                        Text(if (registerMode) "لدي حساب بالفعل — تسجيل الدخول" else "ليس لديك حساب؟ إنشاء حساب")
                    }
                    Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("مرحبًا، $signedInUsername", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("هذا الهاتف: $deviceName")
                            Text("سُجّل الهاتف في حسابك. هذا لا يمنح أي حساب آخر وصولًا إلى الشاشة أو الملفات.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { token?.let { loadDevices(it, false) } }, enabled = !busy) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("تحديث")
                                }
                                OutlinedButton(onClick = {
                                    token = null
                                    signedInUsername = ""
                                    devices = emptyList()
                                    activeTab = 0
                                    chatContacts = emptyList()
                                    selectedChatUsername = ""
                                    chatMessages = emptyList()
                                    chatDraft = ""
                                    notice = "تم تسجيل الخروج من التطبيق."
                                }, enabled = !busy) {
                                    Icon(Icons.Default.Logout, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("خروج")
                                }
                            }
                        }
                    }
                    TabRow(selectedTabIndex = activeTab) {
                        Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("الأجهزة") })
                        Tab(selected = activeTab == 1, onClick = {
                            activeTab = 1
                            token?.let { refreshChatData(it) }
                        }, text = { Text("المحادثات") })
                    }
                    if (activeTab == 0) {
                    Text("الأجهزة المسجلة في الحساب", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    if (devices.isEmpty()) {
                        Text("لا توجد أجهزة مسجلة بعد.")
                    } else {
                        devices.forEach { device ->
                            Card(shape = RoundedCornerShape(16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Devices, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, fontWeight = FontWeight.SemiBold)
                                        Text(device.type, fontSize = 12.sp)
                                    }
                                    Text(if (device.paired) "مرتبط" else "غير مربوط",
                                        color = if (device.paired) Color(0xFF23834A) else Color.Gray,
                                        fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Divider()
                    Text("ربط الحسابات", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("الربط يحتاج موافقة صاحب الحساب الآخر. لا يفعّل التحكم بالجهاز أو الوصول إلى الشاشة والملفات.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { generatePairingCode() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("إنشاء رمز لاستقبال طلب ربط")
                    }
                    if (pairingCode.isNotBlank()) {
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("رمز الربط المؤقت", fontWeight = FontWeight.SemiBold)
                                Text(pairingCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("صالح لمدة 10 دقائق، واستخدمه فقط مع الشخص المقصود.", fontSize = 12.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { value -> codeInput = value.filter { it.isDigit() }.take(6) },
                        label = { Text("رمز ربط حساب آخر") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { submitPairingCode() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("إرسال طلب الربط")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("طلبات الموافقة الواردة", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { token?.let { refreshPairings(it) } }, enabled = !busy) { Text("تحديث") }
                    }
                    if (incoming.isEmpty()) Text("لا توجد طلبات موافقة معلّقة.", fontSize = 13.sp)
                    incoming.forEach { item ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("الحساب ${item.username} يطلب الربط", fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { decidePairing(item, true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("موافقة") }
                                    OutlinedButton(onClick = { decidePairing(item, false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("رفض") }
                                }
                            }
                        }
                    }
                    Divider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("طلبات مشاركة الشاشة", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { token?.let { refreshScreenSessions(it) } }, enabled = !busy) { Text("تحديث") }
                    }
                    val pendingScreenRequests = screenSessions.filter {
                        it.status == "requested" && it.targetUsername.equals(signedInUsername, ignoreCase = true)
                    }
                    if (pendingScreenRequests.isEmpty()) {
                        Text("لا توجد طلبات مشاركة شاشة واردة حاليًا.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    pendingScreenRequests.forEach { item ->
                        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF1FF))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("طلب مشاركة شاشة", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("الحساب ${item.controllerUsername} يريد مشاهدة شاشة هذا الجهاز.")
                                Text("الموافقة لا تبدأ البث؛ التقاط الشاشة ما زال غير مفعّل.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { decideScreenSession(item, true) }, enabled = !busy,
                                        modifier = Modifier.weight(1f)) { Text("موافقة") }
                                    OutlinedButton(onClick = { decideScreenSession(item, false) }, enabled = !busy,
                                        modifier = Modifier.weight(1f)) { Text("رفض") }
                                }
                            }
                        }
                    }
                    Text("جلسات مشاركة الشاشة", fontWeight = FontWeight.Bold)
                    screenSessions.filter { it.status != "requested" || it.controllerUsername.equals(signedInUsername, ignoreCase = true) }
                        .forEach { item ->
                            Card(shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (item.controllerUsername.equals(signedInUsername, ignoreCase = true))
                                        "مع ${item.targetUsername}" else "من ${item.controllerUsername}",
                                        fontWeight = FontWeight.SemiBold)
                                    Text(when (item.status) {
                                        "requested" -> "بانتظار الموافقة"
                                        "accepted" -> "تمت الموافقة — البث غير مفعّل"
                                        "active" -> "نشطة"
                                        else -> item.status
                                    }, fontSize = 13.sp)
                                }
                            }
                        }
                    Text("الحسابات المرتبطة", fontWeight = FontWeight.Bold)
                    if (outgoing.isEmpty()) Text("لا توجد روابط صادرة من هذا الحساب.", fontSize = 13.sp)
                    outgoing.forEach { item ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.username, fontWeight = FontWeight.SemiBold)
                                Text(when (item.status) {
                                    "approved" -> "تمت الموافقة على الربط"
                                    "pending" -> "بانتظار موافقة صاحب الحساب"
                                    "revoked" -> "تم إلغاء الربط"
                                    else -> item.status
                                }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.status == "approved") {
                                    OutlinedButton(onClick = { revokePairing(item) }, enabled = !busy) { Text("إلغاء الربط") }
                                }
                            }
                        }
                    }
                    Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        Text("المحادثات", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("راسل الحسابات التي وافقت على ربطها بحسابك. لا يمكن مراسلة حساب غير مرتبط.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("الحساب المرتبط", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { token?.let { refreshChatData(it) } }, enabled = !chatBusy) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("تحديث")
                            }
                        }
                        if (chatContacts.isEmpty()) {
                            Card(shape = RoundedCornerShape(14.dp)) {
                                Text("لا توجد حسابات مرتبطة وموافق عليها. افتح تبويب الأجهزة لإرسال طلب ربط أو الموافقة عليه.",
                                    modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            var contactMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { contactMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(selectedChatUsername.ifBlank { "اختر حسابًا" })
                                }
                                DropdownMenu(expanded = contactMenuExpanded, onDismissRequest = { contactMenuExpanded = false }) {
                                    chatContacts.forEach { contact ->
                                        DropdownMenuItem(text = { Text(contact) }, onClick = {
                                            selectedChatUsername = contact
                                            chatMessages = emptyList()
                                            contactMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            Card(shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (chatMessages.isEmpty()) {
                                        Text("لا توجد رسائل بعد. ابدأ المحادثة برسالة جديدة.",
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        chatMessages.forEach { message ->
                                            val mine = message.sender.equals(signedInUsername, ignoreCase = true)
                                            Row(Modifier.fillMaxWidth(),
                                                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                                Card(
                                                    shape = RoundedCornerShape(14.dp),
                                                    colors = CardDefaults.cardColors(containerColor =
                                                        if (mine) Color(0xFFE3EDFF) else Color(0xFFF0F2F5)),
                                                    modifier = Modifier.fillMaxWidth(0.88f)
                                                ) {
                                                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                        Text(message.body, color = Color(0xFF1F2937))
                                                        Text((if (mine) "أنت" else message.sender) +
                                                            if (message.createdAt.isNotBlank()) " · " + message.createdAt.replace("T", " ").take(16) else "",
                                                            fontSize = 10.sp, color = Color.DarkGray)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = chatDraft, onValueChange = { chatDraft = it.take(4000) },
                                modifier = Modifier.fillMaxWidth(), label = { Text("اكتب رسالتك...") },
                                enabled = !chatBusy, maxLines = 4
                            )
                            Button(onClick = { token?.let { sendChatMessage(it) } },
                                enabled = !chatBusy && chatDraft.isNotBlank() && selectedChatUsername.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (chatBusy) "جارٍ الإرسال..." else "إرسال")
                            }
                            Text("تُحدّث الرسائل تلقائيًا كل 5 ثوانٍ أثناء فتح المحادثة.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(chatNotice, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
