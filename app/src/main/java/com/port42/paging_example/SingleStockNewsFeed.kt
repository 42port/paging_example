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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.google.firebase.FirebaseNetworkException

val CardBackgroundColor = Color(0xFF9B9DA1)
val CardTextColor = Color(0xFF28292A)
val CardTimeColor = Color(0xFF6A6B6E)

val customSelectionColors = TextSelectionColors(
    handleColor = Color.Blue,
    backgroundColor = Color.Yellow
)

const val COLLECTION_MARKET_NEWS = "market-news"
const val NEWS_PAGE_SIZE = 5L
const val NEWS_PAGE_REFRESH_GAP = 3


// Sample data class representing a market news article
// MarketNewsArticle.kt
data class MarketNewsArticle(
    val aiDone: Boolean = true,
    val aiFinishedAt: Timestamp = Timestamp.now(),
    val aiProcessing: Boolean = false,
    val aiProcessingStartedAt: Timestamp = Timestamp.now(),
    val eventDuration: String = "",
    val eventId: Int = 0,
    val eventImageURL: String = "",
    val eventImpact: Int = 0,
    val eventPublisher: String = "",
    val eventSource: String = "",
    val eventSubType: String = "",
    val eventSummary: String = "",
    val eventTime: Timestamp = Timestamp.now(),
    val eventTitle: String = "",
    val eventType: String = "",
    val eventURL: String = "",
    val stockTicker: String = "",
    val ttlDeleteTime: Timestamp = Timestamp.now(),
    val whyDuration: String = "",
    val whyEventType: String = "",
    val whyImpact: String = ""
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

    fun loadArticles(stockTicker: String, forceRefresh: Boolean = false) {
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
                var query = db.collection(COLLECTION_MARKET_NEWS)
                    .whereEqualTo("stockTicker", stockTicker)
                    .whereEqualTo("eventSource", "Finnhub News")
                    .orderBy("eventTime", Query.Direction.DESCENDING)
                    .limit(NEWS_PAGE_SIZE)

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
                            endReached = querySnapshot.size() < NEWS_PAGE_SIZE
                        )
                    )
                } else {
                    // This handles the case where there are no more articles to load.
                    _uiState.value = UiState.Success(initialState.copy(isLoadingInitialPage = false, isLoadingNextPage = false, endReached = true))
                }
            } catch (e: Exception) {
                Log.e("SingleStockNewsViewModel", "Error loading articles", e)
                val errorMessage = when (e) {
                    is FirebaseNetworkException -> "Network error. Please check your connection."
                    else -> "An unexpected error occurred. Please try again later."
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleStockNewsFeed(
    stockTicker: String,
    marketNewsViewModel: SingleStockNewsViewModel = viewModel()
) {
    val uiState by marketNewsViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(key1 = stockTicker) {
        marketNewsViewModel.loadArticles(stockTicker = stockTicker, forceRefresh = true)
    }

    val isRefreshing = (uiState as? UiState.Success)?.state?.isLoadingInitialPage ?: false

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            marketNewsViewModel.loadArticles(stockTicker = stockTicker, forceRefresh = true)
        },
        state = pullToRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val state = uiState) {
            is UiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Error: ${state.message}", color = Color.Red)
                }
            }
            is UiState.Success -> {
                val marketNewsState = state.state

                if (marketNewsState.articles.isEmpty() && !marketNewsState.isLoadingInitialPage) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "No articles found for $stockTicker.")
                    }
                } else {
                    LazyColumn(state = listState) {
                        items(marketNewsState.articles) { article ->
                            ShowArticleCard(article = article)
                        }

                        item {
                            when {
                                marketNewsState.isLoadingNextPage -> {
                                    Box(modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color.Green)
                                    }
                                }
                                marketNewsState.errorLoadingNextPage != null -> {
                                    Column(modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Error: ${marketNewsState.errorLoadingNextPage}", color = Color.Red)
                                        Button(onClick = { marketNewsViewModel.loadArticles(stockTicker) }) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(key1 = listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .distinctUntilChanged()
        .collect { lastVisibleItemIndex ->
            if (lastVisibleItemIndex != null) {
                val currentSuccessState = (uiState as? UiState.Success)?.state ?: return@collect

                // Add checks to ensure we aren't already loading and haven't reached the end
                if (!currentSuccessState.isLoadingNextPage && !currentSuccessState.endReached) {
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (totalItems > 0 && lastVisibleItemIndex >= totalItems - NEWS_PAGE_REFRESH_GAP) {
                        marketNewsViewModel.loadArticles(stockTicker = stockTicker)
                    }
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
                                color = CardTimeColor
                            )
                        }
                    }
                }
            }
        }
    }
}