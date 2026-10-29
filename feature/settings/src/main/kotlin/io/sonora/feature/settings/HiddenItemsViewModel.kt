package io.sonora.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Hidden Items: what was removed from Sonora (its files stay), the most recently hidden first. */
@HiltViewModel
class HiddenItemsViewModel @Inject constructor(private val marks: LibraryMarksRepository) : ViewModel() {

    val items: StateFlow<List<HiddenItem>> = marks.hidden()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Shows [item] in the library again. */
    fun restore(item: HiddenItem) {
        viewModelScope.launch { marks.restore(item.ref) }
    }
}
