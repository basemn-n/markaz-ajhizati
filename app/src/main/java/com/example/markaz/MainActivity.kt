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
    var outgoing by remember { mutableStateOf<List<PairItem>>(emptyList()) }\n    var screenSessions by remember { mutableStateOf<List<ScreenSessionItem>>(emptyList()) }
    var pairingCode by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("سجّل الدخول لربط تطبيق أندرويد بحسابك.") }

    LaunchedEffect(token) {
        val authToken = token ?: return@LaunchedEffect
        while (true) {
            refreshScreenSessions(authToken)
            delay(5000)
        }
    }

    val deviceName = remember {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        listOf(manufacturer, model).filter { it.isNotBlank() }.distinct().joinToString(" ").take(80)
            .ifBlank { "جهاز أندرويد" }
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
                                    notice = "تم تسجيل الخروج من التطبيق."
                                }, enabled = !busy) {
                                    Icon(Icons.Default.Logout, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("خروج")
                                }
                            }
                        }
                    }
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
                }
            }
        }
    }
}
