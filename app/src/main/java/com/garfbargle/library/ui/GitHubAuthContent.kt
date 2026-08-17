package com.garfbargle.library.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garfbargle.library.auth.GitHubAuthSession
import com.garfbargle.library.auth.GitHubDeviceAuth
import com.garfbargle.library.auth.GitHubDeviceCode
import com.garfbargle.library.ui.theme.Acid
import com.garfbargle.library.ui.theme.Ink
import com.garfbargle.library.ui.theme.TextPrimary
import com.garfbargle.library.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun GitHubAuthContent(
    connected: Boolean,
    clientId: String,
    onAuthorized: (GitHubAuthSession) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember(clientId) { GitHubDeviceAuth(clientId) }
    var pending by remember { mutableStateOf<GitHubDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (connected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Acid, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("GitHub App connected", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("No personal access token stored.", color = TextSecondary, fontSize = 11.sp)
            }
            Text(
                "APP",
                color = Ink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.clip(CircleShape).background(Acid).padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Private releases are available only where both your GitHub account and the Library GitHub App have repository access.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onClear,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B30)),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Disconnect GitHub") }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Security, null, tint = Acid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Connect with GitHub", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("GitHub App · device sign-in", color = TextSecondary, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Library uses GitHub's device sign-in instead of asking you to create or paste a PAT. The resulting session is stored with Android Keystore and can be renewed without a client secret.",
        color = TextSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )

    pending?.let { code ->
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D0F11))
                .padding(14.dp)
        ) {
            Text("ONE-TIME CODE", color = Color(0xFF777A82), fontSize = 9.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                code.userCode,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Acid, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for GitHub…", color = TextSecondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                copyCode(context, code.userCode)
                openBrowser(context, code.verificationUri)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Copy code & open GitHub")
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
        }
    } ?: run {
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (clientId.isBlank()) {
                    error = "GitHub App sign-in is not configured in this build."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    runCatching { auth.begin() }
                        .onSuccess { code ->
                            pending = code
                            copyCode(context, code.userCode)
                            openBrowser(context, code.verificationUri)
                            runCatching { auth.awaitAuthorization(code) }
                                .onSuccess { session ->
                                    pending = null
                                    busy = false
                                    onAuthorized(session)
                                }
                                .onFailure {
                                    pending = null
                                    busy = false
                                    error = it.message ?: "GitHub sign-in failed."
                                }
                        }
                        .onFailure {
                            busy = false
                            error = it.message ?: "GitHub sign-in failed."
                        }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Connect GitHub")
        }
    }

    error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Color(0xFFFF8D8D), fontSize = 11.sp, lineHeight = 16.sp)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "Public catalog and public apps never require sign-in.",
        color = Color(0xFF777A82),
        fontSize = 10.sp
    )
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", code))
}

private fun openBrowser(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
