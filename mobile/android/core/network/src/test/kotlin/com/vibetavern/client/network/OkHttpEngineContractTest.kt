package com.vibetavern.client.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Runs the full contract, including "Stop works while the model is silent", on the production engine. */
class OkHttpEngineContractTest : HttpEngineContractTest() {
    override fun createEngine(): HttpEngine = OkHttpEngine(
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
    )
}
