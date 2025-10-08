package com.example.lockmodule

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class SimpleTagInfo(
    val uid: String,
    val tagType: String
)

fun getSimpleTagInfo(tag: Tag): SimpleTagInfo {
    val uid = tag.id.joinToString(":") { "%02X".format(it) }
    val tagType = tag.techList.firstOrNull()?.substringAfterLast('.') ?: "Unknown"

    return SimpleTagInfo(uid, tagType)
}

//--------------- NFC Helper Functions ------------------//
fun getTagUid(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it) }

fun getTagType(tag: Tag): String = tag.techList.joinToString(", ") { it.substringAfterLast(".") }

fun getDataFormat(tag: Tag): String {
    return when {
        tag.techList.contains("android.nfc.tech.MifareUltralight") -> "NFC Forum Type 2"
        tag.techList.contains("android.nfc.tech.Ndef") -> "NFC Forum Type 4"
        else -> "Unknown"
    }
}

fun getTagSizeInfo(tag: Tag): String {
    val nfcA = NfcA.get(tag) ?: return "Unknown Size"
    return try {
        nfcA.connect()
        val maxTransceive = nfcA.maxTransceiveLength
        nfcA.close()
        "Max Transceive Size: $maxTransceive bytes"
    } catch (e: Exception) {
        "Unknown Size"
    }
}

fun getTagInfoFull(tag: Tag): String {
    val uid = tag.id.joinToString(":") { "%02X".format(it) }
    val type = tag.techList.joinToString(", ") { it.substringAfterLast(".") }
    val nfcA = NfcA.get(tag)
    var writeable = "Unknown"
    var maxSize = "Unknown"

    if (nfcA != null) {
        try {
            nfcA.connect()
            maxSize = "${nfcA.maxTransceiveLength} bytes"

            val cmdReadCC = byteArrayOf(0x30.toByte(), 0x03.toByte())
            val cc = nfcA.transceive(cmdReadCC)

            if (cc.size >= 4) {
                val capability = cc[2].toInt() and 0xFF
                writeable = if ((capability and 0x0F) > 0) "Yes" else "No"
            }

        } catch (e: Exception) {
            writeable = "Unknown"
        } finally {
            nfcA.close()
        }
    }

    return """
        Serial Number: $uid
        Tag Type: $type
        Writeable: $writeable
        Max Transceive Size: $maxSize
    """.trimIndent()
}

fun getWriteStatus(tag: Tag): Pair<Boolean, Boolean> {
    val nfcA = NfcA.get(tag) ?: return Pair(false, false)
    return try {
        nfcA.connect()
        val testBytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        nfcA.transceive(byteArrayOf(0xA2.toByte(), 0x04) + testBytes)
        nfcA.close()
        Pair(true, true)
    } catch (_: Exception) {
        try {
            nfcA.close()
        } catch (_: Exception) {}
        Pair(false, true)
    }
}

// ---------------------NFC Helper Functions -----------------------

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent

    val nfcTagState = mutableStateOf<android.nfc.Tag?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        setContent {
            LockModuleApp(nfcTagState)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val tag = intent.getParcelableExtra<android.nfc.Tag>(NfcAdapter.EXTRA_TAG)
        tag?.let {
            nfcTagState.value = it
        }
    }
}

