package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.domain.rest.*

/** No persistence and no recovery across process death or driver changes. */
class InMemoryRestSessionStore : RestSessionStore {
    private var checkpoint: RestCheckpoint? = null
    override fun load() = checkpoint
    override fun save(value: RestCheckpoint?) { checkpoint = value }
}
