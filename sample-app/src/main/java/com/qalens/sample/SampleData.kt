package com.qalens.sample

data class Account(
    val id: Int,
    val name: String,
    val typeName: String,
    val balance: Double,
    val iban: String,
    val seedColor: Long
) {
    val coverUrl = "https://picsum.photos/seed/accover$id/800/300"
    val iconUrl  = "https://picsum.photos/seed/accicon$id/120/120"
    val formattedBalance get() = "%,.2f SAR".format(balance)
}

data class Txn(
    val id: Int,
    val accountId: Int,
    val merchant: String,
    val category: String,
    val amount: Double,
    val credit: Boolean,
    val date: String
) {
    val iconUrl = "https://picsum.photos/seed/txn${accountId}_$id/80/80"
    val formattedAmount get() = "${if (credit) "+" else "−"}%,.2f".format(amount)
}

val SampleAccounts = listOf(
    Account(1, "Main Current",    "Current",    24_580.00, "SA84 1000 0208 2123 4567 8901", 0xFF1E3A8A),
    Account(2, "Savings Goal",    "Savings",    87_200.00, "SA07 3000 0000 6010 0167 2555", 0xFF065F46),
    Account(3, "Investment",      "Investment", 142_900.00,"SA74 4000 0001 2345 6781 0000", 0xFF7C2D12),
    Account(4, "Daily Spending",  "Current",     3_250.50, "SA36 2000 0000 0232 1995 1234", 0xFF3730A3),
)

private data class TxnSeed(val merchant: String, val cat: String, val amount: Double, val credit: Boolean, val date: String)

private val seeds = listOf(
    TxnSeed("Starbucks",        "Café",         45.00,     false, "Today"),
    TxnSeed("Carrefour",        "Groceries",    380.00,    false, "Today"),
    TxnSeed("Netflix",          "Entertainment",55.00,     false, "Yesterday"),
    TxnSeed("Transfer In",      "Transfer",     2_000.00,  true,  "Yesterday"),
    TxnSeed("ACME Corp",        "Salary",       18_000.00, true,  "Jun 1"),
    TxnSeed("SEWA",             "Utilities",    290.00,    false, "Jun 3"),
    TxnSeed("Amazon",           "Shopping",     450.00,    false, "Jun 3"),
    TxnSeed("Gold's Gym",       "Fitness",      220.00,    false, "Jun 5"),
    TxnSeed("Café Milano",      "Dining",       320.00,    false, "Jun 6"),
    TxnSeed("Uber",             "Transport",    65.00,     false, "Jun 7"),
    TxnSeed("STC",              "Telecom",      149.00,    false, "Jun 7"),
    TxnSeed("ATM Withdrawal",   "Cash",         500.00,    false, "Jun 8"),
    TxnSeed("Noon",             "Shopping",     310.00,    false, "Jun 9"),
    TxnSeed("Jarir",            "Books",        180.00,    false, "Jun 9"),
    TxnSeed("Dividend",         "Investment",   1_250.00,  true,  "Jun 10"),
)

fun sampleTxns(accountId: Int): List<Txn> =
    seeds.mapIndexed { i, s -> Txn(i + 1, accountId, s.merchant, s.cat, s.amount, s.credit, s.date) }

val UserAvatarUrl = "https://picsum.photos/seed/useravatar/200/200"
