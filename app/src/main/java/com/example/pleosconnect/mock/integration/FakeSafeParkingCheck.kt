package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.domain.SafeParking
import com.example.pleosconnect.domain.SafeParkingCheck

/** UNKNOWN in the app. VERIFIED can only be supplied by a test fixture, never a UI toggle. */
class FakeSafeParkingCheck(private val testValue: SafeParking = SafeParking.UNKNOWN) : SafeParkingCheck {
    override fun current() = testValue
}
