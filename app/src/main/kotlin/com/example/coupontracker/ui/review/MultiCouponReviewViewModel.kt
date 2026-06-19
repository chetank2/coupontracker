package com.example.coupontracker.ui.review

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import javax.inject.Inject

data class ReviewableCoupon(
    val id: String,
    val canonical: JSONObject,
    val accepted: Boolean = true
)

data class MultiCouponReviewUiState(
    val coupons: List<ReviewableCoupon> = emptyList(),
    val loading: Boolean = false
)

@HiltViewModel
class MultiCouponReviewViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MultiCouponReviewUiState())
    val uiState: StateFlow<MultiCouponReviewUiState> = _uiState

    fun show(coupons: List<JSONObject>) {
        val indexed = coupons.mapIndexed { i, c -> ReviewableCoupon("c$i", c) }
        _uiState.value = MultiCouponReviewUiState(coupons = indexed)
    }

    fun toggle(id: String) {
        _uiState.value = _uiState.value.copy(
            coupons = _uiState.value.coupons.map {
                if (it.id == id) it.copy(accepted = !it.accepted) else it
            }
        )
    }

    fun edit(id: String, field: String, value: String) {
        _uiState.value = _uiState.value.copy(
            coupons = _uiState.value.coupons.map {
                if (it.id == id) {
                    val json = JSONObject(it.canonical.toString()).put(field, value)
                    it.copy(canonical = json)
                } else it
            }
        )
    }

    fun accepted(): List<JSONObject> =
        _uiState.value.coupons.filter { it.accepted }.map { it.canonical }
}