// General Container for all App
@Composable
fun LockModuleApp(nfcTagState: MutableState<android.nfc.Tag?>) {
    val context = LocalContext.current

    var userHashKey by remember { mutableStateOf<String?>(SecureStorage.getHashKey(context))  }
    var currentScreen by remember { mutableStateOf(if (userHashKey == null) "phase1" else "looking") }
    val showSettings = remember { mutableStateOf(false) }
    var sramData by remember { mutableStateOf<ByteArray?>(null) }
    var tagInfo by remember { mutableStateOf<SimpleTagInfo?>(null) }
    val showDev = remember { mutableStateOf(false) }
    val showSram = remember { mutableStateOf(false) }

    LaunchedEffect(nfcTagState.value) {
        nfcTagState.value?.let { tag ->
            try {
                val nfcA = NfcA.get(tag)
                delay(30L)
                nfcA.connect()
                delay(30L)

                try {
                    try {
                        tagInfo = tag?.let { getSimpleTagInfo(it) }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Unable to get Tag Info", Toast.LENGTH_SHORT).show()
                    }
                    // 1. Authenticate
                    val password = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                    val cmdAuth = byteArrayOf(0x1B.toByte()) + password
                    val authResponse = nfcA.transceive(cmdAuth)
                    delay(50L)

                    // 2. Write Page 0x04
                    val cmdRead = byteArrayOf(0x3A.toByte(), 0x04, 0x07)
                    var response: ByteArray? = null

                    delay(30L)
                    nfcA.transceive(cmdRead)
                    delay(30L)

                    repeat(5) {
                        try {
                            delay(50L)
                            response = nfcA.transceive(cmdRead)
                            if (response != null) return@repeat
                        } catch (_: Exception) {
                            delay(50)
                        }
                    }

                    if (response != null) {
                        sramData = response
                        currentScreen = "ok"
                    } else {
                        currentScreen = "nak"
                    }

                } finally {
                    nfcA.close()
                }

            } catch (e: Exception) {
                currentScreen = "nak"
            } finally {
                nfcTagState.value = null
            }
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0D1B2A))) {

        // Main Content Screens
        when (currentScreen) {
            "phase1" -> Phase1Screen { key ->
                SecureStorage.saveHashKey(context, key)
                userHashKey = key
                currentScreen = "looking"
            }
            "looking" -> Phase2Screen(
                isLooking = true,
                onTagScanned = { result ->
                    currentScreen = if (result == "OK") "ok" else "nak"
                }
            )
            "ok" -> OKScreen(tagInfo = tagInfo)
            "nak" -> NAKScreen()
        }

        // Gear Icon on all screens except phase1
        if (currentScreen != "phase1") {
            GearIcon(showSettings)
            DevIcon(onClick = { showDev.value = true })
            //SRAMIcon(showSram)

        }

        // Reset Icon only show on OK and NAK screens
        if (currentScreen == "ok" || currentScreen == "nak") {
            ResetIcon(onReset = {
                currentScreen = "looking"
                showSettings.value = false
            })
        }


        // Settings overlay
        if (showSettings.value) {
            SettingsScreen(
                currentKey = userHashKey,
                onSaveKey = { newKey ->
                    SecureStorage.saveHashKey(context, newKey)
                    userHashKey = newKey
                    currentScreen = "looking"
                    showSettings.value = false
                },
                onDeleteKey = {
                    SecureStorage.clearHashKey(context)
                    userHashKey = null
                    currentScreen = "phase1"
                    showSettings.value = false
                },
                onClose = { showSettings.value = false },
                onReset = {
                    currentScreen = "looking"
                    showSettings.value = false
                },
                showSettings = showSettings
            )
        }

        if (showDev.value) {
            DevScreen(
                nfcTagState = nfcTagState,
                showDev = showDev
            )
        }

    }
}

// Initial Launch Screen (for now will hold the user hash key
@Composable
fun Phase1Screen(onHashKeySaved: (String) -> Unit) {
    var keyInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Enter KEY", color = Color.White, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = { Text("Hash key", color = Color.LightGray) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { if (keyInput.isNotBlank()) onHashKeySaved(keyInput) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }

        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Warning",
            tint = Color.Yellow,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(80.dp)
        )
    }
}

// Simulated Lock and Unlock for debugging
@Composable
fun Phase2Screen(isLooking: Boolean, onTagScanned: (String) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Looking...", color = Color.White, fontSize = 28.sp)
    }
}

// Verified LOCK signature and user hash key successfully
@Composable
fun OKScreen(tagInfo: SimpleTagInfo?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
            .padding(16.dp)
    ) {
        Text(
            "LOCK OPENING",
            color = Color.White,
            fontSize = 28.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        )

        tagInfo?.let { info ->
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "Serial Number: ${info.uid}",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tag Type: ${info.tagType}",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Authorized",
            tint = Color.Green,
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }
}

// Not verified LOCK signature and user hash key
@Composable
fun NAKScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Not Authorized",
                color = Color.White,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Not Authorized",
                tint = Color.Red,
                modifier = Modifier.size(300.dp)
            )
        }
    }
}

// Settings screen container
@Composable
fun SettingsScreen(
    currentKey: String?,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    showSettings: MutableState<Boolean>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
            .padding(32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            var newKey by remember { mutableStateOf("") }

            Button(
                onClick = {
                    if (newKey.isNotBlank()) onSaveKey(newKey)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CHANGE KEY")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                value = newKey,
                onValueChange = { newKey = it },
                placeholder = { Text("Enter new key", color = Color.LightGray) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onDeleteKey() },
                colors =ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DELETE KEY")
            }
        }

        GearIcon(showSettings = showSettings)
        ResetIcon(onReset = onReset)

        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Warning",
            tint = Color.Yellow,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(80.dp)
        )
    }
}

// Gear Icon to allow opening of settings when clicked
@Composable
fun GearIcon(showSettings: MutableState<Boolean>) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = { showSettings.value = !showSettings.value },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

// Reset Icon to allow user to go back to inital screen
@Composable
fun ResetIcon(onReset: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onReset,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset",
                tint = Color.White
            )
        }
    }
}

// Icon to allow dev admin changes open up
@Composable
fun DevIcon(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = "Developer Mode",
                tint = Color.Cyan
            )
        }
    }
}

// Icon to allow SRAM modifications
@Composable
fun SRAMIcon(showSram: MutableState<Boolean>) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = { showSram.value = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BugReport, // bug icon for SRAM
                contentDescription = "SRAM",
                tint = Color.Magenta
            )
        }
    }
}

