package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"

    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyBwlwuqAE8rPxICBsE8GL89926t9xxZPLM")
                    .setApplicationId("1:1081962350821:web:98ce2f6bf24cd6ce7d6c0c")
                    .setDatabaseUrl("https://tpbongo-default-rtdb.firebaseio.com")
                    .setProjectId("tpbongo")
                    .setStorageBucket("tpbongo.firebasestorage.app")
                    .setGcmSenderId("1081962350821")
                    .build()
                FirebaseApp.initializeApp(context, options)
                Log.d(TAG, "Firebase initialized dynamically with manual parameters successfully!")
            } else {
                Log.d(TAG, "Firebase already initialized.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase dynamically: ${e.message}", e)
        }
    }

    val isFirebaseInitialized: Boolean
        get() = try {
            FirebaseApp.getInstance() != null
        } catch (e: Exception) {
            false
        }

    private val database: FirebaseDatabase?
        get() = if (isFirebaseInitialized) {
            try {
                FirebaseDatabase.getInstance()
            } catch (e: Exception) {
                null
            }
        } else null

    private val auth: FirebaseAuth?
        get() = if (isFirebaseInitialized) {
            try {
                FirebaseAuth.getInstance()
            } catch (e: Exception) {
                null
            }
        } else null

    fun getCurrentUserEmail(): String? {
        return auth?.currentUser?.email
    }

    /**
     * Uploads/Syncs user information, balance, and history to Firebase Realtime Database
     */
    suspend fun syncUserToFirebase(user: User): Boolean {
        if (!isFirebaseInitialized) return false
        val db = database ?: return false
        
        return suspendCancellableCoroutine { continuation ->
            // Replace invalid characters for Firebase database keys if any
            val safeKey = user.username.replace(".", "_")
                .replace("#", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")

            val userRef = db.getReference("users").child(safeKey)
            
            userRef.setValue(user)
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully synced user ${user.username} to Firebase.")
                    if (continuation.isActive) continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync user ${user.username} to Firebase: ${e.message}")
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }

    /**
     * Pulls the latest user data from Firebase during Login or background Sync.
     */
    suspend fun fetchUserFromFirebase(username: String): User? {
        if (!isFirebaseInitialized) return null
        val db = database ?: return null

        return suspendCancellableCoroutine { continuation ->
            val safeKey = username.replace(".", "_")
                .replace("#", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")

            val userRef = db.getReference("users").child(safeKey)
            
            userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (snapshot.exists()) {
                            // Map manually to be 100% type-safe
                            val uUsername = snapshot.child("username").getValue(String::class.java) ?: username
                            val uEmail = snapshot.child("email").getValue(String::class.java) ?: ""
                            val uPasswordHash = snapshot.child("passwordHash").getValue(String::class.java) ?: ""
                            val uReferralCode = snapshot.child("referralCode").getValue(String::class.java) ?: ""
                            val uReferredBy = snapshot.child("referredBy").getValue(String::class.java)
                            val uBalance = snapshot.child("balance").getValue(Double::class.java) ?: 0.0
                            val uHashRate = snapshot.child("hashRate").getValue(Double::class.java) ?: 2.0
                            val uIsMining = snapshot.child("isMining").getValue(Boolean::class.java) ?: false
                            val uMiningStartTime = snapshot.child("miningStartTime").getValue(Long::class.java) ?: 0L
                            val uLastDailyClaimTime = snapshot.child("lastDailyClaimTime").getValue(Long::class.java) ?: 0L
                            val uDailyStreak = snapshot.child("dailyStreak").getValue(Long::class.java)?.toInt() ?: 0
                            val uReferralCount = snapshot.child("referralCount").getValue(Long::class.java)?.toInt() ?: 0
                            val uTotalMined = snapshot.child("totalMined").getValue(Double::class.java) ?: 0.0

                            val fetchedUser = User(
                                username = uUsername,
                                email = uEmail,
                                passwordHash = uPasswordHash,
                                referralCode = uReferralCode,
                                referredBy = uReferredBy,
                                balance = uBalance,
                                hashRate = uHashRate,
                                isMining = uIsMining,
                                miningStartTime = uMiningStartTime,
                                lastDailyClaimTime = uLastDailyClaimTime,
                                dailyStreak = uDailyStreak,
                                referralCount = uReferralCount,
                                totalMined = uTotalMined
                            )
                            if (continuation.isActive) continuation.resume(fetchedUser)
                        } else {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error mapping Firebase snapshot to User: ${e.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase query cancelled: ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    /**
     * Pulls the user data from Firebase by email.
     */
    suspend fun fetchUserByEmailFromFirebase(email: String): User? {
        if (!isFirebaseInitialized) return null
        val db = database ?: return null

        return suspendCancellableCoroutine { continuation ->
            val query = db.getReference("users").orderByChild("email").equalTo(email.trim())
            query.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (snapshot.exists() && snapshot.childrenCount > 0) {
                            val userSnapshot = snapshot.children.first()
                            val uUsername = userSnapshot.child("username").getValue(String::class.java) ?: ""
                            val uEmail = userSnapshot.child("email").getValue(String::class.java) ?: email
                            val uPasswordHash = userSnapshot.child("passwordHash").getValue(String::class.java) ?: ""
                            val uReferralCode = userSnapshot.child("referralCode").getValue(String::class.java) ?: ""
                            val uReferredBy = userSnapshot.child("referredBy").getValue(String::class.java)
                            val uBalance = userSnapshot.child("balance").getValue(Double::class.java) ?: 0.0
                            val uHashRate = userSnapshot.child("hashRate").getValue(Double::class.java) ?: 2.0
                            val uIsMining = userSnapshot.child("isMining").getValue(Boolean::class.java) ?: false
                            val uMiningStartTime = userSnapshot.child("miningStartTime").getValue(Long::class.java) ?: 0L
                            val uLastDailyClaimTime = userSnapshot.child("lastDailyClaimTime").getValue(Long::class.java) ?: 0L
                            val uDailyStreak = userSnapshot.child("dailyStreak").getValue(Long::class.java)?.toInt() ?: 0
                            val uReferralCount = userSnapshot.child("referralCount").getValue(Long::class.java)?.toInt() ?: 0
                            val uTotalMined = userSnapshot.child("totalMined").getValue(Double::class.java) ?: 0.0

                            val fetchedUser = User(
                                username = uUsername,
                                email = uEmail,
                                passwordHash = uPasswordHash,
                                referralCode = uReferralCode,
                                referredBy = uReferredBy,
                                balance = uBalance,
                                hashRate = uHashRate,
                                isMining = uIsMining,
                                miningStartTime = uMiningStartTime,
                                lastDailyClaimTime = uLastDailyClaimTime,
                                dailyStreak = uDailyStreak,
                                referralCount = uReferralCount,
                                totalMined = uTotalMined
                            )
                            if (continuation.isActive) continuation.resume(fetchedUser)
                        } else {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error mapping Firebase snapshot by email to User: ${e.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase query cancelled: ${error.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    /**
     * Creates an auth user in Firebase and sends email verification.
     */
    suspend fun registerFirebaseUser(email: String, passwordPlain: String): Result<String> {
        if (!isFirebaseInitialized) return Result.failure(Exception("Firebase not initialized"))
        val firebaseAuth = auth ?: return Result.failure(Exception("Firebase Auth not available"))

        return suspendCancellableCoroutine { continuation ->
            firebaseAuth.createUserWithEmailAndPassword(email, passwordPlain)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val firebaseUser = firebaseAuth.currentUser
                        if (firebaseUser != null) {
                            firebaseUser.sendEmailVerification()
                                .addOnCompleteListener { verificationTask ->
                                    if (verificationTask.isSuccessful) {
                                        Log.d(TAG, "Email verification sent to $email")
                                        if (continuation.isActive) continuation.resume(Result.success(firebaseUser.uid))
                                    } else {
                                        val errorMsg = verificationTask.exception?.message ?: "Failed to send verification email."
                                        Log.e(TAG, "Failed to send verification email to $email: $errorMsg")
                                        if (continuation.isActive) continuation.resume(Result.failure(Exception("Registered successfully, but failed to send verification email: $errorMsg")))
                                    }
                                }
                        } else {
                            if (continuation.isActive) continuation.resume(Result.failure(Exception("Failed to get current authenticated user.")))
                        }
                    } else {
                        val errorMsg = task.exception?.message ?: "Unknown registration error"
                        Log.e(TAG, "Firebase Auth sign up failed: $errorMsg")
                        if (continuation.isActive) continuation.resume(Result.failure(Exception(errorMsg)))
                    }
                }
        }
    }

    /**
     * Authenticates with Firebase Auth and verifies if the email is verified.
     */
    suspend fun authenticateFirebaseUser(email: String, passwordPlain: String): Result<Boolean> {
        if (!isFirebaseInitialized) return Result.failure(Exception("Firebase not initialized"))
        val firebaseAuth = auth ?: return Result.failure(Exception("Firebase Auth not available"))

        return suspendCancellableCoroutine { continuation ->
            firebaseAuth.signInWithEmailAndPassword(email, passwordPlain)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val firebaseUser = firebaseAuth.currentUser
                        if (firebaseUser != null) {
                            // Reload user to get latest isEmailVerified status
                            firebaseUser.reload().addOnCompleteListener { reloadTask ->
                                if (reloadTask.isSuccessful) {
                                    val isVerified = firebaseUser.isEmailVerified
                                    if (isVerified) {
                                        if (continuation.isActive) continuation.resume(Result.success(true))
                                    } else {
                                        // Try sending verification again so they can verify if they didn't receive it
                                        try {
                                            firebaseUser.sendEmailVerification()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to resend verification email: ${e.message}")
                                        }
                                        if (continuation.isActive) continuation.resume(
                                            Result.failure(Exception("Email is not verified! Please check your inbox (or Spam folder) and click the verification link before logging in."))
                                        )
                                    }
                                } else {
                                    val errorMsg = reloadTask.exception?.message ?: "Failed to refresh user profile status."
                                    if (continuation.isActive) continuation.resume(Result.failure(Exception(errorMsg)))
                                }
                            }
                        } else {
                            if (continuation.isActive) continuation.resume(Result.failure(Exception("Failed to load authenticated user profile.")))
                        }
                    } else {
                        val errorMsg = task.exception?.message ?: "Password verification failed or user not found."
                        Log.e(TAG, "Firebase Auth login failed: $errorMsg")
                        if (continuation.isActive) continuation.resume(Result.failure(Exception(errorMsg)))
                    }
                }
        }
    }

    /**
     * Sends password reset email via Firebase Auth.
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        if (!isFirebaseInitialized) return Result.failure(Exception("Firebase not initialized"))
        val firebaseAuth = auth ?: return Result.failure(Exception("Firebase Auth not available"))

        return suspendCancellableCoroutine { continuation ->
            firebaseAuth.sendPasswordResetEmail(email.trim())
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        if (continuation.isActive) continuation.resume(Result.success(Unit))
                    } else {
                        val errorMsg = task.exception?.message ?: "Failed to send reset password email."
                        if (continuation.isActive) continuation.resume(Result.failure(Exception(errorMsg)))
                    }
                }
        }
    }
}
