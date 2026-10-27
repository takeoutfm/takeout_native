package com.takeoutfm.shared.api

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun httpEngine(): HttpClientEngineFactory<*> = Darwin
