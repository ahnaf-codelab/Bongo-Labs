package com.example.data

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class MiningRepository(private val userDao: UserDao) {

    val activeUser: Flow<User?> = userDao.getActiveUserFlow().map { user ->
        if (user != null && user.hashRate < 2.0) {
            user.copy(hashRate = 2.0)
        } else {
            user
        }
    }
    val session: Flow<Session?> = userDao.getSessionFlow()

    private suspend fun ensureMinHashRate(user: User?): User? {
        if (user == null) return null
        if (user.hashRate < 2.0) {
            val upgraded = user.copy(hashRate = 2.0)
            userDao.insertUser(upgraded)
            if (FirebaseSyncManager.isFirebaseInitialized) {
                try {
                    FirebaseSyncManager.syncUserToFirebase(upgraded)
                    Log.d("MiningRepository", "Successfully upgraded and synced hash rate to 2.0 for ${user.username}")
                } catch (e: Exception) {
                    Log.e("MiningRepository", "Error syncing upgraded hash rate: ${e.message}")
                }
            }
            return upgraded
        }
        return user
    }

    suspend fun getUser(username: String): User? {
        return ensureMinHashRate(userDao.getUserByUsername(username))
    }

    suspend fun getReferredUsers(referralCode: String): Flow<List<User>> {
        return userDao.getReferredUsers(referralCode)
    }

    suspend fun registerUser(
        username: String,
        email: String,
        passwordPlain: String,
        referredByCode: String?
    ): Result<User> {
        val trimmedUsername = username.trim()
        val trimmedEmail = email.trim()

        if (trimmedUsername.length < 3) {
            return Result.failure(Exception("Username must be at least 3 characters."))
        }
        if (!trimmedEmail.contains("@")) {
            return Result.failure(Exception("Invalid email address."))
        }
        if (passwordPlain.length < 6) {
            return Result.failure(Exception("Password must be at least 6 characters."))
        }

        // Check local database first
        var existingUser = userDao.getUserByUsername(trimmedUsername)
        
        // Check Firebase if initialized
        if (existingUser == null && FirebaseSyncManager.isFirebaseInitialized) {
            existingUser = FirebaseSyncManager.fetchUserFromFirebase(trimmedUsername)
            if (existingUser != null) {
                // Save fetched user locally
                userDao.insertUser(existingUser)
            }
        }

        if (existingUser != null) {
            return Result.failure(Exception("Username already exists."))
        }

        // If Firebase is initialized, first register via Firebase Auth
        if (FirebaseSyncManager.isFirebaseInitialized) {
            val fbRegResult = FirebaseSyncManager.registerFirebaseUser(trimmedEmail, passwordPlain)
            if (fbRegResult.isFailure) {
                return Result.failure(fbRegResult.exceptionOrNull() ?: Exception("Firebase Auth registration failed."))
            }
        }

        // Generate clean unique referral code: e.g., BNG-XXXXX
        val randomPart = UUID.randomUUID().toString().take(5).uppercase()
        val ownReferralCode = "BNG-$randomPart"

        var finalReferredByCode: String? = null
        var referrerBonusAdded = false
        var updatedReferrer: User? = null

        if (!referredByCode.isNullOrBlank()) {
            val uppercaseCode = referredByCode.trim().uppercase()
            var referrer = userDao.getUserByReferralCode(uppercaseCode)
            
            // If not found locally, try fetching from Firebase
            if (referrer == null && FirebaseSyncManager.isFirebaseInitialized) {
                Log.d("MiningRepository", "Referrer not found in local db, checking referral code.")
            }

            if (referrer != null) {
                if (referrer.username == trimmedUsername) {
                    return Result.failure(Exception("You cannot use your own referral code."))
                }
                finalReferredByCode = referrer.referralCode
                
                // Reward the referrer: +5.0 BNG instantly and +0.02 BNG/hr to their mining speed!
                val updated = referrer.copy(
                    balance = referrer.balance + 5.0,
                    hashRate = referrer.hashRate + 0.02,
                    referralCount = referrer.referralCount + 1
                )
                userDao.updateUser(updated)
                updatedReferrer = updated
                referrerBonusAdded = true
            } else {
                return Result.failure(Exception("Referral code not found."))
            }
        }

        // Welcome bonus: 10.0 BNG normally, 15.0 if referred!
        val welcomeBalance = if (referrerBonusAdded) 15.0 else 10.0
        val newUser = User(
            username = trimmedUsername,
            email = trimmedEmail,
            passwordHash = passwordPlain, // plaintext for this local demonstration
            referralCode = ownReferralCode,
            referredBy = finalReferredByCode,
            balance = welcomeBalance,
            hashRate = 2.0 // Base hash rate of 2.0 BNG per hour
        )

        userDao.insertUser(newUser)
        
        // Sync to Firebase in background if available
        if (FirebaseSyncManager.isFirebaseInitialized) {
            FirebaseSyncManager.syncUserToFirebase(newUser)
            if (updatedReferrer != null) {
                FirebaseSyncManager.syncUserToFirebase(updatedReferrer)
            }
        }

        // Since email verification is required, we do NOT auto-login/insert session here.
        // Instead, the user must verify their email and log in manually.

        return Result.success(newUser)
    }

    suspend fun loginUser(usernameOrEmail: String, passwordPlain: String): Result<User> {
        val input = usernameOrEmail.trim()
        val isEmail = input.contains("@")
        
        var user: User? = if (isEmail) {
            userDao.getUserByEmail(input)
        } else {
            userDao.getUserByUsername(input)
        }

        // Sync from Firebase if available (downloads latest cloud balance / history!)
        if (FirebaseSyncManager.isFirebaseInitialized) {
            val firebaseUser = if (isEmail) {
                FirebaseSyncManager.fetchUserByEmailFromFirebase(input)
            } else {
                FirebaseSyncManager.fetchUserFromFirebase(input)
            }
            if (firebaseUser != null) {
                val finalUser = ensureMinHashRate(firebaseUser) ?: firebaseUser
                // Update local Room database with latest cloud data
                userDao.insertUser(finalUser)
                user = finalUser
                Log.d("MiningRepository", "Synced user ${finalUser.username} state from Firebase successfully.")
            }
        }

        if (user == null) {
            return Result.failure(Exception("User not found."))
        }

        user = ensureMinHashRate(user) ?: user

        // Verify credentials with Firebase Authentication first if initialized
        if (FirebaseSyncManager.isFirebaseInitialized) {
            val fbAuthResult = FirebaseSyncManager.authenticateFirebaseUser(user.email, passwordPlain)
            if (fbAuthResult.isFailure) {
                return Result.failure(fbAuthResult.exceptionOrNull() ?: Exception("Firebase authentication failed."))
            }
        } else {
            // Local fallback validation
            if (user.passwordHash != passwordPlain) {
                return Result.failure(Exception("Incorrect password."))
            }
        }

        userDao.insertSession(Session(activeUsername = user.username))
        return Result.success(user)
    }

    suspend fun logoutUser() {
        userDao.clearSession()
    }

    suspend fun tryRestoreSession(): Boolean {
        val currentSession = userDao.getSession()
        if (currentSession?.activeUsername != null) {
            return true
        }

        if (FirebaseSyncManager.isFirebaseInitialized) {
            val email = FirebaseSyncManager.getCurrentUserEmail()
            if (email != null) {
                val firebaseUser = FirebaseSyncManager.fetchUserByEmailFromFirebase(email)
                if (firebaseUser != null) {
                    val finalUser = ensureMinHashRate(firebaseUser) ?: firebaseUser
                    userDao.insertUser(finalUser)
                    userDao.insertSession(Session(activeUsername = finalUser.username))
                    Log.d("MiningRepository", "Successfully restored session for ${finalUser.username} from Firebase on startup.")
                    return true
                }
            }
        }
        return false
    }

    suspend fun startMining(username: String): Result<User> {
        val user = ensureMinHashRate(userDao.getUserByUsername(username)) ?: return Result.failure(Exception("User not found."))
        
        if (user.isMining) {
            return Result.success(user)
        }

        val updatedUser = user.copy(
            isMining = true,
            miningStartTime = System.currentTimeMillis()
        )
        userDao.updateUser(updatedUser)

        // Sync to Firebase
        if (FirebaseSyncManager.isFirebaseInitialized) {
            FirebaseSyncManager.syncUserToFirebase(updatedUser)
        }

        return Result.success(updatedUser)
    }

    suspend fun claimMinedTokens(username: String): Result<User> {
        val user = ensureMinHashRate(userDao.getUserByUsername(username)) ?: return Result.failure(Exception("User not found."))
        
        if (!user.isMining) {
            return Result.failure(Exception("Mining is not active."))
        }

        val now = System.currentTimeMillis()
        val elapsedMs = now - user.miningStartTime
        val sessionDurationMs = 12 * 60 * 60 * 1000L // 12 Hours

        if (elapsedMs < sessionDurationMs) {
            val remainingMs = sessionDurationMs - elapsedMs
            val hours = remainingMs / (60 * 60 * 1000)
            val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
            val seconds = (remainingMs % (60 * 1000)) / 1000
            return Result.failure(Exception("Mining session is not finished yet! Please wait ${hours}h ${minutes}m ${seconds}s until the 12-hour session is complete to claim."))
        }

        // Calculate mined amount for exactly the 12-hour cycle duration
        val earned = 12.0 * user.hashRate
        
        val updatedUser = user.copy(
            balance = user.balance + earned,
            totalMined = user.totalMined + earned,
            isMining = false,
            miningStartTime = 0L
        )
        userDao.updateUser(updatedUser)

        // Sync to Firebase
        if (FirebaseSyncManager.isFirebaseInitialized) {
            FirebaseSyncManager.syncUserToFirebase(updatedUser)
        }

        return Result.success(updatedUser)
    }

    suspend fun claimDailyReward(username: String): Result<Pair<User, Double>> {
        val user = ensureMinHashRate(userDao.getUserByUsername(username)) ?: return Result.failure(Exception("User not found."))
        
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        val twoDaysMs = 48 * 60 * 60 * 1000L

        val elapsedMs = now - user.lastDailyClaimTime

        if (user.lastDailyClaimTime > 0L && elapsedMs < oneDayMs) {
            val remainingMs = oneDayMs - elapsedMs
            val hours = remainingMs / (60 * 60 * 1000)
            val minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000)
            return Result.failure(Exception("Next claim available in $hours hours and $minutes minutes."))
        }

        // Determine daily streak
        val newStreak = if (user.lastDailyClaimTime > 0L && elapsedMs < twoDaysMs) {
            val s = user.dailyStreak + 1
            if (s > 7) 1 else s
        } else {
            1 // Streak broken or first claim
        }

        // Rewards scale: Day 1: 1.0, Day 2: 2.0, Day 3: 3.0, Day 4: 4.0, Day 5: 5.0, Day 6: 7.5, Day 7: 15.0
        val rewardAmount = when (newStreak) {
            1 -> 1.0
            2 -> 2.0
            3 -> 3.0
            4 -> 4.0
            5 -> 5.0
            6 -> 7.5
            7 -> 15.0
            else -> 1.0
        }

        val updatedUser = user.copy(
            balance = user.balance + rewardAmount,
            lastDailyClaimTime = now,
            dailyStreak = newStreak
        )

        userDao.updateUser(updatedUser)

        // Sync to Firebase
        if (FirebaseSyncManager.isFirebaseInitialized) {
            FirebaseSyncManager.syncUserToFirebase(updatedUser)
        }

        return Result.success(Pair(updatedUser, rewardAmount))
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty()) {
            return Result.failure(Exception("Please enter your email address."))
        }

        // 1. Check if user exists locally
        var user = userDao.getUserByEmail(trimmedEmail)

        // 2. Check if user exists in Firebase (in case local DB is empty or cleared)
        if (user == null && FirebaseSyncManager.isFirebaseInitialized) {
            user = FirebaseSyncManager.fetchUserByEmailFromFirebase(trimmedEmail)
        }

        if (user == null) {
            return Result.failure(Exception("Email not found. You cannot reset password for an unregistered email."))
        }

        // 3. Send password reset email
        return FirebaseSyncManager.sendPasswordResetEmail(trimmedEmail)
    }
}
