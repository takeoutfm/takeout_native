package com.takeoutfm.shared.api

import io.ktor.client.engine.HttpClientEngineFactory

/** The host app's HTTP (Qt Network, the system's TLS), through the C API: HostEngine.kt. */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = Host
