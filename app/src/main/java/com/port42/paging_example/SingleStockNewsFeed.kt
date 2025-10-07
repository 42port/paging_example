package com.port42.paging_example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import androidx.compose.runtime.LaunchedEffect
import com.google.firebase.Timestamp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import android.util.Log
import androidx.compose.foundation.lazy.items
import com.google.firebase.firestore.DocumentSnapshot
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
// import androidx.compose.material3.ExperimentalMaterial3Api
// import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
// import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

val CardBackgroundColor = Color(0xFF9B9DA1)
val CardTextColor = Color(0xFF28292A)
val customSelectionColors = TextSelectionColors(
    handleColor = Color.Blue,
    backgroundColor = Color.Yellow
)

// Sample data class representing a market news article
// MarketNewsArticle.kt
data class MarketNewsArticle(
    val aiDone: Boolean = true,
    val aiFinishedAt: Timestamp = Timestamp.now(),
    val aiProcessing: Boolean = false,
    val aiProcessingStartedAt: Timestamp = Timestamp.now(),
    val eventDuration: String = "Weeks",
    val eventId: Int = 136007862,
    val eventImageURL: String = "https://s.yimg.com/rz/stage/p/yahoo_finance_en-US_h_p_finance_2.png",
    val eventImpact: Int = 8,
    val eventPublisher: String = "Yahoo",
    val eventSource: String = "Finnhub News",
    val eventSubType: String = "Quarterly Report",
    val eventSummary: String = "Nvidia has announced its fiscal Q1 2025 earnings...",
    val eventTime: Timestamp = Timestamp.now(),
    val eventTitle: String = "6 Frugal Living Lessons From the Great Recession",
    val eventType: String = "Financials",
    val eventURL: String = "https://finnhub.io/api/news?id=12b9fe6a9934275fed30902a10394df30c68f786a8b9a092f0f215e212d8cded",
    val stockTicker: String = "WMT",
    val ttlDeleteTime: Timestamp = Timestamp.now(),
    val whyDuration: String = "The positive outlook and continued AI demand suggest...",
    val whyEventType: String = "The report details Nvidia's financial performance...",
    val whyImpact: String = "Strong earnings beat and robust forward guidance..."
)

// ViewModel will be responsible for fetching data from Firestore and providing it to the UI
// This separates your logic from the UI and handles configuration changes gracefully
// SingleStockNewsViewModel.kt
data class MarketNewsState(
    val articles: List<MarketNewsArticle> = emptyList(),
    val isLoadingInitialPage: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val endReached: Boolean = false,
    val errorLoadingNextPage: String? = null // To show errors without losing the list
)

// Represents the different states our UI can be in
sealed interface UiState {
    data class Success(val state: MarketNewsState) : UiState
    data class Error(val message: String) : UiState
}

class SingleStockNewsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Success(MarketNewsState()))
    val uiState = _uiState.asStateFlow()
    val db = FirebaseFirestore.getInstance()

    // This is our "cursor" for pagination
    private var lastDocumentSnapshot: DocumentSnapshot? = null

    fun loadArticles(stockTicker: String, pageSize: Long, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val currentState = (uiState.value as? UiState.Success)?.state ?: MarketNewsState()

            if (currentState.isLoadingInitialPage || currentState.isLoadingNextPage) return@launch
            if (!forceRefresh && currentState.endReached) return@launch

            if (forceRefresh) {
                lastDocumentSnapshot = null // Reset for refresh
            }

            // Update state to show the correct loading indicator
            val initialState = if (lastDocumentSnapshot == null) {
                _uiState.value = UiState.Success(currentState.copy(articles = if(forceRefresh) emptyList() else currentState.articles, isLoadingInitialPage = true, errorLoadingNextPage = null))
                currentState.copy(isLoadingInitialPage = true)
            } else {
                _uiState.value = UiState.Success(currentState.copy(isLoadingNextPage = true, errorLoadingNextPage = null))
                currentState.copy(isLoadingNextPage = true)
            }

            try {
                var query = db.collection("market-news")
                    .whereEqualTo("stockTicker", stockTicker)
                    .whereEqualTo("eventSource", "Finnhub News")
                    .orderBy("eventTime", Query.Direction.DESCENDING)
                    .limit(pageSize)

                if (lastDocumentSnapshot != null) {
                    query = query.startAfter(lastDocumentSnapshot!!)
                }

                val querySnapshot = query.get().await()

                if (!querySnapshot.isEmpty) {
                    lastDocumentSnapshot = querySnapshot.documents.last()

                    val newArticles = querySnapshot.toObjects(MarketNewsArticle::class.java)
                    val currentArticles = if (forceRefresh) emptyList() else initialState.articles

                    _uiState.value = UiState.Success(
                        MarketNewsState(
                            articles = currentArticles + newArticles,
                            endReached = querySnapshot.size() < pageSize
                        )
                    )
                } else {
                    // This handles the case where there are no more articles to load.
                    _uiState.value = UiState.Success(initialState.copy(isLoadingInitialPage = false, isLoadingNextPage = false, endReached = true))
                }
            } catch (e: Exception) {
                Log.e("SingleStockNewsViewModel", "Error loading articles", e)
                val errorMessage = e.message ?: "An unknown error occurred."
                if (lastDocumentSnapshot == null) {
                    // It failed on the first page, show a full-screen error
                    _uiState.value = UiState.Error(errorMessage)
                } else {
                    // It failed on a subsequent page, show an inline error
                    _uiState.value = UiState.Success(initialState.copy(isLoadingNextPage = false, errorLoadingNextPage = errorMessage))
                }
            }
        }
    }
}

@Composable
fun SingleStockNewsFeed(
    stockTicker: String,
    marketNewsViewModel: SingleStockNewsViewModel = viewModel()
) {
    val uiState by marketNewsViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState() // For tracking scroll position

    LaunchedEffect(key1 = stockTicker) {
        marketNewsViewModel.loadArticles(stockTicker = stockTicker, pageSize = 10, forceRefresh = true)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            is UiState.Error -> {
                Text(text = "Error: ${state.message}", color = Color.Red)
            }
            is UiState.Success -> {
                val marketNewsState = state.state

                if (marketNewsState.articles.isEmpty() && !marketNewsState.isLoadingInitialPage) {
                    Text(text = "No articles found for $stockTicker.")
                }

                LazyColumn(state = listState) {
                    items(marketNewsState.articles) { article ->
                        ShowArticleCard(article = article)
                    }

                    item {
                        // This block handles the footer of the list
                        when {
                            marketNewsState.isLoadingNextPage -> {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            marketNewsState.errorLoadingNextPage != null -> {
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Error: ${marketNewsState.errorLoadingNextPage}", color = Color.Red)
                                    Button(onClick = { marketNewsViewModel.loadArticles(stockTicker, 10) }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }

                // Show a full-screen loader ONLY for the initial load
                if (marketNewsState.isLoadingInitialPage) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    LaunchedEffect(key1 = listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .distinctUntilChanged()
        .collect { lastVisibleItemIndex ->
            if (lastVisibleItemIndex != null) {
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > 0 && lastVisibleItemIndex >= totalItems - 3) {
                    marketNewsViewModel.loadArticles(stockTicker = stockTicker, pageSize = 10)
                }
            }
        }
    }
}

@Composable
fun ShowArticleCard(article: MarketNewsArticle) {
    val instant = article.eventTime.toDate().toInstant()
    val zoneId = ZoneId.of("America/New_York")
    val zonedDateTime = instant.atZone(zoneId)
    val formatter = DateTimeFormatter.ofPattern(
        "M/d/yyyy h:mm a", // E.g., "9/15/2023 3:30 PM"
        Locale.getDefault()
    )
    val formattedDateString = zonedDateTime.format(formatter) + " ET"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardBackgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(modifier = Modifier) {
                CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                    SelectionContainer {
                        Column {
                            Text(
                                text = article.eventTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = CardTextColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formattedDateString,
                                fontSize = 14.sp,
                                color = CardTextColor.copy(alpha = 0.8f) // Slightly less prominent
                            )
                        }
                    }
                }
            }
        }
    }
}