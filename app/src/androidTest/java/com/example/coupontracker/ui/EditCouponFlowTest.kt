package com.example.coupontracker.ui

import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.R
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.ui.fragment.AddFragment
import com.example.coupontracker.ui.fragment.DetailFragment
import com.example.coupontracker.util.launchFragmentInHiltContainer
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class EditCouponFlowTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var couponRepository: CouponRepository

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun editingCouponUpdatesDetailScreen() {
        val couponId = runBlocking {
            couponRepository.insertCoupon(
                Coupon(
                    storeName = "Test Store",
                    description = "Save 10%",
                    redeemCode = "CODE10",
                    imageUri = null,
                    status = "Active"
                )
            )
        }

        val args = bundleOf("couponId" to couponId)
        lateinit var navController: NavController

        launchFragmentInHiltContainer<AddFragment>(fragmentArgs = args) { controller ->
            navController = controller
            (controller as TestNavHostController).setCurrentDestination(R.id.addFragment, args)
        }

        onView(withId(R.id.storeNameInput)).check(matches(withText("Test Store")))

        val updatedDescription = "Updated savings"
        onView(withId(R.id.descriptionInput)).perform(scrollTo(), replaceText(updatedDescription), closeSoftKeyboard())
        onView(withId(R.id.saveButton)).perform(scrollTo(), click())

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertThat(navController.currentDestination?.id).isEqualTo(R.id.detailFragment)

        val updatedCoupon = runBlocking { couponRepository.getCouponById(couponId) }
        assertThat(updatedCoupon?.description).isEqualTo(updatedDescription)

        launchFragmentInHiltContainer<DetailFragment>(fragmentArgs = bundleOf("couponId" to couponId)) { controller ->
            (controller as TestNavHostController).setCurrentDestination(
                R.id.detailFragment,
                bundleOf("couponId" to couponId)
            )
        }

        onView(withId(R.id.description)).check(matches(withText(updatedDescription)))
        onView(withId(R.id.storeName)).check(matches(withText("Test Store")))
    }
}
