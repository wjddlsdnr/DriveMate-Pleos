package com.example.pleosconnect.scheduler

import com.example.pleosconnect.contract.logic.CheckScheduler

/** A-owned scheduling extension; shared CheckScheduler remains source compatible for B. */
interface FollowUpScheduler : CheckScheduler {
    fun cancelFollowUps()
}
