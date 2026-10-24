package com.example.markaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Tab { CHATS, DEVICES, SETTINGS }

private data class DeviceItem(
    val name: String,
    val model: String,
    val status: String,
    val online: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarkazApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkazApp() {
    var selectedTab by remember { mutableStateOf(Tab.DEVICES) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("مرحبًا بك في مركز أجهزتي") }

    val devices = remember {
        mutableStateListOf(
            DeviceItem("سامسونج", "Galaxy — أضف الموديل لاحقًا", "غير مربوط بعد", false),
            DeviceItem("ريدمي", "Redmi — أضف الموديل لاحقًا", "غير مربوط بعد", false),
            DeviceItem("جلاكسي آخر", "Galaxy — أضف الموديل لاحقًا", "غير مربوط بعد", false),
            DeviceItem("إلنترا", "الموديل غير محدد", "غير مربوط بعد", false)
        )
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
                TopAppBar(
                    title = {
                        Column {
                            Text("مركز أجهزتي", fontWeight = FontWeight.Bold)
                            Text("المراسلة وإدارة الأجهزة", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == Tab.CHATS,
                        onClick = { selectedTab = Tab.CHATS },
                        icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                        label = { Text("المحادثات") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == Tab.DEVICES,
                        onClick = { selectedTab = Tab.DEVICES },
                        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                        label = { Text("أجهزتي") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == Tab.SETTINGS,
                        onClick = { selectedTab = Tab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("الإعدادات") }
                    )
                }
            },
            floatingActionButton = {
                if (selectedTab == Tab.DEVICES || selectedTab == Tab.CHATS) {
                    FloatingActionButton(onClick = {
                        if (selectedTab == Tab.DEVICES) showAddDialog = true
                        else notice = "المراسلة ستُفعّل بعد ربط خادم الرسائل."
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "إضافة")
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                when (selectedTab) {
                    Tab.DEVICES -> DevicesScreen(devices, onDeviceClick = {
                        notice = "هذا الجهاز نموذج أولي. يلزم ربطه وتأكيد الصلاحيات قبل الاتصال."
                    })
                    Tab.CHATS -> ChatsScreen(onOpen = { notice = it })
                    Tab.SETTINGS -> SettingsScreen()
                }
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("إضافة جهاز") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("في النسخة المتصلة، ستتم إضافة الجهاز عبر رمز ربط يظهر على الجهاز الآخر.")
                        OutlinedTextField(
                            value = newDeviceName,
                            onValueChange = { newDeviceName = it },
                            label = { Text("اسم الجهاز") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newDeviceName.trim()
                        if (name.isNotEmpty()) {
                            devices.add(DeviceItem(name, "بانتظار إعداد الربط", "غير مربوط بعد", false))
                            newDeviceName = ""
                            showAddDialog = false
                            notice = "أُضيف الاسم محليًا فقط. الربط الحقيقي يحتاج خادمًا وتأكيدًا على الجهاز."
                        }
                    }) { Text("إضافة") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
                }
            )
        }
    }
}

@Composable
private fun DevicesScreen(
    devices: List<DeviceItem>,
    onDeviceClick: (DeviceItem) -> Unit
) {
    Spacer(Modifier.height(10.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("أجهزتك في مكان واحد", fontWeight = FontWeight.Bold)
                Text("اربط كل جهاز بموافقة واضحة مرة واحدة.", fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Text("الأجهزة", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(devices) { device ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onDeviceClick(device) },
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).background(
                            MaterialTheme.colorScheme.secondaryContainer, CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(device.model, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(device.status, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Wifi, contentDescription = "حالة الاتصال",
                        tint = if (device.online) Color(0xFF23834A) else Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun ChatsScreen(onOpen: (String) -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text("المحادثات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("المراسلة جاهزة للتصميم", fontWeight = FontWeight.SemiBold)
            Text("هذه نسخة واجهة أولية. إرسال الرسائل بين المستخدمين يحتاج إلى خادم وحسابات وتخزين آمن.")
            Button(onClick = { onOpen("سيتم تفعيل المحادثات بعد إعداد الخادم.") }) {
                Text("تفاصيل المرحلة التالية")
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Spacer(Modifier.height(18.dp))
    Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الحساب", fontWeight = FontWeight.SemiBold)
            Text("سيُضاف تسجيل الدخول والتحقق من الأجهزة في مرحلة الخادم.")
            HorizontalDivider()
            Text("الخصوصية والأمان", fontWeight = FontWeight.SemiBold)
            Text("سيحتاج كل جهاز إلى موافقة وصلاحيات واضحة. يمكن إلغاء الربط من إعدادات الحساب.")
            HorizontalDivider()
            Text("الإصدار التجريبي 0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
