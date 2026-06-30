package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey val username: String,
    val email: String,
    val passwordHash: String,
    val referralCode: String,
    val referredBy: String? = null,
    val balance: Double = 10.0, // Give them 10 BNG tokens as a signup welcome!
    val hashRate: Double = 2.0, // BNG/hour base mining speed
    val isMining: Boolean = false,
    val miningStartTime: Long = 0L,
    val lastDailyClaimTime: Long = 0L,
    val dailyStreak: Int = 0,
    val referralCount: Int = 0,
    val totalMined: Double = 0.0
)

@Entity(tableName = "session")
data class Session(
    @PrimaryKey val id: Int = 1,
    val activeUsername: String? = null
)
