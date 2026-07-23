package com.vicmobilecams.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** Car-safe destination search: type + submit. Only queries Nominatim on submit, never per keystroke. */
class DestinationSearchScreen(
    carContext: CarContext,
    private val onDestinationSelected: (SearchResult) -> Unit,
) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var results: List<SearchResult> = emptyList()
    private var isSearching = false
    private var lastQuery = ""

    private val searchCallback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            lastQuery = searchText
        }

        override fun onSearchSubmitted(searchText: String) {
            runSearch(searchText)
        }
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()
        when {
            isSearching -> itemListBuilder.setNoItemsMessage(carContext.getString(R.string.searching_message))
            results.isEmpty() && lastQuery.isNotBlank() ->
                itemListBuilder.setNoItemsMessage(carContext.getString(R.string.no_results_message))
            else -> for (result in results) {
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle(result.displayName)
                        .setOnClickListener { onDestinationSelected(result) }
                        .build()
                )
            }
        }

        return SearchTemplate.Builder(searchCallback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setShowKeyboardByDefault(true)
            .setItemList(itemListBuilder.build())
            .build()
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        isSearching = true
        invalidate()

        Thread {
            val found = NominatimSearchClient.search(query)
            mainHandler.post {
                isSearching = false
                results = found
                invalidate()
            }
        }.start()
    }
}
