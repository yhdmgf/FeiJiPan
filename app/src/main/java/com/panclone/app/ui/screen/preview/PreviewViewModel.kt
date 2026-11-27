package com.panclone.app.ui.screen.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panclone.app.App
import com.panclone.app.data.model.PanFile
import com.panclone.app.data.repo.FileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PreviewUiState(
    val imageUrls: Map<Long, String> = emptyMap(),
    val loading: Set<Long> = emptySet(),
    val errors: Map<Long, String> = emptyMap(),
)

/** 每张图片独立加载并缓存，翻页时不会用下一张状态覆盖当前页。 */
class PreviewViewModel : ViewModel() {
    private val repo = FileRepository(App.instance.api)
    private val jobs = mutableMapOf<Long, Job>()

    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    fun load(file: PanFile) {
        val id = file.fsId
        val current = _state.value
        if (id in current.imageUrls || id in current.loading || id in jobs) return

        _state.value = current.copy(
            loading = current.loading + id,
            errors = current.errors - id,
        )
        jobs[id] = viewModelScope.launch {
            val thumb = file.thumbs?.url3?.ifBlank { null }
                ?: file.thumbs?.url2?.ifBlank { null }
                ?: file.thumbs?.url1?.ifBlank { null }
            when (val result = repo.dlink(file.path)) {
                is FileRepository.Result.Ok -> {
                    val state = _state.value
                    _state.value = state.copy(
                        imageUrls = state.imageUrls + (id to result.data),
                        loading = state.loading - id,
                    )
                }
                is FileRepository.Result.Err -> {
                    val state = _state.value
                    _state.value = if (thumb != null) {
                        state.copy(
                            imageUrls = state.imageUrls + (id to thumb),
                            loading = state.loading - id,
                        )
                    } else {
                        state.copy(
                            loading = state.loading - id,
                            errors = state.errors + (id to result.message),
                        )
                    }
                }
            }
            jobs.remove(id)
        }
    }
}
