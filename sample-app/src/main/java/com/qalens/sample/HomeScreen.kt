package com.qalens.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun HomeScreen(onAccountClick: (Int) -> Unit, onTransferClick: (Int) -> Unit) {
    val txns = sampleTxns(SampleAccounts.first().id)
    val grouped = txns.groupBy { it.date }
    val totalBalance = SampleAccounts.sumOf { it.balance }

    // Simulate the balance API that loads when Home appears (a successful call for the timeline).
    LaunchedEffect(Unit) { SampleBackend.fetchBalance() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { HomeHeader() }

        // ── Total balance ────────────────────────────────────────────────
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    "Total balance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "%,.2f SAR".format(totalBalance),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.qaTag("home.total.balance").qaName("Total Balance")
                )
            }
        }

        // ── Account card carousel ────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.width(12.dp))
                SampleAccounts.forEach { acc ->
                    AccountCreditCard(
                        account = acc,
                        modifier = Modifier
                            .clickable { onAccountClick(acc.id) }
                            .qaTag("home.card.${acc.id}")
                            .qaName("${acc.name} Card")
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
        }

        // ── Quick actions ────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickAction("Transfer", "↗", Modifier.weight(1f)) {
                    onTransferClick(SampleAccounts.first().id)
                }
                QuickAction("Pay", "⊕", Modifier.weight(1f)) {
                    QaLens.event("pay_tapped")
                }
                QuickAction("Recharge", "⚡", Modifier.weight(1f)) {
                    QaLens.event("recharge_tapped")
                }
                QuickAction("More", "···", Modifier.weight(1f)) {}
            }
        }

        // ── Transactions by date group ───────────────────────────────────
        item {
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Transactions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.qaTag("home.txn.section")
                )
                Text(
                    "See all",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable { onAccountClick(SampleAccounts.first().id) }
                        .qaTag("home.txn.seeall")
                )
            }
        }

        grouped.entries.take(3).forEachIndexed { groupIdx, (date, dateTxns) ->
            item(key = "date_$groupIdx") {
                Spacer(Modifier.height(16.dp))
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(dateTxns, key = { _, txn -> "txn_${txn.id}" }) { idx, txn ->
                TxnListItem(
                    txn = txn,
                    isFirst = idx == 0,
                    isLast = idx == dateTxns.lastIndex
                )
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Good morning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Abdullah",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.qaTag("home.greeting").qaName("User Greeting")
            )
        }
        AsyncImage(
            model = UserAvatarUrl,
            contentDescription = "Profile",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .qaTag("home.avatar")
                .qaName("User Avatar")
        )
    }
}

@Composable
fun AccountCreditCard(account: Account, modifier: Modifier = Modifier) {
    val colors = CardGradients.forId(account.id)
    Box(
        modifier = modifier
            .width(280.dp)
            .height(165.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(colors))
    ) {
        // Decorative circle accents
        Box(
            Modifier
                .size(180.dp)
                .offset(160.dp, (-60).dp)
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
        )
        Box(
            Modifier
                .size(120.dp)
                .offset(190.dp, 90.dp)
                .background(Color.White.copy(alpha = 0.04f), CircleShape)
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    account.name,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.qaTag("card.${account.id}.name")
                )
                Text(
                    account.typeName,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier
                        .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Column {
                Text(
                    account.formattedBalance,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.qaTag("card.${account.id}.balance").qaName("${account.name} Balance")
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "•••• ${account.iban.takeLast(4)}",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.qaTag("card.${account.id}.iban")
                )
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .qaTag("home.action.${label.lowercase()}")
            .qaName("$label Quick Action"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(icon, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TxnListItem(txn: Txn, isFirst: Boolean = false, isLast: Boolean = false) {
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(14.dp)
        isFirst           -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
        isLast            -> RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
        else              -> RoundedCornerShape(2.dp)
    }
    val topPad = if (isFirst) 0.dp else 1.dp

    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = topPad)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable {}
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .qaTag("txn.${txn.id}")
            .qaName("${txn.merchant} Transaction"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Category color dot + merchant icon
        Box(contentAlignment = Alignment.BottomEnd) {
            AsyncImage(
                model = txn.iconUrl,
                contentDescription = txn.merchant,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Box(
                Modifier
                    .size(10.dp)
                    .offset(2.dp, 2.dp)
                    .background(CategoryColors.of(txn.category), CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.qaTag("txn.${txn.id}.merchant")
            )
            Text(
                txn.category,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                txn.formattedAmount,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = if (txn.credit) Color(0xFF15803D) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.qaTag("txn.${txn.id}.amount")
            )
            Text(
                "SAR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
