package com.example.websockettest.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.websockettest.data.model.CryptoCoin
import com.example.websockettest.data.model.CryptoScreenState
import com.example.websockettest.data.model.PRESET_SYMBOLS
import com.example.websockettest.data.model.PRESET_SYMBOLS_MAP
import com.example.websockettest.network.BinanceApiService
import com.example.websockettest.network.BinanceWebSocketListener
import com.example.websockettest.network.CoinbaseWebSocketListener
import com.example.websockettest.network.NetworkModule
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

enum class SortCriteria {
    NAME, BINANCE_PRICE, COINBASE_PRICE, SPREAD
}

class CryptoViewModel : ViewModel() {

    private val binanceListener: BinanceWebSocketListener = NetworkModule.binanceWebSocketListener
    private val coinbaseListener: CoinbaseWebSocketListener = NetworkModule.coinbaseWebSocketListener
    private val binanceApiService: BinanceApiService = NetworkModule.binanceApiService

    private var binanceTickerJob: Job? = null
    private var binanceStatusJob: Job? = null
    private var coinbaseTickerJob: Job? = null
    private var coinbaseStatusJob: Job? = null

    private var _masterAllSymbolsList = listOf<CryptoCoin>()
    private var _favoriteSymbolsSet = MutableStateFlow<Set<String>>(emptySet())


    private val _uiState = MutableStateFlow(CryptoScreenState())

