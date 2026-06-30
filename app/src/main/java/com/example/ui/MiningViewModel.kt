package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.MiningRepository
import com.example.data.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface AuthState {
    object Idle : AuthState
    object Loading : AuthState
    data class RegisterSuccess(val user: User) : AuthState
    data class LoginSuccess(val user: User) : AuthState
    data class Error(val message: String) : AuthState
}

class MiningViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MiningRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = MiningRepository(database.userDao())
    }

    // Active logged-in user flow
    val activeUser: StateFlow<User?> = repository.activeUser
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Current user's referral list flow
    val referredUsers: StateFlow<List<User>> = activeUser
        .flatMapLatest { user ->
            if (user != null) {
                repository.getReferredUsers(user.referralCode)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Live uncollected mined tokens state
    private val _liveMinedTokens = MutableStateFlow(0.0)
    val liveMinedTokens: StateFlow<Double> = _liveMinedTokens.asStateFlow()

    // Live remaining time for the 24-hour cycle (in milliseconds)
    private val _miningTimeRemainingMs = MutableStateFlow(0L)
    val miningTimeRemainingMs: StateFlow<Long> = _miningTimeRemainingMs.asStateFlow()

    // Auth State for Login/Signup feedback
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Status notifications/alerts
    private val _messageEvent = MutableSharedFlow<String>()
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    private var miningTickerJob: Job? = null

    init {
        // Restore session on launch if possible
        viewModelScope.launch {
            repository.tryRestoreSession()
        }

        // Start a ticker to calculate live mined balance and countdown timer
        viewModelScope.launch {
            activeUser.collect { user ->
                miningTickerJob?.cancel()
                if (user != null && user.isMining) {
                    startMiningTicker(user)
                } else {
                    _liveMinedTokens.value = 0.0
                    _miningTimeRemainingMs.value = 0L
                }
            }
        }
    }

    private fun startMiningTicker(user: User) {
        miningTickerJob = viewModelScope.launch {
            val cycleDurationMs = 12 * 60 * 60 * 1000L // 12 Hours
            while (true) {
                val now = System.currentTimeMillis()
                val elapsedMs = now - user.miningStartTime
                
                if (elapsedMs >= cycleDurationMs) {
                    // Mining session is finished, update states
                    _miningTimeRemainingMs.value = 0L
                    val hours = cycleDurationMs.toDouble() / (1000.0 * 60.0 * 60.0)
                    _liveMinedTokens.value = hours * user.hashRate
                } else {
                    _miningTimeRemainingMs.value = cycleDurationMs - elapsedMs
                    val elapsedHours = elapsedMs.toDouble() / (1000.0 * 60.0 * 60.0)
                    _liveMinedTokens.value = elapsedHours * user.hashRate
                }
                delay(100) // update rapidly for sub-second counting animation
            }
        }
    }

    fun register(username: String, email: String, passwordPlain: String, referralCode: String?) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = repository.registerUser(username, email, passwordPlain, referralCode)
            result.onSuccess { user ->
                _authState.value = AuthState.RegisterSuccess(user)
                _messageEvent.emit("Registration successful! Welcome bonus 10 BNG added.")
            }.onFailure { exception ->
                _authState.value = AuthState.Error(exception.message ?: "Registration failed.")
            }
        }
    }

    fun login(username: String, passwordPlain: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = repository.loginUser(username, passwordPlain)
            result.onSuccess { user ->
                _authState.value = AuthState.LoginSuccess(user)
                _messageEvent.emit("Welcome back, ${user.username}!")
            }.onFailure { exception ->
                _authState.value = AuthState.Error(exception.message ?: "Login failed.")
            }
        }
    }

    fun clearAuthError() {
        _authState.value = AuthState.Idle
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = repository.sendPasswordReset(email)
            result.onSuccess {
                _authState.value = AuthState.Idle
                _messageEvent.emit("Password reset link sent! Please check your email inbox/spam folder.")
            }.onFailure { exception ->
                _authState.value = AuthState.Error(exception.message ?: "Failed to send reset link.")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logoutUser()
            _authState.value = AuthState.Idle
        }
    }

    fun startMiningSession() {
        val user = activeUser.value ?: return
        if (user.isMining) return

        viewModelScope.launch {
            val result = repository.startMining(user.username)
            result.onSuccess {
                _messageEvent.emit("Mining session started successfully!")
            }.onFailure {
                _messageEvent.emit(it.message ?: "Failed to start mining.")
            }
        }
    }

    fun claimMinedTokens() {
        val user = activeUser.value ?: return
        if (!user.isMining) return

        viewModelScope.launch {
            val result = repository.claimMinedTokens(user.username)
            result.onSuccess {
                _messageEvent.emit("Mined tokens claimed and added to your balance!")
            }.onFailure {
                _messageEvent.emit(it.message ?: "Failed to claim tokens.")
            }
        }
    }

    fun claimDailyCheckIn() {
        val user = activeUser.value ?: return

        viewModelScope.launch {
            val result = repository.claimDailyReward(user.username)
            result.onSuccess { (updatedUser, reward) ->
                _messageEvent.emit("Daily claim successful! +$reward BNG claimed. Day ${updatedUser.dailyStreak} Streak!")
            }.onFailure {
                _messageEvent.emit(it.message ?: "Check-in failed.")
            }
        }
    }
}
