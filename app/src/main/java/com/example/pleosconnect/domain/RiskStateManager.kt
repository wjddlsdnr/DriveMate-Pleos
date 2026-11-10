package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.logic.DriverStateManager

/** A-owned extension; preserves the shared A/B DriverStateManager contract. */
interface RiskStateManager : DriverStateManager {
    fun onRepeatedNoResponse()
}