    val uiState: StateFlow<CryptoScreenState> = _uiState
        .map { state ->
            state.copy(
                favoriteCoins = sortCoins(state.favoriteCoins, state.sortBy, state.isSortAscending)
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CryptoScreenState()
        )


    init {
        Log.d("CryptoViewModel", "Initializing")
        loadInitialFavorites()
        fetchAllSymbols()
        observeBinanceConnectionStatus()
        observeBinanceTickerUpdates()
        observeCoinbaseConnectionStatus()
        observeCoinbaseTickerUpdates()
        observeFavoriteChanges()
    }

    private fun loadInitialFavorites() {
        val initialFavSymbols = PRESET_SYMBOLS.toSet()
        _favoriteSymbolsSet.value = initialFavSymbols
        Log.d("CryptoViewModel", "Loaded initial favorites: ${initialFavSymbols.size}")
    }

    fun fetchAllSymbols() {
        if (_uiState.value.isAllCoinsLoading && _masterAllSymbolsList.isNotEmpty()) return

        _uiState.update { it.copy(isAllCoinsLoading = true, allCoinsError = null) }
        viewModelScope.launch {
            try {
                val response = binanceApiService.getExchangeInfo()
                if (response.isSuccessful && response.body() != null) {
                    val apiSymbols = response.body()!!.symbols
                    Log.d("CryptoViewModel", "Fetched ${apiSymbols.size} symbols from API")

                    val currentFavorites = _favoriteSymbolsSet.value
                    _masterAllSymbolsList = withContext(Dispatchers.Default) {
                        apiSymbols
                            .filter { it.quoteAsset == "USDT" && it.status == "TRADING" }
                            .map { apiSymbol ->
                                val name = PRESET_SYMBOLS_MAP[apiSymbol.symbol] ?: apiSymbol.baseAsset
                                CryptoCoin(
                                    symbol = apiSymbol.symbol,
                                    name = name,
                                    isFavorite = currentFavorites.contains(apiSymbol.symbol)
                                )
                            }
                            .sortedBy { it.name }
                    }
                    Log.d("CryptoViewModel", "Processed ${_masterAllSymbolsList.size} USDT trading symbols.")

                    withContext(Dispatchers.Main) {
                        updateUiLists()
                        _uiState.update { it.copy(isAllCoinsLoading = false) }
                        connectWebSockets()
                    }

                } else {
                    throw RuntimeException("API Error ${response.code()}: ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e("CryptoViewModel", "Error fetching all symbols", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isAllCoinsLoading = false, allCoinsError = "Failed to load coin list: ${e.message}") }
                }
            }
        }
    }

    private fun connectWebSockets() {
        val favoriteSymbolsBinance = _favoriteSymbolsSet.value.toList()

        if (favoriteSymbolsBinance.isNotEmpty()) {
            Log.d("CryptoViewModel", "[Binance] Connecting/Updating for ${favoriteSymbolsBinance.size} favorites.")
            _uiState.update { it.copy(isFavoritesLoading = true, favoritesError = it.favoritesError ?: "Connecting Binance...") }
            binanceListener.connect(favoriteSymbolsBinance)
        } else {
            Log.d("CryptoViewModel", "[Binance] No favorites, disconnecting.")
            binanceListener.disconnect()
        }

        val favoriteSymbolsCoinbase = favoriteSymbolsBinance.mapNotNull { binanceSymbol ->
            (_masterAllSymbolsList.find { it.symbol == binanceSymbol }
                ?: _uiState.value.favoriteCoins.find { it.symbol == binanceSymbol })
                ?.getCoinbaseProductId()
        }

        if (favoriteSymbolsCoinbase.isNotEmpty()) {
            Log.d("CryptoViewModel", "[Coinbase] Connecting/Updating for ${favoriteSymbolsCoinbase.size} favorites: $favoriteSymbolsCoinbase")
            _uiState.update { it.copy(isFavoritesLoading = true,  favoritesError = it.favoritesError ?: "Connecting Coinbase...") }
            coinbaseListener.connect(favoriteSymbolsCoinbase)
        } else {
            Log.d("CryptoViewModel", "[Coinbase] No equivalent favorites, disconnecting.")
            coinbaseListener.disconnect()
        }

        if (favoriteSymbolsBinance.isEmpty()) {
            _uiState.update { it.copy(isFavoritesLoading = false, favoritesError = null) }
        }
    }

    private fun observeBinanceTickerUpdates() {
        binanceTickerJob?.cancel()
        binanceTickerJob = binanceListener.tickerUpdates
            .onEach { tickerUpdate ->
                if (_favoriteSymbolsSet.value.contains(tickerUpdate.s)) {
                    updatePriceInLists(Source.BINANCE, tickerUpdate.s, tickerUpdate.c)
                }
            }
            .catch { e -> handleFlowError("Binance Ticker", e) }
            .launchIn(viewModelScope)
    }

    private fun observeBinanceConnectionStatus() {
        binanceStatusJob?.cancel()
        binanceStatusJob = binanceListener.connectionStatus
            .onEach { status ->
                handleConnectionStatus("Binance", status)
            }
            .catch { e -> handleFlowError("Binance Status", e) }
            .launchIn(viewModelScope)
    }

    private fun observeCoinbaseTickerUpdates() {
        coinbaseTickerJob?.cancel()
        coinbaseTickerJob = coinbaseListener.coinbaseTickerUpdates

            .onEach { (productId, priceStr) ->

                val binanceSymbol = _masterAllSymbolsList.find { it.getCoinbaseProductId() == productId }?.symbol
                    ?: _uiState.value.favoriteCoins.find { it.getCoinbaseProductId() == productId }?.symbol

                if (binanceSymbol != null && _favoriteSymbolsSet.value.contains(binanceSymbol) && priceStr != null) {
                    updatePriceInLists(Source.COINBASE, binanceSymbol, priceStr)
                } else if (priceStr == null) {
                    // Log.v("ViewModel", "Coinbase price null for $productId")
                }
            }
            .catch { e -> handleFlowError("Coinbase Ticker", e) }
            .launchIn(viewModelScope)
    }

    private fun observeCoinbaseConnectionStatus() {
        coinbaseStatusJob?.cancel()
        coinbaseStatusJob = coinbaseListener.connectionStatus
            .onEach { status ->
                handleConnectionStatus("Coinbase", status)
            }
            .catch { e -> handleFlowError("Coinbase Status", e) }
            .launchIn(viewModelScope)
    }

    private suspend fun handleConnectionStatus(sourceName: String, status: Any) {
        withContext(Dispatchers.Main) {
            Log.d("CryptoViewModel", "[$sourceName] Status Received: $status")
            _uiState.update { currentState ->
                var newError = currentState.favoritesError
                var newLoading = currentState.isFavoritesLoading

                when (status) {
                    is BinanceWebSocketListener.ConnectionStatus.Connected,
                    is CoinbaseWebSocketListener.ConnectionStatus.Connected -> {
                        val errorCleared = newError?.contains("Connecting", ignoreCase = true) == true ||
                                newError?.contains("Closed", ignoreCase = true) == true ||
                                newError?.contains(sourceName, ignoreCase = true) == true

                        if (errorCleared) {
                            newError = null
                        }
                        newLoading = currentState.favoriteCoins.any { coin ->
                            _favoriteSymbolsSet.value.contains(coin.symbol) &&
                                    (coin.binancePrice == BigDecimal.ZERO || coin.coinbasePrice == BigDecimal.ZERO)
                        }
                    }
                    is BinanceWebSocketListener.ConnectionStatus.Error -> {
                        newLoading = false
                        newError = "$sourceName Error: ${status.message}"
                    }
                    is CoinbaseWebSocketListener.ConnectionStatus.Error -> {
                        newLoading = false
                        newError = "$sourceName Error: ${status.message}"
                    }
                    is CoinbaseWebSocketListener.ConnectionStatus.Connecting -> {
                        newLoading = true
                        newError = "$sourceName Connecting..."
                    }
                    is BinanceWebSocketListener.ConnectionStatus.Closed,
                    is CoinbaseWebSocketListener.ConnectionStatus.Closed -> {
                        val isManual = if (sourceName == "Binance") binanceListener.manualDisconnect else coinbaseListener.manualDisconnect
                        if (!isManual && _favoriteSymbolsSet.value.isNotEmpty()) {
                            newLoading = false
                            newError = "$sourceName closed. Reconnecting..."
                        } else if (isManual) {
                            if (newError?.contains(sourceName, ignoreCase = true) == true) {
                                newError = null
                            }
                            newLoading = false
                        } else {
                            newLoading = false
                            newError = null
                        }
                    }
                }
                currentState.copy(isFavoritesLoading = newLoading, favoritesError = newError)
            }
        }
    }

    private suspend fun handleFlowError(flowName: String, e: Throwable) {
        Log.e("CryptoViewModel", "Error in $flowName flow: ${e.message}", e)
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(isFavoritesLoading = false, favoritesError = "$flowName flow error: ${e.message}") }
        }
    }