// Dev Screen container to hold dev changes
@Composable
fun DevScreen(
    nfcTagState: MutableState<Tag?>,
    showDev: MutableState<Boolean>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tagInfo by remember { mutableStateOf("No tag scanned") }
    var rawPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var sramContent by remember { mutableStateOf<String?>(null) }

    val pendingSramWrites = remember { mutableStateListOf<Pair<Int, ByteArray>>() }
    var writePending by remember { mutableStateOf(false) }

    var writePage by remember { mutableStateOf("") }
    var writeData by remember { mutableStateOf("") }

    LaunchedEffect(nfcTagState.value) {
        nfcTagState.value?.let { tag ->
            try {
                tagInfo = getTagInfoFull(tag)
                val pages = mutableListOf<String>()

                val nfcA = NfcA.get(tag)
                nfcA.connect()

                /*try {
                    nfcA.transceive(byteArrayOf(0xA2.toByte(), 0xFF.toByte(), 0x11.toByte(), 0x11.toByte(), 0x11.toByte(), 0x11.toByte()))
                    pages.add("Good Write to SRAM Termination Page")
                } catch (e: Exception) {
                    pages.add("Bad Write to SRAM Termination Page")
                }*/
                /*try {
                    //nfcA.transceive(byteArrayOf(0x1B.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                    nfcA.transceive(byteArrayOf(0xC2.toByte(), 0xFF.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()))
                    pages.add("Good Sector Select 1 Change")
                } catch(e: Exception) {
                    pages.add("Bad Sector Select 1 Change")
                }*/

                // Read SRAM pages F0-FF
               /* try {
                    nfcA.transceive(byteArrayOf(0x1B.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                    nfcA.transceive(byteArrayOf(0xA2.toByte(), 0xF2.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte()))
                    pages.add("Good SRAM Write")
                } catch (e: Exception) {
                    pages.add("Bad SRAM Write")
                }*/
                nfcA.transceive(byteArrayOf(0x1B.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                //nfcA.transceive(byteArrayOf(0xA2.toByte(), 0xFF.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte()))

                val sramRead = nfcA.transceive(byteArrayOf(0x3A.toByte(), 0xF0.toByte(), 0xFF.toByte()))

                    for (i in 0 until 16) {
                        val pageAddr = 0xF0 + i
                        val pageBytes = sramRead.copyOfRange(i * 4, i * 4 + 4)
                        pages.add("Page 0x%02X: %s".format(pageAddr, pageBytes.joinToString(" ") { "%02X".format(it) }))
                    }

                if (writePending && pendingSramWrites.isNotEmpty()) {
                        nfcA.transceive(byteArrayOf(0x1B.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                        pendingSramWrites.forEach { (pageNum, data) ->
                            try {
                                val cmd = byteArrayOf(0xA2.toByte(), pageNum.toByte()) + data
                                nfcA.transceive(cmd)
                                pages.add("Good SRAM Write")
                            } catch (e: Exception) {
                                pages.add("Bad SRAM Write")
                            }
                        }

                        pendingSramWrites.clear()
                        writePending = false
                        Toast.makeText(context, "Queued writes completed!", Toast.LENGTH_SHORT).show()
                }
                rawPages = pages
                nfcA.close()
            } catch (e: Exception) {
                tagInfo = "Error reading/writing tag: ${e.message}"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1B2A))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1B2A3A))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    Text("TAG INFO", color = Color.Cyan, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(tagInfo, color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("RAW SRAM PAGES:", color = Color.Cyan, fontSize = 18.sp)
                    rawPages.forEach { page ->
                        Text(page, color = Color.LightGray, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Queue Custom Write", color = Color.Cyan, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    OutlinedTextField(
                        value = writePage,
                        onValueChange = { writePage = it },
                        label = { Text("Page # (F0-FF)") },
                        modifier = Modifier.width(100.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = writeData,
                        onValueChange = { writeData = it },
                        label = { Text("Data (hex, e.g. AA BB CC DD)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(onClick = {
                        val pageNum = writePage.toIntOrNull(16)
                        if (pageNum == null || pageNum !in 0xF0..0xFF) {
                            Toast.makeText(context, "Invalid page number", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val bytes = writeData.split(" ")
                            .mapNotNull { it.toIntOrNull(16)?.toByte() }
                            .take(4)
                            .toByteArray()

                        if (bytes.size != 4) {
                            Toast.makeText(context, "Enter exactly 4 bytes", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        pendingSramWrites.add(pageNum to bytes)
                        writePending = true
                        Toast.makeText(context, "Write queued for page 0x%02X".format(pageNum), Toast.LENGTH_SHORT).show()
                        writePage = ""
                        writeData = ""
                    }) {
                        Text("Add Write")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Pending Writes:", color = Color.Cyan, fontSize = 16.sp)
                pendingSramWrites.forEach { (page, bytes) ->
                    Text(
                        "Page 0x%02X: %s".format(page, bytes.joinToString(" ") { "%02X".format(it) }),
                        color = Color.White, fontSize = 14.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val pageNum = 0xF2
                        val data = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte())
                        pendingSramWrites.add(pageNum to data)
                        writePending = true
                        Toast.makeText(context, "Write to page 0xF2 queued for next scan", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Write F2 → AA AA AA AA")
                }
            }
        }
        IconButton(
            onClick = { showDev.value = false },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close Dev Screen", tint = Color.White)
        }
    }
}

// SRAM Screen conatiner
@Composable
fun SRAMScreen(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("SRAM Read/Write Screen")
    }
}
