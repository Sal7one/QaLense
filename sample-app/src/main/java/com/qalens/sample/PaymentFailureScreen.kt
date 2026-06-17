package com.qalens.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qalens.QaLens
import com.qalens.qaName
import com.qalens.qaTag
import kotlinx.coroutines.launch

/**
 * Deterministic "payment fails" demo. On open it fires a slow POST that returns 500, then shows a
 * generic error — exactly the kind of bug QaLens turns into a Backend/API evidence bundle in one tap.
 */
@Composable
fun PaymentFailureScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("loading") } // loading | failed

    LaunchedEffect(Unit) {
        QaLens.event("Submit Payment", "User submitted a payment")
        state = "loading"
        val ok = SampleBackend.submitPayment()
        state = if (ok) "ok" else "failed"
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)
    ) {
        Text(
            "←  Back",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onBack).qaTag("pay.back").qaName("Payment Back")
        )
        Spacer(Modifier.height(40.dp))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state == "loading") {
                    Text("Processing payment…", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp, modifier = Modifier.qaTag("pay.processing"))
                } else {
                    Text("⚠", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Something went wrong",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.qaTag("pay.error.title").qaName("Payment Error")
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Please try again later.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.qaTag("pay.error.message")
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        if (state == "failed") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        scope.launch {
                            state = "loading"
                            val ok = SampleBackend.submitPayment()
                            state = if (ok) "ok" else "failed"
                        }
                    }
                    .padding(vertical = 18.dp)
                    .qaTag("pay.retry").qaName("Retry Payment"),
                contentAlignment = Alignment.Center
            ) {
                Text("Retry", color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Open QaLens → Bug Bundle → Copy Jira Bug to see this captured as a Backend/API issue.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
