package com.example.websockettest.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.websockettest.data.model.CryptoCoin
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.websockettest.data.model.CryptoScreenState
import com.example.websockettest.data.model.PRESET_SYMBOLS_MAP

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScreen(viewModel: CryptoViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddPresetDialog by remember { mutableStateOf(false) }

    val currentSortCriteria = uiState.sortBy
    val isSortAscending = uiState.isSortAscending

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crypto Tracker") },
                actions = {
                    if (uiState.selectedTabIndex == 0) {
                        SortMenu(
                            currentSortCriteria = currentSortCriteria,
                            isAscending = isSortAscending,
                            onSortSelected = { criteria -> viewModel.setSortCriteria(criteria) }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedTabIndex == 0 && uiState.availablePresetFavorites.isNotEmpty()) {
                FloatingActionButton(onClick = { showAddPresetDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Preset Favorite")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val tabTitles = listOf("Favorites", "All Coins")

            TabRow(selectedTabIndex = uiState.selectedTabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTabIndex == index,
                        onClick = { viewModel.onTabSelected(index) },
                        text = { Text(title) }
                    )
                }
            }

            val displayError = when (uiState.selectedTabIndex) {
                0 -> uiState.favoritesError
                1 -> uiState.allCoinsError
                else -> null
            }
            if (displayError != null) {
                ErrorBanner(message = displayError)
            }

            when (uiState.selectedTabIndex) {
                0 -> FavoritesScreenContent(
                    uiState = uiState,
                    onRemoveFavorite = viewModel::removeFavorite,
                )
                1 -> AllCoinsScreenContent(
                    uiState = uiState,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onToggleFavorite = { symbol, isCurrentlyFavorite ->
                        if (isCurrentlyFavorite) {
                            viewModel.removeFavorite(symbol)
                        } else {
                            viewModel.addFavorite(symbol)
                        }
                    },
                    onRetry = viewModel::fetchAllSymbols
                )
            }
        }
    }

    if (showAddPresetDialog) {
        AddPresetFavoriteDialog(
            availablePresets = uiState.availablePresetFavorites,
            onDismiss = { showAddPresetDialog = false },
            onAddCoin = { symbol ->
                viewModel.addFavorite(symbol)
                showAddPresetDialog = false
            }
        )
    }
}

@Composable
fun FavoritesScreenContent(
    uiState: CryptoScreenState,
    onRemoveFavorite: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {

        if (uiState.isFavoritesLoading && uiState.favoriteCoins.isEmpty()) {
            CenteredProgressIndicator(text = "Loading Favorites Data...")
        } else if (uiState.favoriteCoins.isEmpty() && !uiState.isFavoritesLoading && uiState.favoritesError == null) {
            CenteredMessage(text = "Your watchlist is empty.\nAdd coins from the 'All Coins' tab or tap '+' to add presets.")
        } else {
            if (uiState.isFavoritesLoading && uiState.favoriteCoins.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            CryptoList(
                coins = uiState.favoriteCoins,
                isFavoritesList = true,
                onToggleFavorite = { symbol, _ -> onRemoveFavorite(symbol) }
            )
        }
    }
}

@Composable
fun SortMenu(
    currentSortCriteria: SortCriteria,
    isAscending: Boolean,
    onSortSelected: (SortCriteria) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconRotation = if (isAscending) 0f else 180f

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Sort, contentDescription = "Sort Options")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortCriteria.values().forEach { criteria ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = criteria.name.lowercase().replaceFirstChar { it.titlecase() },
                                fontWeight = if (criteria == currentSortCriteria) FontWeight.Bold else FontWeight.Normal
                            )
                            if (criteria == currentSortCriteria) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.ArrowDownward,
                                    contentDescription = if (isAscending) "Ascending" else "Descending",
                                    modifier = Modifier.size(16.dp).rotate(iconRotation)
                                )
                            }
                        }
                    },
                    onClick = {
                        onSortSelected(criteria)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AllCoinsScreenContent(
    uiState: CryptoScreenState,
    onSearchQueryChanged: (String) -> Unit,
    onToggleFavorite: (symbol: String, isCurrentlyFavorite: Boolean) -> Unit,
    onRetry: () -> Unit
) {

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Search All Coins...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                focusManager.clearFocus()
            })
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isAllCoinsLoading -> CenteredProgressIndicator(text = "Loading All Coins...")
                uiState.allCoinsError != null && uiState.allCoins.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error: ${uiState.allCoinsError}", textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                }
                uiState.allCoins.isEmpty() && uiState.searchQuery.isNotEmpty() -> {
                    CenteredMessage(text = "No results found for \"${uiState.searchQuery}\"")
                }
                uiState.allCoins.isEmpty() && uiState.searchQuery.isBlank() && !uiState.isAllCoinsLoading -> {
                    CenteredMessage(text = "No coins found.")
                }
                else -> {
                    CryptoList(
                        coins = uiState.allCoins,
                        isFavoritesList = false,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }
    }
}

