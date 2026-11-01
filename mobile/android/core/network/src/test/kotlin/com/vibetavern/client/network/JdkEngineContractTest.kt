package com.vibetavern.client.network

class JdkEngineContractTest : HttpEngineContractTest() {
    override fun createEngine(): HttpEngine = JdkUrlConnectionEngine(connectTimeoutMs = 2_000)

    // JDK disconnect() blocks while a chunked read is pending; the app uses OkHttpEngine.
    override val promptCancelWhileSilent: Boolean = false
}