    private fun observeFavoriteChanges() {
        _favoriteSymbolsSet.onEach { currentFavs ->
            val available = PRESET_SYMBOLS.filter { it !in currentFavs }
            updateUiLists()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(availablePresetFavorites = available) }
            }
        }.launchIn(viewModelScope)
    }

    private enum class Source { BINANCE, COINBASE }

    private suspend fun updatePriceInLists(source: Source, symbol: String, priceStr: String) {
        val price = try { BigDecimal(priceStr) } catch (e: NumberFormatException) {
            Log.e("CryptoViewModel", "[$source] Invalid price format for $symbol: $priceStr")
            return
        }

        withContext(Dispatchers.Main) {
            val now = System.currentTimeMillis()
            var coinFoundInState = false

            fun updateCoinIfNeeded(coin: CryptoCoin): CryptoCoin {
                if (coin.symbol == symbol) {
                    coinFoundInState = true
                    val updatedCoin = when (source) {
                        Source.BINANCE -> coin.copy(binancePrice = price, binanceLastUpdatedAt = now)
                        Source.COINBASE -> coin.copy(coinbasePrice = price, coinbaseLastUpdatedAt = now)
                    }
                    updatedCoin.updateSpread()
                    return updatedCoin
                }
                return coin
            }

            if (_uiState.value.favoriteCoins.any { it.symbol == symbol } || _uiState.value.allCoins.any { it.symbol == symbol }) {
                _uiState.update { currentState ->
                    val updatedFavorites = currentState.favoriteCoins.map(::updateCoinIfNeeded)
                    val updatedAllCoins = currentState.allCoins.map(::updateCoinIfNeeded)

                    var newLoading = currentState.isFavoritesLoading
                    if (coinFoundInState) {
                        newLoading = updatedFavorites.any { favCoin ->
                            _favoriteSymbolsSet.value.contains(favCoin.symbol) &&
                                    (favCoin.binancePrice == BigDecimal.ZERO || favCoin.coinbasePrice == BigDecimal.ZERO)
                        }
                    }

                    currentState.copy(
                        favoriteCoins = updatedFavorites,
                        allCoins = updatedAllCoins,
                        isFavoritesLoading = newLoading
                    )
                }
            }
        }
    }

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateUiLists()
    }

    fun addFavorite(symbol: String) {
        viewModelScope.launch {
            val currentFavorites = _favoriteSymbolsSet.value
            if (!currentFavorites.contains(symbol)) {
                Log.d("CryptoViewModel", "Adding $symbol to favorites")
                _favoriteSymbolsSet.value = currentFavorites + symbol
                connectWebSockets()
            }
        }
    }

    fun removeFavorite(symbol: String) {
        viewModelScope.launch {
            val currentFavorites = _favoriteSymbolsSet.value
            if (currentFavorites.contains(symbol)) {
                Log.d("CryptoViewModel", "Removing $symbol from favorites")
                _favoriteSymbolsSet.value = currentFavorites - symbol
                connectWebSockets()
            }
        }
    }

    fun setSortCriteria(criteria: SortCriteria) {
        _uiState.update { currentState ->
            val newAscending = if (criteria == currentState.sortBy) {
                !currentState.isSortAscending
            } else {
                true
            }
            currentState.copy(
                sortBy = criteria,
                isSortAscending = newAscending
            )
        }
        Log.d("ViewModel", "Set Sort by $criteria, Ascending: ${_uiState.value.isSortAscending}")

    }


    private fun sortCoins(coins: List<CryptoCoin>, criteria: SortCriteria, ascending: Boolean): List<CryptoCoin> {
        val comparator: Comparator<CryptoCoin> = when (criteria) {
            SortCriteria.NAME -> compareBy { it.name }
            SortCriteria.BINANCE_PRICE -> compareBy { it.binancePrice }
            SortCriteria.COINBASE_PRICE -> compareBy { it.coinbasePrice }

            SortCriteria.SPREAD -> {
                compareBy(nullsLast()) { it.spread }
            }
        }

        return if (ascending) coins.sortedWith(comparator) else coins.sortedWith(comparator.reversed())
    }


    private fun updateUiLists() {
        viewModelScope.launch(Dispatchers.Default) {
            val currentFavoritesSet = _favoriteSymbolsSet.value
            val currentSearchQuery = _uiState.value.searchQuery

            val currentFavCoinsState = _uiState.value.favoriteCoins
            val currentAllCoinsState = _uiState.value.allCoins

            val newFavoriteCoinsSource = _masterAllSymbolsList
                .filter { currentFavoritesSet.contains(it.symbol) }
                .map { masterCoin ->

                    val existingState = currentFavCoinsState.find { it.symbol == masterCoin.symbol }
                        ?: currentAllCoinsState.find {it.symbol == masterCoin.symbol }

                    masterCoin.copy(
                        isFavorite = true,
                        binancePrice = existingState?.binancePrice ?: BigDecimal.ZERO,
                        binanceLastUpdatedAt = existingState?.binanceLastUpdatedAt ?: 0L,
                        coinbasePrice = existingState?.coinbasePrice ?: BigDecimal.ZERO,
                        coinbaseLastUpdatedAt = existingState?.coinbaseLastUpdatedAt ?: 0L,
                        spread = existingState?.spread
                    ).apply { updateSpread() }
                }

            val newAllCoins = _masterAllSymbolsList
                .filter {
                    if (currentSearchQuery.isBlank()) true
                    else it.name.contains(currentSearchQuery, ignoreCase = true) ||
                            it.symbol.contains(currentSearchQuery, ignoreCase = true)
                }
                .map { masterCoin ->
                    val favEquivalent = newFavoriteCoinsSource.find { fav -> fav.symbol == masterCoin.symbol }
                        ?: currentFavCoinsState.find { it.symbol == masterCoin.symbol }
                        ?: currentAllCoinsState.find {it.symbol == masterCoin.symbol }

                    masterCoin.copy(
                        isFavorite = currentFavoritesSet.contains(masterCoin.symbol),
                        binancePrice = favEquivalent?.binancePrice ?: BigDecimal.ZERO,
                        binanceLastUpdatedAt = favEquivalent?.binanceLastUpdatedAt ?: 0L,
                        coinbasePrice = favEquivalent?.coinbasePrice ?: BigDecimal.ZERO,
                        coinbaseLastUpdatedAt = favEquivalent?.coinbaseLastUpdatedAt ?: 0L,
                        spread = favEquivalent?.spread
                    ).apply { updateSpread() }
                }

            withContext(Dispatchers.Main) {
                _uiState.update { currentState ->
                    currentState.copy(
                        favoriteCoins = newFavoriteCoinsSource,
                        allCoins = newAllCoins
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("CryptoViewModel", "ViewModel Cleared - Cleaning up")
        binanceListener.cleanup()
        coinbaseListener.cleanup()
        binanceTickerJob?.cancel()
        binanceStatusJob?.cancel()
        coinbaseTickerJob?.cancel()
        coinbaseStatusJob?.cancel()
    }
}