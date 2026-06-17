package com.qalens.sample

import com.qalens.NetworkEvent
import com.qalens.QaLens
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Demo-only fake backend. A real app would install QaLensOkHttpInterceptor and these events would
 * be captured automatically; here we feed QaLens.logNetwork directly so the sample can deterministically
 * show a successful call and a failing one driving the evidence/score/timeline.
 */
object SampleBackend {

    suspend fun fetchBalance() {
        Timber.tag("Wallet").i("Fetching account balance")
        val start = System.currentTimeMillis()
        delay(280)
        QaLens.logNetwork(
            NetworkEvent(
                timestampMillis = start,
                method = "GET",
                url = "https://api.staging.qalens.dev/v1/balance",
                status = 200,
                latencyMs = System.currentTimeMillis() - start,
                responseBodyBytes = 340
            )
        )
    }

    /** Simulates a transfer that fails server-side — drives Backend/API classification. */
    suspend fun submitTransfer(): Boolean {
        val start = System.currentTimeMillis()
        delay(820)
        QaLens.logNetwork(
            NetworkEvent(
                timestampMillis = start,
                method = "POST",
                url = "https://api.staging.qalens.dev/v1/transfer",
                status = 500,
                latencyMs = System.currentTimeMillis() - start,
                requestBodyBytes = 128
            )
        )
        Timber.tag("Transfer").e("Transfer failed: server returned 500")
        return false
    }

    /** Simulates the payment call behind the Payment Failure demo screen (500 + slow). */
    suspend fun submitPayment(): Boolean {
        val start = System.currentTimeMillis()
        delay(1900)
        QaLens.logNetwork(
            NetworkEvent(
                timestampMillis = start,
                method = "POST",
                url = "https://api.staging.qalens.dev/v1/payments/charge",
                status = 500,
                latencyMs = System.currentTimeMillis() - start,
                requestBodyBytes = 256
            )
        )
        Timber.tag("Payments").e("Payment charge failed: server returned 500")
        return false
    }
}
