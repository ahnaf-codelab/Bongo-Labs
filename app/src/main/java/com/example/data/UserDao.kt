package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE username = :username")
    suspend fun getUserByUsername(username: String): User?

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE referralCode = :code")
    suspend fun getUserByReferralCode(code: String): User?

    @Query("SELECT * FROM users WHERE referredBy = :referralCode")
    fun getReferredUsers(referralCode: String): Flow<List<User>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Update
    suspend fun updateUser(user: User)

    @Query("SELECT * FROM session WHERE id = 1")
    fun getSessionFlow(): Flow<Session?>

    @Query("SELECT * FROM session WHERE id = 1")
    suspend fun getSession(): Session?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)

    @Query("DELETE FROM session WHERE id = 1")
    suspend fun clearSession()

    @Transaction
    @Query("SELECT * FROM users WHERE username = (SELECT activeUsername FROM session WHERE id = 1 LIMIT 1)")
    fun getActiveUserFlow(): Flow<User?>
}
