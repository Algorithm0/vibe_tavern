package com.vibetavern.client.network

/**
 * Where a server lives and how to authenticate against it.
 *
 * Loopback requests need no token (the server trusts 127.0.0.1); every remote
 * request needs the Mobile Access token, sent as a Bearer header.
 */
data class ServerConnection(
    val baseUrl: String,
    val token: String? = null,
) {
    init {
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/'" }
    }
}
