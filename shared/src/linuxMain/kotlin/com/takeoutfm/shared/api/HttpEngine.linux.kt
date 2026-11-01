package com.takeoutfm.shared.api

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

/** Ktor's Curl engine, which links curl and OpenSSL statically (no system libcurl). */
internal actual fun httpEngine(): HttpClientEngineFactory<*> = Curl
