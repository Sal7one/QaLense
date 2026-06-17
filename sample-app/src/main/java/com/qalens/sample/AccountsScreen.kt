package com.qalens.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qalens.QaLens
import com.qalens.qaName
import com.qalens.qaTag
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(onAccountClick: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text(
                "My Cards",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .qaTag("accounts.title")
                    .qaName("My Cards Title")
            )
        }
        items(SampleAccounts, key = { it.id }) { account ->
            AccountListItem(account, onClick = { onAccountClick(account.id) })
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AccountListItem(account: Account, onClick: () -> Unit) {
    val colors = CardGradients.forId(account.id)
    Box(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
            .qaTag("accounts.item.${account.id}")
            .qaName("${account.name} Account Item")
    ) {
        // Decorative circle
        Box(
            Modifier
                .size(130.dp)
                .offset(x = 220.dp, y = (-30).dp)
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    account.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.qaTag("accounts.item.${account.id}.name")
                )
                Text(
                    account.typeName,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    "•••• ${account.iban.takeLast(4)}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    account.formattedBalance,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.qaTag("accounts.item.${account.id}.balance").qaName("${account.name} Balance")
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "SAR",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ── Account Detail ────────────────────────────────────────────────────────────

@Composable
fun AccountDetailScreen(accountId: Int, onTransfer: (Int) -> Unit, onBack: () -> Unit) {
    val account = SampleAccounts.first { it.id == accountId }
    val txns    = sampleTxns(accountId)
    val grouped = txns.groupBy { it.date }
    val colors  = CardGradients.forId(account.id)

    val creditTotal = txns.filter { it.credit }.sumOf { it.amount }
    val debitTotal  = txns.filter { !it.credit }.sumOf { it.amount }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        // ── Hero card header ────────────────────────────────────────────
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Brush.linearGradient(colors))
            ) {
                Box(
                    Modifier.size(260.dp).offset(200.dp, (-80).dp)
                        .background(Color.White.copy(alpha = 0.05f), CircleShape)
                )
                Box(
                    Modifier.size(180.dp).offset(220.dp, 120.dp)
                        .background(Color.White.copy(alpha = 0.04f), CircleShape)
                )
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "←",
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clickable(onClick = onBack)
                                .padding(end = 12.dp)
                                .qaTag("detail.back")
                                .qaName("Back to Accounts")
                        )
                        Text(account.name, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
                    }
                    Column {
                        Text("Balance", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(
                            account.formattedBalance,
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp,
                            modifier = Modifier.qaTag("detail.balance").qaName("Account Balance")
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            account.iban,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.qaTag("detail.iban")
                        )
                    }
                }
            }
        }

        // ── Stats row ────────────────────────────────────────────────────
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatColumn("Income", "+%,.0f".format(creditTotal), Color(0xFF15803D))
                Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                StatColumn("Spent", "−%,.0f".format(debitTotal), MaterialTheme.colorScheme.onSurface)
                Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                StatColumn("Txns", "${txns.size}", MaterialTheme.colorScheme.onSurface)
            }
        }

        // ── Transfer button ──────────────────────────────────────────────
        item {
            Box(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onTransfer(accountId) }
                    .padding(vertical = 16.dp)
                    .qaTag("detail.transfer.btn")
                    .qaName("Transfer From Account"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↗  Transfer",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }

        // ── Transactions ─────────────────────────────────────────────────
        item {
            Text(
                "Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    .qaTag("detail.txn.header")
            )
        }

        grouped.entries.forEach { (date, dateTxns) ->
            item(key = "header_$date") {
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }
            itemsIndexed(dateTxns, key = { _, txn -> "dtxn_${txn.id}" }) { idx, txn ->
                TxnListItem(
                    txn = txn,
                    isFirst = idx == 0,
                    isLast = idx == dateTxns.lastIndex
                )
            }
            item(key = "spacer_$date") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Transfer ──────────────────────────────────────────────────────────────────

@Composable
fun TransferScreen(fromAccountId: Int, onBack: () -> Unit) {
    val from = SampleAccounts.first { it.id == fromAccountId }
    val to   = SampleAccounts.first { it.id != fromAccountId }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "←",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 14.dp)
                        .qaTag("transfer.back")
                        .qaName("Transfer Back")
                )
                Text(
                    "Transfer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.qaTag("transfer.title")
                )
            }
            Spacer(Modifier.height(28.dp))
        }

        item {
            Text(
                "From",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TransferAccountTile(from, "transfer.from")
            Spacer(Modifier.height(10.dp))

            // Swap icon
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⇅", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(
                "To",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            TransferAccountTile(to, "transfer.to")
        }

        item {
            Spacer(Modifier.height(28.dp))
            Text(
                "Amount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .qaTag("transfer.amount.field")
            ) {
                Text(
                    "0.00",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !submitting) {
                        // Business event + simulated backend call. The POST fails (500), which
                        // QaLens captures: timeline, Backend/API classification, and a score drop.
                        QaLens.event("Confirm Transfer", "User tapped Confirm Transfer")
                        submitting = true
                        status = "Processing…"
                        scope.launch {
                            val ok = SampleBackend.submitTransfer()
                            submitting = false
                            status = if (ok) "Transfer complete."
                                     else "Something went wrong. Please try again."
                        }
                    }
                    .padding(vertical = 18.dp)
                    .qaTag("transfer.confirm")
                    .qaName("Confirm Transfer"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (submitting) "Processing…" else "Confirm Transfer",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
            status?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    color = if (it.startsWith("Something")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.qaTag("transfer.status")
                )
            }
        }
    }
}

@Composable
private fun TransferAccountTile(account: Account, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
            .qaTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(CardGradients.forId(account.id)))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(account.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(account.typeName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(account.formattedBalance, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