@Composable
fun CryptoList(
    coins: List<CryptoCoin>,
    isFavoritesList: Boolean,
    onToggleFavorite: (symbol: String, isCurrentlyFavorite: Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(items = coins, key = { it.symbol }) { coin ->
            CryptoListItem(
                coin = coin,
                onFavoriteClick = { onToggleFavorite(coin.symbol, coin.isFavorite) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun CryptoListItem(
    coin: CryptoCoin,
    onFavoriteClick: () -> Unit
) {
    val starIcon = if (coin.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder
    val starColor = if (coin.isFavorite) MaterialTheme.colorScheme.primary else Color.Gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onFavoriteClick, modifier = Modifier.size(36.dp).align(Alignment.Top)) {
            Icon(imageVector = starIcon, contentDescription = "Toggle Favorite", tint = starColor, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = coin.name,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = coin.symbol,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            coin.getFormattedSpreadPercentage()?.let { spreadPercent ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Spread: $spreadPercent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End, modifier = Modifier.defaultMinSize(minWidth = 100.dp)) {
            PriceRow(label = "B:", price = coin.binancePrice)
            Spacer(Modifier.height(2.dp))
            PriceRow(label = "C:", price = coin.coinbasePrice)
        }
    }
}

@Composable
private fun PriceRow(label: String, price: BigDecimal) {
    val priceColor = if (price > BigDecimal.ZERO) LocalContentColor.current else Color.Gray
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = formatPrice(price),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = priceColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPresetFavoriteDialog(
    availablePresets: List<String>,
    onDismiss: () -> Unit,
    onAddCoin: (String) -> Unit
) {
    var selectedSymbol by remember { mutableStateOf(availablePresets.firstOrNull() ?: "") }

    if (availablePresets.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("All Presets Added") },
            text = { Text("All predefined popular coins are already in your favorites.") },
            confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Preset Favorite") },
        text = {
            Column {
                Text("Select a popular coin to add:")
                Spacer(modifier = Modifier.height(16.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = PRESET_SYMBOLS_MAP[selectedSymbol] ?: selectedSymbol,
                        onValueChange = {}, readOnly = true, label = { Text("Coin") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        availablePresets.forEach { symbol ->
                            DropdownMenuItem(
                                text = { Text("${PRESET_SYMBOLS_MAP[symbol] ?: symbol} ($symbol)") },
                                onClick = {
                                    selectedSymbol = symbol
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAddCoin(selectedSymbol) }, enabled = selectedSymbol.isNotEmpty()) { Text("Add") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CenteredProgressIndicator(text: String? = null, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (text != null) {
                Spacer(Modifier.height(8.dp))
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Warning, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
    }
}
fun formatPrice(price: BigDecimal): String {
    return try {
        if (price == BigDecimal.ZERO) return "--.--"
        val format = NumberFormat.getCurrencyInstance(Locale.US)
        format.maximumFractionDigits = when {
            price >= BigDecimal(1000) -> 2
            price >= BigDecimal(1) -> 4
            price >= BigDecimal(0.0001) -> 6
            else -> 8
        }
        format.minimumFractionDigits = 2
        format.format(price)
    } catch (e: Exception) {
        Log.e("Formatting", "Could not format price: $price", e)
        price.toPlainString()
    }
}

fun formatSpread(spread: BigDecimal?): String {
    if (spread == null) return "--"
    return try {
        val format = NumberFormat.getCurrencyInstance(Locale.US)
        format.maximumFractionDigits = 6
        format.minimumFractionDigits = 2
        format.format(spread)
    } catch (e: Exception) {
        spread.toPlainString()
    }
}
